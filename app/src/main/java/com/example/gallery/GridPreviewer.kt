package com.example.gallery

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView

class GridPreviewer(private val rv: RecyclerView, private val adapter: PhotoAdapter) :
    TextureView.SurfaceTextureListener {

    private class Cand(val pos: Int, val id: Long, val uri: Uri, val view: ViewGroup)

    private val ctx: Context = rv.context.applicationContext
    private val hd = Handler(Looper.getMainLooper())
    private val tv = TextureView(rv.context)
    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    private var curSt: SurfaceTexture? = null
    private var host: ViewGroup? = null
    private var curId = -1L
    private var lastPos = -1
    private var pendingUri: Uri? = null
    private var gen = 0
    private var started = false
    private var vw = 0
    private var vh = 0
    private var active = false
    private var blocked = false
    private val bad = HashSet<Long>()

    private val endRun = Runnable { next() }
    private val startRun = Runnable {
        if (canRun() && rv.isShown && rv.scrollState == RecyclerView.SCROLL_STATE_IDLE) next()
    }

    private val scrollL = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(r: RecyclerView, state: Int) {
            if (state == RecyclerView.SCROLL_STATE_IDLE) schedule(250) else stop()
        }
        override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
            if (dy != 0) { stop(); schedule(350) }
        }
    }

    private val childL = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {}
        override fun onChildViewDetachedFromWindow(view: View) {
            if (view === host) { stop(); schedule(250) }
        }
    }

    private val dataL = object : RecyclerView.AdapterDataObserver() {
        private fun changed() { lastPos = -1; stop(); schedule(400) }
        override fun onChanged() = changed()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = changed()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = changed()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = changed()
    }

    init {
        tv.surfaceTextureListener = this
        tv.alpha = 0.01f
        rv.addOnScrollListener(scrollL)
        rv.addOnChildAttachStateChangeListener(childL)
        adapter.registerAdapterDataObserver(dataL)
    }

    private fun canRun() = active && !blocked

    fun resume() { active = true; schedule(300) }
    fun pause() { active = false; stop() }
    fun setBlocked(b: Boolean) {
        if (blocked == b) return
        blocked = b
        if (b) stop() else schedule(300)
    }

    fun stop() {
        hd.removeCallbacks(startRun)
        stopPlayback()
    }

    fun release() {
        stop()
        try { rv.removeOnScrollListener(scrollL) } catch (e: Exception) { }
        try { rv.removeOnChildAttachStateChangeListener(childL) } catch (e: Exception) { }
        try { adapter.unregisterAdapterDataObserver(dataL) } catch (e: Exception) { }
        try { mp?.release() } catch (e: Exception) { }
        mp = null
    }

    private fun schedule(delay: Long) {
        hd.removeCallbacks(startRun)
        hd.postDelayed(startRun, delay)
    }

    private fun eligible(): List<Cand> {
        val out = ArrayList<Cand>()
        val list = adapter.currentList
        val h = rv.height
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i) as? ViewGroup ?: continue
            val pos = rv.getChildAdapterPosition(c)
            if (pos == RecyclerView.NO_POSITION) continue
            val m = (list.getOrNull(pos) as? Row.Photo)?.m ?: continue
            if (!m.video || m.id in bad) continue
            if (c.height <= 0) continue
            val vis = minOf(c.bottom, h) - maxOf(c.top, 0)
            if (vis < c.height * 0.6f) continue
            out.add(Cand(pos, m.id, m.uri, c))
        }
        out.sortBy { it.pos }
        return out
    }

    private fun next() {
        if (!canRun()) return
        val list = eligible()
        if (list.isEmpty()) {
            stopPlayback()
            if (adapter.currentList.any { (it as? Row.Photo)?.m?.video == true }) schedule(800)
            return
        }
        val cand = list.firstOrNull { it.pos > lastPos } ?: list.first()
        if (cand.id == curId && started && host === cand.view) replay() else play(cand)
    }

    private fun play(c: Cand) {
        stopPlayback()
        curId = c.id
        lastPos = c.pos
        pendingUri = c.uri
        host = c.view
        tv.alpha = 0.01f
        c.view.addView(tv, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun replay() {
        val p = mp ?: return
        try {
            p.seekTo(0L, MediaPlayer.SEEK_CLOSEST)
            if (!p.isPlaying) p.start()
            hd.removeCallbacks(endRun)
            hd.postDelayed(endRun, PREVIEW_MS.toLong())
        } catch (e: Exception) { fail(curId) }
    }

    private fun open(st: SurfaceTexture) {
        val u = pendingUri ?: return
        val id = curId
        val my = ++gen
        try {
            val p = mp ?: MediaPlayer().also { mp = it }
            p.reset()
            p.setVolume(0f, 0f)
            surface?.release()
            surface = Surface(st)
            curSt = st
            p.setSurface(surface)
            p.setDataSource(ctx, u)
            p.setOnVideoSizeChangedListener { _, w, h ->
                if (gen == my && w > 0 && h > 0) { vw = w; vh = h; applyTransform() }
            }
            p.setOnInfoListener { _, what, _ ->
                if (gen == my && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    tv.animate().alpha(1f).setDuration(120).start()
                }
                true
            }
            p.setOnPreparedListener { m ->
                if (gen == my) {
                    try {
                        m.seekTo(0L, MediaPlayer.SEEK_CLOSEST)
                        m.start()
                        started = true
                        val len = minOf(PREVIEW_MS, m.duration.coerceAtLeast(300))
                        hd.removeCallbacks(endRun)
                        hd.postDelayed(endRun, len.toLong())
                    } catch (e: Exception) { fail(id) }
                }
            }
            p.setOnCompletionListener { if (gen == my) { hd.removeCallbacks(endRun); next() } }
            p.setOnErrorListener { _, _, _ -> if (gen == my) fail(id); true }
            p.prepareAsync()
        } catch (e: Exception) {
            fail(id)
        }
    }

    private fun fail(id: Long) {
        if (id != -1L) bad.add(id)
        stopPlayback()
        schedule(100)
    }

    private fun stopPlayback() {
        hd.removeCallbacks(endRun)
        if (host == null && curId == -1L) return
        gen++
        try { mp?.reset() } catch (e: Exception) { }
        val h = host
        host = null
        started = false
        curId = -1L
        pendingUri = null
        vw = 0; vh = 0
        tv.animate().cancel()
        tv.alpha = 0f
        h?.removeView(tv)
    }

    private fun applyTransform() {
        val w = tv.width.toFloat()
        val h = tv.height.toFloat()
        if (w <= 0f || h <= 0f || vw <= 0 || vh <= 0) return
        val vr = vw.toFloat() / vh
        val tr = w / h
        val m = Matrix()
        if (vr > tr) m.setScale(vr / tr, 1f, w / 2f, h / 2f) else m.setScale(1f, tr / vr, w / 2f, h / 2f)
        tv.setTransform(m)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { open(st) }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { applyTransform() }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        if (st === curSt) {
            try { mp?.setSurface(null) } catch (e: Exception) { }
            surface?.release()
            surface = null
            curSt = null
        }
        return true
    }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    companion object { private const val PREVIEW_MS = 2000 }
}