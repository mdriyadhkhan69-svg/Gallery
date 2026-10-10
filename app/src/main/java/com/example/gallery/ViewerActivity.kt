package com.example.gallery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
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
    private lateinit var btnPlay: ImageView
    private var barsVisible = true
    private var pending: List<Uri> = emptyList()

    private val handler = Handler(Looper.getMainLooper())
    private var playing = false
    private val tick = object : Runnable {
        override fun run() {
            if (!playing || items.isEmpty()) return
            pager.setCurrentItem((pager.currentItem + 1) % items.size, true)
            handler.removeCallbacks(this)
            handler.postDelayed(this, 3000)
        }
    }

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
        btnPlay = findViewById(R.id.btnPlay)
        pager = findViewById(R.id.pager)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, ins ->
            val s = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            top.setPadding(0, s.top, 0, 0)
            bottom.setPadding(0, 0, 0, s.bottom)
            WindowInsetsCompat.CONSUMED
        }

        adapter = ViewerAdapter(items, { toggleBars() }, { m -> startActivity(VideoActivity.intent(this, m.uri)) })
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        pager.setCurrentItem(intent.getIntExtra("index", 0).coerceIn(0, items.size - 1), false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateUi()
                if (playing) { handler.removeCallbacks(tick); handler.postDelayed(tick, 3000) }
            }
        })

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnShare).setOnClickListener { Share.send(this, listOf(cur().uri)) }
        findViewById<View>(R.id.btnFav).setOnClickListener { toggleFav() }
        findViewById<View>(R.id.btnInfo).setOnClickListener { showDetails() }
        findViewById<View>(R.id.btnDelete).setOnClickListener { confirmDelete() }
        btnPlay.setOnClickListener { setPlaying(!playing) }
        updateUi()
        if (intent.getBooleanExtra("slideshow", false)) setPlaying(true)
    }

    override fun onStop() {
        super.onStop()
        if (::btnPlay.isInitialized) setPlaying(false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun setPlaying(on: Boolean) {
        playing = on
        handler.removeCallbacks(tick)
        btnPlay.setImageResource(if (on) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = if (on) "Pause slideshow" else "Play slideshow"
        if (on) {
            handler.postDelayed(tick, 3000)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (barsVisible) toggleBars()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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
        fun intent(c: Context, index: Int, slideshow: Boolean = false) =
            Intent(c, ViewerActivity::class.java).putExtra("index", index).putExtra("slideshow", slideshow)
    }
}

class ViewerAdapter(
    private val items: List<Media>,
    private val onTap: () -> Unit,
    private val onPlay: (Media) -> Unit
) : RecyclerView.Adapter<ViewerAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val pv: PhotoView = v.findViewById(R.id.pv)
        val play: ImageView = v.findViewById(R.id.play)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_page, p, false)
        val h = VH(v)
        h.pv.maximumScale = 6f
        return h
    }

    override fun onBindViewHolder(h: VH, i: Int) {
        val m = items[i]
        Glide.with(h.pv).load(m.uri).override(2400, 2400).into(h.pv)
        h.play.visibility = if (m.video) View.VISIBLE else View.GONE
        h.play.setOnClickListener { onPlay(m) }
        h.pv.setOnPhotoTapListener { _, _, _ -> onTap() }
        h.pv.setOnOutsidePhotoTapListener { onTap() }
    }

    override fun onViewRecycled(h: VH) { Glide.with(h.pv).clear(h.pv) }
    override fun getItemCount() = items.size
}