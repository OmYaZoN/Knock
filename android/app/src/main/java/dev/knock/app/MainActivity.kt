package dev.knock.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/** Setup screen: the two permissions, pairing, and a test knock. Knocks themselves need no app open. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var pairButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        fun button(label: String, onClick: () -> Unit) =
            Button(this).apply { text = label; setOnClickListener { onClick() } }.also { col.addView(it) }

        col.addView(TextView(this).apply { text = "Knock"; textSize = 28f; typeface = Typeface.DEFAULT_BOLD })
        status = TextView(this).apply { textSize = 15f; setPadding(0, pad / 2, 0, pad) }
        col.addView(status)

        button("1. Turn on Knock in Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("2. Allow all-files access (to send originals)") {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        pairButton = button("3. Pair with your Mac") { pair() }
        button("Test: knock in 5 s") {
            val s = KnockService.instance
            if (s == null) say("Turn on Knock in Accessibility first.")
            else { s.knockLater(5000); say("Open your file and hold the top of the phone on the notch.") }
        }
        button("Forget paired Mac") { Identity(this).forgetMac(); refresh() }

        col.addView(TextView(this).apply {
            textSize = 13f
            setPadding(0, pad, 0, 0)
            text = "How to knock: open a PDF or photo, then press the top of your phone's screen on the " +
                "MacBook notch for a second. The screen goes black, flashes white, and buzzes when the Mac has it.\n\n" +
                "Knock looks at the screen only at that moment. It reads on-screen text only to find the file name " +
                "and never stores or sends it. It never sends from payment, banking or password apps."
        })
        setContentView(col)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val a11y = KnockService.instance != null
        val files = Environment.isExternalStorageManager()
        val mac = Identity(this).mac
        status.text = listOf(
            (if (a11y) "✓" else "✗") + " Accessibility " + if (a11y) "on" else "off",
            (if (files) "✓" else "✗") + " All-files access " + if (files) "on" else "off (screenshots only)",
            if (mac != null) "✓ Paired with ${mac.name}" else "✗ Not paired",
        ).joinToString("\n")
    }

    private fun say(s: String) = runOnUiThread { status.text = s }

    private fun phoneName(): String =
        Settings.Global.getString(contentResolver, "device_name") ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    private fun pair() {
        pairButton.isEnabled = false
        say("Looking for your Mac…")
        thread {
            val me = Identity(this)
            var link: MacLink? = null
            try {
                val found = Discovery(this).find(null, 6000)
                    ?: throw KnockException("No Mac found. Start Knock on your Mac and use the same Wi-Fi.")
                link = MacLink.connect(found.host, found.port, 3000)
                val offer = link.pairStart(me, phoneName())
                val l = link
                runOnUiThread { confirm(found, offer, l) }
            } catch (e: Exception) {
                link?.close()
                done((e as? KnockException)?.message ?: "Pairing failed: ${e.message}")
            }
        }
    }

    private fun confirm(found: Discovery.Found, offer: MacLink.PairOffer, link: MacLink) {
        val code = offer.code.substring(0, 3) + " " + offer.code.substring(3)
        AlertDialog.Builder(this)
            .setTitle("Pair with ${offer.macName}?")
            .setMessage("Check that your Mac shows the same code:\n\n$code")
            .setCancelable(false)
            .setPositiveButton("Codes match") { _, _ ->
                say("Confirm on your Mac too…")
                thread {
                    try {
                        link.pairFinish()
                        val me = Identity(this)
                        me.savePairedMac(offer.macPub, offer.macName)
                        me.lastAddress = found.host.hostAddress!! to found.port
                        done(null)
                    } catch (e: Exception) {
                        done((e as? KnockException)?.message ?: "Pairing failed: ${e.message}")
                    } finally {
                        link.close()
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ -> link.close(); done("Pairing cancelled.") }
            .show()
    }

    private fun done(error: String?) = runOnUiThread {
        pairButton.isEnabled = true
        refresh()
        if (error != null) status.append("\n\n$error")
    }
}
