package com.example.gallery

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class EditorActivity : AppCompatActivity() {

    private lateinit var ed: EditorView
    private lateinit var btnRevert: TextView
    private lateinit var btnSave: TextView
    private lateinit var saving: View
    private lateinit var panels: List<View>
    private lateinit var tools: List<ImageView>
    private lateinit var cats: List<TextView>
    private lateinit var catRow: View
    private lateinit var drawTools: List<ImageView>
    private lateinit var btnUndo: ImageView
    private lateinit var btnRedo: ImageView
    private lateinit var btnStickerDelete: View
    private lateinit var itemActions: View
    private lateinit var btnAspect: TextView
    private lateinit var angleSeek: SeekBar
    private lateinit var angleText: TextView
    private lateinit var filterRow: LinearLayout
    private lateinit var adjRow: LinearLayout
    private lateinit var adjSeek: SeekBar
    private lateinit var adjLabel: TextView
    private lateinit var srcUri: Uri

    private val filterViews = ArrayList<Pair<ImageView, TextView>>()
    private val adjChips = ArrayList<TextView>()
    private val adj = IntArray(6)
    private var filterIdx = 0
    private var adjSel = 0
    private var tool = 4
    private var cat = 0
    private var aspectIdx = 0
    private var resizeIdx = 0
    private var isSaving = false
    private var png = false

    private val accent = 0xFFFFD60A.toInt()
    private val palette = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF4D4D.toInt(), 0xFFFF9F0A.toInt(), 0xFFFFD60A.toInt(),
        0xFF30D158.toInt(), 0xFF40C8E0.toInt(), 0xFF3D8BFF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF6B9D.toInt()
    )
    private val emojis = listOf(
        "😀", "😂", "😍", "😎", "🥳", "😭", "😡", "🤔", "👍", "👎", "👏", "🙏", "💪", "🔥", "✨", "⭐",
        "❤️", "💔", "💯", "🎉", "🎈", "🎁", "🌈", "☀️", "🌙", "⚡", "🌸", "🍀", "🍕", "🍔", "🍦", "☕",
        "🐶", "🐱", "🦋", "🚀", "🏆", "👑", "💎", "📍"
    )
    private val aspectLabels = listOf("Free", "Original", "Square", "4:3", "3:4", "16:9", "9:16")
    private val aspectVals = floatArrayOf(0f, -1f, 1f, 4f / 3f, 3f / 4f, 16f / 9f, 9f / 16f)
    private val resizeLabels = listOf("Original size", "75%", "50%", "25%")
    private val resizeVals = floatArrayOf(1f, 0.75f, 0.5f, 0.25f)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun SeekBar.onUser(f: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) f(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    override fun onCreate(b: Bundle?) {
        Prefs.init(this)
        super.onCreate(b)
        val uri = intent.data
        if (uri == null) { finish(); return }
        srcUri = uri
        png = contentResolver.getType(uri) == "image/png"
        setContentView(R.layout.activity_editor)
        edgeToEdge(findViewById(R.id.root))

        ed = findViewById(R.id.editor)
        btnRevert = findViewById(R.id.btnRevert)
        btnSave = findViewById(R.id.btnSave)
        saving = findViewById(R.id.saving)
        panels = listOf(R.id.panelDraw, R.id.panelSticker, R.id.panelText, R.id.panelCrop, R.id.panelFilters, R.id.panelAdjust)
            .map { findViewById<View>(it) }
        tools = listOf(R.id.tEnhance, R.id.tCrop, R.id.tFilters, R.id.tAdjust, R.id.tDecor, R.id.tMore)
            .map { findViewById<ImageView>(it) }
        cats = listOf(R.id.catDraw, R.id.catSticker, R.id.catText).map { findViewById<TextView>(it) }
        catRow = findViewById(R.id.catRow)
        drawTools = listOf(R.id.toolPen, R.id.toolHl, R.id.toolDot, R.id.toolEraser).map { findViewById<ImageView>(it) }
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)
        btnStickerDelete = findViewById(R.id.btnStickerDelete)
        itemActions = findViewById(R.id.itemActions)
        btnAspect = findViewById(R.id.btnAspect)
        angleSeek = findViewById(R.id.angleSeek)
        angleText = findViewById(R.id.angleText)
        filterRow = findViewById(R.id.filterRow)
        adjRow = findViewById(R.id.adjRow)
        adjSeek = findViewById(R.id.adjSeek)
        adjLabel = findViewById(R.id.adjLabel)

        listOf(
            R.id.btnRevert, R.id.btnSave, R.id.catDraw, R.id.catSticker, R.id.catText, R.id.btnAspect,
            R.id.btnAddText, R.id.btnTextEdit, R.id.btnTextDelete, R.id.btnStickerDelete,
            R.id.btnAdjReset, R.id.btnAdjResetAll, R.id.adjLabel, R.id.angleText
        ).forEach { findViewById<TextView>(it).useDisplay() }

        // top bar
        btnRevert.setOnClickListener { revert() }
        btnSave.setOnClickListener { save() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exit() }
        })

        // bottom tools / categories
        tools.forEachIndexed { i, v -> v.setOnClickListener { selectTool(i) } }
        cats.forEachIndexed { i, v -> v.setOnClickListener { selectCat(i) } }

        // draw
        drawTools.forEachIndexed { i, v -> v.setOnClickListener { selectDrawTool(i) } }
        btnUndo.setOnClickListener { ed.undo() }
        btnRedo.setOnClickListener { ed.redo() }
        buildSwatches(findViewById(R.id.colorRowDraw), Color.WHITE) { ed.brushColor = it }
        findViewById<SeekBar>(R.id.sizeDraw).apply {
            progress = 6
            onUser { ed.brushSize = (it + 2).toFloat() }
        }
        ed.brushSize = 8f

        // stickers
        val stickerRow = findViewById<LinearLayout>(R.id.stickerRow)
        emojis.forEach { em ->
            stickerRow.addView(TextView(this).apply {
                text = em
                textSize = 30f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
                setBackgroundResource(R.drawable.bg_press)
                setOnClickListener { ed.addItem(em, true, Color.WHITE, ed.maxDim * 0.14f) }
            })
        }
        btnStickerDelete.setOnClickListener { ed.deleteSelected() }

        // text
        findViewById<View>(R.id.btnAddText).setOnClickListener { showTextDialog(null) }
        findViewById<View>(R.id.btnTextEdit).setOnClickListener { ed.selected?.let { showTextDialog(it) } }
        findViewById<View>(R.id.btnTextDelete).setOnClickListener { ed.deleteSelected() }
        ed.onItemTap = { item -> if (!item.sticker) showTextDialog(item) }
        ed.onSelect = { refreshBar() }
        ed.onChanged = { refreshBar() }

        // crop
        findViewById<View>(R.id.btnRot90).setOnClickListener {
            ed.rotate90()
            aspectIdx = when (aspectIdx) { 3 -> 4; 4 -> 3; 5 -> 6; 6 -> 5; else -> aspectIdx }
            setAspectLabel()
        }
        findViewById<View>(R.id.btnFlipH).setOnClickListener { ed.flipHorizontal(); syncAngle() }
        findViewById<View>(R.id.btnFlipV).setOnClickListener { ed.flipVertical(); syncAngle() }
        btnAspect.setOnClickListener {
            showDarkMenu(it, aspectLabels) { i ->
                aspectIdx = i
                setAspectLabel()
                ed.setAspect(aspectVals[i])
            }
        }
        findViewById<View>(R.id.btnCropReset).setOnClickListener {
            ed.resetGeometry()
            aspectIdx = 0
            setAspectLabel()
            syncAngle()
        }
        angleSeek.onUser { p ->
            ed.setAngle((p - 45).toFloat())
            angleText.text = "${p - 45}°"
        }

        // adjustments
        buildAdjust()
        adjSeek.onUser { p ->
            adj[adjSel] = p + Fx.ADJ_MIN[adjSel]
            updateAdjLabel()
            applyFx()
            refreshBar()
        }
        findViewById<View>(R.id.btnAdjReset).setOnClickListener {
            adj[adjSel] = 0
            selectAdj(adjSel)
            applyFx(); refreshBar(); updateToolTints()
        }
        findViewById<View>(R.id.btnAdjResetAll).setOnClickListener {
            adj.fill(0)
            selectAdj(adjSel)
            applyFx(); refreshBar(); updateToolTints()
        }

        selectDrawTool(0)
        selectTool(1)
        refreshBar()

        loadSource(uri, true)
    }

    private fun loadSource(u: Uri, first: Boolean) {
        saving.visibility = View.VISIBLE
        Thread {
            val bmp = try { Exporter.load(this, u, 4096) } catch (e: Throwable) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                saving.visibility = View.GONE
                if (bmp == null) {
                    Toast.makeText(this, if (first) "Can't open this image" else "Original image is missing", Toast.LENGTH_SHORT).show()
                    if (first) finish()
                } else {
                    srcUri = u
                    png = contentResolver.getType(u) == "image/png"
                    if (!first) resetAll()
                    ed.setSource(bmp)
                    buildFilters(bmp)
                    refreshBar()
                }
            }
        }.start()
    }

    // ---------- Tool / category / panels ----------
    private fun showPanel(k: Int) {
        panels.forEachIndexed { i, p ->
            if (i == k) {
                if (p.visibility != View.VISIBLE) {
                    p.alpha = 0f
                    p.visibility = View.VISIBLE
                    p.animate().alpha(1f).setDuration(160).start()
                }
            } else {
                p.animate().cancel()
                p.visibility = View.GONE
                p.alpha = 1f
            }
        }
    }

    private fun selectTool(i: Int) {
        when (i) {
            0 -> { toggleEnhance(); return }
            5 -> { showMore(); return }
        }
        tool = i
        when (i) {
            1 -> { showPanel(3); ed.mode = EditorView.CROP }
            2 -> { showPanel(4); ed.mode = EditorView.NONE }
            3 -> { showPanel(5); ed.mode = EditorView.NONE }
            4 -> selectCat(cat)
        }
        catRow.visibility = if (i == 4) View.VISIBLE else View.INVISIBLE
        updateToolTints()
    }

    private fun selectCat(i: Int) {
        cat = i
        cats.forEachIndexed { k, t -> t.setTextColor(if (k == i) accent else Color.WHITE) }
        showPanel(i)
        ed.mode = when (i) { 0 -> EditorView.DRAW; 1 -> EditorView.STICKER; else -> EditorView.TEXT }
        refreshBar()
    }

    private fun selectDrawTool(i: Int) {
        ed.tool = i
        drawTools.forEachIndexed { k, v ->
            v.isSelected = k == i
            v.setColorFilter(if (k == i) Colors.BLUE else Color.WHITE)
        }
    }

    private fun isEnhanced() = adj[0] == 5 && adj[1] == 15 && adj[2] == 20

    private fun updateToolTints() {
        tools.forEachIndexed { k, v ->
            val on = k == tool || (k == 0 && isEnhanced())
            v.setColorFilter(if (on) accent else Color.WHITE)
        }
    }

    private fun toggleEnhance() {
        if (isEnhanced()) { adj[0] = 0; adj[1] = 0; adj[2] = 0 }
        else { adj[0] = 5; adj[1] = 15; adj[2] = 20 }
        selectAdj(adjSel)
        applyFx()
        refreshBar()
        updateToolTints()
    }

    // ---------- Top bar state ----------
    private fun hasChanges() = ed.hasEdits() || filterIdx != 0 || adj.any { it != 0 } || resizeIdx != 0

    private fun refreshBar() {
        val on = hasChanges()
        val canRestore = OriginalStore.get(srcUri) != null
        btnRevert.isEnabled = on || canRestore
        btnSave.isEnabled = on
        val col = if (on || canRestore) Color.WHITE else 0xFF555555.toInt()
        btnRevert.setTextColor(col)
        btnSave.setTextColor(if (on) accent else 0xFF555555.toInt())
        btnUndo.isEnabled = ed.canUndo(); btnUndo.alpha = if (ed.canUndo()) 1f else 0.35f
        btnRedo.isEnabled = ed.canRedo(); btnRedo.alpha = if (ed.canRedo()) 1f else 0.35f
        val sel = ed.selected
        btnStickerDelete.visibility = if (sel != null && sel.sticker) View.VISIBLE else View.INVISIBLE
        itemActions.visibility = if (sel != null && !sel.sticker) View.VISIBLE else View.INVISIBLE
    }

    private fun applyFx() { ed.setFilter(Fx.build(filterIdx, adj)) }

    private fun revert() {
        val orig = OriginalStore.get(srcUri)
        if (orig == null) {
            if (!hasChanges()) return
            MaterialAlertDialogBuilder(this).setMessage("Revert all changes?")
                .setPositiveButton("Revert") { _, _ -> resetAll() }
                .setNegativeButton("Cancel", null).show()
            return
        }
        MaterialAlertDialogBuilder(this).setMessage("Revert to the original image?")
            .setPositiveButton("Revert") { _, _ ->
                val ok = try { contentResolver.openInputStream(orig)?.use { true } ?: false } catch (e: Exception) { false }
                if (ok) {
                    setResult(RESULT_OK, Intent().setData(orig))
                    finish()
                } else Toast.makeText(this, "Original image is missing", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun resetAll() {
        ed.resetAll()
        filterIdx = 0
        adj.fill(0)
        resizeIdx = 0
        aspectIdx = 0
        setAspectLabel()
        syncAngle()
        markFilter()
        selectAdj(adjSel)
        applyFx()
        refreshBar()
        updateToolTints()
    }

    private fun exit() {
        if (isSaving) return
        if (!hasChanges()) { finish(); return }
        MaterialAlertDialogBuilder(this).setMessage("Discard your edits?")
            .setPositiveButton("Discard") { _, _ -> finish() }
            .setNegativeButton("Keep editing", null).show()
    }

    // ---------- Crop helpers ----------
    private fun setAspectLabel() { btnAspect.text = aspectLabels[aspectIdx] }

    private fun syncAngle() {
        val a = ed.angle.roundToInt()
        angleSeek.progress = a + 45
        angleText.text = "$a°"
    }

    // ---------- Colors ----------
    private fun buildSwatches(row: LinearLayout, initial: Int, onPick: (Int) -> Unit) {
        val size = dp(26)
        val m = dp(4)
        palette.forEach { col ->
            val v = View(this)
            v.tag = col
            v.layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(m, 0, m, 0) }
            v.setOnClickListener { onPick(col); markSwatches(row, col) }
            row.addView(v)
        }
        markSwatches(row, initial)
    }

    private fun markSwatches(row: LinearLayout, sel: Int) {
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i)
            val col = v.tag as Int
            val on = col == sel
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(col)
                setStroke(dp(if (on) 3 else 1), if (on) 0xFFFFFFFF.toInt() else 0x66FFFFFF)
            }
        }
    }

    // ---------- Text dialog ----------
    private fun showTextDialog(existing: Item?) {
        val pad = dp(20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }
        val et = EditText(this).apply {
            setText(existing?.text ?: "")
            hint = "Enter text"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            setSelection(text.length)
        }
        var color = existing?.color ?: Color.WHITE
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        buildSwatches(row, color) { color = it }
        val lbl = TextView(this).apply { text = "Size"; setPadding(0, dp(8), 0, 0) }
        val seek = SeekBar(this).apply {
            max = 90
            progress = existing?.let { ((it.size / ed.maxDim) * 1000f - 10f).roundToInt().coerceIn(0, 90) } ?: 50
        }
        box.addView(et); box.addView(hs); box.addView(lbl); box.addView(seek)
        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Add text" else "Edit text")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) {
                    val sz = (seek.progress + 10) / 1000f * ed.maxDim
                    if (existing == null) ed.addItem(t, false, color, sz) else ed.updateSelected(t, color, sz)
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- Filters ----------
    private fun buildFilters(src: Bitmap) {
        val s = min(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
        val th = Bitmap.createScaledBitmap(sq, dp(80), dp(80), true)
        if (sq !== src && sq !== th) sq.recycle()
        filterRow.removeAllViews()
        filterViews.clear()
        Fx.FILTER_NAMES.forEachIndexed { i, name ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), 0, dp(6), 0)
            }
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(76), dp(76))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(dp(3), dp(3), dp(3), dp(3))
                setImageBitmap(th)
                contentDescription = name
                val f = Fx.thumbFilter(i)
                if (f != null) colorFilter = f else clearColorFilter()
                roundCorners(10f)
            }
            val tv = TextView(this).apply {
                text = name
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
                useDisplay()
            }
            cell.addView(iv); cell.addView(tv)
            cell.setOnClickListener {
                filterIdx = i
                markFilter()
                applyFx()
                refreshBar()
            }
            filterRow.addView(cell)
            filterViews.add(iv to tv)
        }
        markFilter()
    }

    private fun markFilter() {
        filterViews.forEachIndexed { i, (iv, tv) ->
            val on = i == filterIdx
            iv.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFF161616.toInt())
                if (on) setStroke(dp(2), accent)
            }
            tv.setTextColor(if (on) accent else 0xFFCCCCCC.toInt())
        }
    }

    // ---------- Adjustments ----------
    private fun buildAdjust() {
        adjRow.removeAllViews()
        adjChips.clear()
        Fx.ADJ_NAMES.forEachIndexed { i, n ->
            val t = TextView(this).apply {
                text = n
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setBackgroundResource(R.drawable.bg_chip)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(4), 0, dp(4), 0) }
                useDisplay()
                setOnClickListener { selectAdj(i) }
            }
            adjRow.addView(t)
            adjChips.add(t)
        }
        selectAdj(0)
    }

    private fun selectAdj(i: Int) {
        adjSel = i
        adjChips.forEachIndexed { k, t -> t.setTextColor(if (k == i) accent else Color.WHITE) }
        adjSeek.max = 100 - Fx.ADJ_MIN[i]
        adjSeek.progress = adj[i] - Fx.ADJ_MIN[i]
        updateAdjLabel()
    }

    private fun updateAdjLabel() {
        val v = adj[adjSel]
        adjLabel.text = "${Fx.ADJ_NAMES[adjSel]}  ${if (v > 0) "+$v" else "$v"}"
    }

    // ---------- More ----------
    private fun showMore() {
        val sheet = BottomSheetDialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(24))
        }
        fun row(label: String, act: () -> Unit) {
            col.addView(TextView(this).apply {
                text = label
                textSize = 17f
                setTextColor(Color.WHITE)
                useDisplay()
                setPadding(dp(24), dp(16), dp(24), dp(16))
                setBackgroundResource(R.drawable.bg_press)
                setOnClickListener { sheet.dismiss(); act() }
            })
        }
        row("Resize on save: ${resizeLabels[resizeIdx]}") { showResize() }
        row("Reset adjustments") {
            adj.fill(0); selectAdj(adjSel); applyFx(); refreshBar(); updateToolTints()
        }
        row("Remove filter") { filterIdx = 0; markFilter(); applyFx(); refreshBar() }
        row("Reset crop & rotation") {
            ed.resetGeometry(); aspectIdx = 0; setAspectLabel(); syncAngle()
        }
        sheet.setContentView(col)
        (col.parent as View).setBackgroundResource(R.drawable.bg_sheet)
        sheet.show()
    }

    private fun showResize() {
        MaterialAlertDialogBuilder(this).setTitle("Resize on save")
            .setSingleChoiceItems(resizeLabels.toTypedArray(), resizeIdx) { dlg, w ->
                resizeIdx = w
                dlg.dismiss()
                refreshBar()
            }.show()
    }

    // ---------- Save ----------
    private fun save() {
        if (isSaving || !hasChanges()) return
        isSaving = true
        saving.visibility = View.VISIBLE
        val scale = resizeVals[resizeIdx]
        Thread {
            var result: Uri? = null
            try {
                val bmp = ed.render(scale)
                if (bmp != null) {
                    result = Exporter.save(this, bmp, png)
                    result?.let { OriginalStore.put(it, OriginalStore.get(srcUri) ?: srcUri) }
                    bmp.recycle()
                }
            } catch (e: Throwable) {
                result = null
            }
            runOnUiThread {
                isSaving = false
                saving.visibility = View.GONE
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result != null) {
                    Toast.makeText(this, "Saved to Pictures/Gallery Edited", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK, Intent().setData(result))
                    finish()
                } else {
                    Toast.makeText(this, "Couldn't save the image", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    companion object {
        fun intent(c: Context, uri: Uri) =
            Intent(c, EditorActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

object Exporter {

    fun load(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val cr = ctx.contentResolver
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bo) }
        if (bo.outWidth <= 0 || bo.outHeight <= 0) return null
        val big = max(bo.outWidth, bo.outHeight)
        var sample = 1
        while (big / (sample * 2) >= maxSide) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        val longest = max(bmp.width, bmp.height)
        if (longest > maxSide) {
            val f = maxSide.toFloat() / longest
            val s = Bitmap.createScaledBitmap(
                bmp, max(1, (bmp.width * f).roundToInt()), max(1, (bmp.height * f).roundToInt()), true
            )
            if (s !== bmp) bmp.recycle()
            bmp = s
        }
        val orient = try {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (r !== bmp) bmp.recycle()
        return r
    }

    fun save(ctx: Context, bmp: Bitmap, png: Boolean): Uri? {
        val cr = ctx.contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "Edited_$stamp" + if (png) ".png" else ".jpg"
        val mime = if (png) "image/png" else "image/jpeg"
        val now = System.currentTimeMillis()
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.DATE_TAKEN, now)
            put(MediaStore.Images.Media.DATE_ADDED, now / 1000)
            put(MediaStore.Images.Media.WIDTH, bmp.width)
            put(MediaStore.Images.Media.HEIGHT, bmp.height)
        }
        var file: File? = null
        if (Build.VERSION.SDK_INT >= 29) {
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Gallery Edited")
            v.put(MediaStore.Images.Media.IS_PENDING, 1)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Gallery Edited")
            dir.mkdirs()
            file = File(dir, name)
            v.put(MediaStore.Images.Media.DATA, file.absolutePath)
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        return try {
            val ok = cr.openOutputStream(uri)?.use { out ->
                bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 95, out)
            } ?: false
            if (!ok) throw IOException("compress failed")
            if (Build.VERSION.SDK_INT >= 29) {
                val u = ContentValues()
                u.put(MediaStore.Images.Media.IS_PENDING, 0)
                cr.update(uri, u, null, null)
            } else if (file != null) {
                MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(mime), null)
            }
            uri
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (x: Exception) { }
            null
        }
    }
}