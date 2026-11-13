package dev.knock.app

import android.content.Context
import dev.knock.proto.KnockCrypto
import dev.knock.proto.hex
import dev.knock.proto.unhex
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

/** This phone's identity key and the Mac it is paired with (protocol §3). App-private storage. */
class Identity(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("knock", Context.MODE_PRIVATE)

    val key: ECPrivateKey
    val pub: ByteArray

    init {
        val s = prefs.getString("id_scalar", null)
        val p = prefs.getString("id_pub", null)
        if (s != null && p != null) {
            key = KnockCrypto.privFromScalar(s.unhex()); pub = p.unhex()
        } else {
            val kp = KnockCrypto.generate()
            key = kp.private as ECPrivateKey
            pub = KnockCrypto.pubBytes(kp.public as ECPublicKey)
            prefs.edit().putString("id_scalar", KnockCrypto.scalar(key).hex()).putString("id_pub", pub.hex()).apply()
        }
    }

    val shortId: ByteArray get() = KnockCrypto.shortId(pub)

    class Mac(val pub: ByteArray, val name: String) {
        val id: String get() = KnockCrypto.shortId(pub).hex()
    }

    val mac: Mac?
        get() {
            val p = prefs.getString("mac_pub", null) ?: return null
            return Mac(p.unhex(), prefs.getString("mac_name", "Mac")!!)
        }

    fun savePairedMac(pub: ByteArray, name: String) {
        prefs.edit().putString("mac_pub", pub.hex()).putString("mac_name", name)
            .remove("mac_host").remove("mac_port").apply()
    }

    fun forgetMac() {
        prefs.edit().remove("mac_pub").remove("mac_name").remove("mac_host").remove("mac_port").apply()
    }

    /** Last address that worked, tried before a fresh Bonjour search. */
    var lastAddress: Pair<String, Int>?
        get() {
            val h = prefs.getString("mac_host", null) ?: return null
            return h to prefs.getInt("mac_port", 0)
        }
        set(v) {
            prefs.edit().apply {
                if (v == null) remove("mac_host").remove("mac_port")
                else putString("mac_host", v.first).putInt("mac_port", v.second)
            }.apply()
        }
}
