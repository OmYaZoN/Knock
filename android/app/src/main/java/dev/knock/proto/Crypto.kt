package dev.knock.proto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Knock v1 crypto (protocol/PROTOCOL.md §3-5). Pure JVM, no Android imports,
 * so the unit tests can check it against protocol/reference/vectors.json.
 */
object KnockCrypto {
    private val random = SecureRandom()

    fun generate(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private val params: ECParameterSpec by lazy { (generate().public as ECPublicKey).params }

    fun random(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** RFC 5869 HKDF-SHA256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t + info.toByteArray() + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun pad32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    /** Uncompressed SEC1: 0x04 || X || Y. */
    fun pubBytes(pub: ECPublicKey): ByteArray = byteArrayOf(4) + pad32(pub.w.affineX) + pad32(pub.w.affineY)

    fun pubFromBytes(b: ByteArray): ECPublicKey {
        require(b.size == 65 && b[0] == 4.toByte()) { "bad public key" }
        val point = ECPoint(BigInteger(1, b.copyOfRange(1, 33)), BigInteger(1, b.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
    }

    fun privFromScalar(s: ByteArray): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, s), params)) as ECPrivateKey

    fun scalar(priv: ECPrivateKey): ByteArray = pad32(priv.s)

    fun ecdh(priv: PrivateKey, peerPub: ByteArray): ByteArray =
        KeyAgreement.getInstance("ECDH").run { init(priv); doPhase(pubFromBytes(peerPub), true); generateSecret() }

    fun shortId(identityPub: ByteArray): ByteArray = sha256(identityPub).copyOf(8)

    fun pairCommit(phonePub: ByteArray, np: ByteArray) = sha256(phonePub, np)

    fun pairCode(own: PrivateKey, peerPub: ByteArray, np: ByteArray, nm: ByteArray): String {
        val okm = hkdf(ecdh(own, peerPub), np + nm, "knock v1 pair code", 4)
        val n = ((okm[0].toLong() and 0xff) shl 24) or ((okm[1].toLong() and 0xff) shl 16) or
                ((okm[2].toLong() and 0xff) shl 8) or (okm[3].toLong() and 0xff)
        return "%06d".format(n % 1_000_000)
    }

    fun transcript(phoneId: ByteArray, ephPhonePub: ByteArray, rp: ByteArray, ephMacPub: ByteArray, rm: ByteArray) =
        sha256("knock v1 hs".toByteArray(), phoneId, ephPhonePub, rp, ephMacPub, rm)

    fun sign(identity: PrivateKey, t: ByteArray, role: String): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(identity); update(t + role.toByteArray()); sign() }

    fun verify(identityPub: ByteArray, sig: ByteArray, t: ByteArray, role: String): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(pubFromBytes(identityPub)); update(t + role.toByteArray()); verify(sig)
        }
    } catch (e: Exception) { false }

    /** (phone->mac, mac->phone) */
    fun sessionKeys(ownEph: PrivateKey, peerEphPub: ByteArray, t: ByteArray): Pair<ByteArray, ByteArray> {
        val ze = ecdh(ownEph, peerEphPub)
        return hkdf(ze, t, "knock v1 phone->mac", 32) to hkdf(ze, t, "knock v1 mac->phone", 32)
    }
}

/** One direction of SEALED frames with its own counter (§5). */
class Channel(key: ByteArray) {
    private val key = SecretKeySpec(key, "AES")
    var counter = 0L
        private set

    private fun nonce() = ByteArray(4) + u64(counter)

    /** Complete SEALED frame, ready to write. */
    fun seal(innerType: Int, body: ByteArray): ByteArray {
        val plaintext = byteArrayOf(innerType.toByte()) + body
        val aad = byteArrayOf(Frames.SEALED.toByte()) + u32(1 + plaintext.size + 16)
        val ct = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce())); updateAAD(aad); doFinal(plaintext)
        }
        counter++
        return Frames.encode(Frames.SEALED, ct)
    }

    /** Opens a SEALED body. Throws on tampering, replay or reordering. */
    fun open(sealedBody: ByteArray): Pair<Int, ByteArray> {
        require(sealedBody.size >= 17) { "short sealed frame" }
        val aad = byteArrayOf(Frames.SEALED.toByte()) + u32(1 + sealedBody.size)
        val pt = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce())); updateAAD(aad); doFinal(sealedBody)
        }
        counter++
        return (pt[0].toInt() and 0xff) to pt.copyOfRange(1, pt.size)
    }
}

fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
fun u64(v: Long) = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }
fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
