package com.retroemulator.gb.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.retroemulator.gb.core.Joypad
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-touch on-screen controls. In portrait the controls sit below the screen; in landscape they are
 * drawn translucently on both sides of it. Fingers can slide between buttons.
 */
class ControllerView(context: Context) : View(context) {

    interface Listener {
        fun onButtonsChanged(mask: Int)
        fun onMenu()
        fun onFastForward()
    }

    var listener: Listener? = null
    var hapticsEnabled = true
    var overlayOpacity = 0.7f
        set(v) { field = v; invalidate() }
    var fastForwardActive = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density

    // Geometry
    private val dpad = PointF()
    private var dpadR = 0f
    private val btnA = PointF()
    private val btnB = PointF()
    private var abR = 0f
    private val selectRect = RectF()
    private val startRect = RectF()
    private val menuBtn = PointF()
    private val ffBtn = PointF()
    private var smallR = 0f
    private var landscape = false
    private var ready = false
    private val screen = RectF()
    private var safe = Rect()

    // Touch state
    private val pointerButtons = SparseIntArray()
    private val pointerSpecial = SparseIntArray()
    private var mask = 0

    // Paints
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val tmpRect = RectF()

    fun layoutAround(screenRect: RectF, isLandscape: Boolean, safeInsets: Rect) {
        screen.set(screenRect)
        landscape = isLandscape
        safe = Rect(safeInsets)
        computeGeometry()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeGeometry()
    }

    private fun computeGeometry() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f || screen.isEmpty) return
        val left = safe.left.toFloat()
        val right = w - safe.right
        if (!landscape) {
            val top = screen.bottom + 18 * d
            val bottom = h - safe.bottom - 12 * d
            val areaW = right - left
            val cx = (left + right) / 2f
            smallR = 18 * d
            menuBtn.set(cx - smallR * 1.6f, top + smallR * 1.2f)
            ffBtn.set(cx + smallR * 1.6f, top + smallR * 1.2f)

            // The d-pad / A-B row and Start/Select are centred in the space below the menu row.
            val clusterTop = menuBtn.y + smallR * 1.6f
            val avail = bottom - clusterTop
            dpadR = min(areaW * 0.21f, avail * 0.3f)
            abR = dpadR * 0.42f
            val pillH = max(dpadR * 0.16f, 14 * d)
            val gap = dpadR * 0.45f
            val clusterH = dpadR * 2.16f + gap + pillH * 2.6f
            val start = clusterTop + max(0f, (avail - clusterH) * 0.45f)
            val rowY = start + dpadR * 1.08f
            dpad.set(left + areaW * 0.06f + dpadR, rowY)
            val abCx = right - areaW * 0.06f - dpadR
            btnA.set(abCx + abR * 1.2f, rowY - abR * 0.7f)
            btnB.set(abCx - abR * 1.2f, rowY + abR * 0.7f)
            val pillW = dpadR * 0.6f
            val pillY = rowY + dpadR * 1.08f + gap + pillH / 2
            selectRect.set(cx - pillW * 1.25f, pillY - pillH / 2, cx - pillW * 0.25f, pillY + pillH / 2)
            startRect.set(cx + pillW * 0.25f, pillY - pillH / 2, cx + pillW * 1.25f, pillY + pillH / 2)
        } else {
            val zoneW = max(screen.left - left, 0f)
            dpadR = min(h * 0.19f, max(zoneW * 0.38f, h * 0.13f))
            abR = dpadR * 0.42f
            smallR = max(dpadR * 0.2f, 17 * d)
            val y = h * 0.55f
            val inset = max(zoneW / 2f, dpadR * 1.2f)
            dpad.set(left + inset, y)
            val abCx = right - inset
            btnA.set(abCx + abR * 1.2f, y - abR * 0.7f)
            btnB.set(abCx - abR * 1.2f, y + abR * 0.7f)
            val pillW = dpadR * 0.6f
            val pillH = max(dpadR * 0.16f, 14 * d)
            val pillY = min(h - safe.bottom - pillH * 1.6f, y + dpadR * 1.7f)
            selectRect.set(dpad.x - pillW / 2, pillY - pillH / 2, dpad.x + pillW / 2, pillY + pillH / 2)
            startRect.set(abCx - pillW / 2, pillY - pillH / 2, abCx + pillW / 2, pillY + pillH / 2)
            menuBtn.set(left + smallR * 1.8f, safe.top + smallR * 1.8f)
            ffBtn.set(right - smallR * 1.8f, safe.top + smallR * 1.8f)
        }
        ready = true
        invalidate()
    }

    // ------------------------------------------------------------------------------------------
    // Touch handling
    // ------------------------------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!ready) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val special = hitSpecial(e.getX(i), e.getY(i))
                if (special != 0) {
                    pointerSpecial.put(id, special)
                    if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                } else {
                    pointerButtons.put(id, hitButtons(e.getX(i), e.getY(i)))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    if (pointerSpecial.indexOfKey(id) < 0) pointerButtons.put(id, hitButtons(e.getX(i), e.getY(i)))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val special = pointerSpecial.get(id, 0)
                if (special != 0 && hitSpecial(e.getX(i), e.getY(i)) == special) {
                    if (special == SPECIAL_MENU) listener?.onMenu() else listener?.onFastForward()
                }
                pointerSpecial.delete(id)
                pointerButtons.delete(id)
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerSpecial.clear()
                pointerButtons.clear()
            }
        }
        var newMask = 0
        for (i in 0 until pointerButtons.size()) newMask = newMask or pointerButtons.valueAt(i)
        if (newMask != mask) {
            if (hapticsEnabled && newMask and mask.inv() != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            mask = newMask
            listener?.onButtonsChanged(mask)
            invalidate()
        }
        return true
    }

    /** Releases everything (e.g. when a dialog takes focus mid-press). */
    fun releaseAll() {
        pointerButtons.clear()
        pointerSpecial.clear()
        if (mask != 0) {
            mask = 0
            listener?.onButtonsChanged(0)
            invalidate()
        }
    }

    private fun hitSpecial(x: Float, y: Float): Int {
        val r = smallR * 1.5f
        if (hypot(x - menuBtn.x, y - menuBtn.y) <= r) return SPECIAL_MENU
        if (hypot(x - ffBtn.x, y - ffBtn.y) <= r) return SPECIAL_FF
        return 0
    }

    private fun hitButtons(x: Float, y: Float): Int {
        val padX = startRect.height() * 0.6f
        val padY = startRect.height() * 0.9f
        if (x >= selectRect.left - padX && x <= selectRect.right + padX && y >= selectRect.top - padY && y <= selectRect.bottom + padY) return Joypad.SELECT
        if (x >= startRect.left - padX && x <= startRect.right + padX && y >= startRect.top - padY && y <= startRect.bottom + padY) return Joypad.START

        var m = 0
        val dx = x - dpad.x
        val dy = y - dpad.y
        val dist = hypot(dx, dy)
        if (dist <= dpadR * 1.45f && dist >= dpadR * 0.12f) {
            val ang = Math.toDegrees(atan2(dy, dx).toDouble())
            if (ang > -67.5 && ang < 67.5) m = m or Joypad.RIGHT
            if (ang > 22.5 && ang < 157.5) m = m or Joypad.DOWN
            if (ang > 112.5 || ang < -112.5) m = m or Joypad.LEFT
            if (ang > -157.5 && ang < -22.5) m = m or Joypad.UP
            return m
        }
        val hit = abR * 1.3f
        if (hypot(x - btnA.x, y - btnA.y) <= hit) m = m or Joypad.A
        if (hypot(x - btnB.x, y - btnB.y) <= hit) m = m or Joypad.B
        val mx = (btnA.x + btnB.x) / 2f
        val my = (btnA.y + btnB.y) / 2f
        if (hypot(x - mx, y - my) <= abR * 0.5f) m = m or Joypad.A or Joypad.B
        return m
    }

    // ------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------

    private fun color(c: Int): Int {
        if (!landscape) return c
        val a = (Color.alpha(c) * overlayOpacity).toInt()
        return (c and 0x00FFFFFF) or (a shl 24)
    }

    override fun onDraw(canvas: Canvas) {
        if (!ready) return
        drawDpad(canvas)
        drawRoundButton(canvas, btnB, "B", mask and Joypad.B != 0)
        drawRoundButton(canvas, btnA, "A", mask and Joypad.A != 0)
        drawPill(canvas, selectRect, "SELECT", mask and Joypad.SELECT != 0)
        drawPill(canvas, startRect, "START", mask and Joypad.START != 0)
        drawMenuButton(canvas)
        drawFastForwardButton(canvas)
    }

    private fun drawDpad(canvas: Canvas) {
        val cx = dpad.x
        val cy = dpad.y
        val r = dpadR
        val arm = r * 0.35f
        val corner = arm * 0.4f

        fill.color = color(PLATE)
        canvas.drawCircle(cx, cy, r * 1.08f, fill)

        fill.color = color(DPAD)
        tmpRect.set(cx - r, cy - arm, cx + r, cy + arm)
        canvas.drawRoundRect(tmpRect, corner, corner, fill)
        tmpRect.set(cx - arm, cy - r, cx + arm, cy + r)
        canvas.drawRoundRect(tmpRect, corner, corner, fill)

        fill.color = color(DPAD_PRESSED)
        if (mask and Joypad.UP != 0) { tmpRect.set(cx - arm, cy - r, cx + arm, cy - arm * 0.2f); canvas.drawRoundRect(tmpRect, corner, corner, fill) }
        if (mask and Joypad.DOWN != 0) { tmpRect.set(cx - arm, cy + arm * 0.2f, cx + arm, cy + r); canvas.drawRoundRect(tmpRect, corner, corner, fill) }
        if (mask and Joypad.LEFT != 0) { tmpRect.set(cx - r, cy - arm, cx - arm * 0.2f, cy + arm); canvas.drawRoundRect(tmpRect, corner, corner, fill) }
        if (mask and Joypad.RIGHT != 0) { tmpRect.set(cx + arm * 0.2f, cy - arm, cx + r, cy + arm); canvas.drawRoundRect(tmpRect, corner, corner, fill) }

        fill.color = color(PLATE)
        canvas.drawCircle(cx, cy, arm * 0.45f, fill)

        fill.color = color(ICON)
        val t = arm * 0.42f
        val o = r * 0.72f
        triangle(canvas, cx, cy - o, t, 0)
        triangle(canvas, cx, cy + o, t, 2)
        triangle(canvas, cx - o, cy, t, 3)
        triangle(canvas, cx + o, cy, t, 1)
    }

    /** Draws a small arrow pointing up (0), right (1), down (2) or left (3). */
    private fun triangle(canvas: Canvas, x: Float, y: Float, s: Float, dir: Int) {
        path.reset()
        when (dir) {
            0 -> { path.moveTo(x, y - s * 0.6f); path.lineTo(x + s * 0.7f, y + s * 0.4f); path.lineTo(x - s * 0.7f, y + s * 0.4f) }
            2 -> { path.moveTo(x, y + s * 0.6f); path.lineTo(x + s * 0.7f, y - s * 0.4f); path.lineTo(x - s * 0.7f, y - s * 0.4f) }
            3 -> { path.moveTo(x - s * 0.6f, y); path.lineTo(x + s * 0.4f, y - s * 0.7f); path.lineTo(x + s * 0.4f, y + s * 0.7f) }
            else -> { path.moveTo(x + s * 0.6f, y); path.lineTo(x - s * 0.4f, y - s * 0.7f); path.lineTo(x - s * 0.4f, y + s * 0.7f) }
        }
        path.close()
        canvas.drawPath(path, fill)
    }

    private fun drawRoundButton(canvas: Canvas, c: PointF, label: String, pressed: Boolean) {
        fill.color = color(PLATE)
        canvas.drawCircle(c.x, c.y + abR * 0.08f, abR * 1.06f, fill)
        fill.color = color(if (pressed) AB_PRESSED else AB)
        canvas.drawCircle(c.x, c.y, abR, fill)
        text.color = color(Color.WHITE)
        text.textSize = abR * 0.8f
        canvas.drawText(label, c.x, c.y - (text.descent() + text.ascent()) / 2f, text)
    }

    private fun drawPill(canvas: Canvas, r: RectF, label: String, pressed: Boolean) {
        fill.color = color(if (pressed) DPAD_PRESSED else DPAD)
        val rad = r.height() / 2f
        canvas.drawRoundRect(r, rad, rad, fill)
        text.color = color(LABEL)
        text.textSize = max(r.height() * 0.62f, 10 * d)
        canvas.drawText(label, r.centerX(), r.bottom + text.textSize * 1.25f, text)
    }

    private fun drawMenuButton(canvas: Canvas) {
        fill.color = color(SMALL)
        canvas.drawCircle(menuBtn.x, menuBtn.y, smallR, fill)
        fill.color = color(ICON)
        val w = smallR * 0.5f
        val h = smallR * 0.09f
        for (k in -1..1) {
            val y = menuBtn.y + k * smallR * 0.3f
            tmpRect.set(menuBtn.x - w, y - h, menuBtn.x + w, y + h)
            canvas.drawRoundRect(tmpRect, h, h, fill)
        }
    }

    private fun drawFastForwardButton(canvas: Canvas) {
        fill.color = color(if (fastForwardActive) ACCENT else SMALL)
        canvas.drawCircle(ffBtn.x, ffBtn.y, smallR, fill)
        fill.color = color(if (fastForwardActive) Color.WHITE else ICON)
        val s = smallR * 0.32f
        for (k in 0..1) {
            val x = ffBtn.x - s * 0.55f + k * s * 1.1f
            path.reset()
            path.moveTo(x - s * 0.55f, ffBtn.y - s)
            path.lineTo(x + s * 0.65f, ffBtn.y)
            path.lineTo(x - s * 0.55f, ffBtn.y + s)
            path.close()
            canvas.drawPath(path, fill)
        }
    }

    companion object {
        private const val SPECIAL_MENU = 1
        private const val SPECIAL_FF = 2

        private val PLATE = Color.parseColor("#1A1A21")
        private val DPAD = Color.parseColor("#3A3A47")
        private val DPAD_PRESSED = Color.parseColor("#5E5E75")
        private val AB = Color.parseColor("#A3285F")
        private val AB_PRESSED = Color.parseColor("#D24A87")
        private val SMALL = Color.parseColor("#2C2C37")
        private val ICON = Color.parseColor("#C9C9D6")
        private val LABEL = Color.parseColor("#8F8FA3")
        private val ACCENT = Color.parseColor("#7C6CF2")
    }
}
