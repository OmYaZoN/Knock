package dev.tapdrop.spike

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Spike #2: can the phone grab "whatever is on screen" at tap time without
 * asking the user for permission every single time?
 *
 * 1. "Allow screen capture" asks once (system dialog, pick ENTIRE SCREEN).
 * 2. "Capture in 5 s" -> switch to any app (e.g. your PDF viewer); 5 s later
 *    the phone buzzes and saves a PNG to Pictures/TapDropSpike.
 * Repeat step 2 several times. The log says whether the session survived.
 */
class MainActivity : Activity() {
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        fun button(label: String, onClick: () -> Unit) =
            col.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })

        button("1. Allow screen capture (pick Entire screen)") {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        }
        button("2. Capture in 5 s (switch to another app now)") { send(CaptureService.ACTION_CAPTURE) }
        button("Stop session") { send(CaptureService.ACTION_STOP) }
        button("A. Turn on Accessibility capture (Settings)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("B. Accessibility capture in 5 s (no dialog)") {
            A11yCaptureService.instance?.captureIn5s()
                ?: CaptureService.log("Accessibility capture is off. Tap A, open Installed apps > TapDrop Spike capture, turn it on.")
        }
        button("C. Allow all-files access (to find the original)") {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }

        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        col.addView(ScrollView(this).apply { addView(log) })
        setContentView(col)

        CaptureService.listener = { line -> runOnUiThread { log.append(line + "\n") } }
        CaptureService.log("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    override fun onDestroy() {
        CaptureService.listener = null
        super.onDestroy()
    }

    private fun send(action: String) {
        startService(Intent(this, CaptureService::class.java).setAction(action))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            CaptureService.log("Consent refused or cancelled.")
            return
        }
        CaptureService.consentCount++
        CaptureService.log("Consent granted (dialog #${CaptureService.consentCount} this run).")
        startForegroundService(
            Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra("code", resultCode)
                .putExtra("data", data)
        )
    }

    companion object { private const val REQ_CAPTURE = 1 }
}
