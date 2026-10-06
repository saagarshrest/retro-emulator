package com.retroemulator.gb.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.retroemulator.gb.R
import com.retroemulator.gb.ui.pixel.PixelPainter
import com.retroemulator.gb.ui.pixel.PixelPainter.Companion.INK
import kotlin.math.max
import kotlin.math.min

/** Static artwork of the handheld: body, name tab, screen frame and scenery. */
class ConsoleSkinView(context: Context) : View(context) {
    private val painter = PixelPainter()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(context.resources.getFont(R.font.pixel), Typeface.BOLD)
        color = INK
        textAlign = Paint.Align.CENTER
    }

    var consoleLayout: ConsoleLayout? = null
        set(v) { field = v; invalidate() }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BACKGROUND)
        val l = consoleLayout ?: return
        if (!l.valid) return
        val u = l.u
        drawBody(canvas, l, u)
        if (!l.tab.isEmpty) drawTab(canvas, l, u)
        drawFrame(canvas, l, u)
        if (!l.scene.isEmpty) drawScene(canvas, l, u)
        if (!l.barcode.isEmpty) drawBarcode(canvas, l, u)
        if (!l.speaker.isEmpty) drawSpeaker(canvas, l, u)
    }

    /** The body fills the whole screen edge to edge, so it has no outline or corners of its own. */
    private fun drawBody(canvas: Canvas, l: ConsoleLayout, u: Float) {
        val b = l.body
        painter.box(canvas, b.left, b.top, b.right, b.bottom, u, 0, BODY_TOP, BODY_BOTTOM, outlineUnits = 0)
    }

    private fun drawTab(canvas: Canvas, l: ConsoleLayout, u: Float) {
        val t = l.tab
        painter.box(canvas, t.left, t.top, t.right, t.bottom, u, 3, TAB_TOP, TAB_BOTTOM)
        val cy = t.centerY()
        labelPaint.textSize = 5.4f * u
        val text = context.getString(R.string.console_label)
        val textW = labelPaint.measureText(text)
        canvas.drawText(text, t.centerX(), cy - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint)
        // Three square dots either side of the label.
        val dot = max(2f, (u * 1.3f).toInt().toFloat())
        for (side in intArrayOf(-1, 1)) {
            for (k in 0 until 3) {
                val x = t.centerX() + side * (textW / 2f + 3f * u + k * 2.6f * u)
                painter.rect(canvas, x - dot / 2, cy - dot / 2, x + dot / 2, cy + dot / 2, INK)
            }
        }
    }

    private fun drawFrame(canvas: Canvas, l: ConsoleLayout, u: Float) {
        val f = l.frame
        painter.box(canvas, f.left, f.top, f.right, f.bottom, u, 3, INK, INK, INK, outlineUnits = 0)
        val g = l.gameArea
        painter.rect(canvas, g.left, g.top, g.right, g.bottom, LCD)
    }

    // ------------------------------------------------------------------------------------------
    // Scenery below the LCD: sky, clouds, trees, bushes, a bench, a kid with presents and the ground.
    // ------------------------------------------------------------------------------------------

    private fun drawScene(canvas: Canvas, l: ConsoleLayout, u: Float) {
        val s = l.scene
        val left = s.left
        val right = s.right
        val top = s.top + u // one-unit divider under the LCD
        val bottom = s.bottom
        painter.box(canvas, left, top, right, bottom, u, 0, SKY_TOP, SKY_BOTTOM, outlineUnits = 0)

        // Faint dotted sky texture.
        var y = top + 2 * u
        var row = 0
        while (y < bottom - u) {
            var x = left + (if (row % 2 == 0) 2 else 4) * u
            while (x < right - u) {
                painter.rect(canvas, x, y, x + u, y + u, SKY_DOT)
                x += 4 * u
            }
            y += 3 * u
            row++
        }

        val heightU = (bottom - top) / u
        val groundU = min(7f, heightU * 0.42f)
        val groundTop = bottom - groundU * u
        val widthU = (right - left) / u

        if (heightU >= 20) {
            drawCloud(canvas, left + 6 * u, top + 2 * u, u)
            drawCloud(canvas, right - 26 * u, top + 4 * u, u)
        }

        // Bushes sit behind everything on the ground line.
        val bushD = min(9, max(5, ((groundTop - top) / u * 0.45f).toInt()))
        for (bx in floatArrayOf(17f, 26f, widthU - 24f)) {
            painter.disc(canvas, left + bx * u, groundTop, bushD, u, BUSH, BUSH_DARK, BUSH_LIGHT)
        }

        // Trees at both ends.
        val avail = (groundTop - top) / u - 1f
        val canopy = (avail - 4f).toInt().coerceIn(5, 15)
        val trunkH = min(5f, max(2f, avail - canopy + 1f))
        for (tx in floatArrayOf(7f, widthU - 8f)) {
            val cx = left + tx * u
            painter.rect(canvas, cx - u, groundTop - trunkH * u, cx + u, groundTop, TRUNK)
            val cy = groundTop - trunkH * u - canopy / 2f * u + u
            painter.disc(canvas, cx, cy, canopy, u, LEAF, LEAF_DARK, LEAF_LIGHT)
            painter.rect(canvas, cx - 2 * u, cy - u, cx - u, cy, LEAF_SPOT)
            painter.rect(canvas, cx + u, cy + u, cx + 2 * u, cy + 2 * u, LEAF_SPOT)
        }

        // Bench, kid and presents standing on the grass.
        if (avail >= 6) {
            drawBench(canvas, left + 18 * u, groundTop, u)
            val kidX = left + (widthU * 0.6f).toInt() * u
            painter.sprite(canvas, KID, PALETTE, kidX, groundTop - KID.size * u, u)
            painter.sprite(canvas, GIFT, PALETTE, kidX + 11 * u, groundTop - GIFT.size * u, u)
            painter.sprite(canvas, SMALL_GIFT, PALETTE, kidX + 17 * u, groundTop - SMALL_GIFT.size * u, u)
        }

        // Ground: grass band with teeth biting into the dirt.
        painter.rect(canvas, left, groundTop, right, bottom, DIRT)
        painter.rect(canvas, left, groundTop, right, groundTop + 2 * u, GRASS)
        var x = left
        var k = 0
        while (x < right) {
            painter.rect(canvas, x, groundTop - u, min(x + u, right), groundTop, if (k % 3 == 0) GRASS_LIGHT else GRASS)
            painter.rect(canvas, x + u, groundTop + 2 * u, min(x + 3 * u, right), groundTop + 3 * u, GRASS_DARK)
            painter.rect(canvas, x + 2 * u, groundTop + 3 * u, min(x + 3 * u, right), groundTop + 4 * u, GRASS_DARK)
            if (k % 2 == 1 && groundU >= 6) painter.rect(canvas, x + u, bottom - 2 * u, min(x + 2 * u, right), bottom - u, DIRT_DARK)
            x += 4 * u
            k++
        }
    }

    private fun drawCloud(canvas: Canvas, x: Float, y: Float, u: Float) {
        painter.sprite(canvas, CLOUD, PALETTE, x, y, u)
    }

    private fun drawBench(canvas: Canvas, x: Float, groundTop: Float, u: Float) {
        painter.rect(canvas, x, groundTop - 5 * u, x + 11 * u, groundTop - 4 * u, WOOD)
        painter.rect(canvas, x, groundTop - 3 * u, x + 11 * u, groundTop - 2 * u, WOOD)
        painter.rect(canvas, x + u, groundTop - 5 * u, x + 2 * u, groundTop, WOOD_DARK)
        painter.rect(canvas, x + 9 * u, groundTop - 5 * u, x + 10 * u, groundTop, WOOD_DARK)
    }

    private fun drawBarcode(canvas: Canvas, l: ConsoleLayout, u: Float) {
        val b = l.barcode
        val half = max(1f, u / 2f)
        var x = b.left
        var i = 0
        while (x < b.right) {
            val w = BARCODE[i % BARCODE.size] * half
            if (i % 2 == 0) painter.rect(canvas, x, b.top, min(x + w, b.right), b.bottom, INK)
            x += w
            i++
        }
    }

    private fun drawSpeaker(canvas: Canvas, l: ConsoleLayout, u: Float) {
        val s = l.speaker
        val d = max(2f, (u * 1.5f).toInt().toFloat())
        for (row in 0 until 3) {
            for (col in 0 until 4) {
                val x = s.left + (col * 4.5f + (2 - row) * 1.5f) * u
                val y = s.top + row * 3.4f * u
                painter.rect(canvas, x, y, x + d, y + d, INK)
            }
        }
    }

    companion object {
        private const val BACKGROUND = 0xFFFFF8E6.toInt()
        private const val BODY_TOP = 0xFFFFD877.toInt()
        private const val BODY_BOTTOM = 0xFFFF9A3E.toInt()
        private const val TAB_TOP = 0xFFFFEDB0.toInt()
        private const val TAB_BOTTOM = 0xFFFFD45E.toInt()
        private const val LCD = 0xFF101010.toInt()
        private const val SKY_TOP = 0xFFFFFDF2.toInt()
        private const val SKY_BOTTOM = 0xFFFFF0B4.toInt()
        private const val SKY_DOT = 0xFFFDEDB0.toInt()
        private const val GRASS = 0xFF7ED957.toInt()
        private const val GRASS_LIGHT = 0xFFA6E87C.toInt()
        private const val GRASS_DARK = 0xFF5CBF3F.toInt()
        private const val DIRT = 0xFF9E6038.toInt()
        private const val DIRT_DARK = 0xFF7B4526.toInt()
        private const val LEAF = 0xFF8CD46B.toInt()
        private const val LEAF_DARK = 0xFF5FAF46.toInt()
        private const val LEAF_LIGHT = 0xFFB7E98E.toInt()
        private const val LEAF_SPOT = 0xFFE4F38A.toInt()
        private const val TRUNK = 0xFF8A4B2A.toInt()
        private const val BUSH = 0xFF76C95A.toInt()
        private const val BUSH_DARK = 0xFF58AE40.toInt()
        private const val BUSH_LIGHT = 0xFF9EDD7B.toInt()
        private const val WOOD = 0xFF8C5A2E.toInt()
        private const val WOOD_DARK = 0xFF5C3518.toInt()

        private val BARCODE = intArrayOf(2, 1, 1, 1, 3, 2, 1, 1, 2, 1, 1, 2, 3, 1, 1, 1, 2, 2, 1, 1, 3, 1, 2, 1)

        val PALETTE = mapOf(
            'K' to INK,
            'O' to 0xFFF39A1E.toInt(), // hair
            'o' to 0xFFD97B0E.toInt(),
            'S' to 0xFFFFDCAE.toInt(), // skin
            'E' to 0xFF3B2A1A.toInt(), // eyes
            'Y' to 0xFFFFC233.toInt(), // coat
            'y' to 0xFFE9A21E.toInt(),
            'B' to 0xFF8B4A1E.toInt(), // boots
            'R' to 0xFFE53935.toInt(), // gift
            'r' to 0xFFB71C1C.toInt(),
            'P' to 0xFFFFCDD2.toInt(), // ribbon
            'G' to 0xFFF7A33A.toInt(), // small gift
            'g' to 0xFFD9822B.toInt(),
            'C' to 0xFFFFFFFF.toInt(), // cloud
            'c' to 0xFFD3E9F7.toInt(),
        )

        val KID = arrayOf(
            "...OOO...",
            ".OOOOOOO.",
            "OOOOoOOOO",
            "OOSSSSSOO",
            ".OSESSES.",
            "..SSSSS..",
            ".YYYYYYY.",
            "SYYyYyYYS",
            ".YYYYYYY.",
            ".YY...YY.",
            ".BB...BB.",
        )
        val GIFT = arrayOf(
            ".P..P.",
            "..PP..",
            "RRPPRR",
            "RRPPRR",
            "rrPPrr",
        )
        val SMALL_GIFT = arrayOf(
            "GGgG",
            "GGgG",
            "ggg.",
        )
        val CLOUD = arrayOf(
            "....CCCC..........",
            "..CCCCCCCC..CCC...",
            ".CCCCCCCCCCCCCCCC.",
            "CCCCCCCCCCCCCCCCCC",
            "cccccccccccccccccc",
        )
    }
}
