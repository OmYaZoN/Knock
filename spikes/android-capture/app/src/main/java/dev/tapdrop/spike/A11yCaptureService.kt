package dev.tapdrop.spike

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Option B for "grab what's on screen": an Accessibility service.
 * Turned on once in Settings > Accessibility; after that, takeScreenshot()
 * needs no per-session consent dialog and shows no screen-sharing banner.
 * It can also read the title of the app window on screen (useful for
 * finding the original document later).
 */
class A11yCaptureService : AccessibilityService() {
    companion object { var instance: A11yCaptureService? = null }

    private var shots = 0

    override fun onServiceConnected() {
        instance = this
        CaptureService.log("[A11y] Accessibility capture is ON.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        CaptureService.log("[A11y] Accessibility capture turned OFF.")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun captureIn5s() {
        CaptureService.log("[A11y] Capturing in 5 s...")
        Handler(Looper.getMainLooper()).postDelayed({ grab() }, 5000)
    }

    private fun foregroundWindows(): String =
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .joinToString(" | ") { w -> "${w.root?.packageName ?: "?"} \"${w.title ?: ""}\"" }
            .ifEmpty { "(none)" }

    private fun grab() {
        val fg = foregroundWindows()
        val t0 = SystemClock.elapsedRealtime()
        findOriginal()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hb = result.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                if (bmp == null) { CaptureService.log("[A11y] Got a screenshot but could not read it."); return }
                val (name, mean) = Shots.save(this@A11yCaptureService, bmp)
                shots++
                val note = if (mean < 3) "  BLACK FRAME" else ""
                CaptureService.log("[A11y] Capture #$shots saved $name in ${SystemClock.elapsedRealtime() - t0} ms, brightness ${"%.0f".format(mean)}$note. Window: $fg")
                getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            }

            override fun onFailure(errorCode: Int) {
                val why = when (errorCode) {
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> " (this app blocks screenshots)"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> " (too soon after the last one)"
                    else -> ""
                }
                CaptureService.log("[A11y] Capture FAILED, code $errorCode$why. Window: $fg")
            }
        })
    }
}

/** Collects visible text on screen (viewer toolbars usually show the file name). */
private fun AccessibilityService.screenTexts(): List<String> {
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

/** Looks each on-screen text up as a file name on the phone and logs any hits. */
private fun AccessibilityService.findOriginal() {
    val texts = screenTexts()
    CaptureService.log("[File] On-screen text: " + texts.take(12).joinToString(" · ") { "\"$it\"" })
    if (!Environment.isExternalStorageManager()) {
        CaptureService.log("[File] All-files access is OFF, so I can't search. Tap C first.")
        return
    }
    val uri = MediaStore.Files.getContentUri("external")
    val cols = arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.RELATIVE_PATH, MediaStore.Files.FileColumns.SIZE)
    var hits = 0
    for (t in texts) {
        val hasExt = Regex("\\.[A-Za-z0-9]{2,5}$").containsMatchIn(t)
        val (sel, arg) = if (hasExt) "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?" to t
                         else "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?" to "$t.%"
        contentResolver.query(uri, cols, sel, arrayOf(arg), null)?.use { c ->
            while (c.moveToNext() && hits < 5) {
                hits++
                CaptureService.log("[File] MATCH for \"$t\": ${c.getString(1) ?: ""}${c.getString(0)} (${c.getLong(2) / 1024} KB)")
            }
        }
        if (hits >= 5) break
    }
    if (hits == 0) CaptureService.log("[File] No file on the phone matches the on-screen text. This would fall back to a screenshot.")
}

/** Saves a bitmap to Pictures/TapDropSpike and returns (path, mean brightness 0..255). */
object Shots {
    fun save(ctx: Context, bmp: Bitmap): Pair<String, Double> {
        val name = "tapdrop_${System.currentTimeMillis()}.png"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TapDropSpike")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        uri?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) } }
        return "Pictures/TapDropSpike/$name" to mean(bmp)
    }

    private fun mean(b: Bitmap): Double {
        var sum = 0.0; var n = 0
        val sx = maxOf(1, b.width / 32); val sy = maxOf(1, b.height / 64)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                sum += ((c shr 16 and 0xff) + (c shr 8 and 0xff) + (c and 0xff)) / 3.0; n++
                x += sx
            }
            y += sy
        }
        return if (n == 0) 0.0 else sum / n
    }
}
