package com.example.gallery

import android.content.ContentUris
import android.content.IntentSender
import android.database.ContentObserver
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class TrashActivity : AppCompatActivity() {

    private class T(val id: Long, val uri: Uri, val video: Boolean, val expiresMs: Long, val duration: Long, val name: String)

    private val items = ArrayList<T>()
    private val sel = HashSet<Long>()
    private var selecting = false
    private lateinit var listAdapter: Adapter
    private lateinit var empty: TextView
    private lateinit var title: TextView
    private lateinit var btnRestore: TextView
    private lateinit var btnDelete: TextView

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { load() }
    }

    private val launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        sel.clear(); selecting = false; load(); updateBar()
    }

    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { exitSelect() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun trashCols(): Int = (Prefs.getStr("trashCols", "3").toIntOrNull() ?: 3).coerceIn(3, 7)

    private fun setupPinch(rv: RecyclerView, glm: GridLayoutManager) {
        var acc = 1f
        val sd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { acc = 1f; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                acc *= d.scaleFactor
                val target = when {
                    acc < 0.8f -> glm.spanCount + 1
                    acc > 1.25f -> glm.spanCount - 1
                    else -> return true
                }.coerceIn(3, 7)
                acc = 1f
                if (target != glm.spanCount) {
                    val first = glm.findFirstVisibleItemPosition().coerceAtLeast(0)
                    val off = glm.findViewByPosition(first)?.top ?: 0
                    glm.spanCount = target
                    Prefs.putStr("trashCols", target.toString())
                    glm.scrollToPositionWithOffset(first, off)
                }
                return true
            }
        })
        sd.isQuickScaleEnabled = false
        rv.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(r: RecyclerView, e: MotionEvent): Boolean {
                sd.onTouchEvent(e); return sd.isInProgress
            }
            override fun onTouchEvent(r: RecyclerView, e: MotionEvent) { sd.onTouchEvent(e) }
            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
        })
    }

    override fun onCreate(b: Bundle?) {
        Prefs.init(this)
        super.onCreate(b)
        setContentView(R.layout.activity_trash)
        edgeToEdge(findViewById(R.id.root))
        title = findViewById(R.id.title)
        empty = findViewById(R.id.empty)
        btnRestore = findViewById(R.id.btnRestore)
        btnDelete = findViewById(R.id.btnDelete)
        val btnAll = findViewById<TextView>(R.id.btnAll)
        listOf(title, empty, btnRestore, btnDelete, btnAll).forEach { it.useDisplay() }
        onBackPressedDispatcher.addCallback(this, backCb)
        findViewById<View>(R.id.back).setOnClickListener {
            if (selecting) exitSelect() else finish()
        }
        btnAll.setOnClickListener {
            if (items.isEmpty()) return@setOnClickListener
            if (sel.size == items.size) { sel.clear(); selecting = false }
            else { sel.addAll(items.map { it.id }); selecting = true }
            listAdapter.notifyDataSetChanged(); updateBar()
        }
        btnRestore.setOnClickListener { restore() }
        btnDelete.setOnClickListener { confirmDelete() }
        listAdapter = Adapter()
        val rv = findViewById<RecyclerView>(R.id.list)
        val glm = GridLayoutManager(this, trashCols())
        rv.layoutManager = glm
        setupPinch(rv, glm)
        rv.adapter = listAdapter
        rv.itemAnimator = null
        rv.setHasFixedSize(true)
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        updateBar()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        super.onDestroy()
    }

    private fun exitSelect() {
        selecting = false
        sel.clear()
        listAdapter.notifyDataSetChanged()
        updateBar()
    }

    private fun load() {
        Thread {
            val list = query()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                items.clear(); items.addAll(list)
                sel.retainAll(items.map { it.id }.toSet())
                if (items.isEmpty()) selecting = false
                listAdapter.notifyDataSetChanged()
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                updateBar()
            }
        }.start()
    }

    private fun query(): List<T> {
        val out = ArrayList<T>()
        if (Build.VERSION.SDK_INT < 30) return out
        collect(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, out)
        collect(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, out)
        out.sortByDescending { it.expiresMs }
        return out
    }

    @RequiresApi(30)
    private fun collect(base: Uri, video: Boolean, out: MutableList<T>) {
        val proj = if (video) arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.Video.Media.DURATION
        ) else arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.MediaColumns.DISPLAY_NAME
        )
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
        try {
            contentResolver.query(base, proj, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val u = ContentUris.withAppendedId(base, id).buildUpon().appendQueryParameter("includeTrashed", "1").build()
                    val name = c.getString(2) ?: (if (video) "Video" else "Image")
                    val dur = if (video) c.getLong(3) else 0L
                    out.add(T(id, u, video, c.getLong(1) * 1000, dur, name))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GalleryDbg", "trash query failed", e)
        }
    }

    private fun selectedUris() = items.filter { it.id in sel }.map { it.uri.buildUpon().clearQuery().build() }

    private fun fire(sender: IntentSender) {
        try { launcher.launch(IntentSenderRequest.Builder(sender).build()) }
        catch (e: Exception) { toast("Couldn't complete the action") }
    }

    private fun restore() {
        val u = selectedUris()
        if (u.isEmpty()) { toast("Select items first"); return }
        if (Build.VERSION.SDK_INT >= 30) fire(MediaStore.createTrashRequest(contentResolver, u, false).intentSender)
    }

    private fun confirmDelete() {
        val u = selectedUris()
        if (u.isEmpty()) { toast("Select items first"); return }
        showDeleteSheet(this, "Delete ${u.size} item(s)? This cannot be undone.") {
            if (Build.VERSION.SDK_INT >= 30) fire(MediaStore.createDeleteRequest(contentResolver, u).intentSender)
        }
    }

    private fun openViewer(pos: Int) {
        Session.items = items.map {
            Media(it.id, it.uri, it.name, it.expiresMs, 0L, "Recycle Bin", 0L, 0, 0, it.video, it.duration)
        }
        startActivity(ViewerActivity.intent(this, pos, false, true))
    }

    private fun updateBar() {
        title.text = if (selecting) "${sel.size} selected" else "Recycle Bin"
        val on = selecting && sel.isNotEmpty()
        findViewById<View>(R.id.actionBar).visibility = if (on) View.VISIBLE else View.GONE
        backCb.isEnabled = selecting
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.thumb)
            val check: ImageView = v.findViewById(R.id.check)
            val vid: View = v.findViewById(R.id.vid)

            init {
                v.setOnClickListener {
                    val p = bindingAdapterPosition
                    if (p == RecyclerView.NO_POSITION) return@setOnClickListener
                    if (selecting) toggle(p) else openViewer(p)
                }
                check.setOnClickListener {
                    val p = bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) toggle(p)
                }
                v.setOnLongClickListener {
                    val p = bindingAdapterPosition
                    if (p == RecyclerView.NO_POSITION) return@setOnLongClickListener true
                    if (!selecting) {
                        selecting = true
                        sel.add(items[p].id)
                        notifyDataSetChanged()
                        updateBar()
                    } else toggle(p)
                    true
                }
            }

            private fun toggle(p: Int) {
                val id = items[p].id
                if (!sel.add(id)) sel.remove(id)
                notifyItemChanged(p)
                updateBar()
            }
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_trash, p, false))

        override fun onBindViewHolder(h: VH, i: Int) {
            val t = items[i]
            Glide.with(h.img).load(t.uri).override(400).centerCrop()
                .placeholder(ColorDrawable(0xFF161616.toInt())).into(h.img)
            h.vid.visibility = if (t.video) View.VISIBLE else View.GONE
            h.check.visibility = if (selecting) View.VISIBLE else View.GONE
            h.check.setImageResource(if (t.id in sel) R.drawable.ic_check_filled else R.drawable.ic_circle)
            h.check.clearColorFilter()
        }

        override fun getItemCount() = items.size
        override fun onViewRecycled(h: VH) { Glide.with(h.img).clear(h.img) }
    }
}