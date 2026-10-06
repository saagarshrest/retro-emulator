package com.retroemulator.gb.ui.pixel

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * A pixel-art panel with stair-stepped corners, an ink outline and a hard drop shadow. When the view
 * is pressed the panel sinks into its shadow.
 */
class PixelBoxDrawable(
    private val unit: Float,
    private val steps: Int,
    private val fillTop: Int,
    private val fillBottom: Int = fillTop,
    private val shadow: Int = 0,
    private val pressedTop: Int = fillTop,
    private val pressedBottom: Int = pressedTop,
    private val outline: Int = PixelPainter.INK,
) : Drawable() {
    private val painter = PixelPainter()
    private var pressed = false

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = if (shadow != 0) unit else 0f
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right - s
        val bt = b.bottom - s
        if (pressed && s > 0f) {
            painter.box(canvas, l + s, t + s, r + s, bt + s, unit, steps, pressedTop, pressedBottom, outline)
        } else {
            painter.box(canvas, l, t, r, bt, unit, steps,
                if (pressed) pressedTop else fillTop, if (pressed) pressedBottom else fillBottom, outline, shadow = shadow)
        }
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        if (p == pressed) return false
        pressed = p
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Library background: dotted sky with a couple of clouds and a strip of grass and dirt at the bottom. */
class SkyDrawable(private val unit: Float) : Drawable() {
    private val painter = PixelPainter()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val u = unit
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right.toFloat()
        val bt = b.bottom.toFloat()
        painter.box(canvas, l, t, r, bt, u, 0, SKY_TOP, SKY_BOTTOM, outlineUnits = 0)

        var y = t + 3 * u
        var row = 0
        while (y < bt) {
            var x = l + (if (row % 2 == 0) 3 else 6) * u
            while (x < r) {
                painter.rect(canvas, x, y, x + u, y + u, DOT)
                x += 6 * u
            }
            y += 5 * u
            row++
        }

        val w = r - l
        val h = bt - t
        painter.sprite(canvas, CLOUD, CLOUD_PALETTE, l + w * 0.05f, t + h * 0.30f, u)
        painter.sprite(canvas, CLOUD, CLOUD_PALETTE, l + w * 0.62f, t + h * 0.52f, u, flip = true)

        // Ground strip.
        val ground = bt - 9 * u
        painter.rect(canvas, l, ground, r, bt, DIRT)
        painter.rect(canvas, l, ground, r, ground + 3 * u, GRASS)
        var x = l
        var k = 0
        while (x < r) {
            painter.rect(canvas, x, ground - u, min(x + u, r), ground, if (k % 3 == 0) GRASS_LIGHT else GRASS)
            painter.rect(canvas, x + u, ground + 3 * u, min(x + 3 * u, r), ground + 4 * u, GRASS_DARK)
            painter.rect(canvas, x + 2 * u, ground + 4 * u, min(x + 3 * u, r), ground + 5 * u, GRASS_DARK)
            if (k % 2 == 1) painter.rect(canvas, x + u, bt - 2 * u, min(x + 2 * u, r), bt - u, DIRT_DARK)
            x += 4 * u
            k++
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    companion object {
        private const val SKY_TOP = 0xFFFFFDF2.toInt()
        private const val SKY_BOTTOM = 0xFFFFEFB5.toInt()
        private const val DOT = 0xFFFCEAB0.toInt()
        private const val GRASS = 0xFF7ED957.toInt()
        private const val GRASS_LIGHT = 0xFFA6E87C.toInt()
        private const val GRASS_DARK = 0xFF5CBF3F.toInt()
        private const val DIRT = 0xFF9E6038.toInt()
        private const val DIRT_DARK = 0xFF7B4526.toInt()
        private val CLOUD_PALETTE = mapOf('C' to 0xFFFFFFFF.toInt(), 'c' to 0xFFD3E9F7.toInt())
        private val CLOUD = arrayOf(
            "....CCCC..........",
            "..CCCCCCCC..CCC...",
            ".CCCCCCCCCCCCCCCC.",
            "CCCCCCCCCCCCCCCCCC",
            "cccccccccccccccccc",
        )
    }
}
