package dev.knock.app

import dev.knock.proto.Channel
import dev.knock.proto.Frames
import dev.knock.proto.KnockCrypto
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.interfaces.ECPublicKey

/** Problem the person can act on; the message is shown as is. */
class KnockException(message: String) : Exception(message)

/** One TCP connection to the Mac: pairing (protocol §3) or an encrypted session (§4-5). Blocking. */
class MacLink private constructor(private val sock: Socket) : Closeable {
    private val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
    private val output = BufferedOutputStream(sock.getOutputStream())
    private var tx: Channel? = null
    private var rx: Channel? = null

    companion object {
        fun connect(host: InetAddress, port: Int, timeoutMs: Int): MacLink {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (e: IOException) {
                s.close(); throw e
            }
            s.tcpNoDelay = true
            s.soTimeout = 10_000
            return MacLink(s)
        }
    }

    private fun write(frame: ByteArray) { output.write(frame); output.flush() }

    private fun expect(type: Int): ByteArray {
        val (t, body) = Frames.read(input)
        if (t != type) throw IOException("expected frame 0x%02x, got 0x%02x".format(type, t))
        return body
    }

    // Pairing (§3)

    class PairOffer(val code: String, val macPub: ByteArray, val macName: String)

    /** Steps 1-3. Returns the code to compare; the Mac shows the same one. */
    fun pairStart(me: Identity, phoneName: String): PairOffer {
        val np = KnockCrypto.random(16)
        write(Frames.encode(Frames.PAIR_COMMIT, KnockCrypto.pairCommit(me.pub, np)))
        val b = expect(Frames.PAIR_MAC)
        if (b.size < 81) throw IOException("short PAIR_MAC")
        val macPub = b.copyOfRange(0, 65)
        val nm = b.copyOfRange(65, 81)
        val name = String(b.copyOfRange(81, b.size), Charsets.UTF_8)
        write(Frames.encode(Frames.PAIR_PHONE, me.pub + np + phoneName.toByteArray(Charsets.UTF_8)))
        return PairOffer(KnockCrypto.pairCode(me.key, macPub, np, nm), macPub, name)
    }

    /** The person said the codes match: send PAIR_OK and wait (up to 60 s) for the Mac's. */
    fun pairFinish() {
        write(Frames.encode(Frames.PAIR_OK, ByteArray(0)))
        sock.soTimeout = 60_000
        try {
            expect(Frames.PAIR_OK)
        } catch (e: IOException) {
            throw KnockException("Pairing was cancelled on the Mac.")
        }
    }

    // Session (§4)

    fun handshake(me: Identity, macPub: ByteArray) {
        val eph = KnockCrypto.generate()
        val ephPub = KnockCrypto.pubBytes(eph.public as ECPublicKey)
        val rp = KnockCrypto.random(16)
        val phoneId = me.shortId
        write(Frames.encode(Frames.HELLO, phoneId + ephPub + rp))
        val b = try {
            expect(Frames.HELLO_MAC)
        } catch (e: IOException) {
            throw KnockException("Your Mac doesn't know this phone any more. Pair again in the Knock app.")
        }
        if (b.size <= 81) throw IOException("short HELLO_MAC")
        val macEph = b.copyOfRange(0, 65)
        val rm = b.copyOfRange(65, 81)
        val sig = b.copyOfRange(81, b.size)
        val t = KnockCrypto.transcript(phoneId, ephPub, rp, macEph, rm)
        if (!KnockCrypto.verify(macPub, sig, t, "mac")) {
            throw KnockException("Something on your network pretended to be your Mac. Nothing was sent.")
        }
        write(Frames.encode(Frames.HELLO_FIN, KnockCrypto.sign(me.key, t, "phone")))
        val (p2m, m2p) = KnockCrypto.sessionKeys(eph.private, macEph, t)
        tx = Channel(p2m); rx = Channel(m2p)
    }

    fun send(innerType: Int, body: ByteArray) = write(tx!!.seal(innerType, body))

    fun recv(): Pair<Int, ByteArray> {
        val (t, body) = Frames.read(input)
        if (t != Frames.SEALED) throw IOException("unsealed frame in session")
        return rx!!.open(body)
    }

    override fun close() { runCatching { sock.close() } }
}
