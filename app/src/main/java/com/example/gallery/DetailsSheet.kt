package com.example.gallery

import android.content.Context
import android.database.Cursor
import android.graphics.Color
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import android.graphics.drawable.GradientDrawable

object DetailsSheet {

    private var busy = false

    fun show(act: AppCompatActivity, m: Media, actions: List<Pair<String, () -> Unit>> = emptyList()) {
        if (busy) return
        busy = true
        val app = act.applicationContext
        Thread {
            val rows = try { load(app, m) } catch (e: Throwable) { listOf("Name" to m.name) }
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) { busy = false; return@runOnUiThread }
                display(act, rows, actions)
            }
        }.start()
    }

    private fun Cursor.str(n: String): String? {
        val i = getColumnIndex(n)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.lng(n: String): Long {
        val i = getColumnIndex(n)
        return if (i < 0 || isNull(i)) 0L else getLong(i)
    }

    private fun fmtDate(ms: Long): String =
        SimpleDateFormat("EEE, d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(ms))

    @Suppress("DEPRECATION")
    private fun load(ctx: Context, m: Media): List<Pair<String, String>> {
        val cr = ctx.contentResolver
        var name = m.name
        var mime: String? = try { cr.getType(m.uri) } catch (e: Exception) { null }
        var size = m.size
        var w = m.w
        var h = m.h
        var taken = 0L
        var added = 0L
        var modified = 0L
        var path: String? = null
        var album: String? = null

        val cols = arrayListOf(
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATA
        )
        if (Build.VERSION.SDK_INT >= 29) cols.add(MediaStore.MediaColumns.RELATIVE_PATH)
        try {
            cr.query(m.uri, cols.toTypedArray(), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.str(MediaStore.MediaColumns.DISPLAY_NAME)?.let { name = it }
                    c.str(MediaStore.MediaColumns.MIME_TYPE)?.let { mime = it }
                    c.lng(MediaStore.MediaColumns.SIZE).takeIf { it > 0 }?.let { size = it }
                    c.lng(MediaStore.MediaColumns.WIDTH).takeIf { it > 0 }?.let { w = it.toInt() }
                    c.lng(MediaStore.MediaColumns.HEIGHT).takeIf { it > 0 }?.let { h = it.toInt() }
                    taken = c.lng(MediaStore.MediaColumns.DATE_TAKEN)
                    added = c.lng(MediaStore.MediaColumns.DATE_ADDED) * 1000
                    modified = c.lng(MediaStore.MediaColumns.DATE_MODIFIED) * 1000
                    album = c.str(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    val data = c.str(MediaStore.MediaColumns.DATA)
                    path = if (!data.isNullOrEmpty()) data else {
                        val rel = if (Build.VERSION.SDK_INT >= 29) c.str(MediaStore.MediaColumns.RELATIVE_PATH) else null
                        if (rel != null) rel + name else null
                    }
                }
            }
        } catch (e: Exception) { }

        val extra = ArrayList<Pair<String, String>>()
        var exifDate = 0L

        if (!m.video) {
            try {
                cr.openInputStream(m.uri)?.use { s ->
                    val ex = ExifInterface(s)
                    val make = ex.getAttribute(ExifInterface.TAG_MAKE)
                    val model = ex.getAttribute(ExifInterface.TAG_MODEL)
                    val cam = listOfNotNull(make, model).joinToString(" ").trim()
                    if (cam.isNotEmpty()) extra.add("Camera" to cam)
                    ex.getAttribute(ExifInterface.TAG_F_NUMBER)?.toDoubleOrNull()?.let {
                        extra.add("Aperture" to "f/" + String.format(Locale.US, "%.1f", it))
                    }
                    ex.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let {
                        if (it > 0) extra.add("Exposure" to if (it < 1) "1/${(1 / it).roundToInt()} s" else "$it s")
                    }
                    ex.getAttribute("ISOSpeedRatings")?.let { extra.add("ISO" to it) }
                    ex.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { f ->
                        val p = f.split("/")
                        val v = if (p.size == 2) (p[0].toDoubleOrNull() ?: 0.0) / (p[1].toDoubleOrNull() ?: 1.0) else f.toDoubleOrNull()
                        if (v != null && v > 0) extra.add("Focal length" to String.format(Locale.US, "%.1f mm", v))
                    }
                    val o = when (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> "90°"
                        ExifInterface.ORIENTATION_ROTATE_180 -> "180°"
                        ExifInterface.ORIENTATION_ROTATE_270 -> "270°"
                        else -> null
                    }
                    if (o != null) extra.add("Orientation" to o)
                    ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
                        try {
                            exifDate = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time ?: 0L
                        } catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) { }
        } else {
            var dur = m.duration
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, m.uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { dur = it }
                if (w <= 0 || h <= 0) {
                    val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (vw > 0 && vh > 0) { if (rot % 180 == 90) { w = vh; h = vw } else { w = vw; h = vh } }
                }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.takeIf { it > 0 }?.let {
                    extra.add("Bitrate" to String.format(Locale.US, "%.2f Mbps", it / 1_000_000.0))
                }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.takeIf { it > 0 }?.let {
                    extra.add("Frame rate" to String.format(Locale.US, "%.0f fps", it))
                }
            } catch (e: Exception) {
            } finally {
                try { r.release() } catch (e: Exception) { }
            }
            val ex = MediaExtractor()
            try {
                ex.setDataSource(ctx, m.uri, null)
                var hasFps = extra.any { it.first == "Frame rate" }
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mt = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mt.startsWith("video/")) {
                        extra.add("Video codec" to mt.substringAfter('/').uppercase(Locale.US))
                        if (!hasFps && f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            val fr = try { f.getInteger(MediaFormat.KEY_FRAME_RATE) } catch (e: Exception) { 0 }
                            if (fr > 0) { extra.add("Frame rate" to "$fr fps"); hasFps = true }
                        }
                    } else if (mt.startsWith("audio/")) {
                        val sb = StringBuilder(mt.substringAfter('/').uppercase(Locale.US))
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sb.append(", ").append(f.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 1000.0).append(" kHz")
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            sb.append(", ").append(if (ch == 1) "Mono" else if (ch == 2) "Stereo" else "$ch channels")
                        }
                        extra.add("Audio" to sb.toString())
                    }
                }
            } catch (e: Exception) {
            } finally {
                try { ex.release() } catch (e: Exception) { }
            }
            if (dur > 0) extra.add(0, "Duration" to fmtDuration(dur))
        }

        val out = ArrayList<Pair<String, String>>()
        out.add("Name" to name)
        mime?.let { out.add("Type" to it) }
        if (size > 0) out.add("Size" to Formatter.formatShortFileSize(ctx, size))
        if (w > 0 && h > 0) out.add("Resolution" to "$w × $h")
        val capturedAt = if (taken > 0) taken else exifDate
        if (capturedAt > 0) out.add((if (m.video) "Recorded" else "Captured") to fmtDate(capturedAt))
        if (added > 0) out.add("Added to device" to fmtDate(added))
        if (modified > 0) out.add("Last modified" to fmtDate(modified))
        album?.let { out.add("Album" to it) }
        path?.let { out.add("Location" to it) }
        out.addAll(extra)
        return out
    }

    private fun display(act: AppCompatActivity, rows: List<Pair<String, String>>, actions: List<Pair<String, () -> Unit>>) {
        val d = act.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val dlg = BottomSheetDialog(act, R.style.App_BottomSheetDialog)

        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_menu_sheet)
            setPadding(0, px(12), 0, px(24))
        }
        card.addView(View(act).apply {
            setBackgroundColor(0x55FFFFFF)
            layoutParams = LinearLayout.LayoutParams(px(36), px(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = px(16)
            }
        })
        card.addView(TextView(act).apply {
            text = "Details"
            textSize = 20f
            setTextColor(Color.WHITE)
            useDisplay()
            setPadding(px(24), 0, px(24), px(4))
        })

        val list = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), 0, px(16), 0)
        }

        val fileKeys = listOf("Name", "Type", "Size", "Resolution", "Duration")
        val dateKeys = listOf("Captured", "Recorded", "Added to device", "Last modified")
        val placeKeys = listOf("Album", "Location")
        val known = fileKeys + dateKeys + placeKeys
        val cameraRows = rows.filter { it.first !in known }

        fun section(title: String, items: List<Pair<String, String>>) {
            if (items.isEmpty()) return
            list.addView(TextView(act).apply {
                text = title
                textSize = 13f
                setTextColor(Colors.BLUE)
                useDisplay()
                setPadding(px(8), px(18), 0, px(8))
            })
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(0xFF262626.toInt()); cornerRadius = 18 * d }
                setPadding(px(16), px(4), px(16), px(4))
            }
            items.forEachIndexed { i, (k, v) ->
                if (i > 0) box.addView(View(act).apply {
                    setBackgroundColor(0xFF333333.toInt())
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                })
                val r = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, px(12), 0, px(12))
                }
                r.addView(TextView(act).apply {
                    text = k
                    textSize = 13f
                    setTextColor(0xFF8A8A8A.toInt())
                    useDisplay()
                    layoutParams = LinearLayout.LayoutParams(px(110), ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                r.addView(TextView(act).apply {
                    text = v
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    useDisplay()
                    setTextIsSelectable(true)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                box.addView(r)
            }
            list.addView(box)
        }

        section("File", rows.filter { it.first in fileKeys })
        section("Date", rows.filter { it.first in dateKeys })
        section("Location", rows.filter { it.first in placeKeys })
        section("More info", cameraRows)

        if (actions.isNotEmpty()) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(px(8), px(20), 0, 0)
            }
            actions.forEach { (label, run) ->
                row.addView(TextView(act).apply {
                    text = label
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    useDisplay()
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_chip)
                    setPadding(px(16), px(9), px(16), px(9))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginEnd = px(10) }
                    setOnClickListener { dlg.dismiss(); run() }
                })
            }
            list.addView(row)
        }

        val scroll = ScrollView(act).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dlg.setContentView(card)
        (card.parent as View).setBackgroundColor(Color.TRANSPARENT)
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dlg.behavior.skipCollapsed = true
        dlg.setOnDismissListener { busy = false }
        dlg.show()
    }
}