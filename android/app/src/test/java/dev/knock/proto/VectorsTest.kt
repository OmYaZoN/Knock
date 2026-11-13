package dev.knock.proto

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the Kotlin crypto byte-for-byte against protocol/reference/vectors.json. */
class VectorsTest {
    private val v = JSONObject(File("../../protocol/reference/vectors.json").readText())
    private fun hx(k: String) = v.getString(k).unhex()
    private fun key(k: String) = KnockCrypto.privFromScalar(hx(k))

    @Test fun publicKeyEncodingRoundTrips() {
        for (k in listOf("phone_identity_pub", "mac_identity_pub", "phone_eph_pub", "mac_eph_pub")) {
            assertArrayEquals(k, hx(k), KnockCrypto.pubBytes(KnockCrypto.pubFromBytes(hx(k))))
        }
    }

    @Test fun ids() {
        assertArrayEquals(hx("phone_id"), KnockCrypto.shortId(hx("phone_identity_pub")))
        assertArrayEquals(hx("mac_id"), KnockCrypto.shortId(hx("mac_identity_pub")))
    }

    @Test fun pairing() {
        assertArrayEquals(hx("pair_commit"), KnockCrypto.pairCommit(hx("phone_identity_pub"), hx("Np")))
        assertArrayEquals(hx("pair_shared_secret"), KnockCrypto.ecdh(key("phone_identity_scalar"), hx("mac_identity_pub")))
        assertEquals(v.getString("pair_code"),
            KnockCrypto.pairCode(key("phone_identity_scalar"), hx("mac_identity_pub"), hx("Np"), hx("Nm")))
    }

    @Test fun handshake() {
        val t = KnockCrypto.transcript(hx("phone_id"), hx("phone_eph_pub"), hx("Rp"), hx("mac_eph_pub"), hx("Rm"))
        assertArrayEquals(hx("transcript"), t)
        val (p2m, m2p) = KnockCrypto.sessionKeys(key("phone_eph_scalar"), hx("mac_eph_pub"), t)
        assertArrayEquals(hx("k_p2m"), p2m)
        assertArrayEquals(hx("k_m2p"), m2p)
        assertTrue(KnockCrypto.verify(hx("mac_identity_pub"), hx("sig_mac"), t, "mac"))
        assertTrue(KnockCrypto.verify(hx("phone_identity_pub"), hx("sig_phone"), t, "phone"))
        assertFalse(KnockCrypto.verify(hx("mac_identity_pub"), hx("sig_mac"), t, "phone"))
        val own = KnockCrypto.sign(key("phone_identity_scalar"), t, "phone")
        assertTrue(KnockCrypto.verify(hx("phone_identity_pub"), own, t, "phone"))
    }

    @Test fun sealedFrames() {
        val ch = Channel(hx("k_p2m"))
        assertArrayEquals(hx("sealed_frame_0_ROUND_START_7"), ch.seal(Frames.ROUND_START, u32(7)))
        assertArrayEquals(hx("sealed_frame_1_FILE_END_sha256_hello"),
            ch.seal(Frames.FILE_END, KnockCrypto.sha256("hello".toByteArray())))

        val rx = Channel(hx("k_p2m"))
        val f0 = hx("sealed_frame_0_ROUND_START_7")
        val (t, b) = rx.open(f0.copyOfRange(5, f0.size))
        assertEquals(Frames.ROUND_START, t)
        assertArrayEquals(u32(7), b)
        assertTrue("replay must fail", runCatching { rx.open(f0.copyOfRange(5, f0.size)) }.isFailure)
    }
}
