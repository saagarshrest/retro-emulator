package com.retroemulator.gb.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.retroemulator.gb.R
import com.retroemulator.gb.core.Joypad
import com.retroemulator.gb.emu.Buttons
import com.retroemulator.gb.ui.pixel.PixelPainter
import com.retroemulator.gb.ui.pixel.PixelPainter.Companion.INK
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * Multi-touch pixel-art controls drawn on the console body described by [ConsoleLayout].
 * Fingers can slide between buttons; hit areas are larger than the drawn buttons.
 */
class ControllerView(context: Context) : View(context) {

    interface Listener {
        fun onButtonsChanged(mask: Int)
        fun onMenu()
        fun onFastForward()
    }

    var listener: Listener? = null
    var hapticsEnabled = true
    var fastForwardActive = false
        set(v) { field = v; invalidate() }

    var consoleLayout: ConsoleLayout? = null
        set(v) { field = v; releaseAll(); invalidate() }

    private val painter = PixelPainter()
    private val font = Typeface.create(context.resources.getFont(R.font.pixel), Typeface.BOLD)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font
        textAlign = Paint.Align.CENTER
    }

    private val pointerButtons = SparseIntArray()
    private val pointerSpecial = SparseIntArray()
    private var mask = 0
    private var menuPressed = false
    private var ffPressed = false

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ------------------------------------------------------------------------------------------
    // Touch handling
    // ------------------------------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val l = consoleLayout ?: return false
        if (!l.valid) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val special = hitSpecial(l, e.getX(i), e.getY(i))
                if (special != 0) {
                    pointerSpecial.put(id, special)
                    if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                } else {
                    pointerButtons.put(id, hitButtons(l, e.getX(i), e.getY(i)))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    if (pointerSpecial.indexOfKey(id) < 0) pointerButtons.put(id, hitButtons(l, e.getX(i), e.getY(i)))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val special = pointerSpecial.get(id, 0)
                if (special != 0 && hitSpecial(l, e.getX(i), e.getY(i)) == special) {
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
        val menuNow = (0 until pointerSpecial.size()).any { pointerSpecial.valueAt(it) == SPECIAL_MENU }
        val ffNow = (0 until pointerSpecial.size()).any { pointerSpecial.valueAt(it) == SPECIAL_FF }
        var newMask = 0
        for (i in 0 until pointerButtons.size()) newMask = newMask or pointerButtons.valueAt(i)
        if (newMask != mask || menuNow != menuPressed || ffNow != ffPressed) {
            if (hapticsEnabled && newMask and mask.inv() != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val changed = newMask != mask
            mask = newMask
            menuPressed = menuNow
            ffPressed = ffNow
            if (changed) listener?.onButtonsChanged(mask)
            invalidate()
        }
        return true
    }

    /** Releases everything (e.g. when a dialog takes focus mid-press). */
    fun releaseAll() {
        pointerButtons.clear()
        pointerSpecial.clear()
        menuPressed = false
        ffPressed = false
        if (mask != 0) {
            mask = 0
            listener?.onButtonsChanged(0)
        }
        invalidate()
    }

    private fun hitSpecial(l: ConsoleLayout, x: Float, y: Float): Int {
        val r = 7.5f * l.u
        if (hypot(x - l.menu.x, y - l.menu.y) <= r) return SPECIAL_MENU
        if (hypot(x - l.fastForward.x, y - l.fastForward.y) <= r) return SPECIAL_FF
        return 0
    }

    private fun hitButtons(l: ConsoleLayout, x: Float, y: Float): Int {
        val u = l.u
        val pad = 3.5f * u
        if (!l.shoulderL.isEmpty) {
            val sl = l.shoulderL
            val sr = l.shoulderR
            if (x >= sl.left - pad && x <= sl.right + pad && y >= sl.top - pad && y <= sl.bottom + pad) return Buttons.L
            if (x >= sr.left - pad && x <= sr.right + pad && y >= sr.top - pad && y <= sr.bottom + pad) return Buttons.R
        }
        val s = l.select
        val st = l.start
        if (x >= s.left - pad && x <= s.right + pad && y >= s.top - pad && y <= s.bottom + pad * 1.6f) return Joypad.SELECT
        if (x >= st.left - pad && x <= st.right + pad && y >= st.top - pad && y <= st.bottom + pad * 1.6f) return Joypad.START

        val arm = l.dpadArm * u
        val dx = x - l.dpad.x
        val dy = y - l.dpad.y
        val dist = hypot(dx, dy)
        if (dist <= arm * 1.6f && dist >= arm * 0.12f) {
            val ang = Math.toDegrees(atan2(dy, dx).toDouble())
            var m = 0
            if (ang > -67.5 && ang < 67.5) m = m or Joypad.RIGHT
            if (ang > 22.5 && ang < 157.5) m = m or Joypad.DOWN
            if (ang > 112.5 || ang < -112.5) m = m or Joypad.LEFT
            if (ang > -157.5 && ang < -22.5) m = m or Joypad.UP
            return m
        }
        var m = 0
        val hit = l.buttonSize / 2f * u * 1.45f
        if (hypot(x - l.btnA.x, y - l.btnA.y) <= hit) m = m or Joypad.A
        if (hypot(x - l.btnB.x, y - l.btnB.y) <= hit) m = m or Joypad.B
        val mx = (l.btnA.x + l.btnB.x) / 2f
        val my = (l.btnA.y + l.btnB.y) / 2f
        if (hypot(x - mx, y - my) <= l.buttonSize * u * 0.28f) m = m or Joypad.A or Joypad.B
        return m
    }

    // ------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val l = consoleLayout ?: return
        if (!l.valid) return
        val u = l.u
        drawDpad(canvas, l, u)
        drawRoundButton(canvas, l.btnB, l.buttonSize, u, "B", BTN_B, BTN_B_LIGHT, BTN_B_DARK, mask and Joypad.B != 0)
        drawRoundButton(canvas, l.btnA, l.buttonSize, u, "A", BTN_A, BTN_A_LIGHT, BTN_A_DARK, mask and Joypad.A != 0)
        drawPill(canvas, l, l.select, u, context.getString(R.string.label_select), mask and Joypad.SELECT != 0)
        drawPill(canvas, l, l.start, u, context.getString(R.string.label_start), mask and Joypad.START != 0)
        if (!l.shoulderL.isEmpty) {
            drawShoulder(canvas, l.shoulderL, u, "L", mask and Buttons.L != 0)
            drawShoulder(canvas, l.shoulderR, u, "R", mask and Buttons.R != 0)
        }
        drawMenu(canvas, l.menu, u)
        drawFastForward(canvas, l.fastForward, u)
    }

    /** A wide pill with its label inside (the GBA shoulder buttons). */
    private fun drawShoulder(canvas: Canvas, r: android.graphics.RectF, u: Float, label: String, pressed: Boolean) {
        val shift = if (pressed) u else 0f
        painter.box(canvas, r.left + u, r.top + u, r.right + u, r.bottom + u, u, 2, SHADOW, outlineUnits = 0)
        painter.box(canvas, r.left + shift, r.top + shift, r.right + shift, r.bottom + shift, u, 2,
            if (pressed) PILL_PRESSED else PILL_TOP, if (pressed) PILL_PRESSED else PILL_BOTTOM)
        text.color = SHOULDER_LABEL
        text.textSize = 4.2f * u
        canvas.drawText(label, r.centerX() + shift, r.centerY() + shift - (text.descent() + text.ascent()) / 2f, text)
    }

    private fun drawDpad(canvas: Canvas, l: ConsoleLayout, u: Float) {
        val cx = l.dpad.x
        val cy = l.dpad.y
        val a = l.dpadArm
        val w = max(4, (a * 0.38f).toInt())
        val ar = a * u
        val wr = w * u
        // Shadow, outline and fill of the cross (both bars before the fills so the centre stays clean).
        painter.box(canvas, cx - ar + u, cy - wr + u, cx + ar + u, cy + wr + u, u, 1, SHADOW, outlineUnits = 0)
        painter.box(canvas, cx - wr + u, cy - ar + u, cx + wr + u, cy + ar + u, u, 1, SHADOW, outlineUnits = 0)
        painter.box(canvas, cx - ar, cy - wr, cx + ar, cy + wr, u, 1, INK, outlineUnits = 0)
        painter.box(canvas, cx - wr, cy - ar, cx + wr, cy + ar, u, 1, INK, outlineUnits = 0)
        painter.rect(canvas, cx - ar + u, cy - wr + u, cx + ar - u, cy + wr - u, DPAD)
        painter.rect(canvas, cx - wr + u, cy - ar + u, cx + wr - u, cy + ar - u, DPAD)
        // Bevel highlights along the top edges.
        painter.rect(canvas, cx - ar + u, cy - wr + u, cx - wr, cy - wr + 2 * u, DPAD_LIGHT)
        painter.rect(canvas, cx + wr, cy - wr + u, cx + ar - u, cy - wr + 2 * u, DPAD_LIGHT)
        painter.rect(canvas, cx - wr + u, cy - ar + u, cx + wr - u, cy - ar + 2 * u, DPAD_LIGHT)

        // Pressed arms sink.
        val inner = wr - u
        if (mask and Joypad.UP != 0) painter.rect(canvas, cx - inner, cy - ar + u, cx + inner, cy - wr, DPAD_PRESSED)
        if (mask and Joypad.DOWN != 0) painter.rect(canvas, cx - inner, cy + wr, cx + inner, cy + ar - u, DPAD_PRESSED)
        if (mask and Joypad.LEFT != 0) painter.rect(canvas, cx - ar + u, cy - inner, cx - wr, cy + inner, DPAD_PRESSED)
        if (mask and Joypad.RIGHT != 0) painter.rect(canvas, cx + wr, cy - inner, cx + ar - u, cy + inner, DPAD_PRESSED)

        // Arrow pixels and the centre dimple.
        val off = (a - (a - w) / 2f) * u
        arrow(canvas, cx, cy - off, u, 0)
        arrow(canvas, cx, cy + off, u, 2)
        arrow(canvas, cx - off, cy, u, 3)
        arrow(canvas, cx + off, cy, u, 1)
        val c = max(1, w / 3) * u
        painter.rect(canvas, cx - c, cy - c, cx + c, cy + c, DPAD_CENTER)
    }

    /** Small stepped triangle pointing up (0), right (1), down (2) or left (3). */
    private fun arrow(canvas: Canvas, x: Float, y: Float, u: Float, dir: Int) {
        for (k in 0 until 3) {
            val half = k + 0.5f
            val depth = (k - 1) * u
            when (dir) {
                0 -> painter.rect(canvas, x - half * u, y + depth, x + half * u, y + depth + u, ARROW)
                2 -> painter.rect(canvas, x - half * u, y - depth - u, x + half * u, y - depth, ARROW)
                3 -> painter.rect(canvas, x + depth, y - half * u, x + depth + u, y + half * u, ARROW)
                else -> painter.rect(canvas, x - depth - u, y - half * u, x - depth, y + half * u, ARROW)
            }
        }
    }

    private fun drawRoundButton(
        canvas: Canvas, c: PointF, size: Int, u: Float, label: String,
        fill: Int, light: Int, dark: Int, pressed: Boolean,
    ) {
        painter.disc(canvas, c.x + u, c.y + u, size, u, SHADOW, SHADOW)
        val shift = if (pressed) u else 0f
        painter.disc(canvas, c.x + shift, c.y + shift, size, u, if (pressed) dark else fill, INK, if (pressed) 0 else light, dark)
        text.color = LABEL
        text.textSize = size * 0.55f * u
        canvas.drawText(label, c.x + shift, c.y + shift - (text.descent() + text.ascent()) / 2f, text)
    }

    private fun drawPill(canvas: Canvas, l: ConsoleLayout, r: android.graphics.RectF, u: Float, label: String, pressed: Boolean) {
        val shift = if (pressed) u else 0f
        painter.box(canvas, r.left + u, r.top + u, r.right + u, r.bottom + u, u, 2, SHADOW, outlineUnits = 0)
        painter.box(canvas, r.left + shift, r.top + shift, r.right + shift, r.bottom + shift, u, 2,
            if (pressed) PILL_PRESSED else PILL_TOP, if (pressed) PILL_PRESSED else PILL_BOTTOM)
        text.color = TEXT
        text.textSize = 3.4f * u
        canvas.drawText(label, r.centerX(), r.bottom + 5.2f * u, text)
    }

    private fun drawMenu(canvas: Canvas, c: PointF, u: Float) {
        val shift = if (menuPressed) u else 0f
        painter.disc(canvas, c.x + u, c.y + u, 9, u, SHADOW, SHADOW)
        painter.disc(canvas, c.x + shift, c.y + shift, 9, u, KNOB, INK, KNOB_LIGHT, KNOB_DARK)
        painter.disc(canvas, c.x + shift, c.y + shift, 5, u, KNOB_DARK, INK)
        painter.rect(canvas, c.x + shift - u / 2, c.y + shift - u / 2, c.x + shift + u / 2, c.y + shift + u / 2, KNOB_LIGHT)
        text.color = TEXT
        text.textSize = 3.2f * u
        canvas.drawText(context.getString(R.string.label_menu), c.x, c.y + 9.5f * u, text)
    }

    private fun drawFastForward(canvas: Canvas, c: PointF, u: Float) {
        val shift = if (ffPressed) u else 0f
        val l = c.x - 6 * u
        val t = c.y - 3.5f * u
        painter.box(canvas, l + u, t + u, l + 13 * u, t + 8 * u, u, 2, SHADOW, outlineUnits = 0)
        val fill = if (fastForwardActive) FF_ACTIVE else PILL_TOP
        painter.box(canvas, l + shift, t + shift, l + 12 * u + shift, t + 7 * u + shift, u, 2, fill,
            if (fastForwardActive) FF_ACTIVE_DARK else PILL_BOTTOM)
        // ">>" in pixels.
        val iconColor = if (fastForwardActive) INK else BTN_B
        for (k in 0..1) {
            val x0 = l + shift + (3.5f + k * 3f).toInt() * u
            for (row in 0 until 3) {
                val len = if (row == 1) 2 else 1
                painter.rect(canvas, x0, t + shift + (2 + row) * u, x0 + len * u, t + shift + (3 + row) * u, iconColor)
            }
        }
        text.color = TEXT
        text.textSize = 3.2f * u
        canvas.drawText(context.getString(R.string.label_fast), c.x, c.y + 8.8f * u, text)
    }

    companion object {
        private const val SPECIAL_MENU = 1
        private const val SPECIAL_FF = 2

        private const val SHADOW = 0x40A0420A
        private const val DPAD = 0xFF4A4A52.toInt()
        private const val DPAD_LIGHT = 0xFF6C6C76.toInt()
        private const val DPAD_PRESSED = 0xFF2F2F35.toInt()
        private const val DPAD_CENTER = 0xFF2A2A30.toInt()
        private const val ARROW = 0xFF26262B.toInt()
        private const val BTN_B = 0xFFFFD54A.toInt()
        private const val BTN_B_LIGHT = 0xFFFFF2A8.toInt()
        private const val BTN_B_DARK = 0xFFE0A91E.toInt()
        private const val BTN_A = 0xFFFF8A3A.toInt()
        private const val BTN_A_LIGHT = 0xFFFFC387.toInt()
        private const val BTN_A_DARK = 0xFFD9621A.toInt()
        private const val LABEL = 0xFF3A2410.toInt()
        private const val PILL_TOP = 0xFF4A4A55.toInt()
        private const val PILL_BOTTOM = 0xFF33333B.toInt()
        private const val PILL_PRESSED = 0xFF26262C.toInt()
        private const val KNOB = 0xFFB9B9C3.toInt()
        private const val KNOB_LIGHT = 0xFFE6E6EE.toInt()
        private const val KNOB_DARK = 0xFF6E6E7A.toInt()
        private const val FF_ACTIVE = 0xFFFFD54A.toInt()
        private const val FF_ACTIVE_DARK = 0xFFF0A92A.toInt()
        private const val TEXT = 0xFF6B3A12.toInt()
        private const val SHOULDER_LABEL = 0xFFE6E6EE.toInt()
    }
}
