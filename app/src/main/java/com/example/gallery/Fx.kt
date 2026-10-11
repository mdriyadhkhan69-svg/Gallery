package com.example.gallery

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

object Fx {
    val FILTER_NAMES = listOf("Original", "Mono", "Noir", "Warm", "Cool", "Vintage", "Vivid", "Fade", "Dramatic")
    val ADJ_NAMES = arrayOf("Brightness", "Contrast", "Saturation", "Warmth", "Tint", "Fade")
    val ADJ_MIN = intArrayOf(-100, -100, -100, -100, -100, 0)

    private fun cm(vararg a: Float) = ColorMatrix(a)

    private fun sat(s: Float) = ColorMatrix().apply { setSaturation(s) }

    private fun contrast(k: Float): ColorMatrix {
        val t = 128f * (1f - k)
        return cm(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)
    }

    private fun bright(o: Float) = cm(1f, 0f, 0f, 0f, o, 0f, 1f, 0f, 0f, o, 0f, 0f, 1f, 0f, o, 0f, 0f, 0f, 1f, 0f)

    private fun rgb(r: Float, g: Float, b: Float) = cm(r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

    private fun chain(vararg l: ColorMatrix): ColorMatrix {
        val r = ColorMatrix()
        l.forEach { r.postConcat(it) }
        return r
    }

    private fun filterMatrix(i: Int): ColorMatrix? = when (i) {
        1 -> sat(0f)
        2 -> chain(sat(0f), contrast(1.35f))
        3 -> chain(rgb(1.1f, 1.02f, 0.88f), bright(6f))
        4 -> chain(rgb(0.9f, 1f, 1.12f))
        5 -> chain(sat(0.6f), rgb(1.12f, 1f, 0.82f), contrast(0.9f), bright(10f))
        6 -> chain(sat(1.45f), contrast(1.1f))
        7 -> chain(sat(0.8f), contrast(0.85f), bright(16f))
        8 -> chain(contrast(1.35f), sat(0.85f), bright(-8f))
        else -> null
    }

    private fun adjMatrix(a: IntArray): ColorMatrix? {
        if (a.all { it == 0 }) return null
        val f = a[5] / 100f
        val fs = 1f - f * 0.2f
        return chain(
            bright(a[0] * 0.7f),
            contrast(1f + a[1] / 100f * 0.6f),
            sat(1f + a[2] / 100f),
            rgb(1f + a[3] / 100f * 0.15f, 1f, 1f - a[3] / 100f * 0.15f),
            rgb(1f, 1f - a[4] / 100f * 0.12f, 1f),
            rgb(fs, fs, fs),
            bright(f * 35f)
        )
    }

    fun build(filter: Int, adj: IntArray): ColorMatrixColorFilter? {
        val f = filterMatrix(filter)
        val a = adjMatrix(adj)
        if (f == null && a == null) return null
        val m = ColorMatrix()
        f?.let { m.postConcat(it) }
        a?.let { m.postConcat(it) }
        return ColorMatrixColorFilter(m)
    }

    fun thumbFilter(i: Int): ColorMatrixColorFilter? = filterMatrix(i)?.let { ColorMatrixColorFilter(it) }
}