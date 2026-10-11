package com.example.gallery

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import java.util.Locale

fun fmtClock(ms: Int): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}

class VideoPlayerView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) :
    TextureView(c, a), TextureView.SurfaceTextureListener {

    var onPrepared: ((Int) -> Unit)? = null
    var onState: (() -> Unit)? = null
    var onTick: ((Int) -> Unit)? = null
    var onError: (() -> Unit)? = null

    var muted = false
        set(v) { field = v; applyVolume() }

    /** extra 90 degree turns (preview only) */
    var quarter = 0
        set(v) { field = v and 3; rotation = field * 90f; requestLayout() }

    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    private var uri: Uri? = null
    private var prepared = false
    private var wantPlay = true
    private var startAt = 0
    private var vw = 0
    private var vh = 0
    private var rangeStart = 0
    private var rangeEnd = 0
    private val hd = Handler(Looper.getMainLooper())

    init { surfaceTextureListener = this }

    val isPlayingNow: Boolean
        get() {
            val p = mp ?: return false
            return try { if (prepared) p.isPlaying else wantPlay } catch (e: IllegalStateException) { false }
        }

    fun position(): Int = try { if (prepared) mp?.currentPosition ?: 0 else 0 } catch (e: IllegalStateException) { 0 }

    fun setRange(s: Int, e: Int) { rangeStart = s; rangeEnd = e }

    fun load(u: Uri, autoPlay: Boolean, startMs: Int = 0) {
        release()
        uri = u
        wantPlay = autoPlay
        startAt = startMs
        rangeStart = 0
        rangeEnd = 0
        if (surface != null) open()
    }

    private fun open() {
        val u = uri ?: return
        val p = MediaPlayer()
        mp = p
        prepared = false
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
            )
            p.setSurface(surface)
            p.setDataSource(context, u)
            p.setOnVideoSizeChangedListener { _, w, h ->
                if (mp === p && w > 0 && h > 0) { vw = w; vh = h; requestLayout() }
            }
            p.setOnPreparedListener {
                if (mp !== p) return@setOnPreparedListener
                prepared = true
                applyVolume()
                try {
                    if (wantPlay) {
                        if (startAt > 0) p.seekTo(startAt.toLong(), MediaPlayer.SEEK_CLOSEST)
                        p.start()
                    } else {
                        p.seekTo(startAt.toLong(), MediaPlayer.SEEK_CLOSEST)
                    }
                    onPrepared?.invoke(p.duration)
                } catch (e: IllegalStateException) { }
                onState?.invoke()
            }
            p.setOnCompletionListener { if (mp === p) onState?.invoke() }
            p.setOnErrorListener { _, _, _ ->
                if (mp === p) { prepared = false; onError?.invoke() }
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            onError?.invoke()
        }
    }

    private fun applyVolume() {
        try {
            val v = if (muted) 0f else 1f
            mp?.setVolume(v, v)
        } catch (e: IllegalStateException) { }
    }

    fun play() {
        val p = mp ?: return
        wantPlay = true
        if (!prepared) return
        try {
            val end = if (rangeEnd > 0) rangeEnd else p.duration
            val pos = p.currentPosition
            if (pos >= end - 50 || pos < rangeStart) p.seekTo(rangeStart.toLong(), MediaPlayer.SEEK_CLOSEST)
            p.start()
        } catch (e: IllegalStateException) { }
        onState?.invoke()
    }

    fun pause() {
        wantPlay = false
        val p = mp
        if (p != null && prepared) {
            try { if (p.isPlaying) p.pause() } catch (e: IllegalStateException) { }
        }
        onState?.invoke()
    }

    fun seek(ms: Int, precise: Boolean) {
        val p = mp ?: return
        if (!prepared) return
        try {
            p.seekTo(ms.toLong(), if (precise) MediaPlayer.SEEK_CLOSEST else MediaPlayer.SEEK_CLOSEST_SYNC)
        } catch (e: IllegalStateException) { }
    }

    fun release() {
        val p = mp
        mp = null
        prepared = false
        uri = null
        vw = 0
        vh = 0
        if (p != null) {
            try { p.setOnPreparedListener(null); p.setOnCompletionListener(null); p.setOnErrorListener(null) } catch (e: Exception) { }
            try { p.reset() } catch (e: Exception) { }
            try { p.release() } catch (e: Exception) { }
        }
        requestLayout()
    }

    override fun onMeasure(w: Int, h: Int) {
        val mw = MeasureSpec.getSize(w)
        val mh = MeasureSpec.getSize(h)
        if (vw <= 0 || vh <= 0 || mw == 0 || mh == 0) { setMeasuredDimension(mw, mh); return }
        val odd = quarter % 2 == 1
        val s = if (odd) minOf(mw.toFloat() / vh, mh.toFloat() / vw) else minOf(mw.toFloat() / vw, mh.toFloat() / vh)
        setMeasuredDimension((vw * s).toInt().coerceAtLeast(1), (vh * s).toInt().coerceAtLeast(1))
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = mp
            if (p != null && prepared) {
                try {
                    val pos = p.currentPosition
                    if (p.isPlaying && rangeEnd > 0 && pos >= rangeEnd && rangeEnd < p.duration - 30) {
                        p.pause()
                        p.seekTo(rangeStart.toLong(), MediaPlayer.SEEK_CLOSEST)
                        onState?.invoke()
                        onTick?.invoke(rangeStart)
                    } else onTick?.invoke(pos)
                } catch (e: IllegalStateException) { }
            }
            hd.postDelayed(this, 33)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hd.removeCallbacks(ticker)
        hd.post(ticker)
    }

    override fun onDetachedFromWindow() {
        hd.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(st)
        val p = mp
        if (p != null) { try { p.setSurface(surface) } catch (e: Exception) { } }
        else if (uri != null) open()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        try { mp?.setSurface(null) } catch (e: Exception) { }
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}