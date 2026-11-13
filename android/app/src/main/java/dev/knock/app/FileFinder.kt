package dev.knock.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore

/**
 * Finds the original of what is on screen by looking up on-screen text as a file name (protocol §8).
 * The texts are used only for this query and are never stored or logged.
 */
object FileFinder {
    class Hit(val uri: Uri, val name: String, val mime: String, val folder: String)

    private val hasExt = Regex("\\.[A-Za-z0-9]{2,5}$")

    fun find(ctx: Context, texts: List<String>): Hit? {
        if (!Environment.isExternalStorageManager()) return null
        val base = MediaStore.Files.getContentUri("external")
        val cols = arrayOf(FileColumns._ID, FileColumns.DISPLAY_NAME, FileColumns.MIME_TYPE, FileColumns.RELATIVE_PATH)
        for (t in texts) {
            // Drive shows "name.pdf"; Samsung Notes and others show the name without its extension.
            val (sel, arg) = if (hasExt.containsMatchIn(t)) "${FileColumns.DISPLAY_NAME} = ?" to t
                             else "${FileColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'" to escapeLike(t) + ".%"
            val where = "$sel AND ${FileColumns.SIZE} > 0 AND ${FileColumns.MIME_TYPE} IS NOT NULL"
            ctx.contentResolver.query(base, cols, where, arrayOf(arg), "${FileColumns.DATE_MODIFIED} DESC")?.use { c ->
                if (c.moveToFirst()) {
                    return Hit(
                        ContentUris.withAppendedId(base, c.getLong(0)),
                        c.getString(1), c.getString(2), c.getString(3) ?: "",
                    )
                }
            }
        }
        return null
    }

    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

/** Apps Knock never captures: payments, banking, passwords, calls, and the system shade. */
object SkipList {
    private val exact = setOf(
        "com.google.android.apps.nbu.paisa.user", // Google Pay (India)
        "com.google.android.apps.walletnfcrel",   // Google Wallet
        "com.samsung.android.spay",               // Samsung Wallet
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp",                     // BHIM
        "in.amazon.mShop.android.shopping",
        "com.x8bit.bitwarden",
        "com.agilebits.onepassword",
        "com.lastpass.lpandroid",
        "com.android.systemui",
        "com.samsung.android.incallui",
        "com.android.incallui",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
    )
    private val words = listOf("bank", "upi", "wallet", "pay")

    fun blocks(pkg: String?): Boolean {
        if (pkg == null) return true
        if (pkg in exact) return true
        val p = pkg.lowercase()
        return words.any { p.contains(it) }
    }
}
