package com.example.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil

class TimelineView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {

    companion object {
        private const val NONE = 0
        private const val LEFT = 1
        private const val RIGHT = 2
        private const val HEAD = 3
    }

    /** (ms, finished) */
    var onSeek: ((Int, Boolean) -> Unit)? = null
    /** (startMs, endMs, which 0=start 1=end, finished) */
    var onTrim: ((Int, Int, Int, Boolean) -> Unit)? = null
    var interactive = false

    var trimEnabled = false
        set(v) {
            if (field == v) return
            field = v
            buildClip()
            reload()
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val handleW: Float get() = if (trimEnabled) 12f * d else 0f
    private val padV: Float get() = if (trimEnabled) 8f * d else 5f * d
    private val radius: Float get() = if (trimEnabled) 0f else 6f * d
    private val minLen: Int get() = minOf(duration, maxOf(100, minOf(1000, duration / 2)))

    private var uri: Uri? = null
    private var duration = 0
    private var startMs = 0
    private var endMs = 0
    private var posMs = 0
    private var frames: Array<Bitmap?> = emptyArray()
    @Volatile private var generation = 0

    private var drag = NONE
    private var grabOff = 0f
    private val slop = 30f * d

    private val clip = Path()
    private val rr = RectF()
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint().apply { color = 0xFF161616.toInt() }
    private val dimPaint = Paint().apply { color = 0xB3000000.toInt() }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A3A.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }

    fun setSource(u: Uri, durMs: Int) {
        if (uri == u && duration == durMs && frames.isNotEmpty()) return
        uri = u
        duration = durMs
        startMs = 0
        endMs = durMs
        posMs = 0
        frames = emptyArray()
        generation++
        reload()
        invalidate()
    }

    fun setRange(s: Int, e: Int) {
        startMs = s
        endMs = e
        invalidate()
    }

    fun setPosition(ms: Int) {
        if (drag != NONE || ms == posMs) return
        posMs = ms
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        buildClip()
        reload()
    }

    private fun buildClip() {
        clip.reset()
        if (width > 0 && height > 0) {
            clip.addRoundRect(RectF(handleW, padV, width - handleW, height - padV), radius, radius, Path.Direction.CW)
        }
    }

    private fun reload() {
        val u = uri ?: return
        if (width <= 0 || height <= 0 || duration <= 0) return
        val gen = ++generation
        val trackW = width - 2 * handleW
        val th = (height - 2 * padV).toInt().coerceAtLeast(1)
        val n = ceil(trackW / (th * 0.62f)).toInt().coerceIn(1, 40)
        val tw = (trackW / n).toInt().coerceAtLeast(1)
        val arr = arrayOfNulls<Bitmap>(n)
        frames = arr
        val ctx = context.applicationContext
        val dur = duration
        Thread {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, u)
                for (i in 0 until n) {
                    if (gen != generation) break
                    val us = ((i + 0.5) / n * dur * 1000).toLong()
                    val raw = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                    val bm = fit(raw, tw, th)
                    post {
                        if (gen == generation) { arr[i] = bm; invalidate() }
                    }
                }
            } catch (e: Exception) {
            } finally {
                try { r.release() } catch (e: Exception) { }
            }
        }.start()
    }

    private fun fit(src: Bitmap, w: Int, h: Int): Bitmap {
        val sr = src.width.toFloat() / src.height
        val tr = w.toFloat() / h
        var cw = src.width
        var ch = src.height
        var cx = 0
        var cy = 0
        if (sr > tr) { cw = (src.height * tr).toInt().coerceAtLeast(1); cx = (src.width - cw) / 2 }
        else { ch = (src.width / tr).toInt().coerceAtLeast(1); cy = (src.height - ch) / 2 }
        val c = Bitmap.createBitmap(src, cx, cy, cw, ch)
        val s = Bitmap.createScaledBitmap(c, w, h, true)
        if (c !== src && c !== s) c.recycle()
        if (src !== s) src.recycle()
        return s
    }

    private fun xOf(ms: Int): Float {
        val l = handleW
        val r = width - handleW
        return l + (r - l) * ms / duration.coerceAtLeast(1)
    }

    private fun msOf(x: Float): Int {
        val l = handleW
        val r = width - handleW
        return (((x - l) / (r - l).coerceAtLeast(1f)) * duration).toInt().coerceIn(0, duration)
    }

    override fun onDraw(c: Canvas) {
        if (duration <= 0 || width == 0) return
        val hw = handleW
        val l = hw
        val r = width - hw
        val t = padV
        val b = height - padV

        c.save()
        c.clipPath(clip)
        c.drawRect(l, t, r, b, bgPaint)
        val n = frames.size
        if (n > 0) {
            val tw = (r - l) / n
            for (i in 0 until n) {
                val bm = frames[i] ?: continue
                rr.set(l + i * tw, t, l + (i + 1) * tw + 1f, b)
                c.drawBitmap(bm, null, rr, tilePaint)
            }
        }
        c.restore()

        if (trimEnabled) {
            val lx = xOf(startMs)
            val rx = xOf(endMs)
            c.drawRect(l, t, lx, b, dimPaint)
            c.drawRect(rx, t, r, b, dimPaint)
            c.drawRect(lx, t, rx, t + 2f * d, white)
            c.drawRect(lx, b - 2f * d, rx, b, white)
            rr.set(lx - hw, t, lx, b)
            c.drawRoundRect(rr, 4f * d, 4f * d, white)
            rr.set(lx - hw / 2 - 1f * d, (t + b) / 2 - 6f * d, lx - hw / 2 + 1f * d, (t + b) / 2 + 6f * d)
            c.drawRoundRect(rr, 1f * d, 1f * d, grip)
            rr.set(rx, t, rx + hw, b)
            c.drawRoundRect(rr, 4f * d, 4f * d, white)
            rr.set(rx + hw / 2 - 1f * d, (t + b) / 2 - 6f * d, rx + hw / 2 + 1f * d, (t + b) / 2 + 6f * d)
            c.drawRoundRect(rr, 1f * d, 1f * d, grip)

            val px = xOf(posMs.coerceIn(0, duration))
            val py = padV - 3f * d
            rr.set(px - 2.25f * d, py, px + 2.25f * d, height - py)
            c.drawRoundRect(rr, 3f * d, 3f * d, edge)
            rr.set(px - 1f * d, py + 1f * d, px + 1f * d, height - py - 1f * d)
            c.drawRoundRect(rr, 2f * d, 2f * d, white)
        } else {
            val inset = 0f
            val half = 1f * d
            val px = xOf(posMs.coerceIn(0, duration)).coerceIn(l + half + inset, r - half - inset)
            c.save()
            c.clipPath(clip)
            rr.set(px - half, t + inset, px + half, b - inset)
            c.drawRoundRect(rr, half, half, white)
            c.restore()
        }
    }

    private fun pick(x: Float): Int {
        if (trimEnabled) {
            val lx = xOf(startMs)
            val rx = xOf(endMs)
            if (x <= lx + 3f * d && x >= lx - handleW - slop) { grabOff = x - lx; return LEFT }
            if (x >= rx - 3f * d && x <= rx + handleW + slop) { grabOff = x - rx; return RIGHT }
        }
        grabOff = 0f
        return HEAD
    }

    private fun apply(x: Float, fin: Boolean) {
        val ms = msOf(x - grabOff)
        when (drag) {
            LEFT -> {
                startMs = ms.coerceIn(0, endMs - minLen)
                posMs = startMs
                onTrim?.invoke(startMs, endMs, 0, fin)
            }
            RIGHT -> {
                endMs = ms.coerceIn(startMs + minLen, duration)
                posMs = endMs
                onTrim?.invoke(startMs, endMs, 1, fin)
            }
            HEAD -> {
                posMs = ms.coerceIn(startMs, endMs)
                onSeek?.invoke(posMs, fin)
            }
        }
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive || duration <= 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                drag = pick(e.x)
                apply(e.x, false)
            }
            MotionEvent.ACTION_MOVE -> if (drag != NONE) apply(e.x, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag != NONE) apply(e.x, true)
                drag = NONE
            }
        }
        return true
    }
}