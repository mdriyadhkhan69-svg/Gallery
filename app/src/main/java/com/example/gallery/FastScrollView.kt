package com.example.gallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class FastScrollView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {

    private val d = resources.displayMetrics.density
    private val hw = 14f * d
    private val hh = 44f * d
    private val edgeGap = 2f * d
    private val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D8BFF.toInt() }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val box = RectF()
    private val tri = Path()
    private var rv: RecyclerView? = null
    private var frac = 0f
    private var dragging = false
    private var grabOff = 0f
    private var vis = 1
    private val hideRun = Runnable { fadeOut() }

    fun attach(r: RecyclerView) {
        rv = r
        alpha = 0f
        visibility = GONE
        r.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dragging || dy == 0) return
                if (!rv.canScrollVertically(1) && !rv.canScrollVertically(-1)) return
                sync()
                show()
                schedule()
            }

            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (dragging) return
                if (state == RecyclerView.SCROLL_STATE_IDLE) schedule() else removeCallbacks(hideRun)
            }
        })
    }

    private fun lm(): LinearLayoutManager? = rv?.layoutManager as? LinearLayoutManager

    private fun trackTop() = 8f * d
    private fun trackLen() = (height - hh - 16f * d).coerceAtLeast(1f)
    private fun handleTop() = trackTop() + frac * trackLen()

    private fun sync() {
        val r = rv ?: return
        val m = lm() ?: return
        val n = r.adapter?.itemCount ?: 0
        if (n <= 1) { frac = 0f; invalidate(); return }
        frac = when {
            !r.canScrollVertically(-1) -> 0f
            !r.canScrollVertically(1) -> 1f
            else -> {
                val f = m.findFirstVisibleItemPosition().coerceAtLeast(0)
                val l = m.findLastVisibleItemPosition().coerceAtLeast(f)
                val v = l - f + 1
                (f.toFloat() / (n - v).coerceAtLeast(1)).coerceIn(0f, 1f)
            }
        }
        invalidate()
    }

    private fun show() {
        animate().cancel()
        if (visibility != VISIBLE) { alpha = 0f; visibility = VISIBLE }
        animate().alpha(1f).setDuration(120).start()
    }

    private fun fadeOut() {
        if (dragging) return
        animate().cancel()
        animate().alpha(0f).setDuration(300).withEndAction { if (alpha <= 0.01f) visibility = GONE }.start()
    }

    private fun schedule() {
        removeCallbacks(hideRun)
        postDelayed(hideRun, 1200)
    }

    private fun moveTo(y: Float) {
        val r = rv ?: return
        val m = lm() ?: return
        val n = r.adapter?.itemCount ?: 0
        if (n == 0) return
        frac = ((y - trackTop()) / trackLen()).coerceIn(0f, 1f)
        invalidate()
        val pos = when {
            frac <= 0.001f -> 0
            frac >= 0.999f -> n - 1
            else -> Math.round(frac * (n - vis).coerceAtLeast(1)).coerceIn(0, n - 1)
        }
        m.scrollToPositionWithOffset(pos, 0)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        sync()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideRun)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (visibility != VISIBLE || alpha < 0.3f) return false
                val top = handleTop()
                val left = width - hw - edgeGap - 20f * d
                if (e.x < left || e.y < top - 8f * d || e.y > top + hh + 8f * d) return false
                val m = lm() ?: return false
                val f = m.findFirstVisibleItemPosition()
                val l = m.findLastVisibleItemPosition()
                vis = if (f >= 0 && l >= f) l - f + 1 else 1
                rv?.stopScroll()
                dragging = true
                grabOff = e.y - top
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(hideRun)
                animate().cancel()
                alpha = 1f
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) { moveTo(e.y - grabOff); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                schedule()
                return true
            }
        }
        return dragging
    }

    override fun onDraw(c: Canvas) {
        val x = width - hw - edgeGap
        val y = handleTop()
        box.set(x, y, x + hw, y + hh)
        c.drawRoundRect(box, hw / 2f, hw / 2f, blue)
        val cx = x + hw / 2f
        val cy = y + hh / 2f
        val w = 3f * d
        val g = 2.5f * d
        val t = 4.5f * d
        tri.reset()
        tri.moveTo(cx, cy - g - t); tri.lineTo(cx - w, cy - g); tri.lineTo(cx + w, cy - g); tri.close()
        tri.moveTo(cx, cy + g + t); tri.lineTo(cx - w, cy + g); tri.lineTo(cx + w, cy + g); tri.close()
        c.drawPath(tri, white)
    }
}

object ScrollAssist {
    fun attach(rv: RecyclerView, fast: FastScrollView, top: View) {
        fast.attach(rv)
        top.alpha = 0f
        top.visibility = View.GONE
        val hide = Runnable { fade(top, false) }
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                if (dy == 0) return
                if (r.canScrollVertically(-1)) {
                    fade(top, true)
                    top.removeCallbacks(hide)
                    top.postDelayed(hide, 1400)
                } else {
                    top.removeCallbacks(hide)
                    fade(top, false)
                }
            }
        })
        top.setOnClickListener { scrollTop(rv) }
    }

    private fun fade(v: View, show: Boolean) {
        v.animate().cancel()
        if (show) {
            if (v.visibility != View.VISIBLE) { v.alpha = 0f; v.visibility = View.VISIBLE }
            v.animate().alpha(1f).setDuration(150).start()
        } else if (v.visibility == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(300).withEndAction { if (v.alpha <= 0.01f) v.visibility = View.GONE }.start()
        }
    }

    private fun scrollTop(rv: RecyclerView) {
        val m = rv.layoutManager as? LinearLayoutManager ?: return
        rv.stopScroll()
        if (m.findFirstVisibleItemPosition() > 40) {
            m.scrollToPositionWithOffset(40, 0)
            rv.postDelayed({ rv.smoothScrollToPosition(0) }, 40)
        } else rv.smoothScrollToPosition(0)
    }
}