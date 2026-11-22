package com.kilon.agentvscreen.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.sqrt

/**
 * [vscreen v34] Pixel helpers for `color:x,y=#RRGGBB[~tol]` conditions and region clipping.
 */
internal object VPixels {
    fun dist(a: Int, b: Int): Double {
        val dr = (Color.red(a) - Color.red(b)).toDouble()
        val dg = (Color.green(a) - Color.green(b)).toDouble()
        val db = (Color.blue(a) - Color.blue(b)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    fun clip(r: Rect, w: Int, h: Int): Rect =
        Rect(r.left.coerceIn(0, w), r.top.coerceIn(0, h), r.right.coerceIn(0, w), r.bottom.coerceIn(0, h))

    /** Mean colour of the (2r+1)² square around (x, y). */
    fun at(bmp: Bitmap, x: Int, y: Int, r: Int = 2): Int {
        var sr = 0; var sg = 0; var sb = 0; var n = 0
        for (dy in -r..r) for (dx in -r..r) {
            val px = (x + dx).coerceIn(0, bmp.width - 1)
            val py = (y + dy).coerceIn(0, bmp.height - 1)
            val c = bmp.getPixel(px, py)
            sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); n++
        }
        return Color.rgb(sr / n, sg / n, sb / n)
    }

    fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    fun parseColor(s: String): Int? {
        val t = s.trim().removePrefix("#")
        if (t.length != 6) return null
        return t.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
    }
}
