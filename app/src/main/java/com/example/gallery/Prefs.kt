package com.example.gallery

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("gallery", Context.MODE_PRIVATE)
    }

    var tab: Int
        get() = sp.getInt("tab", 0)
        set(v) = sp.edit().putInt("tab", v).apply()

    var columns: Int
        get() = sp.getInt("cols", 4).coerceIn(2, 7)
        set(v) = sp.edit().putInt("cols", v.coerceIn(2, 7)).apply()

    var sort: Int   // 0 newest, 1 oldest, 2 name
        get() = sp.getInt("sort", 0)
        set(v) = sp.edit().putInt("sort", v).apply()

    var albumSort: Int  // 0 recent, 1 name, 2 count
        get() = sp.getInt("albumSort", 0)
        set(v) = sp.edit().putInt("albumSort", v).apply()

    fun getSet(k: String): Set<String> = HashSet(sp.getStringSet(k, emptySet())!!)
    fun setSet(k: String, s: Set<String>) = sp.edit().putStringSet(k, HashSet(s)).apply()
    fun has(k: String, v: String): Boolean = sp.getStringSet(k, emptySet())!!.contains(v)

    fun toggle(k: String, v: String): Boolean {
        val s = getSet(k).toMutableSet()
        val on = s.add(v)
        if (!on) s.remove(v)
        setSet(k, s)
        return on
    }

    fun add(k: String, v: String) { setSet(k, getSet(k) + v) }

    fun getStr(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    fun putStr(k: String, v: String) = sp.edit().putString(k, v).apply()
}