package com.example.gallery

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    private const val TAG = "GalleryDbg"

    fun binAvailable() = Build.VERSION.SDK_INT >= 30

    /** Active (non-trashed) item ache kina. Error hole conservative: ache dhora hoy. */
    fun exists(ctx: Context, u: Uri): Boolean = try {
        val clean = u.buildUpon().clearQuery().build()
        if (Build.VERSION.SDK_INT >= 30) {
            val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
            ctx.contentResolver.query(clean, arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)?.use { c ->
                c.moveToFirst() && c.getInt(0) == 0
            } ?: false
        } else {
            ctx.contentResolver.query(clean, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } ?: false
        }
    } catch (e: Exception) {
        android.util.Log.w(TAG, "exists() failed: ${e.javaClass.simpleName}: ${e.message}")
        true
    }

    private fun trashDirect(cr: android.content.ContentResolver, u: Uri): Boolean = try {
        val v = android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }
        cr.update(u, v, null, null) > 0
    } catch (e: Exception) {
        android.util.Log.w(TAG, "direct trash not allowed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private fun deleteDirect(cr: android.content.ContentResolver, u: Uri): Boolean = try {
        cr.delete(u, null, null) > 0
    } catch (e: Exception) {
        android.util.Log.w(TAG, "direct delete not allowed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    /** null = ar kono authorization lagbe na. Non-null = system authorization launch korte hobe. */
    fun perform(ctx: Context, uris: List<Uri>, toBin: Boolean): IntentSender? {
        val cr = ctx.contentResolver
        var todo = uris.filter { exists(ctx, it) }
        if (todo.isEmpty()) return null

        if (Build.VERSION.SDK_INT >= 30) {
            todo = todo.filter { u ->
                val ok = if (toBin) trashDirect(cr, u) else deleteDirect(cr, u)
                !ok || exists(ctx, u)
            }
            if (todo.isEmpty()) return null
            return try {
                if (toBin) MediaStore.createTrashRequest(cr, todo, true).intentSender
                else MediaStore.createDeleteRequest(cr, todo).intentSender
            } catch (e: Exception) {
                android.util.Log.e(TAG, "system request failed (${todo.size} items)", e)
                null
            }
        }

        for (u in todo) {
            try {
                cr.delete(u, null, null)
            } catch (e: SecurityException) {
                android.util.Log.w(TAG, "legacy delete denied: ${e.javaClass.simpleName}")
                if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                    return e.userAction.actionIntent.intentSender
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "legacy delete failed", e)
            }
        }
        return null
    }
    fun performWrite(ctx: Context, uris: List<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < 30) return null
        val todo = uris.filter { exists(ctx, it) }
        if (todo.isEmpty()) return null
        return try {
            MediaStore.createWriteRequest(ctx.contentResolver, todo).intentSender
        } catch (e: Exception) {
            android.util.Log.e(TAG, "write request failed (${todo.size} items)", e)
            null
        }
    }

    fun trashNow(ctx: Context, uris: List<Uri>) {
        val cr = ctx.contentResolver
        for (u in uris) {
            if (exists(ctx, u)) {
                val ok = trashDirect(cr, u)
                android.util.Log.i(TAG, "trashNow ok=$ok")
            }
        }
    }

    fun delete(ctx: Context, uris: List<Uri>): IntentSender? = perform(ctx, uris, binAvailable())
}

object Share {
    fun send(c: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        i.type = "*/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(i, "Share"))
    }
}

fun countLabel(total: Int, videos: Int): String {
    val nf = java.text.NumberFormat.getInstance()
    val p = total - videos
    val parts = ArrayList<String>()
    if (p > 0) parts.add("${nf.format(p)} ${if (p == 1) "photo" else "photos"}")
    if (videos > 0) parts.add("${nf.format(videos)} ${if (videos == 1) "video" else "videos"}")
    return if (parts.isEmpty()) "0" else parts.joinToString(", ")
}

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}