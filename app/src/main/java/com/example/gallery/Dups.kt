package com.example.gallery

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Exact duplicate finder: same size + same SHA-256 of full file content. Hashes are cached on disk. */
object Dups {
    @Volatile var groupOf: Map<Long, String> = emptyMap()
    @Volatile var groups: Map<String, List<Long>> = emptyMap()
    @Volatile var ready = false

    private val cache = ConcurrentHashMap<String, String>()
    private var cacheLoaded = false

    fun countOf(id: Long): Int {
        val k = groupOf[id] ?: return 0
        return groups[k]?.size ?: 0
    }

    suspend fun compute(ctx: Context, list: List<Media>) {
        val app = ctx.applicationContext
        loadCache(app)
        val cand = list.filter { it.size > 0 }.groupBy { it.size }.filter { it.value.size > 1 }
        val byHash = HashMap<String, MutableList<Media>>()
        var changed = false
        for ((size, l) in cand) {
            for (m in l) {
                coroutineContext.ensureActive()
                val ck = "${m.id}|${m.size}"
                var h = cache[ck]
                if (h == null) {
                    h = hashOf(app, m.uri) ?: continue
                    cache[ck] = h
                    changed = true
                }
                byHash.getOrPut("$size:$h") { ArrayList() }.add(m)
            }
        }
        val g = HashMap<String, List<Long>>()
        val of = HashMap<Long, String>()
        for ((k, l) in byHash) {
            if (l.size < 2) continue
            val ids = l.sortedByDescending { it.date }.map { it.id }
            g[k] = ids
            ids.forEach { of[it] = k }
        }
        groups = g
        groupOf = of
        ready = true
        if (changed) saveCache(app)
    }

    private fun hashOf(ctx: Context, u: Uri): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val s = ctx.contentResolver.openInputStream(u) ?: return null
            s.use {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    private fun loadCache(app: Context) {
        if (cacheLoaded) return
        cacheLoaded = true
        try {
            val f = File(app.filesDir, "dup_cache.txt")
            if (f.exists()) f.forEachLine { ln ->
                val i = ln.indexOf('\t')
                if (i > 0) cache[ln.substring(0, i)] = ln.substring(i + 1)
            }
        } catch (e: Exception) { }
    }

    @Synchronized
    private fun saveCache(app: Context) {
        try {
            val f = File(app.filesDir, "dup_cache.txt")
            val tmp = File(app.filesDir, "dup_cache.tmp")
            tmp.bufferedWriter().use { w ->
                for ((k, v) in cache) { w.write(k); w.write("\t"); w.write(v); w.write("\n") }
            }
            tmp.renameTo(f)
        } catch (e: Exception) { }
    }
}