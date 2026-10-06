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
 * The handheld's body fills the whole screen, and the screen frame takes all the width (portrait) or
 * height (landscape) it can. Everything is placed on a grid of "art pixels" of [u] screen pixels:
 * portrait layouts are 120 units wide, landscape layouts at least 100 units tall. [u] is an integer
 * so the pixel art stays crisp.
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
    /** GBA shoulder buttons; empty for the Game Boy. */
    val shoulderL = RectF()
    val shoulderR = RectF()
    val speaker = RectF()

    private fun px(x: Float) = ox + x * u
    private fun py(y: Float) = oy + y * u
    private fun rectU(out: RectF, l: Float, t: Float, r: Float, b: Float) = out.set(px(l), py(t), px(r), py(b))
    private fun pointU(out: PointF, x: Float, y: Float) = out.set(px(x), py(y))

    /** Height / width of the picture: 144/160 for the Game Boy, 160/240 for the GBA. */
    private var aspect = 0.9f

    fun compute(
        width: Int, height: Int, safe: Rect, integerScale: Boolean,
        srcW: Int = 160, srcH: Int = 144, shoulders: Boolean = false,
    ) {
        if (width <= 0 || height <= 0) return
        aspect = srcH.toFloat() / srcW
        shoulderL.setEmpty()
        shoulderR.setEmpty()
        body.set(0f, 0f, width.toFloat(), height.toFloat())
        landscape = width > height * 1.25f
        if (landscape) computeLandscape(width, height, safe, shoulders) else computePortrait(width, height, safe, shoulders)

        // Integer scaling shrinks the picture inside the LCD to a whole multiple of the source size.
        val s = floor(min(gameArea.width() / srcW, gameArea.height() / srcH))
        if (integerScale && s >= 1f) {
            val w = srcW * s
            val h = srcH * s
            val l = floor(gameArea.centerX() - w / 2f)
            val t = floor(gameArea.centerY() - h / 2f)
            screen.set(l, t, l + w, t + h)
        } else {
            screen.set(gameArea)
        }
        valid = true
    }

    private fun computePortrait(width: Int, height: Int, safe: Rect, shoulders: Boolean) {
        u = max(2f, floor(width / 120f))
        ox = floor((width - 120f * u) / 2f)
        oy = 0f
        val units = height / u
        val top = safe.top / u + 2f
        val bottom = units - safe.bottom / u - 2f
        val border = 2f
        val frameTop = top + 12f
        val controlsMin = 80f
        val sceneMax = 40f

        // The screen frame spans the full width; the picture only shrinks if the controls need room.
        var lcdW = 120f - 2 * border
        var lcdH = lcdW * aspect
        val avail = bottom - frameTop - 2 * border - 3f
        if (avail - lcdH < controlsMin) {
            lcdH = max(40f, avail - controlsMin)
            lcdW = lcdH / aspect
        }
        // Leftover height goes to the park scene under the picture, if there's enough for it.
        var sceneH = min(avail - lcdH - controlsMin, sceneMax)
        if (sceneH < 12f) sceneH = 0f
        val controlsH = max(avail - lcdH - sceneH, 60f)

        rectU(tab, 14f, top, 106f, top + 10f)
        val half = lcdW / 2f
        rectU(frame, 60f - half - border, frameTop, 60f + half + border, frameTop + 2 * border + lcdH + sceneH)
        rectU(gameArea, 60f - half, frameTop + border, 60f + half, frameTop + border + lcdH)
        if (sceneH > 0f) {
            rectU(scene, 60f - half, frameTop + border + lcdH, 60f + half, frameTop + border + lcdH + sceneH)
        } else {
            scene.setEmpty()
        }

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
        if (shoulders) {
            // L and R sit above the d-pad and the face buttons; fast-forward moves to the middle.
            rectU(shoulderL, 7f, cTop, 31f, cTop + 7f)
            rectU(shoulderR, 89f, cTop, 113f, cTop + 7f)
            pointU(fastForward, 60f, cTop + 4.5f)
            barcode.setEmpty()
        }
    }

    private fun computeLandscape(width: Int, height: Int, safe: Rect, shoulders: Boolean) {
        u = max(2f, floor(height / 100f))
        ox = 0f
        oy = 0f
        val left = safe.left / u + 2f
        val right = width / u - safe.right / u - 2f
        val top = safe.top / u + 2f
        val bottom = height / u - safe.bottom / u - 2f
        val border = 2f
        val zoneMin = 40f

        // The screen frame spans the full height unless the side controls need more width.
        var lcdH = (bottom - top) - 2 * border
        var lcdW = lcdH / aspect
        val maxW = (right - left) - 2 * zoneMin - 2 * border
        if (lcdW > maxW) {
            lcdW = max(40f, maxW)
            lcdH = lcdW * aspect
        }
        val mid = (left + right) / 2f
        val cy = (top + bottom) / 2f

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
        if (shoulders) {
            rectU(shoulderL, zoneL - 11f, top + 19f, zoneL + 11f, top + 26f)
            rectU(shoulderR, zoneR - 11f, top + 19f, zoneR + 11f, top + 26f)
        }
        rectU(speaker, right - 25f, bottom - 33f, right - 7f, bottom - 25f)
    }
}
