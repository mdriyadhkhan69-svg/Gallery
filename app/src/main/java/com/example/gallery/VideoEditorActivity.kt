package com.example.gallery

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException

class VideoEditorActivity : AppCompatActivity() {

    private class St(val s: Int, val e: Int, val q: Int, val na: Boolean)

    private lateinit var video: VideoPlayerView
    private lateinit var timeline: TimelineView
    private lateinit var btnRevert: TextView
    private lateinit var btnSave: TextView
    private lateinit var playIcon: ImageView
    private lateinit var timeText: TextView
    private lateinit var btnUndo: ImageView
    private lateinit var btnMore: ImageView
    private lateinit var savingView: View
    private lateinit var savingBar: ProgressBar
    private lateinit var savingText: TextView
    private lateinit var src: Uri
    private lateinit var committed: St

    private var duration = 0
    private var origRot = 0
    private var vw = 0
    private var vh = 0
    private var ts = 0
    private var te = 0
    private var rot = 0
    private var noAudio = false
    private var scrubbing = false
    private var resumeAfter = false
    private var saving = false
    @Volatile private var cancel = false
    private val undo = ArrayList<St>()
    private val accent = 0xFFFFD60A.toInt()

    private fun minLen() = minOf(duration, maxOf(100, minOf(1000, duration / 2)))
    private fun cur() = St(ts, te, rot, noAudio)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(b: Bundle?) {
        Prefs.init(this)
        super.onCreate(b)
        val u = intent.data
        if (u == null) { finish(); return }
        src = u
        readInfo()
        if (duration <= 0) { toast("Can't open this video"); finish(); return }

        setContentView(R.layout.activity_video_editor)
        edgeToEdge(findViewById(R.id.root))

        video = findViewById(R.id.video)
        timeline = findViewById(R.id.timeline)
        btnRevert = findViewById(R.id.btnRevert)
        btnSave = findViewById(R.id.btnSave)
        playIcon = findViewById(R.id.playIcon)
        timeText = findViewById(R.id.timeText)
        btnUndo = findViewById(R.id.btnUndo)
        btnMore = findViewById(R.id.btnMore)
        savingView = findViewById(R.id.saving)
        savingBar = findViewById(R.id.savingBar)
        savingText = findViewById(R.id.savingText)
        listOf(btnRevert, btnSave, timeText, savingText, findViewById<TextView>(R.id.btnCancel)).forEach { it.useDisplay() }

        ts = intent.getIntExtra("s", 0).coerceIn(0, duration)
        te = intent.getIntExtra("e", 0)
        if (te <= 0 || te > duration) te = duration
        if (te - ts < minLen()) { ts = 0; te = duration }
        committed = cur()

        timeline.trimEnabled = true
        timeline.interactive = true
        timeline.setSource(src, duration)
        timeline.setRange(ts, te)
        timeline.setPosition(ts)
        timeline.onSeek = { ms, fin -> scrub(ms, fin) }
        timeline.onTrim = { s, e, which, fin ->
            ts = s
            te = e
            video.setRange(s, e)
            scrub(if (which == 0) s else e, fin)
            if (fin) commitTrim()
            updateBar()
        }

        video.onState = { updatePlayIcon() }
        video.onTick = { pos ->
            if (!scrubbing) { timeline.setPosition(pos); updateTime(pos) }
        }
        video.onError = { toast("Can't play this video") }
        video.load(src, false, ts)
        video.setRange(ts, te)

        findViewById<View>(R.id.btnPill).setOnClickListener { togglePlay() }
        btnRevert.setOnClickListener { revert() }
        btnSave.setOnClickListener { save() }
        btnUndo.setOnClickListener { undoLast() }
        btnMore.setOnClickListener { showMore() }
        findViewById<View>(R.id.btnCancel).setOnClickListener {
            cancel = true
            savingText.text = "Cancelling…"
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exit() }
        })

        updateTime(ts)
        updatePlayIcon()
        updateBar()
    }

    override fun onStop() {
        super.onStop()
        if (::video.isInitialized) video.pause()
    }

    override fun onDestroy() {
        cancel = true
        if (::video.isInitialized) video.release()
        super.onDestroy()
    }

    private fun readInfo() {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, src)
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            origRot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            duration = 0
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    // ---------- Playback ----------
    private fun togglePlay() {
        if (saving) return
        if (video.isPlayingNow) video.pause() else video.play()
        updatePlayIcon()
    }

    private fun updatePlayIcon() {
        val p = video.isPlayingNow
        playIcon.setImageResource(if (p) R.drawable.ic_pause else R.drawable.ic_play)
        playIcon.contentDescription = if (p) "Pause" else "Play"
    }

    private fun updateTime(pos: Int) {
        val len = (te - ts).coerceAtLeast(0)
        val rel = (pos - ts).coerceIn(0, len)
        val s = "${fmtDuration(rel.toLong())} / ${fmtDuration(len.toLong())}"
        if (timeText.text.toString() != s) timeText.text = s
    }

    private fun scrub(ms: Int, fin: Boolean) {
        if (!scrubbing) {
            scrubbing = true
            resumeAfter = video.isPlayingNow
            video.pause()
        }
        video.seek(ms, fin)
        updateTime(ms)
        if (fin) {
            scrubbing = false
            if (resumeAfter) video.play()
            updatePlayIcon()
        }
    }

    // ---------- State ----------
    private fun hasChanges() = ts > 0 || te < duration || rot != 0 || noAudio

    private fun updateBar() {
        val on = hasChanges() && !saving
        btnRevert.isEnabled = on
        btnSave.isEnabled = on
        btnRevert.setTextColor(if (on) Color.WHITE else 0xFF555555.toInt())
        btnSave.setTextColor(if (on) accent else 0xFF555555.toInt())
        btnUndo.isEnabled = undo.isNotEmpty()
        btnUndo.alpha = if (undo.isNotEmpty()) 1f else 0.35f
    }

    private fun commitTrim() {
        if (ts != committed.s || te != committed.e) {
            undo.add(committed)
            committed = cur()
        }
    }

    private fun applyState(st: St) {
        ts = st.s
        te = st.e
        rot = st.q
        noAudio = st.na
        timeline.setRange(ts, te)
        timeline.setPosition(ts)
        video.setRange(ts, te)
        video.quarter = rot
        video.pause()
        video.seek(ts, true)
        updateTime(ts)
        updatePlayIcon()
        updateBar()
    }

    private fun undoLast() {
        if (undo.isEmpty() || saving) return
        val st = undo.removeAt(undo.size - 1)
        committed = st
        applyState(st)
    }

    private fun revert() {
        if (!hasChanges() || saving) return
        MaterialAlertDialogBuilder(this).setMessage("Revert all changes?")
            .setPositiveButton("Revert") { _, _ ->
                val st = St(0, duration, 0, false)
                undo.clear()
                committed = st
                applyState(st)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun exit() {
        if (saving) { cancel = true; savingText.text = "Cancelling…"; return }
        if (!hasChanges()) { finish(); return }
        MaterialAlertDialogBuilder(this).setMessage("Discard your edits?")
            .setPositiveButton("Discard") { _, _ -> finish() }
            .setNegativeButton("Keep editing", null).show()
    }

    // ---------- Menu ----------
    private fun showMore() {
        if (saving) return
        val labels = listOf("Rotate", if (noAudio) "Keep sound" else "Remove sound", "Details")
        showDarkMenu(btnMore, labels) { i ->
            when (i) {
                0 -> { undo.add(committed); rot = (rot + 1) % 4; video.quarter = rot; committed = cur(); updateBar() }
                1 -> { undo.add(committed); noAudio = !noAudio; committed = cur(); updateBar(); toast(if (noAudio) "Sound will be removed on save" else "Sound will be kept") }
                2 -> showDetails()
            }
        }
    }

    private fun showDetails() {
        var name = "Video"
        var size = 0L
        try {
            contentResolver.query(src, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) }
            }
        } catch (e: Exception) { }
        val w = if (origRot % 180 == 90) vh else vw
        val h = if (origRot % 180 == 90) vw else vh
        MaterialAlertDialogBuilder(this).setTitle("Video details")
            .setMessage(
                "Name: $name\nDuration: ${fmtDuration(duration.toLong())}\nResolution: $w × $h\n" +
                        "Size: ${Formatter.formatShortFileSize(this, size)}\n" +
                        "Selected: ${fmtDuration(ts.toLong())} – ${fmtDuration(te.toLong())}"
            )
            .setPositiveButton("OK", null).show()
    }

    // ---------- Save ----------
    private fun save() {
        if (saving || !hasChanges()) return
        video.pause()
        updatePlayIcon()
        saving = true
        cancel = false
        savingBar.progress = 0
        savingText.text = "Saving…  0%"
        savingView.visibility = View.VISIBLE
        updateBar()
        val s = ts.toLong()
        val e = te.toLong()
        val total = ((origRot + rot * 90) % 360 + 360) % 360
        val keep = !noAudio
        Thread {
            var result: Uri? = null
            var err: String? = null
            var cancelled = false
            try {
                result = VideoExporter.export(
                    applicationContext, src, s, e, total, keep,
                    { p -> runOnUiThread { savingBar.progress = p; if (!cancel) savingText.text = "Saving…  $p%" } },
                    { cancel }
                )
            } catch (c: VideoExporter.Cancelled) {
                cancelled = true
            } catch (t: Throwable) {
                val m = t.message ?: ""
                err = when {
                    t is SecurityException -> "Permission denied"
                    t is IOException && (m.contains("ENOSPC") || m.contains("space", true)) -> "Not enough storage"
                    else -> "Couldn't save the video"
                }
            }
            runOnUiThread {
                saving = false
                savingView.visibility = View.GONE
                updateBar()
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    result != null -> {
                        toast("Saved to Movies/Gallery Edited")
                        setResult(RESULT_OK, Intent().setData(result))
                        finish()
                    }
                    cancelled -> toast("Cancelled")
                    else -> toast(err ?: "Couldn't save the video")
                }
            }
        }.start()
    }

    companion object {
        fun intent(c: Context, uri: Uri, start: Int, end: Int) =
            Intent(c, VideoEditorActivity::class.java).setData(uri)
                .putExtra("s", start).putExtra("e", end)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}