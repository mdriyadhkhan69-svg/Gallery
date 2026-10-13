package com.example.gallery

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.core.animation.doOnEnd
import kotlin.math.abs
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
import android.view.animation.DecelerateInterpolator
import android.widget.GridLayout
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
    private lateinit var navPill: View
    private lateinit var navItems: List<View>
    private val resetWatch = android.os.Handler(android.os.Looper.getMainLooper())
    private val resetRun = object : Runnable {
        override fun run() {
            if (LockReset.tick()) { Changes.dirty = false; vm.publish() }
            else if (LockReset.running()) resetWatch.postDelayed(this, 1000)
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkAccess(); vm.load() }

    private class Entry(val icon: Int, val label: String, val action: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.init(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val navBar = findViewById<View>(R.id.nav)
        val navBase = (8 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, 0)
            (navBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = navBase + b.bottom
            navBar.requestLayout()
            WindowInsetsCompat.CONSUMED
        }

        permission = findViewById(R.id.permission)
        findViewById<TextView>(R.id.permText).useDisplay()
        findViewById<View>(R.id.btnAllow).setOnClickListener { permLauncher.launch(permissions()) }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }

        tabs = listOf(R.id.tabPictures, R.id.tabAlbums, R.id.tabStories).map { findViewById<TextView>(it) }
        navPill = findViewById(R.id.navPill)
        navItems = listOf<View>(tabs[0], tabs[1], tabs[2], findViewById<View>(R.id.btnMenu))
        tabs.forEachIndexed { i, t -> t.useDisplay(); t.setOnClickListener { select(i, true) } }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenuSheet() }

        select(Prefs.tab.coerceIn(0, 2), false)   // last tab restore (persistent)
        if (savedInstanceState == null && (!hasAccess() || needsVideo())) permLauncher.launch(permissions())
        checkAccess()
        findViewById<View>(R.id.container).post { ensureTabs() }
    }

    override fun onResume() {
        super.onResume()
        (supportFragmentManager.findFragmentByTag("tab0") as? PicturesFragment)?.applyColumns()
        LockReset.tick()
        if (Changes.dirty) { Changes.dirty = false; vm.publish() }
        resetWatch.removeCallbacks(resetRun)
        if (LockReset.running()) resetWatch.postDelayed(resetRun, 1000)
        checkAccess()
    }
    override fun onPause() {
        super.onPause()
        resetWatch.removeCallbacksAndMessages(null)
    }
    // ---------- Tabs ----------
    private fun select(i: Int, animate: Boolean) {
        if (swBusy || swActive) return
        if (i == current) {
            if (animate) {
                when (val f = supportFragmentManager.findFragmentByTag("tab$i")) {
                    is PicturesFragment -> f.scrollToTop()
                    is AlbumsFragment -> f.scrollToTop()
                    is StoriesFragment -> f.scrollToTop()
                }
            }
            return
        }
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
        }
        movePill(i, animate)
    }
    private fun movePill(i: Int, animate: Boolean) {
        val v = navItems[i]
        if (v.width == 0) { v.post { movePill(i, false) }; return }
        val inset = 6 * resources.displayMetrics.density
        val w = (v.width - 2 * inset).toInt()
        val lp = navPill.layoutParams
        if (lp.width != w) { lp.width = w; navPill.layoutParams = lp }
        navPill.visibility = View.VISIBLE
        val x = v.left + inset
        navPill.animate().cancel()
        if (animate) navPill.animate().translationX(x).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        else navPill.translationX = x
    }
    // ---------- Swipe between sections ----------
    private var swTracking = false
    private var swActive = false
    private var swBusy = false
    private var swSwallow = false
    private var swDownX = 0f
    private var swDownY = 0f
    private var swTarget = -1
    private var swW = 0f
    private var swD = 0f
    private var swVt: VelocityTracker? = null
    private var swAnim: ValueAnimator? = null
    private var swCur: View? = null
    private var swTgt: View? = null
    private val swEval = ArgbEvaluator()

    private fun tabFragment(i: Int) = supportFragmentManager.findFragmentByTag("tab$i")

    private fun ensureTabs() {
        if (isFinishing || isDestroyed) return
        val fm = supportFragmentManager
        if (fm.isStateSaved) return
        fm.executePendingTransactions()
        val missing = (0..2).filter { fm.findFragmentByTag("tab$it") == null }
        if (missing.isEmpty()) return
        val ft = fm.beginTransaction()
        missing.forEach { i ->
            val f: Fragment = when (i) {
                0 -> PicturesFragment.create("all", "", "", false)
                1 -> AlbumsFragment()
                else -> StoriesFragment()
            }
            ft.add(R.id.container, f, "tab$i")
            ft.hide(f)
        }
        ft.commitNow()
    }

    private fun swipeBlocked(): Boolean = when (val f = tabFragment(current)) {
        is PicturesFragment -> f.swipeBlocked()
        is AlbumsFragment -> f.swipeBlocked()
        is StoriesFragment -> f.swipeBlocked()
        else -> true
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (swSwallow) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) swSwallow = false
            return true
        }
        if (!Prefs.swipeNav) return super.dispatchTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swVt?.recycle()
                swVt = VelocityTracker.obtain().also { it.addMovement(e) }
                swDownX = e.rawX
                swDownY = e.rawY
                val d = resources.displayMetrics
                val nearScrollbar = e.rawX > d.widthPixels - 48 * d.density
                swTracking = !swBusy && !swActive && current >= 0 && !nearScrollbar &&
                        permission.visibility != View.VISIBLE && !swipeBlocked()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                swTracking = false
                if (swActive) { settle(); swSwallow = true; return true }
            }
            MotionEvent.ACTION_MOVE -> {
                swVt?.addMovement(e)
                if (swActive) { drag(e.rawX - swDownX); return true }
                if (swTracking && e.pointerCount == 1) {
                    val dx = e.rawX - swDownX
                    val dy = e.rawY - swDownY
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
                    if (abs(dy) > slop && abs(dy) > abs(dx)) {
                        swTracking = false
                    } else if (abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.6f) {
                        swTracking = false
                        if (beginSwipe(dx)) {
                            val c = MotionEvent.obtain(e)
                            c.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(c)
                            c.recycle()
                            drag(dx)
                            return true
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                swVt?.addMovement(e)
                swTracking = false
                if (swActive) { settle(); return true }
                swVt?.recycle()
                swVt = null
            }
        }
        return super.dispatchTouchEvent(e)
    }

    private fun beginSwipe(dx: Float): Boolean {
        val t = if (dx < 0) current + 1 else current - 1
        if (t !in 0..2) return false
        val c = tabFragment(current)?.view
        val n = tabFragment(t)?.view
        if (c == null || n == null) return false
        val w = findViewById<View>(R.id.container).width.toFloat()
        if (w <= 0f) return false
        navPill.animate().cancel()
        swCur = c
        swTgt = n
        swTarget = t
        swW = w
        swD = 0f
        n.translationX = if (t > current) w else -w
        n.visibility = View.VISIBLE
        swActive = true
        return true
    }

    private fun drag(raw: Float) {
        val d = if (swTarget > current) raw.coerceIn(-swW, 0f) else raw.coerceIn(0f, swW)
        applySwipe(d)
    }

    private fun applySwipe(d: Float) {
        swD = d
        swCur?.translationX = d
        swTgt?.translationX = d + if (swTarget > current) swW else -swW
        val f = (abs(d) / swW).coerceIn(0f, 1f)
        val inset = 6 * resources.displayMetrics.density
        val a = navItems[current]
        val b = navItems[swTarget]
        navPill.translationX = a.left + inset + (b.left - a.left) * f
        tabs[current].setTextColor(swEval.evaluate(f, Colors.BLUE, Colors.GRAY) as Int)
        tabs[swTarget].setTextColor(swEval.evaluate(f, Colors.GRAY, Colors.BLUE) as Int)
    }

    private fun settle() {
        swActive = false
        val vt = swVt
        swVt = null
        var vx = 0f
        if (vt != null) {
            vt.computeCurrentVelocity(1000)
            vx = vt.xVelocity
            vt.recycle()
        }
        val dir = if (swTarget > current) -1f else 1f
        val progress = abs(swD) / swW
        val fling = abs(vx) > 900f && vx * dir > 0f
        val commit = progress > 0.33f || (fling && progress > 0.04f)
        val end = if (commit) dir * swW else 0f
        val target = swTarget
        swBusy = true
        swAnim = ValueAnimator.ofFloat(swD, end).apply {
            duration = (100 + 180 * (abs(end - swD) / swW)).toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener { applySwipe(it.animatedValue as Float) }
            doOnEnd { finishSwipe(commit, target) }
            start()
        }
    }

    private fun finishSwipe(commit: Boolean, target: Int) {
        val c = swCur
        val n = swTgt
        swCur = null
        swTgt = null
        swBusy = false
        if (commit) {
            select(target, false)
            supportFragmentManager.executePendingTransactions()
        } else {
            n?.visibility = View.GONE
        }
        c?.translationX = 0f
        n?.translationX = 0f
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
            Entry(R.drawable.ic_delete, "Recycle Bin") { startActivity(Intent(this, TrashActivity::class.java)) },            Entry(R.drawable.ic_settings, "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) },
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
                    text = e.label; setTextColor(Color.WHITE); textSize = 12f
                    gravity = Gravity.CENTER; useDisplay()
                    setPadding(0, (8 * dp).toInt(), 0, 0)
                })
                setBackgroundResource(R.drawable.bg_menu_item)
                isClickable = true
                isFocusable = true
                setOnClickListener { postDelayed({ d.dismiss(); e.action() }, 120) }
            }
            grid.addView(cell)
        }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (LockReset.running()) {
            val tv = TextView(this).apply {
                textSize = 12f
                setTextColor(Colors.BLUE)
                useDisplay()
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_chip)
                setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
                text = "Lock reset · " + fmtClock(LockReset.remaining().toInt())
            }
            wrap.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (20 * dp).toInt()
            })
            val tick = object : Runnable {
                override fun run() {
                    if (!d.isShowing) return
                    if (LockReset.tick()) { tv.visibility = View.GONE; Changes.dirty = false; vm.publish(); return }
                    tv.text = "Lock reset · " + fmtClock(LockReset.remaining().toInt())
                    tv.postDelayed(this, 1000)
                }
            }
            tv.postDelayed(tick, 1000)
        }
        wrap.addView(grid)
        d.setContentView(wrap)
        (wrap.parent as View).apply {
            setBackgroundResource(R.drawable.bg_menu_sheet)
            backgroundTintList = null
        }
        movePill(3, true)
        d.setOnDismissListener { movePill(current.coerceIn(0, 2), true) }
        d.show()
    }
}