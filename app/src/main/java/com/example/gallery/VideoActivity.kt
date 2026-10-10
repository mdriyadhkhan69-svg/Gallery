package com.example.gallery

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

class VideoActivity : AppCompatActivity() {

    private var vv: VideoView? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val uri = intent.data
        if (uri == null) { finish(); return }

        val video = VideoView(this)
        vv = video
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(
            video,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )
        setContentView(root)

        val mc = MediaController(this)
        mc.setAnchorView(video)
        video.setMediaController(mc)
        video.setOnPreparedListener { it.start() }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Can't play this video", Toast.LENGTH_SHORT).show()
            finish()
            true
        }
        video.setVideoURI(uri)
    }

    override fun onPause() {
        super.onPause()
        vv?.pause()
    }

    companion object {
        fun intent(c: Context, uri: Uri) = Intent(c, VideoActivity::class.java).setData(uri)
    }
}