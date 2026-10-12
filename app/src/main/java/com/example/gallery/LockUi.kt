package com.example.gallery

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object LockStore {
    private const val ITER = 120_000

    fun hasPassword() = Prefs.getStr("lock_hash").isNotEmpty()

    private fun hash(pw: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pw, salt, ITER, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun save(pw: CharArray) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val h = hash(pw, salt)
        Prefs.putStr("lock_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
        Prefs.putStr("lock_hash", Base64.encodeToString(h, Base64.NO_WRAP))
    }

    fun verify(pw: CharArray): Boolean {
        val s = Prefs.getStr("lock_salt")
        val h = Prefs.getStr("lock_hash")
        if (s.isEmpty() || h.isEmpty()) return false
        val got = hash(pw, Base64.decode(s, Base64.NO_WRAP))
        return MessageDigest.isEqual(got, Base64.decode(h, Base64.NO_WRAP))
    }
}

object LockFlow {
    fun open(act: AppCompatActivity) {
        val go = { act.startActivity(Intent(act, LockAlbumsActivity::class.java)); Unit }
        if (LockStore.hasPassword()) LockDialog.enter(act, go) else LockDialog.create(act, go)
    }
}

object LockDialog {
    private var showing = false

    fun create(act: AppCompatActivity, onDone: () -> Unit) = panel(
        act, "Create Lock Password", listOf("Create Password", "Confirm Password"),
        { v, fin ->
            val a = v[0]
            val b = v[1]
            when {
                a.isEmpty() || b.isEmpty() -> fin("Both fields are required")
                a.length < 4 -> fin("Use at least 4 characters")
                a != b -> fin("Passwords do not match")
                else -> Thread {
                    try { LockStore.save(a.toCharArray()); fin(null) }
                    catch (e: Exception) { fin("Couldn't save password") }
                }.start()
            }
        }, onDone
    )

    fun enter(act: AppCompatActivity, onDone: () -> Unit) = panel(
        act, "Enter Password", listOf("Password"),
        { v, fin ->
            if (v[0].isEmpty()) fin("Enter your password")
            else Thread {
                val ok = try { LockStore.verify(v[0].toCharArray()) } catch (e: Exception) { false }
                fin(if (ok) null else "Incorrect password")
            }.start()
        }, onDone
    )

    private fun panel(
        act: AppCompatActivity, title: String, hints: List<String>,
        submit: (List<String>, (String?) -> Unit) -> Unit, onSuccess: () -> Unit
    ) {
        if (showing) return
        showing = true
        val d = act.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()

        val dlg = Dialog(act)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFF2C2C2C.toInt()); cornerRadius = 28 * d }
            setPadding(px(24), px(24), px(24), px(12))
        }
        card.addView(TextView(act).apply {
            text = title
            textSize = 19f
            setTextColor(Color.WHITE)
            useDisplay()
        })

        val fields = hints.mapIndexed { i, h ->
            EditText(act).apply {
                hint = h
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine()
                imeOptions = if (i == hints.size - 1) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
                setTextColor(Color.WHITE)
                setHintTextColor(0xFF8A8A8A.toInt())
                textSize = 15f
                useDisplay()
                setBackgroundResource(R.drawable.bg_field)
                setPadding(px(14), 0, px(14), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48)).apply { topMargin = px(14) }
            }
        }
        fields.forEach { card.addView(it) }

        val err = TextView(act).apply {
            textSize = 13f
            setTextColor(0xFFFF6B6B.toInt())
            useDisplay()
            visibility = View.GONE
            setPadding(px(2), px(10), 0, 0)
        }
        card.addView(err)

        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, px(14), 0, 0)
        }
        fun btn(label: String) = TextView(act).apply {
            text = label
            textSize = 15f
            setTextColor(Colors.BLUE)
            useDisplay()
            gravity = Gravity.CENTER
            minWidth = px(80)
            setBackgroundResource(R.drawable.bg_capsule_ripple)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(44))
        }
        val cancel = btn("Cancel")
        val done = btn("Done")
        row.addView(cancel)
        row.addView(done)
        card.addView(row)

        fun go() {
            if (!done.isEnabled) return
            err.visibility = View.GONE
            done.isEnabled = false
            done.alpha = 0.5f
            submit(fields.map { it.text.toString() }) { e ->
                act.runOnUiThread {
                    if (!dlg.isShowing) return@runOnUiThread
                    if (e == null) {
                        dlg.dismiss()
                        onSuccess()
                    } else {
                        err.text = e
                        err.visibility = View.VISIBLE
                        done.isEnabled = true
                        done.alpha = 1f
                        if (e == "Incorrect password") fields.last().setText("")
                    }
                }
            }
        }
        cancel.setOnClickListener { dlg.dismiss() }
        done.setOnClickListener { go() }
        fields.last().setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { go(); true } else false }

        val scroll = ScrollView(act).apply {
            isVerticalScrollbarEnabled = false
            addView(card)
        }
        dlg.setContentView(scroll)
        dlg.setOnDismissListener { showing = false }
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((act.resources.displayMetrics.widthPixels * 0.88f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dlg.show()
        fields[0].requestFocus()
    }
}