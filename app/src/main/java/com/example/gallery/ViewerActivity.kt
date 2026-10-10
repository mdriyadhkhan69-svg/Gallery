package com.example.gallery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViewerActivity : AppCompatActivity() {

    private lateinit var items: MutableList<Media>
    private lateinit var pager: ViewPager2
    private lateinit var adapter: ViewerAdapter
    private lateinit var top: View
    private lateinit var bottom: View
    private lateinit var nameView: TextView
    private lateinit var heart: ImageView
    private var barsVisible = true
    private var pending: List<Uri> = emptyList()

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT < 30) Deleter.delete(this, pending)
            removeCurrent()
        }
    }

    override fun onCreate(b: Bundle?) {
        Prefs.init(this)
        super.onCreate(b)
        items = Session.items.toMutableList()
        if (items.isEmpty()) { finish(); return }
        setContentView(R.layout.activity_viewer)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        top = findViewById(R.id.topBar); bottom = findViewById(R.id.bottomBar)
        nameView = findViewById(R.id.name); nameView.useDisplay()
        heart = findViewById(R.id.btnFav)
        pager = findViewById(R.id.pager)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, ins ->
            val s = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            top.setPadding(0, s.top, 0, 0)
            bottom.setPadding(0, 0, 0, s.bottom)
            WindowInsetsCompat.CONSUMED
        }

        adapter = ViewerAdapter(items) { toggleBars() }
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        pager.setCurrentItem(intent.getIntExtra("index", 0).coerceIn(0, items.size - 1), false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { updateUi() }
        })

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnShare).setOnClickListener { Share.send(this, listOf(cur().uri)) }
        findViewById<View>(R.id.btnFav).setOnClickListener { toggleFav() }
        findViewById<View>(R.id.btnInfo).setOnClickListener { showDetails() }
        findViewById<View>(R.id.btnDelete).setOnClickListener { confirmDelete() }
        updateUi()
    }

    private fun cur() = items[pager.currentItem]

    private fun updateUi() {
        nameView.text = cur().name
        setHeart(Prefs.has("fav", "p${cur().id}"))
    }

    private fun setHeart(on: Boolean) {
        heart.setImageResource(if (on) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        heart.setColorFilter(if (on) Colors.HEART else Color.WHITE)
    }

    private fun toggleFav() {
        val on = Prefs.toggle("fav", "p${cur().id}")
        setHeart(on)
        heart.animate().scaleX(1.3f).scaleY(1.3f).setDuration(110).withEndAction {
            heart.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
        }.start()
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        for (v in listOf(top, bottom)) {
            if (barsVisible) { v.visibility = View.VISIBLE; v.animate().alpha(1f).setDuration(150).start() }
            else v.animate().alpha(0f).setDuration(150).withEndAction { v.visibility = View.INVISIBLE }.start()
        }
    }

    private fun showDetails() {
        val m = cur()
        val date = SimpleDateFormat("EEE, d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(m.date))
        MaterialAlertDialogBuilder(this).setTitle("Details")
            .setMessage("Name: ${m.name}\nDate: $date\nResolution: ${m.w} × ${m.h}\nSize: ${Formatter.formatShortFileSize(this, m.size)}\nAlbum: ${m.bucket}")
            .setPositiveButton("OK", null).show()
    }

    private fun confirmDelete() {
        val uris = listOf(cur().uri)
        pending = uris
        val go = {
            val sender = Deleter.delete(this, uris)
            if (sender != null) deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            else removeCurrent()
        }
        if (Build.VERSION.SDK_INT >= 30) go()
        else MaterialAlertDialogBuilder(this).setMessage("Delete this photo?")
            .setPositiveButton("Delete") { _, _ -> go() }.setNegativeButton("Cancel", null).show()
    }

    private fun removeCurrent() {
        val p = pager.currentItem
        items.removeAt(p)
        adapter.notifyItemRemoved(p)
        if (items.isEmpty()) finish() else updateUi()
    }

    companion object {
        fun intent(c: Context, index: Int) = Intent(c, ViewerActivity::class.java).putExtra("index", index)
    }
}

class ViewerAdapter(private val items: List<Media>, private val onTap: () -> Unit) :
    RecyclerView.Adapter<ViewerAdapter.VH>() {

    class VH(val pv: PhotoView) : RecyclerView.ViewHolder(pv)

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val pv = PhotoView(p.context)
        pv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        pv.maximumScale = 6f
        return VH(pv)
    }

    override fun onBindViewHolder(h: VH, i: Int) {
        Glide.with(h.pv).load(items[i].uri).override(2400, 2400).into(h.pv)
        h.pv.setOnPhotoTapListener { _, _, _ -> onTap() }
        h.pv.setOnOutsidePhotoTapListener { onTap() }
    }

    override fun onViewRecycled(h: VH) { Glide.with(h.pv).clear(h.pv) }
    override fun getItemCount() = items.size
}