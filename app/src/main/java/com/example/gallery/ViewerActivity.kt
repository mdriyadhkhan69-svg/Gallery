package com.example.gallery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class ViewerActivity : AppCompatActivity() {

    private lateinit var items: MutableList<Media>
    private lateinit var pager: ViewPager2
    private lateinit var adapter: ViewerAdapter
    private lateinit var film: RecyclerView
    private lateinit var filmAdapter: FilmAdapter
    private lateinit var filmLm: LinearLayoutManager
    private lateinit var top: View
    private lateinit var bottom: View
    private lateinit var nameView: TextView
    private lateinit var heart: ImageView
    private lateinit var btnPlay: ImageView
    private lateinit var btnEdit: ImageView
    private lateinit var btnMore: ImageView
    private var barsVisible = true
    private var pending: List<Uri> = emptyList()
    private var userFilm = false
    private var shownPos = -1

    private val handler = Handler(Looper.getMainLooper())
    private var playing = false
    private val tick = object : Runnable {
        override fun run() {
            if (!playing || items.isEmpty()) return
            pager.setCurrentItem((pager.currentItem + 1) % items.size, true)
            handler.removeCallbacks(this)
            handler.postDelayed(this, 3000)
        }
    }

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT < 30) Deleter.delete(this, pending)
            removeCurrent()
        }
    }

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val u = r.data?.data
        if (r.resultCode == Activity.RESULT_OK && u != null) insertEdited(u)
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        Prefs.init(this)
        super.onCreate(b)
        items = Session.items.toMutableList()
        if (items.isEmpty()) { finish(); return }
        setContentView(R.layout.activity_viewer)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        top = findViewById(R.id.topBar); bottom = findViewById(R.id.bottomBar)
        nameView = findViewById(R.id.name); nameView.useDisplay()
        heart = findViewById(R.id.btnFav)
        btnPlay = findViewById(R.id.btnPlay)
        btnEdit = findViewById(R.id.btnEdit)
        btnMore = findViewById(R.id.btnMore)
        pager = findViewById(R.id.pager)
        film = findViewById(R.id.filmstrip)

        val topPad = dp(8f)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, ins ->
            val s = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(s.left, 0, s.right, 0)
            top.setPadding(0, s.top, 0, topPad)
            bottom.setPadding(0, 0, 0, s.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val start = (b?.getInt("i") ?: intent.getIntExtra("index", 0)).coerceIn(0, items.size - 1)

        adapter = ViewerAdapter(items, { toggleBars() }, { m -> startActivity(VideoActivity.intent(this, m.uri)) })
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        pager.setCurrentItem(start, false)

        filmAdapter = FilmAdapter(items) { p -> goTo(p) }
        filmLm = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        film.layoutManager = filmLm
        film.adapter = filmAdapter
        film.itemAnimator = null
        val side = (resources.displayMetrics.widthPixels - dp(52f)) / 2
        film.setPadding(side, 0, side, 0)
        LinearSnapHelper().attachToRecyclerView(film)
        film.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) userFilm = true
                else if (state == RecyclerView.SCROLL_STATE_IDLE && userFilm) {
                    syncFromFilm()
                    userFilm = false
                }
            }
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { if (userFilm) syncFromFilm() }
        })
        shownPos = start
        filmLm.scrollToPositionWithOffset(start, 0)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val prev = shownPos
                shownPos = position
                updateUi()
                if (!userFilm) {
                    if (prev < 0 || abs(position - prev) > 8) filmLm.scrollToPositionWithOffset(position, 0)
                    else smoothCenter(position)
                }
                if (playing) { handler.removeCallbacks(tick); handler.postDelayed(tick, 3000) }
            }
        })

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnShare).setOnClickListener { Share.send(this, listOf(cur().uri)) }
        findViewById<View>(R.id.btnFav).setOnClickListener { toggleFav() }
        findViewById<View>(R.id.btnEdit).setOnClickListener {
            if (cur().video) Toast.makeText(this, "Videos can't be edited", Toast.LENGTH_SHORT).show()
            else editLauncher.launch(EditorActivity.intent(this, cur().uri))
        }
        findViewById<View>(R.id.btnDelete).setOnClickListener { confirmDelete() }
        btnMore.setOnClickListener { showMore() }
        btnPlay.setOnClickListener { setPlaying(!playing) }
        updateUi()
        if (intent.getBooleanExtra("slideshow", false)) setPlaying(true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::pager.isInitialized) outState.putInt("i", pager.currentItem)
    }

    override fun onStop() {
        super.onStop()
        if (::btnPlay.isInitialized) setPlaying(false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------- Filmstrip ----------
    private fun goTo(p: Int) {
        pager.setCurrentItem(p, abs(p - pager.currentItem) <= 3)
    }

    private fun centerPos(): Int {
        val cx = film.width / 2
        var best = -1
        var bd = Int.MAX_VALUE
        for (i in 0 until film.childCount) {
            val c = film.getChildAt(i)
            val d = abs((c.left + c.right) / 2 - cx)
            val p = film.getChildAdapterPosition(c)
            if (p != RecyclerView.NO_POSITION && d < bd) { bd = d; best = p }
        }
        return best
    }

    private fun syncFromFilm() {
        val c = centerPos()
        if (c >= 0 && c != pager.currentItem) pager.setCurrentItem(c, false)
    }

    private fun smoothCenter(p: Int) {
        val s = object : LinearSmoothScroller(this) {
            override fun calculateDtToFit(vs: Int, ve: Int, bs: Int, be: Int, snap: Int) = (bs + be) / 2 - (vs + ve) / 2
            override fun calculateSpeedPerPixel(m: DisplayMetrics) = 120f / m.densityDpi
        }
        s.targetPosition = p
        filmLm.startSmoothScroll(s)
    }

    // ---------- Slideshow ----------
    private fun setPlaying(on: Boolean) {
        playing = on
        handler.removeCallbacks(tick)
        btnPlay.setImageResource(if (on) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = if (on) "Pause slideshow" else "Play slideshow"
        if (on) {
            handler.postDelayed(tick, 3000)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (barsVisible) toggleBars()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun cur() = items[pager.currentItem]

    private fun updateUi() {
        nameView.text = cur().name
        setHeart(Prefs.has("fav", "p${cur().id}"))
        filmAdapter.select(pager.currentItem)
        btnEdit.alpha = if (cur().video) 0.35f else 1f
    }

    private fun setHeart(on: Boolean) {
        heart.setImageResource(if (on) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        heart.setColorFilter(if (on) Colors.HEART else Color.WHITE)
    }

    private fun toggleFav() {
        val on = Prefs.toggle("fav", "p${cur().id}")
        setHeart(on)
        heart.animate().scaleX(1.3f).scaleY(1.3f).setDuration(110).withEndAction {
            heart.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
        }.start()
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        for (v in listOf(top, bottom)) {
            if (barsVisible) { v.visibility = View.VISIBLE; v.animate().alpha(1f).setDuration(150).start() }
            else v.animate().alpha(0f).setDuration(150).withEndAction { v.visibility = View.INVISIBLE }.start()
        }
    }

    // ---------- More menu ----------
    private fun showMore() {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        labels.add("Details"); acts.add { showDetails() }
        if (!cur().video) {
            labels.add("Rotate"); acts.add { rotateView() }
            labels.add("Set as wallpaper"); acts.add { setWallpaper() }
        }
        labels.add("Open with"); acts.add { openWith() }
        showDarkMenu(btnMore, labels) { i -> acts[i]() }
    }

    private fun rotateView() {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        val h = rv.findViewHolderForAdapterPosition(pager.currentItem) as? ViewerAdapter.VH ?: return
        h.pv.setRotationBy(90f)
    }

    private fun setWallpaper() {
        val i = Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(cur().uri, "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, "Set as")) }
        catch (e: Exception) { Toast.makeText(this, "No app found", Toast.LENGTH_SHORT).show() }
    }

    private fun openWith() {
        val m = cur()
        val type = contentResolver.getType(m.uri) ?: if (m.video) "video/*" else "image/*"
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, "Open with")) }
        catch (e: Exception) { Toast.makeText(this, "No app found", Toast.LENGTH_SHORT).show() }
    }

    private fun showDetails() {
        val m = cur()
        val date = SimpleDateFormat("EEE, d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(m.date))
        MaterialAlertDialogBuilder(this).setTitle("Details")
            .setMessage("Name: ${m.name}\nDate: $date\nResolution: ${m.w} × ${m.h}\nSize: ${Formatter.formatShortFileSize(this, m.size)}\nAlbum: ${m.bucket}")
            .setPositiveButton("OK", null).show()
    }

    // ---------- Delete ----------
    private fun confirmDelete() {
        val uris = listOf(cur().uri)
        pending = uris
        val go = {
            val sender = Deleter.delete(this, uris)
            if (sender != null) deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            else removeCurrent()
        }
        if (Build.VERSION.SDK_INT >= 30) go()
        else MaterialAlertDialogBuilder(this).setMessage("Delete this photo?")
            .setPositiveButton("Delete") { _, _ -> go() }.setNegativeButton("Cancel", null).show()
    }

    private fun removeCurrent() {
        val p = pager.currentItem
        items.removeAt(p)
        filmAdapter.selected = -1
        adapter.notifyItemRemoved(p)
        filmAdapter.notifyItemRemoved(p)
        if (items.isEmpty()) finish() else updateUi()
    }

    // ---------- Edited photo ----------
    private fun insertEdited(u: Uri) {
        val m = queryMedia(u) ?: return
        val at = (pager.currentItem + 1).coerceAtMost(items.size)
        items.add(at, m)
        adapter.notifyItemInserted(at)
        filmAdapter.notifyItemInserted(at)
        pager.setCurrentItem(at, false)
    }

    private fun queryMedia(u: Uri): Media? {
        val p = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT
        )
        try {
            contentResolver.query(u, p, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val taken = c.getLong(2)
                    return Media(
                        c.getLong(0), u, c.getString(1) ?: "Image",
                        if (taken > 0) taken else c.getLong(3) * 1000,
                        c.getLong(4), c.getString(5) ?: "Unknown",
                        c.getLong(6), c.getInt(7), c.getInt(8)
                    )
                }
            }
        } catch (e: Exception) { }
        return null
    }

    companion object {
        fun intent(c: Context, index: Int, slideshow: Boolean = false) =
            Intent(c, ViewerActivity::class.java).putExtra("index", index).putExtra("slideshow", slideshow)
    }
}

class FilmAdapter(
    private val items: List<Media>,
    private val onPick: (Int) -> Unit
) : RecyclerView.Adapter<FilmAdapter.VH>() {

    var selected = -1

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val ring: View = v.findViewById(R.id.ring)
    }

    fun select(p: Int) {
        val old = selected
        if (old == p) return
        selected = p
        if (old in 0 until itemCount) notifyItemChanged(old, "sel")
        if (p in 0 until itemCount) notifyItemChanged(p, "sel")
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val h = VH(LayoutInflater.from(p.context).inflate(R.layout.item_film, p, false))
        h.thumb.roundCorners(6f)
        h.itemView.setOnClickListener {
            val i = h.bindingAdapterPosition
            if (i != RecyclerView.NO_POSITION) onPick(i)
        }
        return h
    }

    override fun onBindViewHolder(h: VH, i: Int) {
        Glide.with(h.thumb).load(items[i].uri).override(160).centerCrop()
            .placeholder(ColorDrawable(0xFF161616.toInt())).into(h.thumb)
        applySel(h, i == selected, false)
    }

    override fun onBindViewHolder(h: VH, i: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) onBindViewHolder(h, i) else applySel(h, i == selected, true)
    }

    private fun applySel(h: VH, on: Boolean, anim: Boolean) {
        val d = h.itemView.resources.displayMetrics.density
        val s = if (on) 1.15f else 1f
        val ty = if (on) -4f * d else 0f
        val a = if (on) 1f else 0f
        val v = h.itemView
        v.animate().cancel(); h.ring.animate().cancel()
        if (anim) {
            v.animate().scaleX(s).scaleY(s).translationY(ty).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            h.ring.animate().alpha(a).setDuration(180).start()
        } else {
            v.scaleX = s; v.scaleY = s; v.translationY = ty; h.ring.alpha = a
        }
    }

    override fun onViewRecycled(h: VH) {
        h.itemView.animate().cancel()
        Glide.with(h.thumb).clear(h.thumb)
    }

    override fun getItemCount() = items.size
}

class ViewerAdapter(
    private val items: List<Media>,
    private val onTap: () -> Unit,
    private val onPlay: (Media) -> Unit
) : RecyclerView.Adapter<ViewerAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val pv: PhotoView = v.findViewById(R.id.pv)
        val play: ImageView = v.findViewById(R.id.play)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_page, p, false)
        val h = VH(v)
        h.pv.maximumScale = 6f
        return h
    }

    override fun onBindViewHolder(h: VH, i: Int) {
        val m = items[i]
        Glide.with(h.pv).load(m.uri).override(2400, 2400).into(h.pv)
        h.play.visibility = if (m.video) View.VISIBLE else View.GONE
        h.play.setOnClickListener { onPlay(m) }
        h.pv.setOnPhotoTapListener { _, _, _ -> onTap() }
        h.pv.setOnOutsidePhotoTapListener { onTap() }
    }

    override fun onViewRecycled(h: VH) { Glide.with(h.pv).clear(h.pv) }
    override fun getItemCount() = items.size
}