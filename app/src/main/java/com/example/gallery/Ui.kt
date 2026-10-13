package com.example.gallery

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.widget.doAfterTextChanged

class SquareImageView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : AppCompatImageView(c, a) {
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
}

class PortraitImageView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : AppCompatImageView(c, a) {
    override fun onMeasure(w: Int, h: Int) {
        val width = View.MeasureSpec.getSize(w)
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec((width * 1.3f).toInt(), View.MeasureSpec.EXACTLY))
    }
}

fun View.roundCorners(dp: Float) {
    val r = dp * resources.displayMetrics.density
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) }
    }
    clipToOutline = true
}

/** Reference er moto rounded dark popup menu */
fun showDarkMenu(anchor: View, items: List<String>, onClick: (Int) -> Unit) {
    val ctx = anchor.context
    val d = ctx.resources.displayMetrics.density
    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(0xFF262626.toInt()); cornerRadius = 28 * d }
        setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
        elevation = 10 * d
        alpha = 0f; scaleX = .85f; scaleY = .85f
    }
    val pw = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    items.forEachIndexed { i, label ->
        box.addView(TextView(ctx).apply {
            text = label; textSize = 15f; setTextColor(Color.WHITE); useDisplay()
            minWidth = (170 * d).toInt()
            setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), (16 * d).toInt())
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { pw.dismiss(); onClick(i) }
        })
    }
    pw.showAsDropDown(anchor, (-8 * d).toInt(), 0, Gravity.END)
    box.post {
        box.pivotX = box.width.toFloat(); box.pivotY = 0f
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).setInterpolator(DecelerateInterpolator()).start()
    }
}
fun confirmTrash(ctx: Context, count: Int, onYes: () -> Unit) {
    val msg = if (count == 1) "Move this item to the Recycle Bin?" else "Move $count items to the Recycle Bin?"
    showDeleteSheet(ctx, msg, if (android.os.Build.VERSION.SDK_INT >= 30) "Move to Recycle Bin" else "Delete", Colors.BLUE, Colors.BLUE, onYes)
}

/** Naam lekhar dialog (album / story create & rename) */
fun askName(ctx: Context, title: String, initial: String, onOk: (String) -> Unit) {
    val et = EditText(ctx)
    et.setSingleLine()
    et.setText(initial)
    et.setSelection(et.text.length)
    val pad = (20 * ctx.resources.displayMetrics.density).toInt()
    val box = FrameLayout(ctx)
    box.setPadding(pad, pad / 2, pad, 0)
    box.addView(et)
    MaterialAlertDialogBuilder(ctx).setTitle(title).setView(box)
        .setPositiveButton("OK") { _, _ ->
            val n = et.text.toString().trim()
            if (n.isNotEmpty()) onOk(n)
        }
        .setNegativeButton("Cancel", null).show()
}
/** Top bar er search open/close handle kore */
class SearchController(
    private val input: EditText,
    private val hideWhenOpen: List<View>,
    private val button: ImageView,
    private val onQuery: (String) -> Unit
) {
    var active = false
        private set

    init { input.doAfterTextChanged { onQuery(it?.toString().orEmpty()) } }

    private val imm get() = input.context.getSystemService(InputMethodManager::class.java)

    fun open() {
        active = true
        hideWhenOpen.forEach { it.visibility = View.GONE }
        input.visibility = View.VISIBLE
        button.setImageResource(R.drawable.ic_close)
        button.contentDescription = "Close search"
        input.requestFocus()
        imm.showSoftInput(input, 0)
    }

    fun close() {
        if (!active) return
        active = false
        input.setText("")
        input.visibility = View.GONE
        hideWhenOpen.forEach { it.visibility = View.VISIBLE }
        button.setImageResource(R.drawable.ic_search)
        button.contentDescription = "Search"
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }
}

/** Grey bottom-sheet delete confirmation (shob jaygay ek style) */
fun showDeleteSheet(ctx: Context, message: String, confirmLabel: String = "Delete",confirmColor: Int = 0xFFFF4D4D.toInt(), cancelColor: Int = Color.WHITE, onDelete: () -> Unit) {
    val d = ctx.resources.displayMetrics.density
    val dlg = BottomSheetDialog(ctx, R.style.App_BottomSheetDialog)
    val outer = FrameLayout(ctx)
    outer.setPadding((12 * d).toInt(), 0, (12 * d).toInt(), (12 * d).toInt())
    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(0xFF2C2C2C.toInt()); cornerRadius = 28 * d }
        setPadding((8 * d).toInt(), (28 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
    }
    card.addView(TextView(ctx).apply {
        text = message
        textSize = 16f
        setTextColor(Color.WHITE)
        useDisplay()
        gravity = Gravity.CENTER
        setPadding((24 * d).toInt(), 0, (24 * d).toInt(), (20 * d).toInt())
    })
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    fun btn(label: String, color: Int, act: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 16f
        setTextColor(color)
        useDisplay()
        gravity = Gravity.CENTER
        isSingleLine = true
        setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (52 * d).toInt()).apply {
            marginStart = (14 * d).toInt()
            marginEnd = (14 * d).toInt()
        }
        setBackgroundResource(R.drawable.bg_dialog_btn)
        setOnClickListener { dlg.dismiss(); act() }
    }
    row.addView(btn("Cancel", cancelColor) { })

    row.addView(btn(confirmLabel, confirmColor) { onDelete() }.apply {
        (layoutParams as LinearLayout.LayoutParams).marginStart = (12 * d).toInt()
    })
    card.addView(row)
    outer.addView(card)
    dlg.setContentView(outer)
    (outer.parent as View).setBackgroundColor(Color.TRANSPARENT)
    dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    dlg.behavior.skipCollapsed = true
    dlg.show()
}

/** Ek button-er info sheet */
fun showInfoSheet(ctx: Context, message: String) {
    val d = ctx.resources.displayMetrics.density
    val dlg = BottomSheetDialog(ctx, R.style.App_BottomSheetDialog)
    val outer = FrameLayout(ctx)
    outer.setPadding((12 * d).toInt(), 0, (12 * d).toInt(), (12 * d).toInt())
    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(0xFF2C2C2C.toInt()); cornerRadius = 28 * d }
        setPadding((8 * d).toInt(), (28 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
    }
    card.addView(TextView(ctx).apply {
        text = message
        textSize = 16f
        setTextColor(Color.WHITE)
        useDisplay()
        gravity = Gravity.CENTER
        setPadding((24 * d).toInt(), 0, (24 * d).toInt(), (20 * d).toInt())
    })
    card.addView(TextView(ctx).apply {
        text = "OK"
        textSize = 16f
        setTextColor(Colors.BLUE)
        useDisplay()
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_dialog_btn)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * d).toInt())
        setOnClickListener { dlg.dismiss() }
    })
    outer.addView(card)
    dlg.setContentView(outer)
    (outer.parent as View).setBackgroundColor(Color.TRANSPARENT)
    dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    dlg.behavior.skipCollapsed = true
    dlg.show()
}