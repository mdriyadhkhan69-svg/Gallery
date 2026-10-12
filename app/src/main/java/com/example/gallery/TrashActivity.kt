package com.example.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.DialogInterface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrashActivity : AppCompatActivity() {

    private class T(val id: Long, val uri: Uri, val name: String, val video: Boolean, val expiresMs: Long)

    private val items = ArrayList<T>()
    private val sel = HashSet<Long>()
    private lateinit var listAdapter: Adapter
    private lateinit var empty: TextView
    private lateinit var title: TextView
    private lateinit var btnRestore: TextView
    private lateinit var btnDelete: TextView

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
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = listAdapter
        rv.itemAnimator = null
        updateBar()
        load()
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
        val typeCol = MediaStore.Files.FileColumns.MEDIA_TYPE
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            typeCol, MediaStore.MediaColumns.DATE_EXPIRES
        )
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "$typeCol IN (?, ?)")
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                )
            )
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_EXPIRES} DESC")
        }
        try {
            contentResolver.query(MediaStore.Files.getContentUri("external"), proj, args, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(proj[0]); val iName = c.getColumnIndexOrThrow(proj[1])
                val iType = c.getColumnIndexOrThrow(proj[2]); val iExp = c.getColumnIndexOrThrow(proj[3])
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val video = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val u = ContentUris.withAppendedId(base, id).buildUpon().appendQueryParameter("includeTrashed", "1").build()
                    out.add(T(id, u, c.getString(iName) ?: "Item", video, c.getLong(iExp) * 1000))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GalleryDbg", "trash query failed", e)
        }
        return out
    }

    private fun info(t: T): String {
        val day = 86_400_000L
        val kind = if (t.video) "Video" else "Photo"
        if (t.expiresMs <= 0) return kind
        val deleted = t.expiresMs - 30L * day
        val left = ((t.expiresMs - System.currentTimeMillis() + day - 1) / day).coerceAtLeast(0)
        val d = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(deleted))
        return "$kind · Deleted $d · $left ${if (left == 1L) "day" else "days"} left"
    }

    private fun selectedUris() = items.filter { it.id in sel }.map { it.uri.buildUpon().clearQuery().build() }

    private fun fire(sender: android.content.IntentSender) {
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
        val d = MaterialAlertDialogBuilder(this)
            .setMessage("Permanently delete ${u.size} item(s)? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) fire(MediaStore.createDeleteRequest(contentResolver, u).intentSender)
            }
            .setNegativeButton("Cancel", null).show()
        d.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(0xFFFF4D4D.toInt())
    }

    private fun updateBar() {
        title.text = if (sel.isEmpty()) "Recycle Bin" else "${sel.size} selected"
        val on = sel.isNotEmpty()
        btnRestore.alpha = if (on) 1f else 0.4f
        btnDelete.alpha = if (on) 1f else 0.4f
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.thumb)
            val name: TextView = v.findViewById(R.id.name)
            val sub: TextView = v.findViewById(R.id.sub)
            val check: ImageView = v.findViewById(R.id.check)
            init { name.useDisplay(); sub.useDisplay(); img.roundCorners(10f) }
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_trash, p, false))

        override fun onBindViewHolder(h: VH, i: Int) {
            val t = items[i]
            Glide.with(h.img).load(t.uri).override(240).centerCrop()
                .placeholder(ColorDrawable(0xFF161616.toInt())).into(h.img)
            h.name.text = t.name
            h.sub.text = info(t)
            val on = t.id in sel
            if (on) { h.check.setImageResource(R.drawable.ic_check_filled); h.check.clearColorFilter() }
            else { h.check.setImageResource(R.drawable.ic_circle); h.check.setColorFilter(0xFFB0B0B0.toInt()) }
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