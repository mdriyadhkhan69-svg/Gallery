package com.example.gallery

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val _all = MutableLiveData<List<Media>>(emptyList())
    /** Hidden album bade sob chobi, newest first */
    val all: LiveData<List<Media>> = _all
    val loaded = MutableLiveData(false)

    private var raw: List<Media> = emptyList()
    private var storyCache: List<Story>? = null
    private var job: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { load(500) }
    }

    init {
        app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    fun load(delayMs: Long = 0) {
        job?.cancel()
        job = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            raw = withContext(Dispatchers.IO) { query() }
            publish()
            loaded.value = true
        }
    }

    fun publish() {
        val hidden = Prefs.getSet("hidden")
        storyCache = null
        _all.value = if (hidden.isEmpty()) raw else raw.filter { it.bucketId.toString() !in hidden }
    }

    private fun query(): List<Media> {
        val out = ArrayList<Media>()
        val uriBase = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT
        )
        try {
            getApplication<Application>().contentResolver
                .query(uriBase, proj, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                    val iId = c.getColumnIndexOrThrow(proj[0]); val iName = c.getColumnIndexOrThrow(proj[1])
                    val iTaken = c.getColumnIndexOrThrow(proj[2]); val iAdded = c.getColumnIndexOrThrow(proj[3])
                    val iBid = c.getColumnIndexOrThrow(proj[4]); val iBname = c.getColumnIndexOrThrow(proj[5])
                    val iSize = c.getColumnIndexOrThrow(proj[6]); val iW = c.getColumnIndexOrThrow(proj[7])
                    val iH = c.getColumnIndexOrThrow(proj[8])
                    while (c.moveToNext()) {
                        val id = c.getLong(iId)
                        val taken = c.getLong(iTaken)
                        val date = if (taken > 0) taken else c.getLong(iAdded) * 1000
                        out.add(
                            Media(
                                id, android.content.ContentUris.withAppendedId(uriBase, id),
                                c.getString(iName) ?: "Image", date,
                                c.getLong(iBid), c.getString(iBname) ?: "Unknown",
                                c.getLong(iSize), c.getInt(iW), c.getInt(iH)
                            )
                        )
                    }
                }
        } catch (e: SecurityException) { /* permission nai */ }
        out.sortByDescending { it.date }
        return out
    }

    private fun albumsOf(list: List<Media>): List<Album> =
        list.groupBy { it.bucketId }.map { (id, l) -> Album(id, l[0].bucket, l.size, l[0].uri, l[0].date) }

    fun albums(): List<Album> = albumsOf(_all.value.orEmpty())
    fun rawAlbums(): List<Album> = albumsOf(raw)

    fun stories(): List<Story> {
        storyCache?.let { return it }
        val groups = LinkedHashMap<String, MutableList<Media>>()
        for (m in _all.value.orEmpty()) groups.getOrPut(monthKey(m.date)) { ArrayList() }.add(m)
        val res = groups.filter { it.value.size >= 5 }.map { (k, l) ->
            Story(k, monthTitle(l.first().date), rangeLabel(l.last().date, l.first().date), l[l.size / 2].uri, l.size)
        }
        storyCache = res
        return res
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }
}