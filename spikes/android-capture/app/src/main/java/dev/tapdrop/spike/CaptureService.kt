package dev.tapdrop.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Holds one MediaProjection session open and keeps the newest screen frame ready. */
class CaptureService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_CAPTURE = "capture"
        const val ACTION_STOP = "stop"
        var listener: ((String) -> Unit)? = null
        var consentCount = 0
        private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        fun log(s: String) {
            val line = "${fmt.format(Date())}  $s"
            Log.i("TapDropSpike", line)
            listener?.invoke(line)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var imgThread: HandlerThread? = null
    private var latest: Image? = null
    private var shots = 0
    private var userStopped = false
    private var sessionStart = 0L

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_CAPTURE ->
                if (projection == null) log("No capture session. Tap step 1 first.")
                else { log("Capturing in 5 s..."); main.postDelayed({ capture() }, 5000) }
            ACTION_STOP -> { log("Stopping session."); stopAll() }
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        // Android 14+: the foreground service must be running BEFORE getMediaProjection().
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cap", "Screen capture", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "cap")
            .setContentTitle("TapDrop spike")
            .setContentText("Screen capture session running")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        userStopped = true
        projection?.stop()
        cleanup() // a second consent replaces any old session
        userStopped = false
        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java)
                            else @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        if (data == null) { log("Missing consent data."); stopAll(); return }

        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (userStopped) return
                log("SESSION ENDED by the system after $shots capture(s), ${(SystemClock.elapsedRealtime() - sessionStart) / 1000} s. Next capture needs the dialog again.")
                cleanup()
            }
        }, main)

        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val w = bounds.width(); val h = bounds.height()
        val t = HandlerThread("frames").also { it.start() }
        imgThread = t
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            synchronized(lock) { latest?.close(); latest = img }
        }, Handler(t.looper))
        display = p.createVirtualDisplay("tapdrop", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        sessionStart = SystemClock.elapsedRealtime()
        shots = 0
        log("Session started, ${w}x$h. Tap 'Capture in 5 s' and switch apps.")
    }

    private fun capture() {
        if (projection == null) { log("Session is gone, capture skipped."); return }
        val bmp: Bitmap
        val ageMs: Long
        synchronized(lock) {
            val img = latest ?: run { log("No frame received yet."); return }
            ageMs = (System.nanoTime() - img.timestamp) / 1_000_000
            val plane = img.planes[0]
            val rowPadPx = (plane.rowStride - plane.pixelStride * img.width) / plane.pixelStride
            val padded = Bitmap.createBitmap(img.width + rowPadPx, img.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer.rewind())
            bmp = Bitmap.createBitmap(padded, 0, 0, img.width, img.height)
        }
        val mean = meanBrightness(bmp)
        val name = "tapdrop_${System.currentTimeMillis()}.png"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TapDropSpike")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        uri?.let { contentResolver.openOutputStream(it)?.use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) } }
        shots++
        val note = if (mean < 3) "  BLACK FRAME (app blocks capture?)" else ""
        log("Capture #$shots saved Pictures/TapDropSpike/$name, newest frame ${ageMs} ms old, brightness ${"%.0f".format(mean)}$note")
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Average 0..255 brightness over a coarse grid; ~0 means the frame came back black. */
    private fun meanBrightness(b: Bitmap): Double {
        var sum = 0.0; var n = 0
        val stepX = maxOf(1, b.width / 32); val stepY = maxOf(1, b.height / 64)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                sum += ((c shr 16 and 0xff) + (c shr 8 and 0xff) + (c and 0xff)) / 3.0; n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun cleanup() {
        display?.release(); display = null
        synchronized(lock) { latest?.close(); latest = null }
        reader?.close(); reader = null
        imgThread?.quitSafely(); imgThread = null
        projection = null
    }

    private fun stopAll() {
        val p = projection
        userStopped = true
        cleanup()
        p?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }
}
