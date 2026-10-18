package com.example.gallery

import android.animation.ValueAnimator
import android.content.Context
import android.os.Parcelable
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Zoom level 0 = custom (justified) layout, 1..5 = uniform square grid with 3,4,5,6,7 columns.
 * zoom is a float: between two levels every item is interpolated between its two layout rects.
 */
class ZoomGridLayoutManager(
    ctx: Context,
    private val rows: () -> List<Row>,
    private val minLevel: Int = 0
) : LinearLayoutManager(ctx, VERTICAL, false) {

    companion object {
        const val MAX_ZOOM = 5
        private val COLS = intArrayOf(3, 3, 4, 5, 6, 7)
    }

    var zoom = 0f
        private set

    private var scrollY = 0
    private var pendingPos = -1
    private var pendingOff = 0
    private var list: List<Row>? = null
    private var cw = 0
    private val rects = arrayOfNulls<FloatArray>(MAX_ZOOM + 1)
    private val heights = FloatArray(MAX_ZOOM + 1)
    private val tmp = FloatArray(4)
    private var anim: ValueAnimator? = null
    private val headerH = 48f * ctx.resources.displayMetrics.density

    init { isItemPrefetchEnabled = false; zoom = minLevel.toFloat() }

    // ---------- Layout data ----------
    private fun ensure(): Boolean {
        val l = rows()
        val w = width - paddingLeft - paddingRight
        if (w <= 0) return false
        if (l !== list || w != cw) {
            list = l
            cw = w
            rects.fill(null)
        }
        return true
    }

    private fun asp(r: Row): Float {
        val m = (r as Row.Photo).m
        val a = if (m.w > 0 && m.h > 0) m.w.toFloat() / m.h else 1f
        return a.coerceIn(0.5f, 1.6f)
    }

    private fun put(o: FloatArray, i: Int, x: Float, y: Float, w: Float, h: Float) {
        val k = i * 4
        o[k] = x; o[k + 1] = y; o[k + 2] = w; o[k + 3] = h
    }

    private fun level(k: Int): FloatArray {
        rects[k]?.let { return it }
        val l = list!!
        val n = l.size
        val out = FloatArray(n * 4)
        val gw = cw.toFloat()
        val px = paddingLeft.toFloat()
        var y = paddingTop.toFloat()
        var i = 0
        while (i < n) {
            if (l[i] is Row.Header) {
                put(out, i, px, y, gw, headerH)
                y += headerH
                i++
                continue
            }
            var e = i
            while (e < n && l[e] is Row.Photo) e++
            y = if (k == 0) justified(l, out, i, e, px, y, gw) else uniform(out, i, e, px, y, gw, COLS[k])
            i = e
        }
        heights[k] = y + paddingBottom
        rects[k] = out
        return out
    }

    private fun justified(l: List<Row>, o: FloatArray, s: Int, e: Int, px: Float, y0: Float, gw: Float): Float {
        var y = y0
        val pattern = intArrayOf(3, 2, 3, 3, 2, 3, 2, 2)
        val target = gw / 2.2f
        var a = s
        var r = 0
        while (a < e) {
            val want = pattern[r % pattern.size]
            r++
            val b = minOf(a + want, e)
            var sum = 0f
            for (k in a until b) sum += asp(l[k])
            var h = gw / sum
            if (b - a < want) h = minOf(h, target)
            h = minOf(h, gw * 1.1f)
            var x = px
            for (k in a until b) {
                val w = asp(l[k]) * h
                put(o, k, x, y, w, h)
                x += w
            }
            y += h
            a = b
        }
        return y
    }

    private fun uniform(o: FloatArray, s: Int, e: Int, px: Float, y0: Float, gw: Float, cols: Int): Float {
        val cell = gw / cols
        for (k in s until e) {
            val j = k - s
            put(o, k, px + (j % cols) * cell, y0 + (j / cols) * cell, cell, cell)
        }
        return y0 + ((e - s + cols - 1) / cols) * cell
    }

    private fun lo() = min(floor(zoom).toInt(), MAX_ZOOM)
    private fun fr(): Float { val l = lo(); return if (l >= MAX_ZOOM) 0f else zoom - l }

    private fun rectOf(p: Int, out: FloatArray) {
        val lo = lo()
        val f = fr()
        val a = level(lo)
        val i = p * 4
        if (f <= 0f) {
            out[0] = a[i]; out[1] = a[i + 1]; out[2] = a[i + 2]; out[3] = a[i + 3]
            return
        }
        val b = level(lo + 1)
        for (j in 0..3) out[j] = a[i + j] + (b[i + j] - a[i + j]) * f
    }

    private fun totalH(): Float {
        val lo = lo()
        level(lo)
        val f = fr()
        if (f <= 0f) return heights[lo]
        level(lo + 1)
        return heights[lo] + (heights[lo + 1] - heights[lo]) * f
    }

    private fun maxScroll(): Int = max(0, (totalH() - height).toInt())

    private fun visible(p: Int): Boolean {
        rectOf(p, tmp)
        val top = tmp[1] - scrollY
        val m = height * 0.3f
        return top + tmp[3] > -m && top < height + m
    }

    // ---------- Zoom ----------
    fun setLevel(l: Int) {
        zoom = l.coerceIn(minLevel, MAX_ZOOM).toFloat()
        requestLayout()
    }

    private fun nearest(cx: Float, wy: Float): Int {
        val n = list?.size ?: return -1
        var best = -1
        var bd = Float.MAX_VALUE
        for (p in 0 until n) {
            rectOf(p, tmp)
            val dx = if (cx < tmp[0]) tmp[0] - cx else if (cx > tmp[0] + tmp[2]) cx - tmp[0] - tmp[2] else 0f
            val dy = if (wy < tmp[1]) tmp[1] - wy else if (wy > tmp[1] + tmp[3]) wy - tmp[1] - tmp[3] else 0f
            val d = dx + dy
            if (d < bd) { bd = d; best = p; if (d == 0f) break }
        }
        return best
    }

    fun setZoom(z: Float, fx: Float, fy: Float) {
        val nz = z.coerceIn(minLevel.toFloat(), MAX_ZOOM.toFloat())
        if (!ensure() || list.isNullOrEmpty()) { zoom = nz; return }
        if (nz == zoom) return
        val cx = fx.coerceIn(0f, width.toFloat())
        val cy = fy.coerceIn(0f, height.toFloat())
        val wy = scrollY + cy
        val p = nearest(cx, wy)
        var frac = 0f
        if (p >= 0) {
            rectOf(p, tmp)
            frac = if (tmp[3] > 0f) ((wy - tmp[1]) / tmp[3]).coerceIn(0f, 1f) else 0f
        }
        zoom = nz
        if (p >= 0) {
            rectOf(p, tmp)
            scrollY = (tmp[1] + frac * tmp[3] - cy).roundToInt()
        }
        scrollY = scrollY.coerceIn(0, maxScroll())
        requestLayout()
    }

    // ---------- Child layout ----------
    private fun place(rec: RecyclerView.Recycler, p: Int) {
        rectOf(p, tmp)
        val l = tmp[0].roundToInt()
        val r = (tmp[0] + tmp[2]).roundToInt()
        val t = (tmp[1] - scrollY).roundToInt()
        val b = (tmp[1] + tmp[3] - scrollY).roundToInt()
        val w = max(1, r - l)
        val h = max(1, b - t)
        val v = rec.getViewForPosition(p)
        addView(v)
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        layoutDecorated(v, l, t, l + w, t + h)
    }

    private fun fill(rec: RecyclerView.Recycler, full: Boolean) {
        val n = list?.size ?: 0
        val have = HashSet<Int>()
        if (full) detachAndScrapAttachedViews(rec)
        else {
            for (c in childCount - 1 downTo 0) {
                val v = getChildAt(c) ?: continue
                val p = getPosition(v)
                if (p < 0 || p >= n || !visible(p)) removeAndRecycleViewAt(c, rec) else have.add(p)
            }
        }
        for (p in 0 until n) if (!have.contains(p) && visible(p)) place(rec, p)
    }

    override fun onLayoutChildren(rec: RecyclerView.Recycler, state: RecyclerView.State) {
        if (state.itemCount == 0) { removeAndRecycleAllViews(rec); return }
        if (!ensure()) return
        val n = list?.size ?: 0
        if (n == 0) { removeAndRecycleAllViews(rec); return }
        if (pendingPos >= 0) {
            rectOf(pendingPos.coerceIn(0, n - 1), tmp)
            scrollY = (tmp[1] - pendingOff).roundToInt()
            pendingPos = -1
        }
        scrollY = scrollY.coerceIn(0, maxScroll())
        fill(rec, true)
    }

    override fun scrollVerticallyBy(dy: Int, rec: RecyclerView.Recycler, state: RecyclerView.State): Int {
        if (childCount == 0 || dy == 0 || !ensure()) return 0
        val old = scrollY
        scrollY = (scrollY + dy).coerceIn(0, maxScroll())
        val consumed = scrollY - old
        if (consumed != 0) {
            offsetChildrenVertical(-consumed)
            fill(rec, false)
        }
        return consumed
    }

    // ---------- Scroll API ----------
    override fun scrollToPositionWithOffset(position: Int, offset: Int) {
        anim?.cancel()
        pendingPos = position
        pendingOff = offset
        requestLayout()
    }

    override fun scrollToPosition(position: Int) = scrollToPositionWithOffset(position, 0)

    override fun smoothScrollToPosition(recyclerView: RecyclerView, state: RecyclerView.State, position: Int) {
        if (!ensure()) return
        val n = list?.size ?: return
        if (n == 0) return
        rectOf(position.coerceIn(0, n - 1), tmp)
        val target = tmp[1].toInt().coerceIn(0, maxScroll())
        anim?.cancel()
        anim = ValueAnimator.ofInt(scrollY, target).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener { scrollY = it.animatedValue as Int; requestLayout() }
            start()
        }
    }

    override fun onScrollStateChanged(state: Int) {
        if (state == RecyclerView.SCROLL_STATE_DRAGGING) anim?.cancel()
    }

    private fun edgeVisible(first: Boolean): Int {
        if (!ensure()) return -1
        val n = list?.size ?: return -1
        var res = -1
        for (p in 0 until n) {
            rectOf(p, tmp)
            val top = tmp[1] - scrollY
            if (top + tmp[3] > 0 && top < height) {
                if (first) return p
                res = p
            }
        }
        return res
    }

    override fun findFirstVisibleItemPosition(): Int = edgeVisible(true)
    override fun findLastVisibleItemPosition(): Int = edgeVisible(false)

    override fun computeVerticalScrollOffset(state: RecyclerView.State): Int = scrollY
    override fun computeVerticalScrollRange(state: RecyclerView.State): Int = if (ensure() && !list.isNullOrEmpty()) totalH().toInt() else 0
    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int = height

    override fun supportsPredictiveItemAnimations(): Boolean = false
    override fun onSaveInstanceState(): Parcelable? = null
    override fun onRestoreInstanceState(state: Parcelable?) {}
}