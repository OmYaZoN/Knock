package dev.knock.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Finds Knock Macs on the Wi-Fi via Bonjour `_knock._tcp` (protocol §1). Blocking: call off the main thread. */
class Discovery(ctx: Context) {
    private val nsd = ctx.getSystemService(NsdManager::class.java)

    class Found(val host: InetAddress, val port: Int, val id: String, val name: String)

    /** First Mac whose TXT `id` equals [wantId], or the first Mac at all when [wantId] is null. */
    fun find(wantId: String?, timeoutMs: Long): Found? {
        val queue = LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) { queue.add(s) }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        nsd.discoverServices("_knock._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        try {
            while (true) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) return null
                val s = queue.poll(left, TimeUnit.MILLISECONDS) ?: return null
                val r = resolve(s, minOf(left, 2000)) ?: continue
                val id = r.attributes["id"]?.let { String(it) } ?: continue
                if (wantId != null && id != wantId) continue
                @Suppress("DEPRECATION")
                val host = r.host ?: continue
                val name = r.attributes["name"]?.let { String(it) } ?: r.serviceName
                return Found(host, r.port, id, name)
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private fun resolve(s: NsdServiceInfo, timeoutMs: Long): NsdServiceInfo? {
        val done = CountDownLatch(1)
        var out: NsdServiceInfo? = null
        @Suppress("DEPRECATION")
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onServiceResolved(r: NsdServiceInfo) { out = r; done.countDown() }
            override fun onResolveFailed(r: NsdServiceInfo, e: Int) { done.countDown() }
        })
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out
    }
}
