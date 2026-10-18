package com.example.gallery

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.IOException

class AlbumPickerActivity : AppCompatActivity() {

    private val vm: GalleryViewModel by viewModels()
    private val hd = Handler(Looper.getMainLooper())
    private var ids: List<Long> = emptyList()
    private var move = false
    private var srcMode = ""
    private var srcParam = ""
    private var busy = false
    private var bar: MoveProgress? = null
    private var moveTotal = 0
    private var moveCopied = 0
    private lateinit var adapter: AlbumAdapter
    private lateinit var empty: TextView

    private val deleter = DeleteHelper(this, { this }) { removed, failed -> afterDelete(removed.size, failed) }
        .apply { quiet = true }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(b: Bundle?) {
        Prefs.init(this)
        super.onCreate(b)
        ids = (intent.getLongArrayExtra("ids") ?: LongArray(0)).distinct()
        move = intent.getBooleanExtra("move", false)
        srcMode = intent.getStringExtra("srcMode") ?: ""
        srcParam = intent.getStringExtra("srcParam") ?: ""
        if (ids.isEmpty()) { finish(); return }

        setContentView(R.layout.activity_album_picker)
        edgeToEdge(findViewById(R.id.root))
        findViewById<TextView>(R.id.title).useDisplay()
        empty = findViewById(R.id.empty); empty.useDisplay()
        val btnCreate = findViewById<TextView>(R.id.btnCreate); btnCreate.useDisplay()
        val btnCancel = findViewById<TextView>(R.id.btnCancel); btnCancel.useDisplay()
        pressFx(btnCreate)
        pressFx(btnCancel)

        btnCreate.setOnClickListener {
            if (busy) return@setOnClickListener
            askName(this, "New album", "") { n ->
                CustomAlbums.create(n, emptyList())
                refresh()
            }
        }
        btnCancel.setOnClickListener {
            if (busy) return@setOnClickListener
            setResult(RESULT_CANCELED)
            finish()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (busy) return
                setResult(RESULT_CANCELED)
                finish()
            }
        })

        adapter = AlbumAdapter({ a -> choose(a) }, {}, {})
        val rv = findViewById<RecyclerView>(R.id.list)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.itemAnimator = null

        vm.all.observe(this) { refresh() }
        vm.loaded.observe(this) { refresh() }
        vm.load()
        refresh()
    }

    override fun onDestroy() {
        hd.removeCallbacksAndMessages(null)
        bar?.dismiss()
        bar = null
        super.onDestroy()
    }

    private fun pressFx(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }

    private fun refresh() {
        if (!::adapter.isInitialized) return
        val l = vm.albums()
            .filter { a ->
                val isSrc = (srcMode == "calbum" || srcMode == "album") && a.id.toString() == srcParam
                !isSrc && !(a.name == RECOVER_NAME && a.count == 0) && (isCustomId(a.id) || a.count > 0)
            }
            .sortedByDescending { it.latest }
        adapter.submitList(l)
        empty.visibility = if (l.isEmpty() && vm.loaded.value == true) View.VISIBLE else View.GONE
    }

    private fun choose(a: Album) {
        if (busy) return
        if (isCustomId(a.id)) chooseCustom(a) else chooseDevice(a)
    }

    private fun complete(p: MoveProgress, msg: String) {
        Changes.dirty = true
        p.success(msg)
        setResult(RESULT_OK)
        hd.postDelayed({ finish() }, 800)
    }

    // ---------- Custom (app) album destination ----------
    private fun chooseCustom(a: Album) {
        busy = true
        val total = ids.size
        val p = MoveProgress(this, if (move) "Moving" else "Copying")
        bar = p
        p.show()
        if (total == 1) p.waiting()
        val all = ids
        val srcCustomId = if (srcMode == "calbum") srcParam.toLongOrNull() else null
        val hideFromDevice = srcMode != "calbum"
        Thread {
            var done = 0
            var failed = 0
            val batch = maxOf(1, total / 20)
            var i = 0
            while (i < total) {
                val part = all.subList(i, minOf(i + batch, total))
                try {
                    CustomAlbums.add(a.id, part)
                    if (move) {
                        if (srcCustomId != null) CustomAlbums.remove(srcCustomId, part)
                        else if (hideFromDevice) Prefs.setSet("albumGone", Prefs.getSet("albumGone") + part.map { it.toString() })
                    }
                    done += part.size
                } catch (e: Exception) {
                    failed += part.size
                }
                i += batch
                if (total > 1) {
                    val pct = minOf(99, done * 100 / total)
                    runOnUiThread { p.set(pct) }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) { p.dismiss(); return@runOnUiThread }
                when {
                    failed == 0 -> complete(p, if (move) "Moved to album" else "Copied to album")
                    done == 0 -> {
                        p.dismiss()
                        bar = null
                        busy = false
                        toast(if (move) "Couldn't move" else "Couldn't copy")
                    }
                    else -> {
                        p.dismiss()
                        bar = null
                        Changes.dirty = true
                        toast("$done of $total ${if (move) "moved" else "copied"}, $failed failed")
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            }
        }.start()
    }

    // ---------- Device album destination (real file copy) ----------
    private fun chooseDevice(a: Album) {
        val byId = Library.snapshot.associateBy { it.id }
        val items = ids.mapNotNull { byId[it] }.filter { it.bucketId != a.id }
        if (items.isEmpty()) { toast("Already in this album"); return }
        busy = true
        val p = MoveProgress(this, if (move) "Moving" else "Copying")
        bar = p
        p.show()
        val rel = relPath(a)
        val app = applicationContext
        val scale = if (move) 90 else 99
        Thread {
            val copied = ArrayList<Media>()
            var failed = 0
            val totalBytes = items.sumOf { maxOf(it.size, 1L) }
            var doneBytes = 0L
            var last = -1
            for (m in items) {
                val ok = try {
                    copyOne(app, m, rel) { n ->
                        doneBytes += n
                        val pct = (doneBytes * scale / totalBytes).toInt().coerceIn(0, scale)
                        if (pct != last) { last = pct; runOnUiThread { p.set(pct) } }
                    }
                    true
                } catch (e: Exception) { false }
                if (ok) copied.add(m) else failed++
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) { p.dismiss(); return@runOnUiThread }
                afterCopy(p, items.size, copied, failed)
            }
        }.start()
    }

    private fun afterCopy(p: MoveProgress, total: Int, copied: List<Media>, failed: Int) {
        if (copied.isEmpty()) {
            p.dismiss()
            bar = null
            busy = false
            toast(if (move) "Couldn't move" else "Couldn't copy")
            return
        }
        if (!move) {
            if (failed == 0) complete(p, "Copied to album")
            else {
                p.dismiss()
                bar = null
                Changes.dirty = true
                toast("${copied.size} of $total copied, $failed failed")
                setResult(RESULT_OK)
                finish()
            }
            return
        }
        moveTotal = total
        moveCopied = copied.size
        deleter.begin(copied.map { it.uri }, false)
    }

    private fun afterDelete(removed: Int, failed: Int) {
        val p = bar ?: return
        if (failed == 0 && moveCopied == moveTotal) {
            complete(p, "Moved to album")
            return
        }
        p.dismiss()
        bar = null
        Changes.dirty = true
        toast(
            when {
                removed == 0 -> "Copied, but originals could not be removed"
                else -> "$removed of $moveTotal moved"
            }
        )
        setResult(RESULT_OK)
        finish()
    }

    private fun relPath(a: Album): String {
        var rel: String? = null
        if (Build.VERSION.SDK_INT >= 29) {
            val m = Library.snapshot.firstOrNull { it.bucketId == a.id }
            if (m != null) {
                try {
                    contentResolver.query(m.uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                        if (c.moveToFirst()) rel = c.getString(0)
                    }
                } catch (e: Exception) { }
            }
        }
        var r = rel ?: "Pictures/${a.name}/"
        if (!r.endsWith("/")) r += "/"
        return r
    }

    private fun copyOne(ctx: Context, m: Media, rel: String, onBytes: (Long) -> Unit) {
        val cr = ctx.contentResolver
        val mime = cr.getType(m.uri) ?: if (m.video) "video/mp4" else "image/jpeg"
        val base = if (m.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, m.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.Images.Media.DATE_TAKEN, m.date)
        }
        var file: File? = null
        if (Build.VERSION.SDK_INT >= 29) {
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            v.put(MediaStore.MediaColumns.IS_PENDING, 1)
        } else {
            val f = File(Environment.getExternalStorageDirectory(), rel + m.name)
            f.parentFile?.mkdirs()
            file = f
            v.put(MediaStore.MediaColumns.DATA, f.absolutePath)
        }
        val out = cr.insert(base, v) ?: throw IOException("insert failed")
        try {
            val input = cr.openInputStream(m.uri) ?: throw IOException("no input")
            val output = cr.openOutputStream(out) ?: throw IOException("no output")
            input.use { inp ->
                output.use { o ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val u = ContentValues()
                u.put(MediaStore.MediaColumns.IS_PENDING, 0)
                cr.update(out, u, null, null)
            } else if (file != null) {
                MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(mime), null)
            }
        } catch (e: Exception) {
            try { cr.delete(out, null, null) } catch (x: Exception) { }
            throw e
        }
    }

    companion object {
        fun intent(c: Context, ids: LongArray, move: Boolean, srcMode: String, srcParam: String) =
            Intent(c, AlbumPickerActivity::class.java)
                .putExtra("ids", ids)
                .putExtra("move", move)
                .putExtra("srcMode", srcMode)
                .putExtra("srcParam", srcParam)
    }
}