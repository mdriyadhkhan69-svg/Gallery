package com.example.gallery

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Colors {
    val BLUE = 0xFF3D8BFF.toInt()
    val GRAY = 0xFF8A8A8A.toInt()
    val HEART = 0xFFFF4D6D.toInt()
}

object Fonts {
    private var tf: Typeface? = null
    /** assets/fonts/display.ttf thakle seta, na thakle default bold */
    fun display(c: Context): Typeface {
        tf?.let { return it }
        val t = try { Typeface.createFromAsset(c.assets, "fonts/display.ttf") } catch (e: Exception) { Typeface.DEFAULT_BOLD }
        tf = t
        return t
    }
}

fun TextView.useDisplay() { typeface = Fonts.display(context) }

fun Activity.edgeToEdge(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
        val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(b.left, b.top, b.right, b.bottom)
        WindowInsetsCompat.CONSUMED
    }
}

private const val DAY = 86_400_000L

fun dayNum(ms: Long): Long = (ms + TimeZone.getDefault().getOffset(ms)) / DAY

private fun yearOf(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR)

fun dayLabel(ms: Long): String {
    val d = dayNum(ms)
    val t = dayNum(System.currentTimeMillis())
    return when {
        d == t -> "Today"
        d == t - 1 -> "Yesterday"
        else -> {
            val same = yearOf(ms) == yearOf(System.currentTimeMillis())
            SimpleDateFormat(if (same) "EEE, d MMM" else "EEE, d MMM yyyy", Locale.getDefault()).format(Date(ms))
        }
    }
}

fun monthKey(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH)}"
}

fun monthTitle(ms: Long): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(ms))

fun rangeLabel(a: Long, b: Long): String {
    val loc = Locale.getDefault()
    val f1 = SimpleDateFormat(if (yearOf(a) == yearOf(b)) "d MMM" else "d MMM yyyy", loc)
    val f2 = SimpleDateFormat("d MMM yyyy", loc)
    return "${f1.format(Date(a))} – ${f2.format(Date(b))}"
}

object Deleter {
    /** IntentSender return korle user confirmation lagbe (launch korte hobe). null hole direct delete hoyeche. */
    fun delete(ctx: Context, uris: List<Uri>): IntentSender? {
        val cr = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 30) return MediaStore.createDeleteRequest(cr, uris).intentSender
        for (u in uris) {
            try {
                cr.delete(u, null, null)
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                    return e.userAction.actionIntent.intentSender
                }
            }
        }
        return null
    }
}

object Share {
    fun send(c: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        i.type = "image/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(i, "Share"))
    }
}