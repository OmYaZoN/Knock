package dev.knock.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Choreographer
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import dev.knock.proto.Frames
import dev.knock.proto.KnockCrypto
import dev.knock.proto.u32
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * The phone side of a knock (protocol §6-8). Turned on once in Settings > Accessibility, which lets it
 * take a screenshot with no dialog, read on-screen text, and draw the black/white overlay.
 */
class KnockService : AccessibilityService(), SensorEventListener {
    companion object {
        @Volatile var instance: KnockService? = null
        private const val CHUNK = 256 * 1024

        fun sentence(reason: Int) = when (reason) {
            1 -> "Press the top of your phone's screen flat on the notch."
            2 -> "Your Mac didn't see your screen. Turn it around and press it on the notch."
            3 -> "Keep your phone on the notch until it buzzes."
            else -> "That tap didn't count. Try again."
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    private var lastRound = 0L
    private var overlay: View? = null

    private lateinit var sensors: SensorManager
    private var proximity: Sensor? = null
    private var wasNear = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON) listen(true) else listen(false)
        }
    }

    override fun onServiceConnected() {
        instance = this
        sensors = getSystemService(SensorManager::class.java)
        proximity = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        })
        listen(getSystemService(PowerManager::class.java).isInteractive)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        listen(false)
        runCatching { unregisterReceiver(screenReceiver) }
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // Trigger: proximity sensor goes near while the screen is on (the top of the phone touches the notch).

    private fun listen(on: Boolean) {
        val p = proximity ?: return
        if (on) sensors.registerListener(this, p, SensorManager.SENSOR_DELAY_GAME)
        else { sensors.unregisterListener(this); wasNear = false }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val near = e.values[0] < minOf(e.sensor.maximumRange, 5f)
        if (near && !wasNear) knock(fromButton = false)
        wasNear = near
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /** In-app test: gives the person [delayMs] to switch to their file and hold the phone on the notch. */
    fun knockLater(delayMs: Long) { main.postDelayed({ knock(fromButton = true) }, delayMs) }

    // The round

    private fun knock(fromButton: Boolean) {
        if (busy || SystemClock.elapsedRealtime() - lastRound < 2000) return
        if (Identity(this).mac == null) {
            if (fromButton) toast("Pair with your Mac first in the Knock app.")
            return
        }
        val pkg = rootInActiveWindow?.packageName?.toString()
        if (pkg == packageName && !fromButton) return
        if (pkg != packageName && SkipList.blocks(pkg)) {
            if (fromButton) toast("Knock never sends from this app.")
            return
        }
        busy = true
        // §8: grab the screen once, now, before our own overlay covers it. Memory only.
        val texts = screenTexts()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val bmp = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                r.hardwareBuffer.close()
                showOverlay()
                worker.execute { round(texts, bmp, fromButton) }
            }

            override fun onFailure(code: Int) {
                busy = false
                if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) toast("This app blocks screenshots, so Knock won't send from it.")
                else if (fromButton) toast("Couldn't read the screen. Try again.")
            }
        })
    }

    private fun round(texts: List<String>, shot: Bitmap?, fromButton: Boolean) {
        var link: MacLink? = null
        try {
            val me = Identity(this)
            val mac = me.mac ?: throw KnockException("Pair with your Mac first in the Knock app.")
            link = connect(me, mac)
            link.handshake(me, mac.pub)

            val roundId = u32(KnockCrypto.random(4).fold(0) { a, b -> (a shl 8) or (b.toInt() and 0xff) } and 0x7fffffff)
            link.send(Frames.ROUND_START, roundId)
            var ok = false
            var reason = 4
            while (true) {
                val (t, b) = link.recv()
                if (t == Frames.SHOW && b.size == 5) {
                    setColor(if (b[4].toInt() == 1) Color.WHITE else Color.BLACK)
                    link.send(Frames.SHOWN, b)
                } else if (t == Frames.ROUND_RESULT && b.size == 6) {
                    ok = b[4].toInt() == 1
                    reason = b[5].toInt()
                    break
                }
            }
            lastRound = SystemClock.elapsedRealtime()
            main.post { hideOverlay() }
            if (!ok) {
                // A hand over the proximity sensor is not a knock; only explain failures that look intended.
                if (fromButton || reason != 1) toast(sentence(reason))
                return
            }
            buzz()
            sendFile(link, texts, shot)
        } catch (e: KnockException) {
            toast(e.message!!)
        } catch (e: Exception) {
            toast(if (link == null) "Can't reach your Mac. Is Knock running there, on the same Wi-Fi?"
                  else "The connection to your Mac dropped. Try again.")
        } finally {
            link?.close()
            shot?.recycle()
            main.post { hideOverlay(); busy = false }
        }
    }

    private fun connect(me: Identity, mac: Identity.Mac): MacLink {
        me.lastAddress?.let { (h, p) ->
            runCatching { return MacLink.connect(InetAddress.getByName(h), p, 800) }
        }
        val found = Discovery(this).find(mac.id, 4000)
            ?: throw KnockException("Can't find ${mac.name}. Is Knock running there, on the same Wi-Fi?")
        val link = MacLink.connect(found.host, found.port, 2000)
        me.lastAddress = found.host.hostAddress!! to found.port
        return link
    }

    private fun sendFile(link: MacLink, texts: List<String>, shot: Bitmap?) {
        val hit = FileFinder.find(this, texts)
        val header = JSONObject()
        val stream: InputStream
        val size: Long
        if (hit != null) {
            size = contentResolver.openFileDescriptor(hit.uri, "r")!!.use { it.statSize }
            stream = contentResolver.openInputStream(hit.uri)!!
            header.put("name", hit.name).put("mime", hit.mime).put("kind", "original").put("from", hit.folder)
        } else {
            val bmp = shot ?: throw KnockException("Couldn't find the file or read the screen. Try again.")
            val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            size = png.size.toLong()
            stream = png.inputStream()
            val stamp = SimpleDateFormat("yyyy-MM-dd 'at' HH.mm.ss", Locale.US).format(Date())
            header.put("name", "Screenshot $stamp.png").put("mime", "image/png").put("kind", "screenshot").put("from", "")
        }
        header.put("size", size)
        link.send(Frames.FILE_HEADER, header.toString().toByteArray(Charsets.UTF_8))
        val sha = MessageDigest.getInstance("SHA-256")
        stream.use { s ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = s.readNBytesCompat(buf)
                if (n <= 0) break
                sha.update(buf, 0, n)
                link.send(Frames.FILE_CHUNK, buf.copyOf(n))
            }
        }
        link.send(Frames.FILE_END, sha.digest())
        val (t, b) = link.recv()
        if (t == Frames.FILE_SAVED && b.isNotEmpty() && b[0].toInt() == 1) {
            toast("Sent to Mac: " + String(b, 1, b.size - 1, Charsets.UTF_8))
        } else {
            toast("Your Mac couldn't save the file. Try again.")
        }
    }

    // Screen

    /** Visible text, used only to find the file name (§8). Never stored or logged. */
    private fun screenTexts(): List<String> {
        val out = LinkedHashSet<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || out.size >= 40) return
            listOf(n.text, n.contentDescription).forEach { t ->
                val s = t?.toString()?.trim()
                if (!s.isNullOrEmpty() && s.length in 3..120) out.add(s)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.forEach { walk(it.root, 0) }
        return out.toList()
    }

    private fun showOverlay() {
        if (overlay != null) return
        val v = View(this).apply { setBackgroundColor(Color.BLACK) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            screenBrightness = 1f
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        getSystemService(WindowManager::class.java).addView(v, lp)
        overlay = v
    }

    private fun hideOverlay() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
    }

    /** Changes the overlay colour and returns once it has been drawn, so SHOWN is honest. */
    private fun setColor(c: Int) {
        val drawn = CountDownLatch(1)
        main.post {
            overlay?.setBackgroundColor(c)
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback { drawn.countDown() }
            }
        }
        drawn.await(300, TimeUnit.MILLISECONDS)
    }

    private fun buzz() {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
}

/** InputStream.readNBytes needs API 33; fill the buffer as far as the stream allows. */
private fun InputStream.readNBytesCompat(buf: ByteArray): Int {
    var n = 0
    while (n < buf.size) {
        val r = read(buf, n, buf.size - n)
        if (r < 0) break
        n += r
    }
    return n
}
