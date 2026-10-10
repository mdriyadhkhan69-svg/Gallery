package com.example.gallery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity

class GridActivity : AppCompatActivity() {
    private val vm: GalleryViewModel by viewModels()

    override fun onCreate(b: Bundle?) {
        Prefs.init(this)
        super.onCreate(b)
        setContentView(R.layout.activity_grid)
        edgeToEdge(findViewById(R.id.root))
        vm.load()
        if (b == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, PicturesFragment.create(
                    intent.getStringExtra("mode") ?: "all",
                    intent.getStringExtra("param") ?: "",
                    intent.getStringExtra("title") ?: "",
                    true
                )).commit()
        }
    }

    companion object {
        fun intent(c: Context, mode: String, param: String, title: String) =
            Intent(c, GridActivity::class.java)
                .putExtra("mode", mode).putExtra("param", param).putExtra("title", title)
    }
}