package com.example.gallery

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private val vm: GalleryViewModel by viewModels()
    private lateinit var tabs: List<TextView>
    private lateinit var permission: View
    private var current = -1

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkAccess(); vm.load() }

    private class Entry(val icon: Int, val label: String, val action: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.init(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        edgeToEdge(findViewById(R.id.root))

        permission = findViewById(R.id.permission)
        findViewById<TextView>(R.id.permText).useDisplay()
        findViewById<View>(R.id.btnAllow).setOnClickListener { permLauncher.launch(permissions()) }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }

        tabs = listOf(R.id.tabPictures, R.id.tabAlbums, R.id.tabStories).map { findViewById<TextView>(it) }
        tabs.forEachIndexed { i, t -> t.useDisplay(); t.setOnClickListener { select(i, true) } }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenuSheet() }

        select(Prefs.tab.coerceIn(0, 2), false)   // last tab restore (persistent)
        if (savedInstanceState == null && (!hasAccess() || needsVideo())) permLauncher.launch(permissions())
        checkAccess()
    }

    override fun onResume() {
        super.onResume()
        if (Changes.dirty) { Changes.dirty = false; vm.publish() }
        checkAccess()
    }

    // ---------- Tabs ----------
    private fun select(i: Int, animate: Boolean) {
        if (i == current) return
        val fm = supportFragmentManager
        val ft = fm.beginTransaction().setReorderingAllowed(true)
        if (animate) ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
        fm.fragments.forEach { ft.hide(it) }
        val tag = "tab$i"
        val existing = fm.findFragmentByTag(tag)
        if (existing == null) {
            val f: Fragment = when (i) {
                0 -> PicturesFragment.create("all", "", "", false)
                1 -> AlbumsFragment()
                else -> StoriesFragment()
            }
            ft.add(R.id.container, f, tag)
        } else ft.show(existing)
        ft.commit()

        Prefs.tab = i
        current = i
        tabs.forEachIndexed { k, t ->
            val sel = k == i
            val target = if (sel) Colors.BLUE else Colors.GRAY
            if (animate) {
                ValueAnimator.ofArgb(t.currentTextColor, target).apply {
                    duration = 180
                    addUpdateListener { t.setTextColor(it.animatedValue as Int) }
                    start()
                }
            } else t.setTextColor(target)
            t.paintFlags = if (sel) t.paintFlags or Paint.UNDERLINE_TEXT_FLAG else t.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }
    }

    // ---------- Permission ----------
    private fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) permissions().any { granted(it) }
        else granted(permissions()[0])

    /** Age shudhu photo permission dewa thakle video permission chaite hobe */
    private fun needsVideo(): Boolean = Build.VERSION.SDK_INT >= 33 &&
            !granted(Manifest.permission.READ_MEDIA_VIDEO) &&
            !(Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

    private fun checkAccess() {
        val ok = hasAccess()
        permission.visibility = if (ok) View.GONE else View.VISIBLE
        if (ok && vm.loaded.value != true) vm.load()
    }
    // ---------- Settings ----------
    private fun showSettings() {
        val items = arrayOf("Grid columns", "Photo sort order", "Show hidden albums & stories again", "Clear all favourites")
        MaterialAlertDialogBuilder(this).setTitle("Settings").setItems(items) { _, i ->
            when (i) {
                0 -> MaterialAlertDialogBuilder(this).setTitle("Grid columns")
                    .setSingleChoiceItems(Array(6) { "${it + 2} columns" }, Prefs.columns - 2) { d, w ->
                        Prefs.columns = w + 2
                        (supportFragmentManager.findFragmentByTag("tab0") as? PicturesFragment)?.applyColumns()
                        d.dismiss()
                    }.show()
                1 -> MaterialAlertDialogBuilder(this).setTitle("Photo sort order")
                    .setSingleChoiceItems(arrayOf("Date, newest first", "Date, oldest first", "Name"), Prefs.sort) { d, w ->
                        Prefs.sort = w; vm.publish(); d.dismiss()
                    }.show()
                2 -> {
                    Prefs.setSet("hidden", emptySet()); Prefs.setSet("hiddenStories", emptySet())
                    vm.publish()
                    Toast.makeText(this, "Hidden albums & stories are visible again", Toast.LENGTH_SHORT).show()
                }
                3 -> MaterialAlertDialogBuilder(this).setMessage("Clear all favourites?")
                    .setPositiveButton("Clear") { _, _ -> Prefs.setSet("fav", emptySet()); vm.publish() }
                    .setNegativeButton("Cancel", null).show()
            }
        }.show()
    }
    // ---------- Hamburger menu ----------
    private fun showMenuSheet() {
        val d = BottomSheetDialog(this)
        val dp = resources.displayMetrics.density
        val grid = GridLayout(this).apply {
            columnCount = 4
            setPadding((12 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt(), (32 * dp).toInt())
        }
        val entries = listOf(
            Entry(R.drawable.ic_favorite_border, "Favourites") { startActivity(GridActivity.intent(this, "fav", "", "Favourites")) },
            Entry(R.drawable.ic_video, "Videos") { startActivity(GridActivity.intent(this, "video", "", "Videos")) },
            Entry(R.drawable.ic_history, "Recent") { startActivity(GridActivity.intent(this, "recent", "", "Recent")) },
            Entry(R.drawable.ic_hidden, "Hide albums") { HideAlbumsDialog.show(this, vm) },
            Entry(R.drawable.ic_settings, "Settings") { showSettings() },
            Entry(R.drawable.ic_info, "About") {
                MaterialAlertDialogBuilder(this).setTitle("Gallery")
                    .setMessage("Your photos, straight from device storage.").setPositiveButton("OK", null).show()
            }
        )
        entries.forEach { e ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
                layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 }
                addView(ImageView(context).apply {
                    setImageResource(e.icon); setColorFilter(Color.WHITE)
                    contentDescription = e.label
                    layoutParams = LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt())
                })
                addView(TextView(context).apply {
                    text = e.label; setTextColor(Color.WHITE); textSize = 14f
                    gravity = Gravity.CENTER; useDisplay()
                    setPadding(0, (8 * dp).toInt(), 0, 0)
                })
                setOnClickListener { d.dismiss(); e.action() }
            }
            grid.addView(cell)
        }
        d.setContentView(grid)
        (grid.parent as View).setBackgroundResource(R.drawable.bg_sheet)
        d.show()
    }
}