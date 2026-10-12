package com.example.gallery

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class LockAlbumsActivity : AppCompatActivity() {

    private val vm: GalleryViewModel by viewModels()
    private val locked = HashSet<String>()
    private var albums: List<Album> = emptyList()
    private lateinit var listAdapter: Adapter

    override fun onCreate(b: Bundle?) {
        Prefs.init(this)
        super.onCreate(b)
        setContentView(R.layout.activity_lock_albums)
        edgeToEdge(findViewById(R.id.root))
        findViewById<TextView>(R.id.title).useDisplay()
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        locked.addAll(Prefs.getSet("locked"))
        listAdapter = Adapter()
        val rv = findViewById<RecyclerView>(R.id.list)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = listAdapter
        rv.itemAnimator = null
        ScrollAssist.attach(rv, findViewById(R.id.fastScroll), findViewById(R.id.btnTop))
        vm.load()
        vm.loaded.observe(this) { refresh() }
    }

    /** Bahire gele (home / app switch) abar password lagbe */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    private fun refresh() {
        albums = vm.lockableAlbums().sortedBy { it.name.lowercase() }
        listAdapter.notifyDataSetChanged()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.thumb)
            val name: TextView = v.findViewById(R.id.name)
            val count: TextView = v.findViewById(R.id.count)
            val sw: CapsuleSwitch = v.findViewById(R.id.sw)
            init { name.useDisplay(); count.useDisplay(); img.roundCorners(14f) }
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_hide_album, p, false))

        private fun label(h: VH, a: Album, on: Boolean) {
            h.count.text = countLabel(a.count, a.videos) + if (on) " · Locked" else ""
            h.count.setTextColor(if (on) Colors.BLUE else Colors.GRAY)
        }

        override fun onBindViewHolder(h: VH, i: Int) {
            val a = albums[i]
            val key = a.id.toString()
            Glide.with(h.img).load(a.cover).override(300).centerCrop()
                .placeholder(ColorDrawable(0xFF161616.toInt())).into(h.img)
            h.name.text = a.name
            label(h, a, key in locked)
            h.sw.contentDescription = "Lock ${a.name}"
            h.sw.onChange = null
            h.sw.set(key in locked, false)
            h.sw.onChange = { on ->
                if (on) locked.add(key) else locked.remove(key)
                Prefs.setSet("locked", locked)
                Changes.dirty = true
                label(h, a, on)
            }
            h.itemView.setOnClickListener { h.sw.toggle() }
        }

        override fun getItemCount() = albums.size
        override fun onViewRecycled(h: VH) { Glide.with(h.img).clear(h.img) }
    }
}