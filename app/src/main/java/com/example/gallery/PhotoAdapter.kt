package com.example.gallery

import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class PhotoAdapter(
    private val onTap: (Row.Photo, Int) -> Unit,
    private val onLong: (Row.Photo, Int) -> Unit,
    private val onHeader: (Int) -> Unit,
    private val onDup: (Media) -> Unit
) : ListAdapter<Row, RecyclerView.ViewHolder>(Diff) {

    var selecting = false
    var dupActive = false
    val selected = HashSet<Long>()

    companion object {
        const val HEADER = 0
        const val PHOTO = 1
        val Diff = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = when {
                a is Row.Header && b is Row.Header -> a.title == b.title
                a is Row.Photo && b is Row.Photo -> a.m.id == b.m.id
                else -> false
            }
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }
    }

    override fun getItemViewType(position: Int) = if (getItem(position) is Row.Header) HEADER else PHOTO

    fun sectionIds(pos: Int): List<Long> {
        val out = ArrayList<Long>()
        var i = pos + 1
        while (i < itemCount) {
            val r = getItem(i)
            if (r is Row.Header) break
            if (r is Row.Photo) out.add(r.m.id)
            i++
        }
        return out
    }

    private fun sectionSelected(pos: Int): Boolean {
        val ids = sectionIds(pos)
        return ids.isNotEmpty() && ids.all { it in selected }
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(p.context)
        return if (t == HEADER) HeaderVH(inf.inflate(R.layout.item_header, p, false))
        else PhotoVH(inf.inflate(R.layout.item_photo, p, false))
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        when (val r = getItem(position)) {
            is Row.Header -> {
                val hv = h as HeaderVH
                hv.tv.text = r.title
                hv.bindSelection(position)
            }
            is Row.Photo -> (h as PhotoVH).bind(r)
        }
    }

    /** payload thakle shudhu select UI update hoy (image abar load hoy na, drag-select smooth thake) */
    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) { onBindViewHolder(h, position); return }
        val r = getItem(position)
        if (h is PhotoVH && r is Row.Photo) h.bindSelection(r)
        else if (h is HeaderVH && r is Row.Header) h.bindSelection(position)
    }

    override fun onViewRecycled(h: RecyclerView.ViewHolder) {
        if (h is PhotoVH) Glide.with(h.img).clear(h.img)
    }

    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.hTitle)
        private val check: ImageView = v.findViewById(R.id.hCheck)

        init {
            tv.useDisplay()
            val click = View.OnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION && selecting) onHeader(p)
            }
            check.setOnClickListener(click)
            v.setOnClickListener(click)
        }

        fun bindSelection(pos: Int) {
            check.visibility = if (selecting) View.VISIBLE else View.GONE
            if (sectionSelected(pos)) {
                check.setImageResource(R.drawable.ic_check_filled)
                check.clearColorFilter()
            } else {
                check.setImageResource(R.drawable.ic_circle)
                check.setColorFilter(0xFFB0B0B0.toInt())
            }
        }
    }

    inner class PhotoVH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        private val check: ImageView = v.findViewById(R.id.check)
        private val fav: ImageView = v.findViewById(R.id.fav)
        private val vid: View = v.findViewById(R.id.vid)
        private val dur: TextView = v.findViewById(R.id.dur)
        private val selBox: View = v.findViewById(R.id.selBox)
        private val dupBadge: View = v.findViewById(R.id.dupBadge)
        private val dupIcon: ImageView = v.findViewById(R.id.dupIcon)
        private val dupCount: TextView = v.findViewById(R.id.dupCount)

        init {
            dupCount.useDisplay()
            dupBadge.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) (getItem(p) as? Row.Photo)?.let { onDup(it.m) }
            }
            dupBadge.setOnLongClickListener { v.performLongClick(); true }
        }

        init {
            v.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) (getItem(p) as? Row.Photo)?.let { onTap(it, p) }
            }
            v.setOnLongClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) (getItem(p) as? Row.Photo)?.let { onLong(it, p) }
                true
            }
        }

        fun bind(r: Row.Photo) {
            Glide.with(img).load(r.m.uri).override(400).centerCrop()
                .placeholder(ColorDrawable(0xFF161616.toInt())).into(img)
            if (r.m.video) {
                vid.visibility = View.VISIBLE
                dur.text = fmtDuration(r.m.duration)
            } else vid.visibility = View.GONE
            bindSelection(r)
        }

        fun bindSelection(r: Row.Photo) {
            val sel = r.m.id in selected
            check.visibility = if (selecting) View.VISIBLE else View.GONE
            selBox.visibility = if (sel) View.VISIBLE else View.GONE
            if (sel) {
                check.setImageResource(R.drawable.ic_check_filled)
                check.clearColorFilter()
            } else {
                check.setImageResource(R.drawable.ic_circle)
                check.setColorFilter(0xFFB0B0B0.toInt())
            }
            fav.visibility = if (Prefs.has("fav", "p${r.m.id}")) View.VISIBLE else View.GONE
            val dc = Dups.countOf(r.m.id)
            if (dc > 1) {
                dupBadge.visibility = View.VISIBLE
                dupCount.text = dc.toString()
                dupIcon.setColorFilter(if (dupActive) Colors.BLUE else Color.WHITE)
            } else dupBadge.visibility = View.GONE
        }
    }
}