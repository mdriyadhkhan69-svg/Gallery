package com.example.gallery

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private val sortLabels = arrayOf("Date, newest first", "Date, oldest first", "Name")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        Prefs.init(this)
        super.onCreate(b)
        setContentView(R.layout.activity_settings)
        edgeToEdge(findViewById(R.id.root))
        findViewById<TextView>(R.id.title).useDisplay()
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        content = findViewById(R.id.content)
        build()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun section(name: String) {
        if (content.childCount > 0) {
            content.addView(View(this).apply {
                setBackgroundColor(0xFF1F1F1F.toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    .apply { topMargin = dp(8) }
            })
        }
        content.addView(TextView(this).apply {
            text = name
            textSize = 13f
            setTextColor(Colors.BLUE)
            useDisplay()
            setPadding(dp(24), dp(20), dp(24), dp(6))
        })
    }

    private fun row(title: String, value: String, onClick: (() -> Unit)?) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(14))
            if (onClick != null) {
                setBackgroundResource(R.drawable.bg_press)
                setOnClickListener { onClick() }
            }
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
        content.addView(r)
    }

    private fun build() {
        content.removeAllViews()

        section("Gallery")
        row("Grid columns", "${Prefs.columns} columns") {
            MaterialAlertDialogBuilder(this).setTitle("Grid columns")
                .setSingleChoiceItems(Array(6) { "${it + 2} columns" }, Prefs.columns - 2) { d, w ->
                    Prefs.columns = w + 2
                    d.dismiss()
                    build()
                }.show()
        }
        row("Photo sort order", sortLabels[Prefs.sort.coerceIn(0, 2)]) {
            MaterialAlertDialogBuilder(this).setTitle("Photo sort order")
                .setSingleChoiceItems(sortLabels, Prefs.sort) { d, w ->
                    Prefs.sort = w
                    Changes.dirty = true
                    d.dismiss()
                    build()
                }.show()
        }

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
        if (Build.VERSION.SDK_INT >= 31) {
            val on = MediaStore.canManageMedia(this)
            row("Instant Recycle Bin", if (on) "On · items move to the Recycle Bin without an extra prompt" else "Off · tap to allow") {
                startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", packageName, null)))
            }
        } else {
            row("Instant Recycle Bin", "Not needed on this Android version", null)
        }

        section("About")
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        row("Gallery", "Version $ver", null)
    }
}