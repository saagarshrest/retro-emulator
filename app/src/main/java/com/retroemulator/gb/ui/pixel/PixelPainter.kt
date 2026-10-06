package com.retroemulator.gb.ui.pixel

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader

/**
 * Helpers for drawing chunky pixel art at an arbitrary art-pixel size [u] (in screen pixels).
 * Shapes are built from axis-aligned edges only, so they stay crisp at any scale.
 */
class PixelPainter {
    private val paint = Paint().apply { isAntiAlias = false }
    private val path = Path()

    /** Builds a rectangle whose corners are cut into [steps] one-unit stairs. */
    fun steppedRect(p: Path, l: Float, t: Float, r: Float, b: Float, u: Float, steps: Int) {
        p.reset()
        val n = steps.coerceAtLeast(0)
        p.moveTo(l + n * u, t)
        p.lineTo(r - n * u, t)
        for (i in 0 until n) {
            val x = r - (n - i) * u
            val y = t + (i + 1) * u
            p.lineTo(x, y); p.lineTo(x + u, y)
        }
        p.lineTo(r, b - n * u)
        for (i in 0 until n) {
            val x = r - (i + 1) * u
            val y = b - (n - i) * u
            p.lineTo(x, y); p.lineTo(x, y + u)
        }
        p.lineTo(l + n * u, b)
        for (i in 0 until n) {
            val x = l + (n - i) * u
            val y = b - (i + 1) * u
            p.lineTo(x, y); p.lineTo(x - u, y)
        }
        p.lineTo(l, t + n * u)
        for (i in 0 until n) {
            val x = l + (i + 1) * u
            val y = t + (n - i) * u
            p.lineTo(x, y); p.lineTo(x, y - u)
        }
        p.close()
    }

    /**
     * Draws a pixel box: optional drop shadow, an outline [outlineUnits] thick and a vertical gradient fill.
     * Coordinates are in screen pixels.
     */
    fun box(
        canvas: Canvas, l: Float, t: Float, r: Float, b: Float, u: Float, steps: Int,
        fillTop: Int, fillBottom: Int = fillTop, outline: Int = INK, outlineUnits: Int = 1,
        shadow: Int = 0, shadowUnits: Float = 1f,
    ) {
        paint.shader = null
        if (shadow != 0) {
            val s = shadowUnits * u
            steppedRect(path, l + s, t + s, r + s, b + s, u, steps)
            paint.color = shadow
            canvas.drawPath(path, paint)
        }
        if (outlineUnits > 0) {
            steppedRect(path, l, t, r, b, u, steps)
            paint.color = outline
            canvas.drawPath(path, paint)
        }
        val o = outlineUnits * u
        steppedRect(path, l + o, t + o, r - o, b - o, u, steps - outlineUnits)
        fill(fillTop, fillBottom, t + o, b - o)
        canvas.drawPath(path, paint)
        paint.shader = null
    }

    /** Plain axis-aligned rectangle in screen pixels. */
    fun rect(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        paint.shader = null
        paint.color = color
        canvas.drawRect(l, t, r, b, paint)
    }

    private fun fill(top: Int, bottom: Int, y0: Float, y1: Float) {
        if (top == bottom) {
            paint.shader = null
            paint.color = top
        } else {
            paint.color = 0xFF000000.toInt()
            paint.shader = LinearGradient(0f, y0, 0f, y1, top, bottom, Shader.TileMode.CLAMP)
        }
    }

    /**
     * Draws a pixel disc of [diameter] units centred on ([cx], [cy]), with a 1-unit outline and an
     * optional highlight crescent in the upper-left.
     */
    fun disc(
        canvas: Canvas, cx: Float, cy: Float, diameter: Int, u: Float,
        fillColor: Int, outline: Int = INK, highlight: Int = 0, shade: Int = 0,
    ) {
        paint.shader = null
        val d = diameter
        val rad = d / 2f
        val left = cx - rad * u
        val top = cy - rad * u
        fun inside(i: Int, j: Int): Boolean {
            if (i < 0 || j < 0 || i >= d || j >= d) return false
            val dx = i + 0.5f - rad
            val dy = j + 0.5f - rad
            return dx * dx + dy * dy <= rad * rad
        }
        for (j in 0 until d) {
            var runStart = -1
            var runColor = 0
            for (i in 0..d) {
                var c = 0
                if (i < d && inside(i, j)) {
                    val edge = !inside(i - 1, j) || !inside(i + 1, j) || !inside(i, j - 1) || !inside(i, j + 1)
                    val dx = i + 0.5f - rad
                    val dy = j + 0.5f - rad
                    c = when {
                        edge -> outline
                        highlight != 0 && dx < -rad * 0.15f && dy < -rad * 0.15f &&
                            (dx + rad * 0.45f) * (dx + rad * 0.45f) + (dy + rad * 0.45f) * (dy + rad * 0.45f) < rad * rad * 0.12f -> highlight
                        shade != 0 && dx + dy > rad * 0.75f -> shade
                        else -> fillColor
                    }
                }
                if (c != runColor || i == d) {
                    if (runColor != 0) {
                        paint.color = runColor
                        canvas.drawRect(left + runStart * u, top + j * u, left + i * u, top + (j + 1) * u, paint)
                    }
                    runStart = i
                    runColor = c
                }
            }
        }
    }

    /** Draws a sprite given as rows of palette characters ('.' or ' ' is transparent). */
    fun sprite(canvas: Canvas, rows: Array<String>, palette: Map<Char, Int>, x: Float, y: Float, u: Float, flip: Boolean = false) {
        paint.shader = null
        for ((j, row) in rows.withIndex()) {
            val w = row.length
            var i = 0
            while (i < w) {
                val ch = row[if (flip) w - 1 - i else i]
                val color = palette[ch]
                if (color == null) { i++; continue }
                var end = i + 1
                while (end < w && row[if (flip) w - 1 - end else end] == ch) end++
                paint.color = color
                canvas.drawRect(x + i * u, y + j * u, x + end * u, y + (j + 1) * u, paint)
                i = end
            }
        }
    }

    companion object {
        const val INK = 0xFF1E1A16.toInt()
    }
}
