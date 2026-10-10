package com.example.gallery

import android.graphics.Color
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

class StoryAdapter(
    private val onClick: (Story) -> Unit,
    private val onHeartChanged: () -> Unit,
    private val onLong: (Story) -> Unit
) : ListAdapter<Story, StoryAdapter.VH>(object : DiffUtil.ItemCallback<Story>() {
    override fun areItemsTheSame(a: Story, b: Story) = a.key == b.key
    override fun areContentsTheSame(a: Story, b: Story) = a == b
}) {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val title: TextView = v.findViewById(R.id.sTitle)
        val range: TextView = v.findViewById(R.id.sRange)
        val heart: ImageView = v.findViewById(R.id.heart)
        val badge: TextView = v.findViewById(R.id.badge)

        init {
            v.roundCorners(28f)
            title.useDisplay(); range.useDisplay(); badge.useDisplay()
            v.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) {
                    val s = getItem(p)
                    Prefs.add("viewed", s.key)
                    badge.visibility = View.GONE
                    onClick(s)
                }
            }
            v.setOnLongClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onLong(getItem(p))
                true
            }
            heart.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) {
                    val on = Prefs.toggle("fav", "s${getItem(p).key}")
                    applyHeart(on)
                    heart.animate().scaleX(1.3f).scaleY(1.3f).setDuration(110).withEndAction {
                        heart.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    }.start()
                    onHeartChanged()
                }
            }
        }

        fun applyHeart(on: Boolean) {
            heart.setImageResource(if (on) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            heart.setColorFilter(if (on) Colors.HEART else Color.WHITE)
        }
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_story, p, false))

    override fun onBindViewHolder(h: VH, i: Int) {
        val s = getItem(i)
        Glide.with(h.cover).load(s.cover).override(800).centerCrop()
            .placeholder(ColorDrawable(0xFF161616.toInt())).into(h.cover)
        h.title.text = s.title
        h.range.text = s.range
        h.applyHeart(Prefs.has("fav", "s${s.key}"))
        h.badge.visibility = if (Prefs.has("viewed", s.key)) View.GONE else View.VISIBLE
    }

    override fun onViewRecycled(h: VH) { Glide.with(h.cover).clear(h.cover) }
}