package com.retroemulator.gb.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1C1C24") }
    private val fpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13 * density
        typeface = Typeface.MONOSPACE
        setShadowLayer(3 * density, 0f, 0f, Color.BLACK)
    }

    val screenRect = RectF()
    private val bezelRect = RectF()
    private var landscape = false
    private var safeInsets = Rect()

    var integerScaling = false
        set(v) { field = v; relayout() }
    var smoothScaling = false
        set(v) { field = v; pixelPaint.isFilterBitmap = v; invalidate() }
    var fpsText: String? = null
        set(v) { field = v; postInvalidateOnAnimation() }

    /** Invoked after the screen rectangle changes so the controls can lay out around it. */
    var onLayoutChanged: ((RectF, Boolean) -> Unit)? = null

    init {
        bitmap.eraseColor(Color.BLACK)
    }

    fun setSafeInsets(insets: Rect) {
        safeInsets = Rect(insets)
        relayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
    }

    private fun relayout() {
        if (width == 0 || height == 0) return
        landscape = ScreenLayout.compute(width, height, safeInsets, integerScaling, density, screenRect)
        val pad = 10 * density
        bezelRect.set(screenRect.left - pad, screenRect.top - pad, screenRect.right + pad, screenRect.bottom + pad)
        onLayoutChanged?.invoke(screenRect, landscape)
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
        if (!landscape) {
            val r = 14 * density
            canvas.drawRoundRect(bezelRect, r, r, bezelPaint)
        }
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
