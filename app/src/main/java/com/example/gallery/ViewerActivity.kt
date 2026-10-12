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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
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

    // video
    private lateinit var videoHost: View
    private lateinit var vplayer: VideoPlayerView
    private lateinit var videoControls: View
    private lateinit var btnToggle: ImageView
    private lateinit var btnMute: ImageView
    private lateinit var timeText: TextView
    private lateinit var timelinePanel: View
    private lateinit var expTimeline: TimelineView
    private var loadedId = -1L
    private var muted = false
    private var vDuration = 0
    private var scrubbing = false
    private var resumeAfterScrub = false
    private var expanded = false
    private var rangeStart = 0
    private var rangeEnd = 0
    private var pagerState = ViewPager2.SCROLL_STATE_IDLE

    private var barsVisible = true
    private var pending: List<Uri> = emptyList()
    private var userFilm = false
    private var trash = false
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
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        top = findViewById(R.id.topBar); bottom = findViewById(R.id.bottomBar)
        nameView = findViewById(R.id.name); nameView.useDisplay()
        heart = findViewById(R.id.btnFav)
        btnPlay = findViewById(R.id.btnPlay)
        btnEdit = findViewById(R.id.btnEdit)
        btnMore = findViewById(R.id.btnMore)
        trash = intent.getBooleanExtra("trash", false)
        if (trash) {
            findViewById<View>(R.id.actionRow).visibility = View.GONE
            findViewById<View>(R.id.trashBar).visibility = View.VISIBLE
            findViewById<TextView>(R.id.btnTrashRestore).useDisplay()
            findViewById<TextView>(R.id.btnTrashDelete).useDisplay()
            findViewById<View>(R.id.btnTrashRestore).setOnClickListener { trashRestore() }
            findViewById<View>(R.id.btnTrashDelete).setOnClickListener { trashDelete() }
        }
        pager = findViewById(R.id.pager)
        film = findViewById(R.id.filmstrip)

        videoHost = findViewById(R.id.videoHost)
        vplayer = findViewById(R.id.video)
        videoControls = findViewById(R.id.videoControls)
        btnToggle = findViewById(R.id.btnToggle)
        btnMute = findViewById(R.id.btnMute)
        timeText = findViewById(R.id.timeText); timeText.useDisplay()
        timelinePanel = findViewById(R.id.timelinePanel)
        expTimeline = findViewById(R.id.expTimeline)
        expTimeline.interactive = true

        val topPad = dp(8f)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, ins ->
            val s = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            top.setPadding(s.left, s.top, s.right, topPad)
            bottom.setPadding(s.left, 0, s.right, s.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val start = (b?.getInt("i") ?: intent.getIntExtra("index", 0)).coerceIn(0, items.size - 1)

        adapter = ViewerAdapter(items) { onStageTap() }
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        pager.setCurrentItem(start, false)

        filmAdapter = FilmAdapter(items) { p -> goTo(p) }
        filmLm = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        film.layoutManager = filmLm
        film.adapter = filmAdapter
        film.itemAnimator = null
        val side = (resources.displayMetrics.widthPixels - dp(30f)) / 2
        film.setPadding(side, 0, side, 0)
        LinearSnapHelper().attachToRecyclerView(film)
        film.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userFilm = true
                    filmAdapter.frozen = true
                } else if (state == RecyclerView.SCROLL_STATE_IDLE && userFilm) {
                    syncFromFilm()
                    userFilm = false
                    filmAdapter.unfreeze()
                    handler.postDelayed({ centerOn(pager.currentItem, true, 0) }, 60)
                    syncVideo()
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
                if (cur().id != loadedId) holdVideo()
                if (!userFilm) {
                    if (prev < 0 || abs(position - prev) > 8) filmLm.scrollToPositionWithOffset(position, 0)
                    handler.postDelayed({ centerOn(position, prev >= 0 && abs(position - prev) <= 8, 0) }, 60)
                }
                if (playing) { handler.removeCallbacks(tick); handler.postDelayed(tick, 3000) }
                handler.postDelayed({ syncVideo() }, 90)
            }

            override fun onPageScrollStateChanged(state: Int) {
                pagerState = state
                if (state != ViewPager2.SCROLL_STATE_IDLE) holdVideo() else syncVideo()
            }
        })

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnShare).setOnClickListener { Share.send(this, listOf(cur().uri)) }
        findViewById<View>(R.id.btnFav).setOnClickListener { toggleFav() }
        btnEdit.setOnClickListener {
            val m = cur()
            if (m.video) {
                vplayer.pause()
                editLauncher.launch(VideoEditorActivity.intent(this, m.uri, rangeStart, rangeEnd))
            } else editLauncher.launch(EditorActivity.intent(this, m.uri))
        }
        findViewById<View>(R.id.btnDelete).setOnClickListener { confirmDelete() }
        btnMore.setOnClickListener { showMore() }
        btnPlay.setOnClickListener { setPlaying(!playing) }

        // video controls
        findViewById<View>(R.id.btnPill).setOnClickListener { togglePlay() }
        btnMute.setOnClickListener {
            muted = !muted
            vplayer.muted = muted
            updateMuteUi()
        }
        findViewById<View>(R.id.btnFull).setOnClickListener { toggleBars() }
        findViewById<View>(R.id.btnCollapse).setOnClickListener { collapse(true) }
        expTimeline.onSeek = { ms, fin -> scrub(ms, fin) }
        expTimeline.onTrim = { s, e, which, fin ->
            rangeStart = s
            rangeEnd = e
            vplayer.setRange(s, e)
            scrub(if (which == 0) s else e, fin)
        }
        vplayer.onPrepared = { d ->
            if (cur().id == loadedId) {
                vDuration = d
                if (pagerState == ViewPager2.SCROLL_STATE_IDLE) videoHost.alpha = 1f

                updateTime(vplayer.position(), d)
                updatePlayUi()
            }
        }
        vplayer.onState = { updatePlayUi() }
        vplayer.onTick = { pos -> onVideoTick(pos) }
        vplayer.onError = { Toast.makeText(this, "Can't play this video", Toast.LENGTH_SHORT).show() }
        updateMuteUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (expanded) collapse(true)
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        updateUi()
        if (intent.getBooleanExtra("slideshow", false)) setPlaying(true)
        handler.postDelayed({ centerOn(start, false, 0); syncVideo() }, 120)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::pager.isInitialized) outState.putInt("i", pager.currentItem)
    }

    override fun onStop() {
        super.onStop()
        if (::btnPlay.isInitialized) setPlaying(false)
        if (::vplayer.isInitialized) { vplayer.pause(); updatePlayUi() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::vplayer.isInitialized) vplayer.release()
        super.onDestroy()
    }

    // ---------- Video ----------
    private fun durationNow(): Int = if (vDuration > 0) vDuration else cur().duration.toInt()

    private fun holdVideo() {
        if (loadedId != -1L) {
            vplayer.pause()
            videoHost.alpha = 0f
        }
    }

    private fun syncVideo() {
        if (userFilm || pagerState != ViewPager2.SCROLL_STATE_IDLE || items.isEmpty()) return
        val m = cur()
        if (!m.video) { stopVideo(); return }
        if (loadedId == m.id) {videoHost.alpha = 1f; return }
        loadVideo(m)
    }

    private fun loadVideo(m: Media) {
        loadedId = m.id
        collapse(false)
        rangeStart = 0
        rangeEnd = 0
        scrubbing = false
        vDuration = m.duration.toInt()
        videoHost.visibility = View.VISIBLE
        videoHost.alpha = 0f
        vplayer.muted = muted
        vplayer.load(m.uri, !playing)
        updateTime(0, vDuration)
        updatePlayUi()
    }

    private fun stopVideo() {
        loadedId = -1L
        vDuration = 0
        vplayer.release()
        videoHost.alpha = 0f
        updatePlayUi()
    }

    private fun togglePlay() {
        if (cur().id != loadedId) return
        if (playing) setPlaying(false)
        if (vplayer.isPlayingNow) vplayer.pause() else vplayer.play()
        updatePlayUi()
    }

    private fun updatePlayUi() {
        val p = vplayer.isPlayingNow
        btnToggle.setImageResource(if (p) R.drawable.ic_pause else R.drawable.ic_play)
        btnToggle.contentDescription = if (p) "Pause" else "Play"
    }

    private fun updateMuteUi() {
        btnMute.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
        btnMute.contentDescription = if (muted) "Unmute" else "Mute"
    }

    private fun updateTime(pos: Int, total: Int) {
        val s = "${fmtClock(pos)} / ${fmtClock(total)}"
        if (timeText.text.toString() != s) timeText.text = s
    }

    private fun onVideoTick(pos: Int) {
        if (cur().id != loadedId) return
        if (!scrubbing) updateTime(pos, durationNow())
        expTimeline.setPosition(pos)
        if (filmAdapter.wideIdx >= 0) {
            (film.findViewHolderForAdapterPosition(filmAdapter.wideIdx) as? FilmAdapter.VH)?.tl?.setPosition(pos)
        }
    }

    private fun scrub(ms: Int, fin: Boolean) {
        if (!scrubbing) {
            scrubbing = true
            resumeAfterScrub = vplayer.isPlayingNow
            vplayer.pause()
            updatePlayUi()
        }
        vplayer.seek(ms, fin)
        updateTime(ms, durationNow())
        if (fin) {
            scrubbing = false
            if (resumeAfterScrub) vplayer.play()
            updatePlayUi()
        }
    }

    private fun expand() {
        if (expanded || !cur().video || cur().id != loadedId) return
        expanded = true
        val d = durationNow()
        expTimeline.setSource(cur().uri, d)
        expTimeline.setRange(rangeStart, if (rangeEnd > 0) rangeEnd else d)
        expTimeline.setPosition(vplayer.position())
        film.animate().cancel()
        timelinePanel.animate().cancel()
        timelinePanel.alpha = 0f
        timelinePanel.visibility = View.VISIBLE
        timelinePanel.animate().alpha(1f).setDuration(180).start()
        film.visibility = View.GONE
    }

    private fun collapse(animate: Boolean) {
        if (!expanded) return
        expanded = false
        film.animate().cancel()
        timelinePanel.animate().cancel()
        timelinePanel.visibility = View.GONE
        film.visibility = View.VISIBLE
        if (animate) {
            film.alpha = 0f
            film.animate().alpha(1f).setDuration(180).start()
        } else film.alpha = 1f
        handler.postDelayed({ centerOn(pager.currentItem, false, 0) }, 60)
    }

    private fun onStageTap() { toggleBars() }

    // ---------- Filmstrip ----------
    private fun goTo(p: Int) {
        if (p == pager.currentItem) { if (cur().video) expand(); return }
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

    private fun centerOn(p: Int, smooth: Boolean, tries: Int) {
        if (userFilm || p !in 0 until items.size) return
        val c = filmLm.findViewByPosition(p)
        if (c == null) {
            if (tries > 3) return
            filmLm.scrollToPositionWithOffset(p, 0)
            film.post { centerOn(p, false, tries + 1) }
            return
        }
        val dx = (c.left + c.right) / 2 - film.width / 2
        if (dx == 0) return
        if (smooth) film.smoothScrollBy(dx, 0, DecelerateInterpolator()) else film.scrollBy(dx, 0)
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
        val m = cur()
        nameView.text = m.name
        setHeart(Prefs.has("fav", "p${m.id}"))
        filmAdapter.select(pager.currentItem)
        videoControls.visibility = if (m.video) (if (barsVisible) View.VISIBLE else View.INVISIBLE) else View.GONE
        videoControls.alpha = if (barsVisible) 1f else 0f
        if (!m.video) collapse(false)
        if (m.video && m.id != loadedId) updateTime(0, m.duration.toInt())
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
        for (v in listOf(top, bottom, videoControls)) {
            if (v === videoControls && !cur().video) continue
            v.animate().cancel()
            if (barsVisible) { v.visibility = View.VISIBLE; v.animate().alpha(1f).setDuration(150).start() }
            else v.animate().alpha(0f).setDuration(150).withEndAction { v.visibility = View.INVISIBLE }.start()
        }
    }

    // ---------- More menu ----------
    private fun showMore() {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        if (!cur().video) {
            acts.add("Rotate" to { rotateView() })
            acts.add("Set as wallpaper" to { setWallpaper() })
        }
        acts.add("Open with" to { openWith() })
        DetailsSheet.show(this, cur(), acts)
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

    // ---------- Recycle Bin actions ----------
    private fun trashRestore() {
        if (Build.VERSION.SDK_INT < 30) return
        val u = listOf(cur().uri.buildUpon().clearQuery().build())
        pending = u
        vplayer.pause()
        try {
            deleteLauncher.launch(IntentSenderRequest.Builder(MediaStore.createTrashRequest(contentResolver, u, false).intentSender).build())
        } catch (e: Exception) { Toast.makeText(this, "Couldn't complete the action", Toast.LENGTH_SHORT).show() }
    }

    private fun trashDelete() {
        if (Build.VERSION.SDK_INT < 30) return
        val u = listOf(cur().uri.buildUpon().clearQuery().build())
        pending = u
        showDeleteSheet(this, "Delete this item? This cannot be undone.") {
            vplayer.pause()
            try {
                deleteLauncher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(contentResolver, u).intentSender).build())
            } catch (e: Exception) { Toast.makeText(this, "Couldn't complete the action", Toast.LENGTH_SHORT).show() }
        }
    }

    // ---------- Delete ----------
    private fun confirmDelete() {
        val uris = listOf(cur().uri)
        pending = uris
        val go = {
            vplayer.pause()
            val sender = Deleter.delete(this, uris)
            if (sender != null) deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            else removeCurrent()
        }
        confirmTrash(this, 1) { go() }
    }

    private fun removeCurrent() {
        val p = pager.currentItem
        items.removeAt(p)
        filmAdapter.reset()
        loadedId = -1L
        vplayer.release()
        videoHost.visibility = View.GONE
        adapter.notifyItemRemoved(p)
        filmAdapter.notifyItemRemoved(p)
        if (items.isEmpty()) finish()
        else {
            updateUi()
            handler.postDelayed({ syncVideo() }, 90)
        }
    }

    // ---------- Edited item ----------
    private fun insertEdited(u: Uri) {
        val m = queryMedia(u) ?: return
        val ex = items.indexOfFirst { it.id == m.id }
        if (ex >= 0) { pager.setCurrentItem(ex, false); return }
        val at = (pager.currentItem + 1).coerceAtMost(items.size)
        items.add(at, m)
        adapter.notifyItemInserted(at)
        filmAdapter.notifyItemInserted(at)
        pager.setCurrentItem(at, false)
    }

    private fun queryMedia(u: Uri): Media? {
        val video = contentResolver.getType(u)?.startsWith("video") == true
        val cols = arrayListOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT
        )
        if (video) cols.add(MediaStore.Video.Media.DURATION)
        try {
            contentResolver.query(u, cols.toTypedArray(), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val taken = c.getLong(2)
                    return Media(
                        c.getLong(0), u, c.getString(1) ?: (if (video) "Video" else "Image"),
                        if (taken > 0) taken else c.getLong(3) * 1000,
                        c.getLong(4), c.getString(5) ?: "Unknown",
                        c.getLong(6), c.getInt(7), c.getInt(8),
                        video, if (video) c.getLong(9) else 0L
                    )
                }
            }
        } catch (e: Exception) { }
        return null
    }

    companion object {
        fun intent(c: Context, index: Int, slideshow: Boolean = false, trash: Boolean = false) =
            Intent(c, ViewerActivity::class.java).putExtra("index", index).putExtra("slideshow", slideshow).putExtra("trash", trash)
    }
}

class FilmAdapter(
    private val items: List<Media>,
    private val onPick: (Int) -> Unit
) : RecyclerView.Adapter<FilmAdapter.VH>() {

    var selected = -1
    var wideIdx = -1
        private set
    var frozen = false

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val ring: View = v.findViewById(R.id.ring)
        val tl: TimelineView = v.findViewById(R.id.tl)
    }

    fun reset() { selected = -1; wideIdx = -1 }

    private fun setWide(p: Int) {
        val m = items.getOrNull(p)
        val nw = if (m != null && m.video && m.duration > 0) p else -1
        if (nw == wideIdx) return
        val ow = wideIdx
        wideIdx = nw
        if (ow in 0 until itemCount) notifyItemChanged(ow, "sel")
        if (nw in 0 until itemCount) notifyItemChanged(nw, "sel")
    }

    fun unfreeze() {
        frozen = false
        setWide(selected)
        if (selected in 0 until itemCount) notifyItemChanged(selected, "sel")
    }

    fun select(p: Int) {
        val old = selected
        if (old == p) return
        selected = p
        if (!frozen) setWide(p)
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
        applySel(h, i, false)
    }

    override fun onBindViewHolder(h: VH, i: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) onBindViewHolder(h, i) else applySel(h, i, true)
    }

    private fun applySel(h: VH, i: Int, anim: Boolean) {
        val d = h.itemView.resources.displayMetrics.density
        val m = items[i]
        val wide = i == wideIdx
        val lp = h.itemView.layoutParams
        val w = ((if (wide) 120f else 30f) * d).toInt()
        if (lp.width != w) { lp.width = w; h.itemView.layoutParams = lp }
        h.thumb.visibility = if (wide) View.GONE else View.VISIBLE
        h.tl.visibility = if (wide) View.VISIBLE else View.GONE
        if (wide) h.tl.setSource(m.uri, m.duration.toInt())

        val on = i == selected && !wide
        val s = if (on) 1.15f else 1f
        val ty = if (on) -2f * d else 0f
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
    private val onTap: () -> Unit
) : RecyclerView.Adapter<ViewerAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val pv: PhotoView = v.findViewById(R.id.pv)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_page, p, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, i: Int) {
        val m = items[i]
        if (m.video) h.pv.setScaleLevels(1f, 1.001f, 1.002f) else h.pv.setScaleLevels(1f, 1.75f, 6f)
        Glide.with(h.pv).load(m.uri).override(2400, 2400).into(h.pv)
        h.pv.setOnPhotoTapListener { _, _, _ -> onTap() }
        h.pv.setOnOutsidePhotoTapListener { onTap() }
    }

    override fun onViewRecycled(h: VH) { Glide.with(h.pv).clear(h.pv) }
    override fun getItemCount() = items.size
}