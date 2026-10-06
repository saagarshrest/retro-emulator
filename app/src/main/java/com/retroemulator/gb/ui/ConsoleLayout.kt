package com.retroemulator.gb.ui

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry of the on-screen handheld, shared by the skin, the game view and the controls.
 *
 * Everything is designed on a grid of "art pixels" of [u] screen pixels: portrait layouts are 120
 * units wide, landscape layouts 100 units tall. [u] is an integer so the pixel art stays crisp.
 */
class ConsoleLayout {
    var valid = false
        private set
    var landscape = false
        private set
    var u = 1f
        private set
    private var ox = 0f
    private var oy = 0f

    val body = RectF()
    val tab = RectF()
    val frame = RectF()
    /** The LCD area (black); the picture is drawn into [screen] inside it. */
    val gameArea = RectF()
    val screen = RectF()
    /** Decorative strip below the LCD (portrait only; may be empty). */
    val scene = RectF()

    val dpad = PointF()
    /** Half length of the d-pad cross, in units. */
    var dpadArm = 0
        private set
    val btnA = PointF()
    val btnB = PointF()
    /** A/B button diameter in units. */
    var buttonSize = 0
        private set
    val select = RectF()
    val start = RectF()
    val menu = PointF()
    val fastForward = PointF()
    val barcode = RectF()
    val speaker = RectF()

    class Decoration(val kind: Int, val x: Float, val y: Float, val size: Int)
    val decorations = ArrayList<Decoration>()

    private fun px(x: Float) = ox + x * u
    private fun py(y: Float) = oy + y * u
    private fun rectU(out: RectF, l: Float, t: Float, r: Float, b: Float) = out.set(px(l), py(t), px(r), py(b))
    private fun pointU(out: PointF, x: Float, y: Float) = out.set(px(x), py(y))

    fun compute(width: Int, height: Int, safe: Rect, integerScale: Boolean) {
        if (width <= 0 || height <= 0) return
        decorations.clear()
        landscape = width > height * 1.25f
        if (landscape) computeLandscape(width, height, safe) else computePortrait(width, height, safe)

        // Integer scaling shrinks the picture inside the LCD to a whole multiple of 160x144.
        val s = floor(min(gameArea.width() / 160f, gameArea.height() / 144f))
        if (integerScale && s >= 1f) {
            val w = 160f * s
            val h = 144f * s
            val l = floor(gameArea.centerX() - w / 2f)
            val t = floor(gameArea.centerY() - h / 2f)
            screen.set(l, t, l + w, t + h)
        } else {
            screen.set(gameArea)
        }
        valid = true
    }

    private fun computePortrait(width: Int, height: Int, safe: Rect) {
        u = max(2f, floor(width / 120f))
        ox = floor((width - 120f * u) / 2f)
        oy = 0f
        val units = height / u
        val top = safe.top / u + 3f
        val bottom = units - safe.bottom / u - 3f
        val border = 2f
        val frameTop = top + 15f
        val controlsMin = 82f
        val sceneMin = 12f
        val sceneMax = 46f

        var lcdW = 98f
        var lcdH = lcdW * 0.9f
        val avail = bottom - frameTop - 2 * border - 5f
        if (avail - lcdH - sceneMin < controlsMin) {
            lcdH = max(40f, avail - sceneMin - controlsMin)
            lcdW = lcdH / 0.9f
        }
        val sceneH = (avail - lcdH - controlsMin).coerceIn(sceneMin, sceneMax)
        val controlsH = max(avail - lcdH - sceneH, 60f)

        rectU(body, 4f, top + 6f, 116f, bottom)
        rectU(tab, 14f, top, 106f, top + 11f)
        val half = lcdW / 2f
        rectU(frame, 60f - half - border, frameTop, 60f + half + border, frameTop + 2 * border + lcdH + sceneH)
        rectU(gameArea, 60f - half, frameTop + border, 60f + half, frameTop + border + lcdH)
        rectU(scene, 60f - half, frameTop + border + lcdH, 60f + half, frameTop + border + lcdH + sceneH)

        // Controls, laid out like the reference handheld: d-pad left, B/A right, pills between.
        val cTop = frameTop + 2 * border + lcdH + sceneH + 3f
        val arm = (controlsH * 0.18f).coerceIn(12f, 18f).toInt()
        dpadArm = arm
        val cluster = 8f + 2 * arm + 26f
        val rowY = cTop + 8f + arm + max(0f, controlsH - cluster) * 0.42f
        pointU(dpad, 32f, rowY)
        buttonSize = (arm * 0.95f).toInt().coerceIn(12, 17)
        pointU(btnB, 73f, rowY + 3f)
        pointU(btnA, 93f, rowY - 4f)
        val pillY = rowY + arm + 8f
        rectU(select, 43.5f, pillY - 3f, 56.5f, pillY + 3f)
        rectU(start, 60.5f, pillY - 3f, 73.5f, pillY + 3f)
        rectU(speaker, 85f, pillY - 4f, 105f, pillY + 6f)
        pointU(menu, 17f, pillY + 6f)
        pointU(fastForward, 19f, cTop + 5f)
        rectU(barcode, 80f, cTop + 1.5f, 106f, cTop + 5.5f)

        val frameMidY = frameTop + (lcdH + sceneH) * 0.3f
        decorations += Decoration(COIN, px(15f), py(top + 1.5f), 9)
        decorations += Decoration(HEART, px(5f), py(frameMidY), 1)
        decorations += Decoration(SPARKLE, px(1.5f), py(frameMidY - 3f), 1)
        decorations += Decoration(COIN, px(5f), py(rowY + 6f), 11)
        decorations += Decoration(COIN, px(7f), py(rowY - 8f), 8)
        decorations += Decoration(SPARKLE, px(3f), py(rowY - 18f), 1)
        decorations += Decoration(SPARKLE, px(14.5f), py(rowY + 1f), 1)
    }

    private fun computeLandscape(width: Int, height: Int, safe: Rect) {
        u = max(2f, floor(height / 100f))
        ox = 0f
        oy = floor((height - 100f * u) / 2f)
        val units = width / u
        val left = safe.left / u + 3f
        val right = units - safe.right / u - 3f
        val top = 4f + safe.top / u
        val bottom = 96f - safe.bottom / u
        val border = 2f
        val zoneMin = 46f

        var lcdH = (bottom - top) - 2 * border - 8f
        var lcdW = lcdH / 0.9f
        val maxW = (right - left) - 2 * zoneMin - 2 * border
        if (lcdW > maxW) {
            lcdW = max(40f, maxW)
            lcdH = lcdW * 0.9f
        }
        val mid = (left + right) / 2f
        val cy = (top + bottom) / 2f

        rectU(body, left, top, right, bottom)
        tab.setEmpty()
        scene.setEmpty()
        rectU(frame, mid - lcdW / 2f - border, cy - lcdH / 2f - border, mid + lcdW / 2f + border, cy + lcdH / 2f + border)
        rectU(gameArea, mid - lcdW / 2f, cy - lcdH / 2f, mid + lcdW / 2f, cy + lcdH / 2f)

        val zoneL = (left + mid - lcdW / 2f - border) / 2f
        val zoneR = (mid + lcdW / 2f + border + right) / 2f
        val zoneW = (mid - lcdW / 2f - border) - left
        val arm = (zoneW * 0.24f).coerceIn(11f, 17f).toInt()
        dpadArm = arm
        pointU(dpad, zoneL, cy + 2f)
        buttonSize = (arm * 0.95f).toInt().coerceIn(11, 16)
        pointU(btnB, zoneR - buttonSize * 0.62f, cy + 5f)
        pointU(btnA, zoneR + buttonSize * 0.62f, cy - 3f)
        val pillY = bottom - 14f
        rectU(select, zoneL - 6.5f, pillY - 3f, zoneL + 6.5f, pillY + 3f)
        rectU(start, zoneR - 6.5f, pillY - 3f, zoneR + 6.5f, pillY + 3f)
        pointU(menu, left + 8f, top + 8f)
        pointU(fastForward, right - 10f, top + 8f)
        barcode.setEmpty()
        rectU(speaker, right - 25f, bottom - 26f, right - 7f, bottom - 18f)

        decorations += Decoration(COIN, px(left + 1f), py(bottom - 6f), 10)
        decorations += Decoration(COIN, px(left + 5f), py(bottom - 15f), 7)
        decorations += Decoration(SPARKLE, px(left + 12f), py(bottom - 9f), 1)
        decorations += Decoration(HEART, px(right - 1f), py(top + 26f), 1)
        decorations += Decoration(SPARKLE, px(right - 0.5f), py(top + 20f), 1)
    }

    companion object {
        const val COIN = 0
        const val HEART = 1
        const val SPARKLE = 2
    }
}
