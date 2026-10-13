package com.example.gallery

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** App-managed album / story. Shudhu photo id list save hoy, original photo kokhono delete hoy na. */
class Coll(val id: Long, var name: String, val ids: MutableSet<Long>, var cover: Long = 0L)

/** MainActivity ke bole custom data change hoyeche, refresh dorkar */
object Changes { @Volatile var dirty = false }

/** Custom album id hocche -currentTimeMillis (Int range er baire). Device bucket id Int range er moddhe, negative-o hote pare. */
fun isCustomId(id: Long): Boolean = id < Int.MIN_VALUE.toLong()

const val RECOVER_NAME = "Recover Images"

class CollStore(private val key: String) {

    fun all(): MutableList<Coll> {
        val out = ArrayList<Coll>()
        try {
            val arr = JSONArray(Prefs.getStr(key, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ids = LinkedHashSet<Long>()
                val a = o.getJSONArray("ids")
                for (j in 0 until a.length()) ids.add(a.getLong(j))
                out.add(Coll(o.getLong("id"), o.getString("name"), ids, o.optLong("cover", 0L)))
            }
        } catch (e: Exception) { /* corrupt data hole ignore */ }
        return out
    }

    private fun save(l: List<Coll>) {
        val arr = JSONArray()
        l.forEach { c ->
            arr.put(JSONObject().put("id", c.id).put("name", c.name).put("cover", c.cover).put("ids", JSONArray(c.ids.toList())))
        }
        Prefs.putStr(key, arr.toString())
        Changes.dirty = true
    }

    private fun edit(id: Long, f: (Coll) -> Unit) {
        val l = all()
        l.firstOrNull { it.id == id }?.let { f(it); save(l) }
    }

    fun get(id: Long): Coll? = all().firstOrNull { it.id == id }

    fun create(name: String, ids: Collection<Long>): Long {
        val l = all()
        val id = -System.currentTimeMillis()   // negative id: device album id er sathe mile jabe na
        l.add(Coll(id, name, LinkedHashSet(ids)))
        save(l)
        return id
    }

    fun rename(id: Long, name: String) = edit(id) { it.name = name }
    fun add(id: Long, ids: Collection<Long>) = edit(id) { it.ids.addAll(ids) }
    fun remove(id: Long, ids: Collection<Long>) = edit(id) {
        it.ids.removeAll(ids.toSet())
        if (it.cover in ids) it.cover = 0L
    }
    fun setCover(id: Long, mediaId: Long) = edit(id) { it.cover = mediaId }

    fun delete(id: Long) {
        val l = all()
        if (l.removeAll { it.id == id }) save(l)
    }
}

val CustomAlbums = CollStore("calbums")
val CustomStories = CollStore("cstories")


/** edited image uri -> original image uri (original file is never overwritten; edits are saved as copies) */
object OriginalStore {
    private const val KEY = "origmap"
    private fun load(): JSONObject = try { JSONObject(Prefs.getStr(KEY, "{}")) } catch (e: Exception) { JSONObject() }

    fun get(edited: Uri): Uri? {
        val s = load().optString(edited.toString(), "")
        return if (s.isEmpty()) null else Uri.parse(s)
    }

    fun put(edited: Uri, original: Uri) {
        Prefs.putStr(KEY, load().put(edited.toString(), original.toString()).toString())
    }
}


/** Device album er display name override. Original folder/file kokhono rename hoy na. */
object AlbumNames {
    private const val KEY = "albumNames"
    private fun load(): JSONObject = try { JSONObject(Prefs.getStr(KEY, "{}")) } catch (e: Exception) { JSONObject() }

    fun get(id: Long): String? {
        val s = load().optString(id.toString(), "")
        return if (s.isEmpty()) null else s
    }

    fun put(id: Long, name: String) {
        Prefs.putStr(KEY, load().put(id.toString(), name).toString())
        Changes.dirty = true
    }
}