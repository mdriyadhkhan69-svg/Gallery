package com.example.gallery

import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class MoveProgress(private val ctx: Context, label: String) {
    private val d = ctx.resources.displayMetrics.density
    private val hd = Handler(Looper.getMainLooper())
    private val dlg = Dialog(ctx)
    private val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
    private val pct = TextView(ctx)
    private val title = TextView(ctx)
    private var value = 0

    init {
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setCancelable(false)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFF2C2C2C.toInt()); cornerRadius = 24 * d }
            setPadding((22 * d).toInt(), (20 * d).toInt(), (22 * d).toInt(), (20 * d).toInt())
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.text = label
        title.textSize = 15f
        title.setTextColor(Color.WHITE)
        title.useDisplay()
        pct.text = "0%"
        pct.textSize = 15f
        pct.setTextColor(Colors.BLUE)
        pct.useDisplay()
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(pct)
        card.addView(top)
        bar.max = 100
        bar.progress = 0
        bar.progressTintList = ColorStateList.valueOf(Colors.BLUE)
        bar.progressBackgroundTintList = ColorStateList.valueOf(0xFF3A3A3A.toInt())
        card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (8 * d).toInt()).apply { topMargin = (14 * d).toInt() })
        dlg.setContentView(card)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((ctx.resources.displayMetrics.widthPixels * 0.82f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setDimAmount(0.25f)
        }
    }

    fun show() { try { dlg.show() } catch (e: Exception) { } }

    fun set(p: Int) {
        val t = p.coerceIn(0, 100)
        if (t <= value) return
        value = t
        pct.text = "$t%"
        ObjectAnimator.ofInt(bar, "progress", bar.progress, t).setDuration(200).apply {
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    fun success(text: String) {
        title.text = text
        set(100)
        hd.postDelayed({ dismiss() }, 700)
    }

    fun dismiss() {
        hd.removeCallbacksAndMessages(null)
        try { if (dlg.isShowing) dlg.dismiss() } catch (e: Exception) { }
    }
}