package com.example.gallery

import android.app.Application
import android.content.ContentUris
import android.database.ContentObserver
import android.net.Uri
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
    /** Photo + video, hidden album bade, newest first */
    val all: LiveData<List<Media>> = _all
    val loaded = MutableLiveData(false)

    private var raw: List<Media> = Library.snapshot
    private var storyCache: List<Story>? = null
    private var job: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { load(500) }
    }

    init {
        val cr = app.contentResolver
        cr.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        cr.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        if (raw.isNotEmpty()) { publish(); loaded.value = true }
    }

    fun load(delayMs: Long = 0) {
        job?.cancel()
        job = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            val res = withContext(Dispatchers.IO) { query() }
            if (res != null) {
                raw = res
                Library.snapshot = res
                publish()
                loaded.value = true
            }
        }
    }

    fun publish() {
        val hidden = Prefs.getSet("hidden")
        storyCache = null
        _all.value = if (hidden.isEmpty()) raw else raw.filter { it.bucketId.toString() !in hidden }
    }

    /** Files table theke image + video ek query te. Files table e id unique, tai photo/video id er clash hoy na. */
    private fun query(): List<Media>? {
        val out = ArrayList<Media>()
        val files = MediaStore.Files.getContentUri("external")
        val imgBase = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val vidBase = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val typeCol = MediaStore.Files.FileColumns.MEDIA_TYPE
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
            typeCol, MediaStore.Video.Media.DURATION
        )
        val sel = "$typeCol IN (?, ?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        try {
            getApplication<Application>().contentResolver
                .query(files, proj, sel, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                    val iId = c.getColumnIndexOrThrow(proj[0]); val iName = c.getColumnIndexOrThrow(proj[1])
                    val iTaken = c.getColumnIndexOrThrow(proj[2]); val iAdded = c.getColumnIndexOrThrow(proj[3])
                    val iBid = c.getColumnIndexOrThrow(proj[4]); val iBname = c.getColumnIndexOrThrow(proj[5])
                    val iSize = c.getColumnIndexOrThrow(proj[6]); val iW = c.getColumnIndexOrThrow(proj[7])
                    val iH = c.getColumnIndexOrThrow(proj[8]); val iType = c.getColumnIndexOrThrow(proj[9])
                    val iDur = c.getColumnIndexOrThrow(proj[10])
                    while (c.moveToNext()) {
                        val id = c.getLong(iId)
                        val video = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                        val taken = c.getLong(iTaken)
                        val date = if (taken > 0) taken else c.getLong(iAdded) * 1000
                        out.add(
                            Media(
                                id, ContentUris.withAppendedId(if (video) vidBase else imgBase, id),
                                c.getString(iName) ?: (if (video) "Video" else "Image"), date,
                                c.getLong(iBid), c.getString(iBname) ?: "Unknown",
                                c.getLong(iSize), c.getInt(iW), c.getInt(iH),
                                video, if (video) c.getLong(iDur) else 0L
                            )
                        )
                    }
                }
        } catch (e: Exception) {
            android.util.Log.e("GalleryDbg", "query failed", e)
            return null
        }
        out.sortByDescending { it.date }
        return out
    }

    private fun albumsOf(list: List<Media>): List<Album> =
        list.groupBy { it.bucketId }.map { (id, l) -> Album(id, l[0].bucket, l.size, l[0].uri, l[0].date) }

    /** Device album + app-managed (custom) album. Custom album er id negative. */
    fun albums(): List<Album> {
        val list = _all.value.orEmpty()
        val custom = CustomAlbums.all().map { c ->
            val m = list.filter { it.id in c.ids }
            val cover = m.firstOrNull { it.id == c.cover } ?: m.firstOrNull()
            if (cover == null) Album(c.id, c.name, 0, Uri.EMPTY, 0L)
            else Album(c.id, c.name, m.size, cover.uri, m.first().date)
        }
        return albumsOf(list) + custom
    }

    fun rawAlbums(): List<Album> = albumsOf(raw)

    private fun buildStories(): List<Story> {
        val all = _all.value.orEmpty()
        val groups = LinkedHashMap<String, MutableList<Media>>()
        for (m in all) groups.getOrPut(monthKey(m.date)) { ArrayList() }.add(m)
        val auto = groups.filter { it.value.size >= 5 }.map { (k, l) ->
            Story(k, monthTitle(l.first().date), rangeLabel(l.last().date, l.first().date), l[l.size / 2].uri, l.size)
        }
        val custom = CustomStories.all().mapNotNull { c ->
            val m = all.filter { it.id in c.ids }
            if (m.isEmpty()) null
            else {
                val cover = m.firstOrNull { it.id == c.cover } ?: m.first()
                Story("u${c.id}", c.name, rangeLabel(m.last().date, m.first().date), cover.uri, m.size)
            }
        }
        return custom + auto
    }

    fun stories(): List<Story> {
        storyCache?.let { return it }
        val hidden = Prefs.getSet("hiddenStories")
        val res = buildStories().filter { it.key !in hidden }
        storyCache = res
        return res
    }

    fun allStories(): List<Story> = buildStories()

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }
}