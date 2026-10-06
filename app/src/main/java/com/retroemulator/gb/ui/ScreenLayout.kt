package com.retroemulator.gb.ui

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.min

/** Decides where the 160x144 picture goes; shared by the game view and the on-screen controls. */
object ScreenLayout {
    const val GB_WIDTH = 160f
    const val GB_HEIGHT = 144f

    /** Fills [out] with the screen rectangle and returns true for landscape layouts. */
    fun compute(width: Int, height: Int, safe: Rect, integerScale: Boolean, density: Float, out: RectF): Boolean {
        val landscape = width > height
        val left = safe.left.toFloat()
        val top = safe.top.toFloat()
        val right = (width - safe.right).toFloat()
        val bottom = (height - safe.bottom).toFloat()
        val availW = right - left
        val availH = bottom - top

        var scale: Float
        if (landscape) {
            val margin = 4 * density
            scale = (availH - 2 * margin) / GB_HEIGHT
            // Leave at least some room on the sides for the overlay controls on squarish screens.
            scale = min(scale, availW * 0.72f / GB_WIDTH)
        } else {
            val margin = 16 * density
            scale = (availW - 2 * margin) / GB_WIDTH
            // Keep at least half of the screen for the controls.
            scale = min(scale, availH * 0.48f / GB_HEIGHT)
        }
        if (integerScale && scale >= 1f) scale = floor(scale)
        val w = GB_WIDTH * scale
        val h = GB_HEIGHT * scale
        val x = left + (availW - w) / 2f
        val y = if (landscape) top + (availH - h) / 2f else top + 28 * density
        out.set(x, y, x + w, y + h)
        return landscape
    }
}
