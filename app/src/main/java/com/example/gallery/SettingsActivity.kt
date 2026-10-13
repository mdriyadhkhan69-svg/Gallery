package com.example.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.MotionEvent
import android.view.animation.OvershootInterpolator
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable

class SettingsActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private var card: LinearLayout? = null
    private val sortLabels = arrayOf("Date, newest first", "Date, oldest first", "Name")
    private val ui = Handler(Looper.getMainLooper())
    private val taps = IntArray(3)
    private val last = LongArray(3)
    private var started = false

    private val rebuild = Runnable { build(false) }
    private val resetAct = Runnable { taps[1] = 0; confirmReset() }
    private val notifyAct = Runnable { taps[2] = 0; pickTimer() }

    private val permL = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        LockReset.notifyNow(this)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        Prefs.init(this)
        super.onCreate(b)
        setContentView(R.layout.activity_settings)
        edgeToEdge(findViewById(R.id.root))
        findViewById<TextView>(R.id.title).useDisplay()
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        content = findViewById(R.id.content)
        build(true)
        started = true
    }

    override fun onResume() {
        super.onResume()
        if (started) build(false)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun seven(i: Int): Boolean {
        val n = System.currentTimeMillis()
        taps[i] = if (n - last[i] > 1500) 1 else taps[i] + 1
        last[i] = n
        if (taps[i] >= 7) { taps[i] = 0; return true }
        return false
    }

    // ---------- UI helpers ----------
    private fun section(name: String) {
        content.addView(TextView(this).apply {
            text = name
            textSize = 13f
            setTextColor(Colors.BLUE)
            useDisplay()
            setPadding(dp(48), dp(22), dp(24), dp(8))
        })
        val r = dp(16).toFloat()
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val base = GradientDrawable().apply { setColor(0xFF1C1C1C.toInt()); cornerRadius = r }
            val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = r }
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, mask)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, r)
                }
            }
        }
        content.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(16); marginEnd = dp(16) })
        card = c
    }

    private fun row(title: String, value: String, onClick: (() -> Unit)?) {
        val c = card ?: return
        if (c.childCount > 0) {
            c.addView(View(this).apply {
                setBackgroundColor(0xFF2A2A2A.toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    .apply { marginStart = dp(32); marginEnd = dp(32) }
            })
        }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(16), dp(32), dp(16))
        }
        r.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            useDisplay()
        })
        r.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(Colors.GRAY)
            useDisplay()
            setPadding(0, dp(2), 0, 0)
        })
        c.addView(r)
        if (onClick != null) {
            r.setOnClickListener { onClick() }
            r.isClickable = true
            r.isFocusable = true
            r.foreground = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), null,
                GradientDrawable().apply { setColor(Color.WHITE) }
            )
        }
    }

    private fun durLabel(): String {
        val m = LockReset.duration() / 60_000L
        return "${m / 60}h ${m % 60}m"
    }
    private fun switchRow(title: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_press)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            useDisplay()
        })
        col.addView(TextView(this).apply {
            text = desc
            textSize = 13f
            setTextColor(Colors.GRAY)
            useDisplay()
            setPadding(0, dp(2), dp(12), 0)
        })
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = CapsuleSwitch(this)
        sw.contentDescription = title
        sw.set(on, false)
        sw.onChange = onChange
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        content.addView(r)
    }

    // ---------- Build ----------
    private fun build(animate: Boolean) {
        LockReset.tick()
        ui.removeCallbacks(rebuild)
        content.removeAllViews()
        card = null

        section("Gallery")
        row("Grid columns", "${Prefs.columns} columns") {
            MaterialAlertDialogBuilder(this).setTitle("Grid columns")
                .setSingleChoiceItems(Array(6) { "${it + 2} columns" }, Prefs.columns - 2) { d, w ->
                    Prefs.columns = w + 2
                    d.dismiss()
                    build(false)
                }.show()
        }
        row("Photo sort order", sortLabels[Prefs.sort.coerceIn(0, 2)]) {
            MaterialAlertDialogBuilder(this).setTitle("Photo sort order")
                .setSingleChoiceItems(sortLabels, Prefs.sort) { d, w ->
                    Prefs.sort = w
                    Changes.dirty = true
                    d.dismiss()
                    build(false)
                }.show()
        }

        switchRow(
            "Swipe Between Sections",
            "Swipe horizontally to move between Pictures, Albums, and Stories.",
            Prefs.swipeNav
        ) { Prefs.swipeNav = it }
        section("Albums")
        switchRow(
            "Move deleted album media to Recover Images",
            "OFF: deleting an album sends its media to the Recycle Bin. ON: its media goes to the Recover Images album instead.",
            Prefs.albumToRecover
        ) { Prefs.albumToRecover = it }
        section("Favourites")
        row("Clear all favourites", "Remove the heart from every photo and story") {
            MaterialAlertDialogBuilder(this).setMessage("Clear all favourites?")
                .setPositiveButton("Clear") { _, _ ->
                    Prefs.setSet("fav", emptySet())
                    Changes.dirty = true
                    Toast.makeText(this, "Favourites cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null).show()
        }

        section("Storage")
        switchRow(
            "Move to Recycle Bin",
            if (Deleter.binAvailable()) "Choose whether deleted items go to the Recycle Bin or are permanently deleted."
            else "Not available on this Android version. Items are deleted permanently.",
            Prefs.useBin && Deleter.binAvailable()
        ) { Prefs.useBin = it }
        if (Build.VERSION.SDK_INT >= 31) {
            val on = MediaStore.canManageMedia(this)
            row("Skip system prompt", if (on) "On · deleting or moving media needs no extra system prompt" else "Off · tap to allow") {
                startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", packageName, null)))
            }
        } else {
            row("Skip system prompt", "Not needed on this Android version", null)
        }

        if (LockReset.resetVisible()) {
            section("Security")
            val sub = if (LockReset.running()) "Countdown running · ${fmtClock(LockReset.remaining().toInt())} left"
            else "Remove the lock password and hide locked albums"
            row("Reset lock password", sub) { onResetTap() }
            if (LockReset.timerVisible()) row("Notify me timer", durLabel()) { onNotifyTap() }
            if (LockReset.stopVisible() && LockReset.running()) {
                row("Stop notify me", "Enter lock password to cancel the reset") { stopNotify() }
            }
            ui.postDelayed(rebuild, LockReset.visLeft() + 300)
        }

        section("About")
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        row("Gallery", "Version $ver") {
            if (seven(0)) {
                LockReset.toggleReset()
                build(false)
            }
        }

        if (animate) {
            for (i in 0 until content.childCount) {
                val v = content.getChildAt(i)
                v.alpha = 0f
                v.translationY = dp(14).toFloat()
                v.animate().alpha(1f).translationY(0f).setDuration(280).setStartDelay(i * 35L)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    // ---------- Lock reset ----------
    private fun onNotifyTap() {
        ui.removeCallbacks(notifyAct)
        if (seven(2)) {
            LockReset.toggleStop()
            build(false)
            return
        }
        ui.postDelayed(notifyAct, 650)
    }

    private fun stopNotify() {
        if (!LockReset.running()) { build(false); return }
        val done = {
            LockReset.stop(this)
            Toast.makeText(this, "Reset cancelled. Lock password restored.", Toast.LENGTH_LONG).show()
            build(false)
        }
        if (LockStore.hasBackup()) LockDialog.verifyOld(this, done) else done()
    }
    private fun onResetTap() {
        ui.removeCallbacks(resetAct)
        if (seven(1)) {
            LockReset.toggleTimer()
            build(false)
            return
        }
        ui.postDelayed(resetAct, 650)
    }

    private fun confirmReset() {
        if (LockReset.running()) {
            showInfoSheet(this, "Password reset countdown is already running.\nTime left: ${fmtClock(LockReset.remaining().toInt())}")
            return
        }
        showDeleteSheet(
            this,
            "Reset lock password?\n\nThe password will be removed and all locked albums will move to Hide albums. " +
                    "Your photos are NOT deleted. After the countdown (${durLabel()}) the albums become visible again " +
                    "and you can create a new password.",
            "Reset"
        ) {
            LockReset.perform(this)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) permL.launch(Manifest.permission.POST_NOTIFICATIONS)
            Toast.makeText(this, "Password reset done. Locked albums moved to Hide albums.", Toast.LENGTH_LONG).show()
            build(false)
        }
    }

    private fun pickTimer() {
        val total = (LockReset.duration() / 60_000L).toInt()
        val hp = NumberPicker(this).apply { minValue = 0; maxValue = 24; value = total / 60; wrapSelectorWheel = false }
        val mp = NumberPicker(this).apply { minValue = 0; maxValue = 59; value = total % 60 }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        row.addView(hp)
        row.addView(TextView(this).apply { text = "h"; setPadding(dp(8), 0, dp(24), 0) })
        row.addView(mp)
        row.addView(TextView(this).apply { text = "min"; setPadding(dp(8), 0, 0, 0) })
        MaterialAlertDialogBuilder(this).setTitle("Notify me timer (max 24 h)").setView(row)
            .setPositiveButton("Set") { _, _ ->
                val m = (hp.value * 60 + mp.value).coerceIn(1, 1440)
                LockReset.setDuration(m * 60_000L)
                Toast.makeText(this, "Timer set", Toast.LENGTH_SHORT).show()
                build(false)
            }
            .setNegativeButton("Cancel", null).show()
    }
}