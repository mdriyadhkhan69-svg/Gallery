package com.example.gallery

import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.text.NumberFormat

class AlbumAdapter(
    private val onClick: (Album) -> Unit,
    private val onSelectChanged: () -> Unit,
    private val onLong: (Album) -> Unit
) : ListAdapter<Album, AlbumAdapter.VH>(object : DiffUtil.ItemCallback<Album>() {
    override fun areItemsTheSame(a: Album, b: Album) = a.id == b.id
    override fun areContentsTheSame(a: Album, b: Album) = a == b
}) {

    var editing = false
    val selected = HashSet<Long>()

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.thumb)
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)
        val check: ImageView = v.findViewById(R.id.check)
        var lastOn: Boolean? = null
        init {
            name.useDisplay(); count.useDisplay()
            img.roundCorners(14f)
            v.setOnClickListener {
                val p = bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return@setOnClickListener
                val a = getItem(p)
                if (editing) {
                    if (!selected.add(a.id)) selected.remove(a.id)
                    notifyItemChanged(p)
                    onSelectChanged()
                } else onClick(a)
            }
            v.setOnLongClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onLong(getItem(p))
                true
            }
        }
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_album, p, false))

    override fun onBindViewHolder(h: VH, i: Int) {
        val a = getItem(i)
        if (a.cover == Uri.EMPTY) {
            Glide.with(h.img).clear(h.img)
            h.img.setImageDrawable(ColorDrawable(0xFF161616.toInt()))
        } else {
            Glide.with(h.img).load(a.cover).override(300).centerCrop()
                .placeholder(ColorDrawable(0xFF161616.toInt())).into(h.img)
        }
        h.name.text = a.name
        h.count.text = NumberFormat.getInstance().format(a.count)
        h.check.visibility = if (editing) View.VISIBLE else View.GONE
        val on = a.id in selected
        h.check.setImageResource(if (on) R.drawable.ic_check_filled else R.drawable.ic_circle_thin)
        h.check.clearColorFilter()
        h.check.animate().cancel()
        val prev = h.lastOn
        if (editing && prev != null && prev != on) {
            h.check.scaleX = 0.6f
            h.check.scaleY = 0.6f
            h.check.animate().scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(OvershootInterpolator(2.2f)).start()
        } else {
            h.check.scaleX = 1f
            h.check.scaleY = 1f
        }
        h.lastOn = on
    }

    override fun onViewRecycled(h: VH) {
        Glide.with(h.img).clear(h.img)
        h.check.animate().cancel()
        h.lastOn = null
    }
}