package com.example.gallery

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class Stroke(val color: Int, val width: Float, val kind: Int) {
    val pts = ArrayList<Float>()
    val path = Path()

    fun start(x: Float, y: Float) {
        pts.add(x); pts.add(y)
        path.moveTo(x, y)
        path.lineTo(x + 0.01f, y)
    }

    fun add(x: Float, y: Float) {
        val n = pts.size
        val px = pts[n - 2]
        val py = pts[n - 1]
        path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
        pts.add(x); pts.add(y)
    }

    fun end() {
        val n = pts.size
        path.lineTo(pts[n - 2], pts[n - 1])
    }
}

class Item(var text: String, val sticker: Boolean, var color: Int, var size: Float, var cx: Float, var cy: Float, var rot: Float) {
    fun dup() = Item(text, sticker, color, size, cx, cy, rot)
}

class Snap(val strokes: List<Stroke>, val items: List<Item>)

class EditorView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {

    companion object {
        const val NONE = 0
        const val DRAW = 1
        const val STICKER = 2
        const val TEXT = 3
        const val CROP = 4
        private const val H_L = 1
        private const val H_T = 2
        private const val H_R = 4
        private const val H_B = 8
        private const val H_MOVE = 16
    }

    var onChanged: (() -> Unit)? = null
    var onSelect: ((Item?) -> Unit)? = null
    var onItemTap: ((Item) -> Unit)? = null

    /** 0 pen, 1 highlighter, 2 dotted, 3 eraser */
    var tool = 0
    var brushColor = 0xFFFFFFFF.toInt()
    var brushSize = 8f

    var mode = NONE
        set(v) {
            if (field == v) return
            field = v
            if (v != STICKER && v != TEXT) select(null)
            syncBox(true)
            invalidate()
        }

    var selected: Item? = null
        private set

    var angle = 0f
        private set

    private val d = resources.displayMetrics.density
    private var bitmap: Bitmap? = null
    private var sw = 0f
    private var sh = 0f
    private var fx: ColorFilter? = null
    private var quarter = 0
    private var flipH = false
    private var flipV = false
    private var aspect = 0f
    private val crop = RectF()
    private val dispBox = RectF()
    private var boxAnim: ValueAnimator? = null
    private var cropAnim: ValueAnimator? = null

    private val strokes = ArrayList<Stroke>()
    private val items = ArrayList<Item>()
    private val undoS = ArrayList<Snap>()
    private val redoS = ArrayList<Snap>()
    private var pendingSnap: Snap? = null
    private val measure = Paint(Paint.ANTI_ALIAS_FLAG)

    private val dimPaint = Paint().apply { color = 0xAA000000.toInt() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99FFFFFF.toInt(); strokeWidth = d }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = 1.5f * d }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = 3f * d; strokeCap = Paint.Cap.SQUARE }

    val maxDim: Float get() = max(sw, sh)
    private val frameW: Float get() = if (quarter % 2 == 0) sw else sh
    private val frameH: Float get() = if (quarter % 2 == 0) sh else sw

    // ---------- Public API ----------
    fun setSource(b: Bitmap) {
        bitmap = b
        sw = b.width.toFloat()
        sh = b.height.toFloat()
        crop.set(0f, 0f, frameW, frameH)
        dispBox.set(crop)
        invalidate()
    }

    fun setFilter(f: ColorFilter?) { fx = f; invalidate() }

    fun canUndo() = undoS.isNotEmpty()
    fun canRedo() = redoS.isNotEmpty()

    fun hasEdits() = strokes.isNotEmpty() || items.isNotEmpty() || geometryChanged()

    fun undo() {
        if (undoS.isEmpty()) return
        redoS.add(snap())
        restore(undoS.removeAt(undoS.size - 1))
    }

    fun redo() {
        if (redoS.isEmpty()) return
        undoS.add(snap())
        restore(redoS.removeAt(redoS.size - 1))
    }

    fun rotate90() {
        cropAnim?.cancel()
        quarter = (quarter + 1) % 4
        if (aspect > 0f) aspect = 1f / aspect
        crop.set(if (aspect > 0f) fitRect(aspect) else RectF(0f, 0f, frameW, frameH))
        syncBox(true)
        invalidate()
        notifyChanged()
    }

    fun flipHorizontal() {
        cropAnim?.cancel()
        flipH = !flipH
        angle = -angle
        val l = frameW - crop.right
        val r = frameW - crop.left
        crop.left = l
        crop.right = r
        invalidate()
        notifyChanged()
    }

    fun flipVertical() {
        cropAnim?.cancel()
        flipV = !flipV
        angle = -angle
        val t = frameH - crop.bottom
        val b = frameH - crop.top
        crop.top = t
        crop.bottom = b
        invalidate()
        notifyChanged()
    }

    fun setAngle(deg: Float) {
        angle = deg.coerceIn(-45f, 45f)
        invalidate()
        notifyChanged()
    }

    /** 0 = free, -1 = original ratio, >0 = width/height */
    fun setAspect(r: Float) {
        aspect = if (r < 0f) frameW / frameH else r
        if (aspect > 0f) animateCropTo(fitRect(aspect))
        notifyChanged()
    }

    fun resetGeometry() {
        cropAnim?.cancel()
        quarter = 0; flipH = false; flipV = false; angle = 0f; aspect = 0f
        crop.set(0f, 0f, frameW, frameH)
        syncBox(true)
        invalidate()
        notifyChanged()
    }

    fun resetAll() {
        strokes.clear(); items.clear(); undoS.clear(); redoS.clear(); pendingSnap = null
        select(null)
        resetGeometry()
    }

    fun addItem(text: String, sticker: Boolean, color: Int, size: Float) {
        val inv = Matrix()
        imgMatrix().invert(inv)
        val p = floatArrayOf(crop.centerX(), crop.centerY())
        inv.mapPoints(p)
        val it = Item(text, sticker, color, size, p[0], p[1], -(quarter * 90f + angle))
        pushUndo(snap())
        items.add(it)
        select(it)
        invalidate()
    }

    fun updateSelected(text: String, color: Int, size: Float) {
        val s = selected ?: return
        pushUndo(snap())
        s.text = text; s.color = color; s.size = size
        invalidate()
    }

    fun deleteSelected() {
        val s = selected ?: return
        pushUndo(snap())
        items.remove(s)
        select(null)
        invalidate()
    }

    fun render(scale: Float): Bitmap? {
        val bmp = bitmap ?: return null
        val w = max(1, (crop.width() * scale).roundToInt())
        val h = max(1, (crop.height() * scale).roundToInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.scale(w / crop.width(), h / crop.height())
        c.translate(-crop.left, -crop.top)
        c.concat(imgMatrix())
        drawContent(c, bmp, true, 1f)
        return out
    }

    // ---------- State / undo ----------
    private fun notifyChanged() { onChanged?.invoke() }

    private fun snap() = Snap(ArrayList(strokes), items.map { it.dup() })

    private fun pushUndo(s: Snap) {
        undoS.add(s)
        if (undoS.size > 60) undoS.removeAt(0)
        redoS.clear()
        notifyChanged()
    }

    private fun restore(s: Snap) {
        strokes.clear(); strokes.addAll(s.strokes)
        items.clear(); items.addAll(s.items)
        select(null)
        invalidate()
        notifyChanged()
    }

    private fun beginChange() { pendingSnap = snap() }
    private fun commitChange() { pendingSnap?.let { pushUndo(it) }; pendingSnap = null }
    private fun cancelChange() { pendingSnap = null }

    private fun select(i: Item?) {
        if (selected === i) return
        selected = i
        onSelect?.invoke(i)
        invalidate()
    }

    private fun geometryChanged(): Boolean {
        if (bitmap == null) return false
        return quarter != 0 || flipH || flipV || angle != 0f ||
                abs(crop.left) > 0.5f || abs(crop.top) > 0.5f ||
                abs(crop.right - frameW) > 0.5f || abs(crop.bottom - frameH) > 0.5f
    }

    // ---------- Geometry ----------
    private fun coverScale(): Float {
        val th = Math.toRadians(abs(angle).toDouble())
        val r = max(frameW / frameH, frameH / frameW)
        return (cos(th) + r * sin(th)).toFloat()
    }

    private fun imgMatrix(): Matrix {
        val m = Matrix()
        m.postTranslate(-sw / 2f, -sh / 2f)
        m.postRotate(quarter * 90f)
        if (flipH) m.postScale(-1f, 1f)
        if (flipV) m.postScale(1f, -1f)
        val s = coverScale()
        m.postScale(s, s)
        m.postRotate(angle)
        m.postTranslate(frameW / 2f, frameH / 2f)
        return m
    }

    private fun viewMatrix(): Matrix {
        val m = Matrix()
        val pad = 20f * d
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        val bw = max(dispBox.width(), 1f)
        val bh = max(dispBox.height(), 1f)
        val sc = min(aw / bw, ah / bh)
        m.postTranslate(-dispBox.left, -dispBox.top)
        m.postScale(sc, sc)
        m.postTranslate(width / 2f - bw * sc / 2f, height / 2f - bh * sc / 2f)
        return m
    }

    private fun total(): Matrix {
        val m = imgMatrix()
        m.postConcat(viewMatrix())
        return m
    }

    private fun scaleOf(m: Matrix): Float {
        val v = FloatArray(9)
        m.getValues(v)
        return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
    }

    private fun viewScale() = scaleOf(viewMatrix())

    private fun toSrc(x: Float, y: Float): FloatArray {
        val inv = Matrix()
        total().invert(inv)
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        return p
    }

    private fun fitRect(r: Float): RectF {
        val fw = frameW
        val fh = frameH
        var w = fw
        var h = w / r
        if (h > fh) { h = fh; w = h * r }
        val l = (fw - w) / 2f
        val t = (fh - h) / 2f
        return RectF(l, t, l + w, t + h)
    }

    private fun targetBox() = if (mode == CROP) RectF(0f, 0f, frameW, frameH) else RectF(crop)

    private fun lerp(a: RectF, b: RectF, t: Float, out: RectF) {
        out.set(
            a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
            a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t
        )
    }

    private fun syncBox(animate: Boolean) {
        boxAnim?.cancel()
        val t = targetBox()
        if (!animate || width == 0 || dispBox.isEmpty) {
            dispBox.set(t)
            invalidate()
            return
        }
        val from = RectF(dispBox)
        boxAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { lerp(from, t, it.animatedFraction, dispBox); invalidate() }
            start()
        }
    }

    private fun animateCropTo(t: RectF) {
        cropAnim?.cancel()
        val from = RectF(crop)
        val to = RectF(t)
        cropAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                lerp(from, to, it.animatedFraction, crop)
                if (mode != CROP) dispBox.set(crop)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { notifyChanged() }
            })
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (bitmap != null) dispBox.set(targetBox())
    }

    // ---------- Drawing ----------
    override fun onDraw(c: Canvas) {
        val bmp = bitmap ?: return
        if (width == 0 || dispBox.isEmpty) return
        val v = viewMatrix()
        val img = imgMatrix()
        val tot = Matrix(img)
        tot.postConcat(v)
        val ts = scaleOf(tot)
        c.save()
        c.concat(v)
        if (mode == CROP) c.clipRect(0f, 0f, frameW, frameH) else c.clipRect(crop)
        c.concat(img)
        drawContent(c, bmp, false, ts)
        c.restore()
        if (mode == CROP) drawCropOverlay(c, v)
    }

    private fun drawContent(c: Canvas, bmp: Bitmap, export: Boolean, ts: Float) {
        val ip = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        ip.colorFilter = fx
        c.drawBitmap(bmp, 0f, 0f, ip)

        val sp = Paint(Paint.ANTI_ALIAS_FLAG)
        sp.style = Paint.Style.STROKE
        sp.strokeJoin = Paint.Join.ROUND
        for (s in ArrayList(strokes)) {
            sp.color = s.color
            sp.strokeWidth = s.width
            sp.strokeCap = if (s.kind == 1) Paint.Cap.SQUARE else Paint.Cap.ROUND
            sp.pathEffect = if (s.kind == 2) DashPathEffect(floatArrayOf(0.1f, s.width * 1.8f), 0f) else null
            c.drawPath(s.path, sp)
        }

        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.textAlign = Paint.Align.CENTER
        val op = Paint(Paint.ANTI_ALIAS_FLAG)
        op.style = Paint.Style.STROKE
        op.color = 0xFFFFFFFF.toInt()
        op.strokeWidth = 1.5f * d / ts
        op.pathEffect = DashPathEffect(floatArrayOf(8f * d / ts, 6f * d / ts), 0f)
        for (t in ArrayList(items)) {
            tp.textSize = t.size
            tp.color = t.color
            if (t.sticker) tp.clearShadowLayer() else tp.setShadowLayer(t.size * 0.04f, 0f, 0f, 0x66000000)
            val fm = tp.fontMetrics
            val lh = fm.descent - fm.ascent
            val lines = t.text.split("\n")
            val tw = lines.maxOf { tp.measureText(it) }
            val th = lh * lines.size
            c.save()
            c.translate(t.cx, t.cy)
            c.rotate(t.rot)
            var y = -th / 2f - fm.ascent
            for (ln in lines) { c.drawText(ln, 0f, y, tp); y += lh }
            if (!export && t === selected) {
                val pad = 10f * d / ts
                c.drawRect(-tw / 2f - pad, -th / 2f - pad, tw / 2f + pad, th / 2f + pad, op)
            }
            c.restore()
        }
    }

    private fun drawCropOverlay(c: Canvas, v: Matrix) {
        val r = RectF()
        v.mapRect(r, crop)
        c.save()
        c.clipOutRect(r)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        c.restore()
        for (i in 1..2) {
            val x = r.left + r.width() * i / 3f
            val y = r.top + r.height() * i / 3f
            c.drawLine(x, r.top, x, r.bottom, gridPaint)
            c.drawLine(r.left, y, r.right, y, gridPaint)
        }
        c.drawRect(r, borderPaint)
        val len = 22f * d
        val mid = 10f * d
        c.drawLine(r.left, r.top, r.left + len, r.top, cornerPaint)
        c.drawLine(r.left, r.top, r.left, r.top + len, cornerPaint)
        c.drawLine(r.right, r.top, r.right - len, r.top, cornerPaint)
        c.drawLine(r.right, r.top, r.right, r.top + len, cornerPaint)
        c.drawLine(r.left, r.bottom, r.left + len, r.bottom, cornerPaint)
        c.drawLine(r.left, r.bottom, r.left, r.bottom - len, cornerPaint)
        c.drawLine(r.right, r.bottom, r.right - len, r.bottom, cornerPaint)
        c.drawLine(r.right, r.bottom, r.right, r.bottom - len, cornerPaint)
        c.drawLine(r.centerX() - mid, r.top, r.centerX() + mid, r.top, cornerPaint)
        c.drawLine(r.centerX() - mid, r.bottom, r.centerX() + mid, r.bottom, cornerPaint)
        c.drawLine(r.left, r.centerY() - mid, r.left, r.centerY() + mid, cornerPaint)
        c.drawLine(r.right, r.centerY() - mid, r.right, r.centerY() + mid, cornerPaint)
    }

    // ---------- Touch ----------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return false
        return when (mode) {
            DRAW -> touchDraw(e)
            STICKER, TEXT -> touchItems(e)
            CROP -> touchCrop(e)
            else -> false
        }
    }

    // --- draw ---
    private var cur: Stroke? = null
    private var erased = false

    private fun touchDraw(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val p = toSrc(e.x, e.y)
                beginChange()
                if (tool == 3) {
                    erased = false
                    erase(p[0], p[1])
                } else {
                    val base = brushSize * maxDim / 1500f
                    val s = when (tool) {
                        1 -> Stroke((brushColor and 0x00FFFFFF) or (0x80 shl 24), base * 2.5f, 1)
                        2 -> Stroke(brushColor, base, 2)
                        else -> Stroke(brushColor, base, 0)
                    }
                    s.start(p[0], p[1])
                    strokes.add(s)
                    cur = s
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val p = toSrc(e.x, e.y)
                if (tool == 3) erase(p[0], p[1]) else cur?.add(p[0], p[1])
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tool == 3) {
                    if (erased) commitChange() else cancelChange()
                } else {
                    cur?.end()
                    commitChange()
                }
                cur = null
                invalidate()
            }
        }
        return true
    }

    private fun erase(x: Float, y: Float) {
        val r = (10f * d + brushSize * d * 0.8f) / scaleOf(total())
        val it = strokes.iterator()
        while (it.hasNext()) {
            if (hit(it.next(), x, y, r)) { it.remove(); erased = true }
        }
    }

    private fun hit(s: Stroke, x: Float, y: Float, r: Float): Boolean {
        val p = s.pts
        val rr = r + s.width / 2f
        if (p.size < 4) return hypot(p[0] - x, p[1] - y) <= rr
        var i = 0
        while (i + 3 < p.size) {
            if (distSeg(x, y, p[i], p[i + 1], p[i + 2], p[i + 3]) <= rr) return true
            i += 2
        }
        return false
    }

    private fun distSeg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    // --- stickers / text ---
    private var dragItem: Item? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downBlank = false
    private var wasSelected = false
    private var moved = false
    private var itemChanged = false
    private var pinching = false
    private var pinched = false
    private var d0 = 1f
    private var a0 = 0f
    private var s0 = 0f
    private var r0 = 0f

    private fun touchItems(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val p = toSrc(e.x, e.y)
                val h = hitItem(p[0], p[1])
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                moved = false; itemChanged = false; pinching = false; pinched = false
                if (h != null) {
                    wasSelected = h === selected
                    select(h)
                    dragItem = h
                    downBlank = false
                    beginChange()
                } else {
                    dragItem = null
                    downBlank = true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val sel = selected
                if (sel != null && e.pointerCount == 2) {
                    pinching = true; pinched = true; downBlank = false
                    if (pendingSnap == null) beginChange()
                    d0 = max(1f, hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)))
                    a0 = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
                    s0 = sel.size
                    r0 = sel.rot
                    dragItem = null
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val sel = selected
                if (pinching && sel != null && e.pointerCount >= 2) {
                    val dd = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    val aa = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
                    sel.size = (s0 * dd / d0).coerceIn(maxDim * 0.02f, maxDim * 0.8f)
                    sel.rot = r0 + Math.toDegrees((aa - a0).toDouble()).toFloat()
                    itemChanged = true
                    invalidate()
                } else {
                    val di = dragItem
                    if (di != null && e.pointerCount == 1) {
                        if (hypot(e.x - downX, e.y - downY) > 8f * d) moved = true
                        if (moved) {
                            val p0 = toSrc(lastX, lastY)
                            val p1 = toSrc(e.x, e.y)
                            di.cx += p1[0] - p0[0]
                            di.cy += p1[1] - p0[1]
                            lastX = e.x; lastY = e.y
                            itemChanged = true
                            invalidate()
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (pinching) { pinching = false; dragItem = null }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (itemChanged) commitChange() else cancelChange()
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    val di = dragItem
                    if (downBlank && !pinched && hypot(e.x - downX, e.y - downY) < 12f * d) select(null)
                    else if (di != null && wasSelected && !itemChanged && !pinched) onItemTap?.invoke(di)
                }
                dragItem = null
                pinching = false
            }
        }
        return true
    }

    private fun hitItem(x: Float, y: Float): Item? {
        val pad = 12f * d / scaleOf(total())
        for (i in items.indices.reversed()) {
            val t = items[i]
            val a = Math.toRadians(-t.rot.toDouble())
            val cs = cos(a).toFloat()
            val sn = sin(a).toFloat()
            val dx = x - t.cx
            val dy = y - t.cy
            val lx = dx * cs - dy * sn
            val ly = dx * sn + dy * cs
            measure.textSize = t.size
            val fm = measure.fontMetrics
            val lines = t.text.split("\n")
            val w = lines.maxOf { measure.measureText(it) }
            val h = (fm.descent - fm.ascent) * lines.size
            if (abs(lx) <= w / 2f + pad && abs(ly) <= h / 2f + pad) return t
        }
        return null
    }

    // --- crop ---
    private var handle = 0
    private val startCrop = RectF()
    private var cx0 = 0f
    private var cy0 = 0f

    private fun touchCrop(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handle = hitHandle(e.x, e.y)
                if (handle != 0) {
                    cropAnim?.cancel()
                    startCrop.set(crop)
                    cx0 = e.x; cy0 = e.y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (handle != 0) {
                    val vs = viewScale()
                    resize(handle, (e.x - cx0) / vs, (e.y - cy0) / vs)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (handle != 0) { handle = 0; notifyChanged() }
            }
        }
        return true
    }

    private fun hitHandle(x: Float, y: Float): Int {
        val r = RectF()
        viewMatrix().mapRect(r, crop)
        val s = 30f * d
        val inX = x >= r.left - s && x <= r.right + s
        val inY = y >= r.top - s && y <= r.bottom + s
        val dl = abs(x - r.left)
        val dr = abs(x - r.right)
        val dt = abs(y - r.top)
        val db = abs(y - r.bottom)
        var h = 0
        if (inY) {
            if (dl <= s && dl <= dr) h = h or H_L
            else if (dr <= s) h = h or H_R
        }
        if (inX) {
            if (dt <= s && dt <= db) h = h or H_T
            else if (db <= s) h = h or H_B
        }
        if (h == 0 && r.contains(x, y)) h = H_MOVE
        return h
    }

    private fun clampF(v: Float, lo: Float, hi: Float) = if (hi < lo) lo else v.coerceIn(lo, hi)

    private fun resize(h: Int, tdx: Float, tdy: Float) {
        val fw = frameW
        val fh = frameH
        val minF = min(56f * d / viewScale(), min(fw, fh) * 0.5f)
        val s = startCrop
        val r = RectF(s)
        val horiz = (h and (H_L or H_R)) != 0
        val vert = (h and (H_T or H_B)) != 0
        if (h == H_MOVE) {
            val nl = clampF(s.left + tdx, 0f, fw - s.width())
            val nt = clampF(s.top + tdy, 0f, fh - s.height())
            r.set(nl, nt, nl + s.width(), nt + s.height())
        } else if (aspect <= 0f) {
            if ((h and H_L) != 0) r.left = clampF(s.left + tdx, 0f, s.right - minF)
            if ((h and H_R) != 0) r.right = clampF(s.right + tdx, s.left + minF, fw)
            if ((h and H_T) != 0) r.top = clampF(s.top + tdy, 0f, s.bottom - minF)
            if ((h and H_B) != 0) r.bottom = clampF(s.bottom + tdy, s.top + minF, fh)
        } else {
            val ro = aspect
            if (horiz && vert) {
                val left = (h and H_L) != 0
                val top = (h and H_T) != 0
                val ax = if (left) s.right else s.left
                val ay = if (top) s.bottom else s.top
                val cx = (if (left) s.left else s.right) + tdx
                val cy = (if (top) s.top else s.bottom) + tdy
                var w = abs(cx - ax)
                var hh = abs(cy - ay)
                if (w / ro > hh) hh = w / ro else w = hh * ro
                val maxW = min(if (left) ax else fw - ax, (if (top) ay else fh - ay) * ro)
                w = clampF(w, min(minF, maxW), maxW)
                hh = w / ro
                r.set(
                    if (left) ax - w else ax, if (top) ay - hh else ay,
                    if (left) ax else ax + w, if (top) ay else ay + hh
                )
            } else if (horiz) {
                val left = (h and H_L) != 0
                val ax = if (left) s.right else s.left
                val ex = (if (left) s.left else s.right) + tdx
                var w = abs(ex - ax)
                val maxW = min(if (left) ax else fw - ax, fh * ro)
                w = clampF(w, min(minF, maxW), maxW)
                val hh = w / ro
                val t = clampF(s.centerY() - hh / 2f, 0f, fh - hh)
                r.set(if (left) ax - w else ax, t, if (left) ax else ax + w, t + hh)
            } else {
                val top = (h and H_T) != 0
                val ay = if (top) s.bottom else s.top
                val ey = (if (top) s.top else s.bottom) + tdy
                var hh = abs(ey - ay)
                val maxH = min(if (top) ay else fh - ay, fw / ro)
                hh = clampF(hh, min(minF, maxH), maxH)
                val w = hh * ro
                val l = clampF(s.centerX() - w / 2f, 0f, fw - w)
                r.set(l, if (top) ay - hh else ay, l + w, if (top) ay else ay + hh)
            }
        }
        crop.set(r)
    }
}