package com.retroemulator.gb.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.floor

/**
 * Displays emulator frames. Frames arrive from the emulator thread via [submitFrame]; drawing happens
 * on the UI thread at display refresh, so a slow frame never stalls emulation.
 */
class GameView(context: Context) : View(context) {
    private val bitmap = Bitmap.createBitmap(160, 144, Bitmap.Config.ARGB_8888)
    private val pending = IntArray(160 * 144)
    private var hasPending = false
    private val frameLock = Any()

    private val density = resources.displayMetrics.density
    private val pixelPaint = Paint().apply { isFilterBitmap = false }
    private val fpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13 * density
        typeface = Typeface.create(context.resources.getFont(com.retroemulator.gb.R.font.pixel), Typeface.BOLD)
        setShadowLayer(3 * density, 0f, 0f, Color.BLACK)
    }

    /** Where the 160x144 picture is drawn; supplied by [ConsoleLayout]. */
    val screenRect = RectF()

    var smoothScaling = false
        set(v) { field = v; pixelPaint.isFilterBitmap = v; invalidate() }
    var fpsText: String? = null
        set(v) { field = v; postInvalidateOnAnimation() }

    init {
        bitmap.eraseColor(Color.BLACK)
    }

    fun setScreen(rect: RectF) {
        screenRect.set(rect)
        invalidate()
    }

    /** Called from the emulator thread. */
    fun submitFrame(pixels: IntArray) {
        synchronized(frameLock) {
            System.arraycopy(pixels, 0, pending, 0, pending.size)
            hasPending = true
        }
        postInvalidateOnAnimation()
    }

    // "Sharp bilinear": pre-scale by an integer factor with nearest neighbour, then let the GPU filter
    // the small remaining fraction. Pixels stay crisp but uniformly sized at non-integer scales.
    private var sharpBitmap: Bitmap? = null
    private var sharpPixels = IntArray(0)
    private var sharpRow = IntArray(0)
    private var lastFactor = 0
    private val sharpPaint = Paint().apply { isFilterBitmap = true }

    private fun sharpFactor(): Int {
        if (smoothScaling) return 0
        val scale = screenRect.width() / 160f
        if (scale < 1.5f || abs(scale - Math.round(scale)) < 0.01f) return 0
        return floor(scale).toInt().coerceIn(2, 8)
    }

    private fun upscale(n: Int) {
        val w = 160 * n
        var big = sharpBitmap
        if (big == null || big.width != w) {
            big = Bitmap.createBitmap(w, 144 * n, Bitmap.Config.ARGB_8888)
            sharpBitmap = big
            sharpPixels = IntArray(w * 144 * n)
            sharpRow = IntArray(w)
        }
        val out = sharpPixels
        val row = sharpRow
        for (y in 0 until 144) {
            val src = y * 160
            var o = 0
            for (x in 0 until 160) {
                val c = pending[src + x]
                for (k in 0 until n) row[o++] = c
            }
            val base = y * n * w
            for (k in 0 until n) System.arraycopy(row, 0, out, base + k * w, w)
        }
        big!!.setPixels(out, 0, w, 0, 0, w, 144 * n)
    }

    override fun onDraw(canvas: Canvas) {
        val factor = sharpFactor()
        synchronized(frameLock) {
            if (factor != lastFactor) {
                // Scale changed (rotation, settings): rebuild from the last frame.
                hasPending = true
                lastFactor = factor
            }
            if (hasPending) {
                if (factor > 1) upscale(factor) else bitmap.setPixels(pending, 0, 160, 0, 0, 160, 144)
                hasPending = false
            }
        }
        if (screenRect.isEmpty) return
        val big = sharpBitmap
        if (factor > 1 && big != null) {
            canvas.drawBitmap(big, null, screenRect, sharpPaint)
        } else {
            canvas.drawBitmap(bitmap, null, screenRect, pixelPaint)
        }
        fpsText?.let {
            canvas.drawText(it, screenRect.left + 6 * density, screenRect.top + 16 * density, fpsPaint)
        }
    }
}
