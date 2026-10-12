package com.example.gallery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

class DupGroupActivity : AppCompatActivity() {

    private val items = ArrayList<Media>()
    private val sel = HashSet<Long>()
    private var cur = 0
    private var keepId = 0L
    private var pending: List<Uri> = emptyList()
    private var pendingIds: Set<Long> = emptySet()

    private lateinit var preview: ImageView
    private lateinit var playBadge: View
    private lateinit var previewCheck: ImageView
    private lateinit var countText: TextView
    private lateinit var title: TextView
    private lateinit var allIcon: ImageView
    private lateinit var strip: RecyclerView
    private lateinit var stripAdapter: Strip
    private lateinit var btnDelete: ImageView

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT < 30) Deleter.delete(this, pending)
            removeIds(pendingIds)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun oldestId() = items.minByOrNull { it.date }?.id ?: 0L

    override fun onCreate(b: Bundle?) {
        Prefs.init(this)
        super.onCreate(b)
        val ids = Dups.groups[intent.getStringExtra("key") ?: ""] ?: emptyList()
        val byId = Library.snapshot.associateBy { it.id }
        ids.mapNotNullTo(items) { byId[it] }
        if (items.size < 2) { finish(); return }

        setContentView(R.layout.activity_dup_group)
        edgeToEdge(findViewById(R.id.root))

        val startId = intent.getLongExtra("id", -1L)
        cur = (b?.getInt("cur") ?: items.indexOfFirst { it.id == startId }).coerceIn(0, items.size - 1)
        keepId = if (b != null && b.containsKey("keep")) b.getLong("keep") else oldestId()
        b?.getLongArray("sel")?.forEach { sel.add(it) }

        preview = findViewById(R.id.preview)
        playBadge = findViewById(R.id.playBadge)
        previewCheck = findViewById(R.id.previewCheck)
        countText = findViewById(R.id.countText); countText.useDisplay()
        title = findViewById(R.id.title); title.useDisplay()
        allIcon = findViewById(R.id.allIcon)
        btnDelete = findViewById(R.id.btnDelete)
        findViewById<TextView>(R.id.allText).useDisplay()
        strip = findViewById(R.id.strip)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAll).setOnClickListener { toggleAll() }
        previewCheck.setOnClickListener { toggle(items[cur].id) }
        preview.setOnClickListener {
            Session.items = ArrayList(items)
            startActivity(ViewerActivity.intent(this, cur))
        }
        findViewById<View>(R.id.btnCrown).setOnClickListener { keepThis() }
        findViewById<View>(R.id.btnShare).setOnClickListener { share() }
        btnDelete.setOnClickListener { confirmDelete() }

        stripAdapter = Strip()
        strip.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        strip.adapter = stripAdapter
        strip.itemAnimator = null

        showPreview()
        updateUi()
        centerStrip()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("cur", cur)
        outState.putLong("keep", keepId)
        outState.putLongArray("sel", sel.toLongArray())
    }

    private fun centerStrip() {
        strip.post {
            val total = items.size * dp(56)
            val pad = ((strip.width - total) / 2).coerceAtLeast(dp(12))
            strip.setPadding(pad, strip.paddingTop, pad, strip.paddingBottom)
            strip.scrollToPosition(cur)
        }
    }

    private fun showPreview() {
        val m = items[cur]
        Glide.with(preview).load(m.uri).override(1600, 1600).fitCenter().into(preview)
        playBadge.visibility = if (m.video) View.VISIBLE else View.GONE
    }

    private fun updateUi() {
        val n = sel.size
        title.text = if (n == 0) "Select items" else "$n selected"
        if (n == items.size) { allIcon.setImageResource(R.drawable.ic_check_filled); allIcon.clearColorFilter() }
        else { allIcon.setImageResource(R.drawable.ic_circle); allIcon.setColorFilter(Color.WHITE) }
        if (items[cur].id in sel) { previewCheck.setImageResource(R.drawable.ic_check_filled); previewCheck.clearColorFilter() }
        else { previewCheck.setImageResource(R.drawable.ic_circle); previewCheck.setColorFilter(0xFFB0B0B0.toInt()) }
        countText.text = items.size.toString()
        btnDelete.alpha = if (n == 0) 0.4f else 1f
        stripAdapter.notifyItemRangeChanged(0, items.size, "s")
    }

    private fun toggle(id: Long) {
        if (!sel.add(id)) sel.remove(id)
        updateUi()
    }

    private fun toggleAll() {
        if (sel.size == items.size) sel.clear()
        else items.forEach { sel.add(it.id) }
        updateUi()
    }

    private fun keepThis() {
        keepId = items[cur].id
        sel.clear()
        items.forEach { if (it.id != keepId) sel.add(it.id) }
        updateUi()
    }

    private fun share() {
        val u = if (sel.isEmpty()) listOf(items[cur].uri) else items.filter { it.id in sel }.map { it.uri }
        Share.send(this, u)
    }

    private fun confirmDelete() {
        val chosen = items.filter { it.id in sel }
        if (chosen.isEmpty()) { toast("Select items first"); return }
        if (chosen.size >= items.size) { toast("Keep at least one copy"); return }
        pending = chosen.map { it.uri }
        pendingIds = chosen.map { it.id }.toSet()
        confirmTrash(this, chosen.size) {
            val sender = Deleter.delete(this, pending)
            if (sender != null) deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            else removeIds(pendingIds)
        }
    }

    private fun removeIds(ids: Set<Long>) {
        items.removeAll { it.id in ids }
        sel.removeAll(ids)
        if (items.size < 2) { finish(); return }
        cur = cur.coerceIn(0, items.size - 1)
        if (items.none { it.id == keepId }) keepId = oldestId()
        stripAdapter.notifyDataSetChanged()
        showPreview()
        updateUi()
        centerStrip()
    }

    private inner class Strip : RecyclerView.Adapter<Strip.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.thumb)
            val ring: View = v.findViewById(R.id.ring)
            val check: ImageView = v.findViewById(R.id.check)
            val crown: ImageView = v.findViewById(R.id.crown)

            init {
                img.roundCorners(6f)
                crown.setColorFilter(0xFFFFD60A.toInt())
                v.setOnClickListener {
                    val p = bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) {
                        cur = p
                        showPreview()
                        updateUi()
                        strip.smoothScrollToPosition(p)
                    }
                }
                v.setOnLongClickListener {
                    val p = bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) toggle(items[p].id)
                    true
                }
                check.setOnClickListener {
                    val p = bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) toggle(items[p].id)
                }
            }
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_dup_thumb, p, false))

        override fun onBindViewHolder(h: VH, i: Int) {
            Glide.with(h.img).load(items[i].uri).override(200).centerCrop()
                .placeholder(ColorDrawable(0xFF161616.toInt())).into(h.img)
            state(h, i)
        }

        override fun onBindViewHolder(h: VH, i: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) onBindViewHolder(h, i) else state(h, i)
        }

        private fun state(h: VH, i: Int) {
            val m = items[i]
            val current = i == cur
            h.ring.visibility = if (current) View.VISIBLE else View.GONE
            h.itemView.alpha = if (current) 1f else 0.7f
            h.itemView.scaleX = if (current) 1.12f else 1f
            h.itemView.scaleY = if (current) 1.12f else 1f
            h.check.visibility = if (m.id in sel) View.VISIBLE else View.GONE
            h.crown.visibility = if (m.id == keepId) View.VISIBLE else View.GONE
        }

        override fun onViewRecycled(h: VH) { Glide.with(h.img).clear(h.img) }
        override fun getItemCount() = items.size
    }

    companion object {
        fun intent(c: Context, key: String, id: Long) =
            Intent(c, DupGroupActivity::class.java).putExtra("key", key).putExtra("id", id)
    }
}