package com.example.gallery

import android.content.ContentUris
import android.content.DialogInterface
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
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class TrashActivity : AppCompatActivity() {

    private class T(val id: Long, val uri: Uri, val video: Boolean, val expiresMs: Long)

    private val items = ArrayList<T>()
    private val sel = HashSet<Long>()
    private lateinit var listAdapter: Adapter
    private lateinit var empty: TextView
    private lateinit var title: TextView
    private lateinit var btnRestore: TextView
    private lateinit var btnDelete: TextView

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { load() }
    }

    private val launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        sel.clear(); load()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

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
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        btnAll.setOnClickListener {
            if (sel.size == items.size) sel.clear() else sel.addAll(items.map { it.id })
            listAdapter.notifyDataSetChanged(); updateBar()
        }
        btnRestore.setOnClickListener { restore() }
        btnDelete.setOnClickListener { confirmDelete() }
        listAdapter = Adapter()
        val rv = findViewById<RecyclerView>(R.id.list)
        rv.layoutManager = GridLayoutManager(this, Prefs.columns)
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

    private fun load() {
        Thread {
            val list = query()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                items.clear(); items.addAll(list)
                sel.retainAll(items.map { it.id }.toSet())
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
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_EXPIRES)
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
        try {
            contentResolver.query(base, proj, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val u = ContentUris.withAppendedId(base, id).buildUpon().appendQueryParameter("includeTrashed", "1").build()
                    out.add(T(id, u, video, c.getLong(1) * 1000))
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

    private fun updateBar() {
        title.text = if (sel.isEmpty()) "Recycle Bin" else "${sel.size} selected"
        val on = sel.isNotEmpty()
        findViewById<View>(R.id.actionBar).visibility = if (on) View.VISIBLE else View.GONE
        btnRestore.alpha = if (on) 1f else 0.4f
        btnDelete.alpha = if (on) 1f else 0.4f
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.thumb)
            val check: ImageView = v.findViewById(R.id.check)
            val vid: View = v.findViewById(R.id.vid)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_trash, p, false))

        override fun onBindViewHolder(h: VH, i: Int) {
            val t = items[i]
            Glide.with(h.img).load(t.uri).override(400).centerCrop()
                .placeholder(ColorDrawable(0xFF161616.toInt())).into(h.img)
            h.vid.visibility = if (t.video) View.VISIBLE else View.GONE
            val on = t.id in sel
            h.check.setImageResource(if (on) R.drawable.ic_check_filled else R.drawable.ic_circle)
            h.check.clearColorFilter()
            h.itemView.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return@setOnClickListener
                val id = items[p].id
                if (!sel.add(id)) sel.remove(id)
                notifyItemChanged(p); updateBar()
            }
        }

        override fun getItemCount() = items.size
        override fun onViewRecycled(h: VH) { Glide.with(h.img).clear(h.img) }
    }
}