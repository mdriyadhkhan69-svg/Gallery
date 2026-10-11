package com.example.gallery

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoExporter {

    class Cancelled : Exception()

    /** Source ke kakhono change kore na. Notun file Movies/Gallery Edited e save hoy. */
    fun export(
        ctx: Context, src: Uri, startMs: Long, endMs: Long, rotation: Int, keepAudio: Boolean,
        onProgress: (Int) -> Unit, cancelled: () -> Boolean
    ): Uri {
        val cr = ctx.contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "Edited_$stamp.mp4"
        val now = System.currentTimeMillis()
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, now)
            put(MediaStore.Video.Media.DATE_ADDED, now / 1000)
        }
        var file: File? = null
        if (Build.VERSION.SDK_INT >= 29) {
            v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Gallery Edited")
            v.put(MediaStore.Video.Media.IS_PENDING, 1)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Gallery Edited")
            dir.mkdirs()
            file = File(dir, name)
            v.put(MediaStore.Video.Media.DATA, file.absolutePath)
        }
        val out = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: throw IOException("Can't create output file")
        var ok = false
        try {
            val pfd = cr.openFileDescriptor(out, "rw") ?: throw IOException("Can't open output file")
            pfd.use {
                copy(ctx, src, it.fileDescriptor, startMs * 1000, endMs * 1000, rotation, keepAudio, onProgress, cancelled)
            }
            if (cancelled()) throw Cancelled()
            if (Build.VERSION.SDK_INT >= 29) {
                val u = ContentValues()
                u.put(MediaStore.Video.Media.IS_PENDING, 0)
                cr.update(out, u, null, null)
            } else if (file != null) {
                MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
            }
            ok = true
            onProgress(100)
            return out
        } finally {
            if (!ok) {
                try { cr.delete(out, null, null) } catch (e: Exception) { }
            }
        }
    }

    private fun copy(
        ctx: Context, src: Uri, fd: FileDescriptor, startUs: Long, endUs: Long, rotation: Int,
        keepAudio: Boolean, onProgress: (Int) -> Unit, cancelled: () -> Boolean
    ) {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            ex.setDataSource(ctx, src, null)
            var vIn = -1
            var aIn = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (vIn < 0 && mime.startsWith("video/")) vIn = i
                else if (aIn < 0 && keepAudio && mime.startsWith("audio/")) aIn = i
            }
            if (vIn < 0) throw IOException("No video track")

            muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(((rotation % 360) + 360) % 360)

            val map = HashMap<Int, Int>()
            var bufSize = 1 shl 20
            for (i in intArrayOf(vIn, aIn)) {
                if (i < 0) continue
                val f = ex.getTrackFormat(i)
                val idx = try { muxer.addTrack(f) } catch (e: Exception) { if (i == vIn) throw e else continue }
                map[i] = idx
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) bufSize = maxOf(bufSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }

            // video keyframe ke base dhore start time ber kori
            ex.selectTrack(vIn)
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val base = ex.sampleTime
            if (base < 0) throw IOException("Can't seek")
            if (aIn >= 0 && map.containsKey(aIn)) {
                ex.selectTrack(aIn)
                ex.seekTo(base, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            muxer.start()
            started = true

            val buf = ByteBuffer.allocate(bufSize)
            val info = MediaCodec.BufferInfo()
            val active = HashSet(map.keys)
            val span = (endUs - base).coerceAtLeast(1L)
            var wrote = 0
            var lastPct = -1

            while (active.isNotEmpty()) {
                if (cancelled()) throw Cancelled()
                val ti = ex.sampleTrackIndex
                if (ti < 0) break
                val t = ex.sampleTime
                if (t < 0) break
                if (t > endUs) {
                    active.remove(ti)
                    ex.unselectTrack(ti)
                    continue
                }
                if (t < base) { ex.advance(); continue }
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, t - base, flags)
                muxer.writeSampleData(map.getValue(ti), buf, info)
                wrote++
                ex.advance()
                val pct = (((t - base).toDouble() / span) * 100).toInt().coerceIn(0, 99)
                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
            }
            if (wrote == 0) throw IOException("Nothing to write")
            muxer.stop()
            started = false
        } finally {
            if (started) { try { muxer?.stop() } catch (e: Exception) { } }
            try { muxer?.release() } catch (e: Exception) { }
            try { ex.release() } catch (e: Exception) { }
        }
    }
}