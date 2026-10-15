package com.example.gallery

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class SelectionBar @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : LinearLayout(c, a) {

    /** Main screen e navigation row/pill; khali hole capsule nijei show/hide hoy (album screen) */
    var peers: List<View> = emptyList()
    var growParent = false
    var onCreate: (() -> Unit)? = null
    var onShare: (() -> Unit)? = null
    var onDelete: (() -> Unit)? = null
    var onMore: ((View) -> Unit)? = null

    var active = false
        private set

    private val d = resources.displayMetrics.density
    private var baseH = 0
    private var hAnim: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addAction("Create", R.drawable.ic_create) { onCreate?.invoke() }
        addAction("Share", R.drawable.ic_share_o) { onShare?.invoke() }
        addAction("Delete", R.drawable.ic_trash_o) { onDelete?.invoke() }
        addAction("More", R.drawable.ic_more) { onMore?.invoke(it) }
    }

    private fun addAction(label: String, icon: Int, click: (View) -> Unit) {
        addView(TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            useDisplay()
            gravity = Gravity.CENTER
            setSingleLine()
            compoundDrawablePadding = (2 * d).toInt()
            setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0)
            contentDescription = label
            setBackgroundResource(R.drawable.bg_capsule_ripple)
            isClickable = true
            isFocusable = true
            setOnClickListener { click(it) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
    }

    fun setActive(on: Boolean, animate: Boolean = true) {
        if (on == active) return
        active = on
        val dur = if (animate) 180L else 0L
        val cap = parent as? View ?: return

        if (peers.isEmpty()) {
            cap.animate().cancel()
            if (on) {
                cap.visibility = View.VISIBLE
                cap.alpha = 0f
                cap.translationY = 24f * d
                cap.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(dur).start()
            } else {
                cap.animate().alpha(0f).translationY(24f * d).setStartDelay(0).setDuration(dur)
                    .withEndAction { if (!active) cap.visibility = View.GONE }.start()
            }
            return
        }

        val half = dur / 2
        if (growParent) {
            val lp = cap.layoutParams
            if (baseH == 0) baseH = lp.height
            val to = if (on) (64f * d).toInt() else baseH
            hAnim?.cancel()
            if (lp.height != to) {
                hAnim = ValueAnimator.ofInt(lp.height, to).apply {
                    duration = dur
                    addUpdateListener { lp.height = it.animatedValue as Int; cap.layoutParams = lp }
                    start()
                }
            }
        }
        if (on) {
            animate().cancel()
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setStartDelay(half).setDuration(dur - half).start()
            peers.forEach { p ->
                p.animate().cancel()
                p.animate().alpha(0f).setStartDelay(0).setDuration(half)
                    .withEndAction { if (active) p.visibility = View.INVISIBLE }.start()
            }
        } else {
            animate().cancel()
            animate().alpha(0f).setStartDelay(0).setDuration(half)
                .withEndAction { if (!active) visibility = View.INVISIBLE }.start()
            peers.forEach { p ->
                p.animate().cancel()
                p.visibility = View.VISIBLE
                p.animate().alpha(1f).setStartDelay(half).setDuration(dur - half).start()
            }
        }
    }
}