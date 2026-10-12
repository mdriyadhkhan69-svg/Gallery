package com.example.gallery

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator

class CapsuleSwitch @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {

    private val d = resources.displayMetrics.density
    private val trackW = 38f * d
    private val trackH = 22f * d
    private val pad = 4f * d
    private val r = 7f * d
    private val offFill = 0xFF2A2A2A.toInt()
    private val onFill = 0xFF3D8BFF.toInt()
    private val offStroke = 0xFF666666.toInt()
    private val ev = ArgbEvaluator()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.75f * d; color = 0x33000000 }
    private val box = RectF()
    private var p = 0f
    private var anim: ValueAnimator? = null

    var checked = false
        private set
    var onChange: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle() }
    }

    fun set(on: Boolean, animate: Boolean) {
        checked = on
        anim?.cancel()
        val to = if (on) 1f else 0f
        if (!animate || !isShown) { p = to; invalidate(); return }
        val from = p
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener { p = from + (to - from) * (it.animatedValue as Float); invalidate() }
            start()
        }
    }

    fun toggle() {
        set(!checked, true)
        onChange?.invoke(checked)
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension((56 * d).toInt(), (48 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        box.set(cx - trackW / 2f, cy - trackH / 2f, cx + trackW / 2f, cy + trackH / 2f)
        val left = box.left
        val t = p.coerceIn(0f, 1f)
        fill.color = ev.evaluate(t, offFill, onFill) as Int
        c.drawRoundRect(box, trackH / 2f, trackH / 2f, fill)
        stroke.color = ev.evaluate(t, offStroke, onFill) as Int
        box.inset(0.5f * d, 0.5f * d)
        c.drawRoundRect(box, trackH / 2f, trackH / 2f, stroke)
        val travel = trackW - 2 * pad - 2 * r
        val tx = left + pad + r + p.coerceIn(-0.08f, 1.08f) * travel
        c.drawCircle(tx, cy, r, thumb)
        c.drawCircle(tx, cy, r, edge)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = checked
    }
}