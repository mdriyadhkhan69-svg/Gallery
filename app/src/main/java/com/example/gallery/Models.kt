package com.example.gallery

import android.net.Uri

data class Media(
    val id: Long, val uri: Uri, val name: String, val date: Long,
    val bucketId: Long, val bucket: String, val size: Long, val w: Int, val h: Int
)

data class Album(val id: Long, val name: String, val count: Int, val cover: Uri, val latest: Long)

data class Story(val key: String, val title: String, val range: String, val cover: Uri, val count: Int)

sealed class Row {
    data class Header(val title: String) : Row()
    data class Photo(val m: Media, val index: Int) : Row()
}

/** Viewer e list pass korar jonno (Intent e boro list pass kora jay na) */
object Session { var items: List<Media> = emptyList() }