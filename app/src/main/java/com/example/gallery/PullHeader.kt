package com.example.gallery

import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

class PullHeader(
    private val rv: RecyclerView,
    private val header: View,
    private val enabled: () -> Boolean = { true }
) {
    private val maxPull = 120f * rv.resources.displayMetrics.density
    private val snapAt = maxPull * 0.3f
    private val slop = ViewConfiguration.get(rv.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var baseY = 0f
    private var hdrDownY = 0f
    private var tracking = false
    private var pulling = false
    private var pull = 0f
    private var expanded = false
    private var anim: ValueAnimator? = null

    init {
        header.alpha = 0f
        header.visibility = View.INVISIBLE

        rv.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(r: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x; downY = e.y; pulling = false
                        tracking = !expanded && pull < 1f && enabled() && !r.canScrollVertically(-1)
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> tracking = false
                    MotionEvent.ACTION_MOVE -> {
                        if (expanded && e.pointerCount == 1 && e.y - downY < -slop) {
                            collapse()
                        } else if (tracking && !pulling && e.pointerCount == 1) {
                            val dy = e.y - downY
                            val dx = abs(e.x - downX)
                            if (dy < -slop) tracking = false
                            else if (dy > slop * 2 && dy > dx * 1.5f && !r.canScrollVertically(-1)) {
                                pulling = true
                                baseY = e.y
                                anim?.cancel()
                                return true
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
                }
                return false
            }

            override fun onTouchEvent(r: RecyclerView, e: MotionEvent) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> if (pulling) {
                        val raw = (e.y - baseY).coerceAtLeast(0f)
                        setPull(maxPull * raw / (raw + maxPull * 0.6f))
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pulling) {
                        pulling = false
                        tracking = false
                        if (pull >= snapAt) expand() else animateTo(0f)
                    }
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
        })

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                if (expanded && !pulling && r.canScrollVertically(-1)) collapse()
            }
        })

        header.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { hdrDownY = e.y; expanded }
                MotionEvent.ACTION_MOVE -> {
                    if (expanded && hdrDownY - e.y > slop) collapse()
                    expanded
                }
                else -> false
            }
        }
    }

    private fun expand() {
        expanded = true
        animateTo(maxPull)
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        animateTo(0f)
    }

    private fun animateTo(target: Float) {
        anim?.cancel()
        if (abs(pull - target) < 0.5f) { setPull(target); return }
        anim = ValueAnimator.ofFloat(pull, target).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener { setPull(it.animatedValue as Float) }
            start()
        }
    }

    private fun setPull(p: Float) {
        pull = p
        rv.translationY = p
        if (p <= 0.5f) {
            header.visibility = View.INVISIBLE
            header.clipBounds = null
            return
        }
        header.visibility = View.VISIBLE
        header.alpha = (p / maxPull * 1.4f).coerceIn(0f, 1f)
        header.clipBounds = Rect(0, 0, header.width, p.toInt())
    }
}