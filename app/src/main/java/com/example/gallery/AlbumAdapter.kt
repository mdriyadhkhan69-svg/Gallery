package com.example.gallery

import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.text.NumberFormat

class AlbumAdapter(private val onClick: (Album) -> Unit) :
    ListAdapter<Album, AlbumAdapter.VH>(object : DiffUtil.ItemCallback<Album>() {
        override fun areItemsTheSame(a: Album, b: Album) = a.id == b.id
        override fun areContentsTheSame(a: Album, b: Album) = a == b
    }) {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.thumb)
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)
        init {
            name.useDisplay(); count.useDisplay()
            img.roundCorners(14f)
            v.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(getItem(p))
            }
        }
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_album, p, false))

    override fun onBindViewHolder(h: VH, i: Int) {
        val a = getItem(i)
        Glide.with(h.img).load(a.cover).override(300).centerCrop()
            .placeholder(ColorDrawable(0xFF161616.toInt())).into(h.img)
        h.name.text = a.name
        h.count.text = NumberFormat.getInstance().format(a.count)
    }

    override fun onViewRecycled(h: VH) { Glide.with(h.img).clear(h.img) }
}