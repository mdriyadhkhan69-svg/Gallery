package com.example.gallery

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object Creator {

    private val ui = Handler(Looper.getMainLooper())

    // ---------- Create bottom sheet ----------
    /** kind: 0 GIF, 1 Collage, 2 PDF */
    fun showSheet(act: Activity, onPick: (Int) -> Unit) {
        val d = act.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val dlg = BottomSheetDialog(act, R.style.App_BottomSheetDialog)
        val navB = ViewCompat.getRootWindowInsets(act.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_create_sheet)
            setPadding(px(24), px(30), px(24), px(16) + navB)
        }
        card.addView(TextView(act).apply {
            text = "Create"
            textSize = 16f
            setTextColor(Color.WHITE)
            useDisplay()
            setPadding(0, 0, 0, px(10))
        })

        fun row(icon: Int, title: String, desc: String, kind: Int) {
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, px(14), 0, px(14))
                setBackgroundResource(R.drawable.bg_menu_item)
                isClickable = true
                isFocusable = true
                setOnClickListener { dlg.dismiss(); onPick(kind) }
            }
            r.addView(ImageView(act).apply { setImageResource(icon) },
                LinearLayout.LayoutParams(px(32), px(32)).apply { marginEnd = px(24); topMargin = px(4) })
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(act).apply {
                text = title; textSize = 18f; setTextColor(Color.WHITE); useDisplay()
            })
            col.addView(TextView(act).apply {
                text = desc; textSize = 15f; setTextColor(0xFFA8A8A8.toInt()); useDisplay()
                setPadding(0, px(4), 0, 0)
            })
            r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(r)
        }
        row(R.drawable.ic_gif, "GIF", "Select a sequence of images and combine them into an animated image.", 0)
        row(R.drawable.ic_collage, "Collage", "Combine up to 6 images into an easy-to-share collage.", 1)
        row(R.drawable.ic_pdf_img, "Save as PDF", "Make a PDF file with images.", 2)

        val scroll = ScrollView(act).apply {
            isVerticalScrollBarEnabled = false
            addView(card)
        }
        dlg.setContentView(scroll)
        (scroll.parent as View).setBackgroundColor(Color.TRANSPARENT)
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dlg.behavior.skipCollapsed = true
        dlg.show()
    }

    // ---------- More popup (capsule er upore ashe) ----------
    fun showMenu(anchor: View, labels: List<String>, dividerBefore: Int, onPick: (Int) -> Unit) {
        val ctx = anchor.context
        val d = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val pw = PopupWindow(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(8), 0, px(8))
        }
        labels.forEachIndexed { i, label ->
            if (i == dividerBefore) {
                box.addView(View(ctx).apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.LINE
                        setStroke(px(1), 0x66FFFFFF, px(2).toFloat(), px(3).toFloat())
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(2)).apply {
                    setMargins(px(20), px(6), px(20), px(6))
                })
            }
            box.addView(TextView(ctx).apply {
                text = label
                textSize = 16f
                setTextColor(Color.WHITE)
                useDisplay()
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(24), 0, px(24), 0)
                setBackgroundResource(R.drawable.bg_menu_item)
                isClickable = true
                isFocusable = true
                setOnClickListener { pw.dismiss(); onPick(i) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48)))
        }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(box)
        }
        val wrap = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { setColor(0xFF262626.toInt()); cornerRadius = 26 * d }
            roundCorners(26f)
            elevation = 10 * d
            addView(scroll)
        }
        val panelW = min(px(250), ctx.resources.displayMetrics.widthPixels - px(24))
        box.measure(
            View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val avail = loc[1] - px(32)
        val h = min(box.measuredHeight, avail).coerceAtLeast(px(96))
        pw.contentView = wrap
        pw.width = panelW
        pw.height = h
        pw.isFocusable = true
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.animationStyle = android.R.style.Animation_Dialog
        pw.showAsDropDown(anchor, -(panelW - anchor.width) - px(8), -(h + anchor.height + px(8)))
    }

    // ---------- Create actions ----------
    /** kind: 0 GIF, 1 Collage, 2 PDF */
    fun create(ctx: Context, kind: Int, list: List<Media>, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val photos = list.filter { !it.video }
        val err = when {
            photos.isEmpty() -> "Select photos first"
            kind == 0 && photos.size < 2 -> "Select at least 2 photos"
            kind == 0 && photos.size > 20 -> "Select up to 20 photos for a GIF"
            kind == 1 && photos.size < 2 -> "Select at least 2 photos"
            kind == 1 && photos.size > 6 -> "Select up to 6 photos for a collage"
            else -> null
        }
        if (err != null) { Toast.makeText(app, err, Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(app, "Creating…", Toast.LENGTH_SHORT).show()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        Thread {
            val ok = try {
                when (kind) {
                    0 -> store(app, "GIF_$stamp.gif", "image/gif", false) { gif(app, photos, it) }
                    1 -> {
                        val bmp = collage(app, photos)
                        try {
                            store(app, "Collage_$stamp.jpg", "image/jpeg", false) {
                                bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
                            }
                        } finally { bmp.recycle() }
                    }
                    else -> store(app, "Images_$stamp.pdf", "application/pdf", true) { pdf(app, photos, it) }
                }
            } catch (e: Throwable) {
                android.util.Log.e("GalleryDbg", "create failed", e)
                false
            }
            ui.post {
                val msg = when {
                    !ok -> "Couldn't create the file"
                    kind == 2 -> "PDF saved to Download/Gallery Edited"
                    kind == 1 -> "Collage saved to Pictures/Gallery Edited"
                    else -> "GIF saved to Pictures/Gallery Edited"
                }
                Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
                done(ok)
            }
        }.start()
    }

    private fun store(ctx: Context, name: String, mime: String, isPdf: Boolean, write: (OutputStream) -> Unit): Boolean {
        val cr = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val base = if (isPdf) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isPdf) "Download/Gallery Edited" else "Pictures/Gallery Edited")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(base, v) ?: return false
            return try {
                cr.openOutputStream(uri)?.use { write(it) } ?: throw IOException("no stream")
                val u = ContentValues()
                u.put(MediaStore.MediaColumns.IS_PENDING, 0)
                cr.update(uri, u, null, null)
                true
            } catch (e: Exception) {
                try { cr.delete(uri, null, null) } catch (x: Exception) { }
                false
            }
        }
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(if (isPdf) Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_PICTURES),
                "Gallery Edited"
            )
            dir.mkdirs()
            val f = File(dir, name)
            f.outputStream().use { write(it) }
            MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(mime), null)
            true
        } catch (e: Exception) { false }
    }

    private fun crop(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val s = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val dw = src.width * s
        val dh = src.height * s
        c.drawBitmap(src, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    // ---------- Collage ----------
    private fun collage(ctx: Context, list: List<Media>): Bitmap {
        val n = list.size
        val cols = if (n <= 2) n else if (n <= 4) 2 else 3
        val rows = (n + cols - 1) / cols
        val cell = 640
        val gap = 8
        val out = Bitmap.createBitmap(cols * cell + (cols + 1) * gap, rows * cell + (rows + 1) * gap, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        list.forEachIndexed { i, m ->
            val src = Exporter.load(ctx, m.uri, 1280) ?: return@forEachIndexed
            val t = crop(src, cell, cell)
            val x = gap + (i % cols) * (cell + gap)
            val y = gap + (i / cols) * (cell + gap)
            c.drawBitmap(t, x.toFloat(), y.toFloat(), null)
            t.recycle()
            if (src !== t) src.recycle()
        }
        return out
    }

    // ---------- PDF ----------
    private fun pdf(ctx: Context, list: List<Media>, out: OutputStream) {
        val doc = PdfDocument()
        try {
            var n = 0
            for (m in list) {
                val b = Exporter.load(ctx, m.uri, 1600) ?: continue
                val page = doc.startPage(PdfDocument.PageInfo.Builder(b.width, b.height, ++n).create())
                page.canvas.drawBitmap(b, 0f, 0f, null)
                doc.finishPage(page)
                b.recycle()
            }
            if (n == 0) throw IOException("no pages")
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    // ---------- GIF (framework te encoder nai, tai nijer) ----------
    private fun le(o: OutputStream, v: Int) { o.write(v and 255); o.write((v shr 8) and 255) }

    private fun gif(ctx: Context, list: List<Media>, out: OutputStream) {
        val first = Exporter.load(ctx, list[0].uri, 480) ?: throw IOException("no frame")
        val w = first.width
        val h = first.height
        out.write("GIF89a".toByteArray())
        le(out, w); le(out, h)
        out.write(0xF7); out.write(0); out.write(0)
        for (i in 0 until 256) {
            out.write(min(i / 42, 5) * 51)
            out.write(((i % 42) / 6) * 255 / 6)
            out.write((i % 6) * 51)
        }
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))

        var wrote = 0
        list.forEachIndexed { i, m ->
            val frame: Bitmap = if (i == 0) first else {
                val src = Exporter.load(ctx, m.uri, 480) ?: return@forEachIndexed
                val t = crop(src, w, h)
                src.recycle()
                t
            }
            val px = IntArray(w * h)
            frame.getPixels(px, 0, w, 0, 0, w, h)
            frame.recycle()
            val idx = ByteArray(w * h)
            for (k in px.indices) {
                val p = px[k]
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                idx[k] = (((r * 5 + 127) / 255) * 42 + ((g * 6 + 127) / 255) * 6 + (b * 5 + 127) / 255).toByte()
            }
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00, 80, 0, 0, 0x00))
            out.write(0x2C)
            le(out, 0); le(out, 0); le(out, w); le(out, h)
            out.write(0)
            lzw(idx, out)
            wrote++
        }
        if (wrote == 0) throw IOException("no frames")
        out.write(0x3B)
    }

    private fun lzw(px: ByteArray, out: OutputStream) {
        out.write(8)
        val buf = ByteArrayOutputStream()
        var acc = 0
        var nb = 0
        var size = 9
        var next = 258
        val dict = HashMap<Int, Int>()
        fun emit(code: Int) {
            acc = acc or (code shl nb)
            nb += size
            while (nb >= 8) { buf.write(acc and 0xFF); acc = acc ushr 8; nb -= 8 }
        }
        fun bump() { if (next > (1 shl size) - 1 && size < 12) size++ }
        emit(256)
        var prefix = px[0].toInt() and 0xFF
        for (i in 1 until px.size) {
            val c = px[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            val hit = dict[key]
            if (hit != null) { prefix = hit; continue }
            emit(prefix)
            bump()
            if (next < 4096) { dict[key] = next; next++ }
            else { emit(256); dict.clear(); next = 258; size = 9 }
            prefix = c
        }
        emit(prefix)
        bump()
        emit(257)
        if (nb > 0) buf.write(acc and 0xFF)
        val bytes = buf.toByteArray()
        var o = 0
        while (o < bytes.size) {
            val n = min(255, bytes.size - o)
            out.write(n)
            out.write(bytes, o, n)
            o += n
        }
        out.write(0)
    }
}