package com.retroemulator.gb.gba

import com.retroemulator.gb.core.StateReader
import com.retroemulator.gb.core.StateWriter
import java.util.Arrays
import kotlin.math.pow

/**
 * GBA video: a scanline renderer that draws each visible line when it enters HBlank, plus the
 * DISPSTAT/VCOUNT timing that drives the HBlank, VBlank and VCount interrupts and DMAs.
 */
class GbaPpu(private val gba: Gba) {
    val frameBuffer = IntArray(Gba.WIDTH * Gba.HEIGHT)
    var frameReady = false

    var vcount = 0
        private set
    var nextEvent = HDRAW_CYCLES.toLong()
        private set
    private var inHblank = false
    private var lineStart = 0L

    // Internal affine reference points for BG2 and BG3 (20.8 fixed point).
    private val refX = IntArray(2)
    private val refY = IntArray(2)

    private val lut = IntArray(32768)

    // Per-line layer buffers: 15-bit colours, or -1 where transparent.
    private val bgLine = Array(4) { IntArray(Gba.WIDTH) }
    private val objColor = IntArray(Gba.WIDTH)
    private val objPrio = IntArray(Gba.WIDTH)
    private val objSemi = BooleanArray(Gba.WIDTH)
    private val objWin = BooleanArray(Gba.WIDTH)
    private val winMask = IntArray(Gba.WIDTH)
    private val order = IntArray(8)

    private val io get() = gba.bus.io
    private val vram get() = gba.bus.vram
    private val pal get() = gba.bus.palette

    init {
        setColorCorrection(false)
    }

    fun reset() {
        vcount = 0
        inHblank = false
        lineStart = 0
        nextEvent = HDRAW_CYCLES.toLong()
        frameReady = false
        Arrays.fill(frameBuffer, 0xFF000000.toInt())
        latchAffine(0); latchAffine(1)
    }

    /** Approximates the darker, less saturated GBA screen when enabled. */
    fun setColorCorrection(enabled: Boolean) {
        for (c in 0 until 32768) {
            val r = (c and 0x1F) / 31.0
            val g = ((c ushr 5) and 0x1F) / 31.0
            val b = ((c ushr 10) and 0x1F) / 31.0
            val rr: Int; val gg: Int; val bb: Int
            if (enabled) {
                val lr = r.pow(4.0 / 2.2); val lg = g.pow(4.0 / 2.2); val lb = b.pow(4.0 / 2.2)
                fun out(v: Double) = (minOf(1.0, v).pow(1 / 2.2) * 255).toInt().coerceIn(0, 255)
                rr = out(0.82 * lr + 0.24 * lg - 0.06 * lb)
                gg = out(0.10 * lr + 0.69 * lg + 0.21 * lb)
                bb = out(0.12 * lr + 0.07 * lg + 0.81 * lb)
            } else {
                val r5 = c and 0x1F; val g5 = (c ushr 5) and 0x1F; val b5 = (c ushr 10) and 0x1F
                rr = (r5 shl 3) or (r5 ushr 2)
                gg = (g5 shl 3) or (g5 ushr 2)
                bb = (b5 shl 3) or (b5 ushr 2)
            }
            lut[c] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    private fun reg(off: Int): Int = (io[off].toInt() and 0xFF) or ((io[off + 1].toInt() and 0xFF) shl 8)

    val bitmapMode: Boolean get() = reg(0) and 7 >= 3

    fun readDispstat(): Int = reg(4)

    private fun setDispstatFlags(flags: Int) {
        io[4] = ((io[4].toInt() and 0xF8.toInt()) or flags).toByte()
    }

    private fun dispstatFlags(): Int = io[4].toInt() and 7

    /** Lines are drawn at HBlank with the registers of that moment, so writes need no catch-up. */
    fun syncBeforeWrite() {}

    /** Writing a reference point register reloads the internal one immediately. */
    fun onAffineWrite(off: Int) {
        when (off) {
            in 0x028..0x02F -> latchAffine(0)
            in 0x038..0x03F -> latchAffine(1)
        }
    }

    private fun latchAffine(i: Int) {
        val base = 0x28 + i * 0x10
        refX[i] = ((reg(base) or (reg(base + 2) shl 16)) shl 4) shr 4
        refY[i] = ((reg(base + 4) or (reg(base + 6) shl 16)) shl 4) shr 4
    }

    // ------------------------------------------------------------------------------------------
    // Timing
    // ------------------------------------------------------------------------------------------

    fun onEvent() {
        if (!inHblank) {
            if (vcount < Gba.HEIGHT) renderLine(vcount)
            setDispstatFlags(dispstatFlags() or 2)
            if (reg(4) and 0x10 != 0) gba.requestIrq(1)
            if (vcount < Gba.HEIGHT) gba.dma.onHBlank()
            inHblank = true
            nextEvent = lineStart + LINE_CYCLES
        } else {
            lineStart += LINE_CYCLES
            vcount = if (vcount == TOTAL_LINES - 1) 0 else vcount + 1
            var flags = dispstatFlags() and 2.inv()
            when (vcount) {
                Gba.HEIGHT -> {
                    flags = flags or 1
                    if (reg(4) and 0x08 != 0) gba.requestIrq(0)
                    gba.dma.onVBlank()
                    latchAffine(0); latchAffine(1)
                    frameReady = true
                }
                TOTAL_LINES - 1 -> flags = flags and 1.inv()
            }
            if (vcount == reg(4) ushr 8) {
                flags = flags or 4
                if (reg(4) and 0x20 != 0) gba.requestIrq(2)
            } else {
                flags = flags and 4.inv()
            }
            setDispstatFlags(flags)
            inHblank = false
            nextEvent = lineStart + HDRAW_CYCLES
        }
    }

    // ------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------

    private fun renderLine(line: Int) {
        val dispcnt = reg(0)
        val fbBase = line * Gba.WIDTH
        if (dispcnt and 0x80 != 0) {
            Arrays.fill(frameBuffer, fbBase, fbBase + Gba.WIDTH, 0xFFFFFFFF.toInt())
            advanceAffine()
            return
        }
        val mode = dispcnt and 7
        val mosaic = reg(0x4C)
        val bgMosH = (mosaic and 0xF) + 1
        val bgMosV = ((mosaic ushr 4) and 0xF) + 1

        // Which backgrounds exist in this mode.
        var bgMask = (dispcnt ushr 8) and 0xF
        bgMask = bgMask and when (mode) {
            0 -> 0xF
            1 -> 0x7
            2 -> 0xC
            3, 4, 5 -> 0x4
            else -> 0
        }
        for (bg in 0 until 4) {
            if (bgMask and (1 shl bg) == 0) continue
            val out = bgLine[bg]
            when {
                mode == 0 || (mode == 1 && bg < 2) -> renderText(bg, line, bgMosV, out)
                mode == 1 || mode == 2 -> renderAffine(bg, out)
                else -> renderBitmap(mode, dispcnt, out)
            }
            if (reg(0x08 + bg * 2) and 0x40 != 0 && bgMosH > 1) {
                for (x in 0 until Gba.WIDTH) out[x] = out[x - x % bgMosH]
            }
        }
        advanceAffine()

        Arrays.fill(objColor, -1)
        Arrays.fill(objPrio, 4)
        Arrays.fill(objSemi, false)
        Arrays.fill(objWin, false)
        val objOn = dispcnt and 0x1000 != 0
        if (objOn) renderSprites(line, dispcnt, mosaic)

        buildWindows(line, dispcnt)
        compose(fbBase, bgMask, objOn)
    }

    private fun advanceAffine() {
        for (i in 0 until 2) {
            val base = 0x20 + i * 0x10
            refX[i] += reg(base + 2).toShort().toInt()
            refY[i] += reg(base + 6).toShort().toInt()
        }
    }

    private fun color(index: Int): Int = (pal[index * 2].toInt() and 0xFF) or ((pal[index * 2 + 1].toInt() and 0x7F) shl 8)

    private fun renderText(bg: Int, line: Int, mosV: Int, out: IntArray) {
        val cnt = reg(0x08 + bg * 2)
        val hofs = reg(0x10 + bg * 4) and 0x1FF
        val vofs = reg(0x12 + bg * 4) and 0x1FF
        val charBase = ((cnt ushr 2) and 3) * 0x4000
        val screenBase = ((cnt ushr 8) and 0x1F) * 0x800
        val color256 = cnt and 0x80 != 0
        val size = cnt ushr 14
        val wMask = if (size and 1 != 0) 511 else 255
        val hMask = if (size and 2 != 0) 511 else 255
        val y = if (cnt and 0x40 != 0) line - line % mosV else line
        val py = (y + vofs) and hMask
        val tileRow = py ushr 3
        val fineY = py and 7
        val blockRow = when (size) { 2 -> tileRow ushr 5; 3 -> (tileRow ushr 5) * 2; else -> 0 }
        val vram = vram
        val pal = pal

        var x = 0
        while (x < Gba.WIDTH) {
            val px = (x + hofs) and wMask
            val tileCol = px ushr 3
            val block = blockRow + if (size and 1 != 0) tileCol ushr 5 else 0
            val mapAddr = screenBase + block * 0x800 + (((tileRow and 31) shl 5) + (tileCol and 31)) * 2
            val entry = if (mapAddr < 0x10000) GbaBus.get16(vram, mapAddr) else 0
            val tile = entry and 0x3FF
            val hflip = entry and 0x400 != 0
            val ty = if (entry and 0x800 != 0) 7 - fineY else fineY
            var fx = px and 7
            if (color256) {
                val rowAddr = charBase + tile * 64 + ty * 8
                while (fx < 8 && x < Gba.WIDTH) {
                    val a = rowAddr + if (hflip) 7 - fx else fx
                    val idx = if (a < 0x10000) vram[a].toInt() and 0xFF else 0
                    out[x] = if (idx == 0) -1 else (pal[idx * 2].toInt() and 0xFF) or ((pal[idx * 2 + 1].toInt() and 0x7F) shl 8)
                    fx++; x++
                }
            } else {
                val rowAddr = charBase + tile * 32 + ty * 4
                val palBase = (entry ushr 12) * 16
                while (fx < 8 && x < Gba.WIDTH) {
                    val tx = if (hflip) 7 - fx else fx
                    val a = rowAddr + (tx ushr 1)
                    val b = if (a < 0x10000) vram[a].toInt() else 0
                    val idx = if (tx and 1 != 0) (b ushr 4) and 0xF else b and 0xF
                    out[x] = if (idx == 0) -1 else color(palBase + idx)
                    fx++; x++
                }
            }
        }
    }

    private fun renderAffine(bg: Int, out: IntArray) {
        val cnt = reg(0x08 + bg * 2)
        val charBase = ((cnt ushr 2) and 3) * 0x4000
        val screenBase = ((cnt ushr 8) and 0x1F) * 0x800
        val size = 128 shl (cnt ushr 14)
        val wrap = cnt and 0x2000 != 0
        val i = bg - 2
        val pa = reg(0x20 + i * 0x10).toShort().toInt()
        val pc = reg(0x24 + i * 0x10).toShort().toInt()
        var tx = refX[i]
        var ty = refY[i]
        val tilesPerRow = size ushr 3
        val vram = vram
        for (x in 0 until Gba.WIDTH) {
            var ix = tx shr 8
            var iy = ty shr 8
            tx += pa; ty += pc
            if (wrap) {
                ix = ix and (size - 1); iy = iy and (size - 1)
            } else if (ix < 0 || iy < 0 || ix >= size || iy >= size) {
                out[x] = -1
                continue
            }
            val mapAddr = screenBase + (iy ushr 3) * tilesPerRow + (ix ushr 3)
            val tile = if (mapAddr < 0x10000) vram[mapAddr].toInt() and 0xFF else 0
            val a = charBase + tile * 64 + (iy and 7) * 8 + (ix and 7)
            val idx = if (a < 0x10000) vram[a].toInt() and 0xFF else 0
            out[x] = if (idx == 0) -1 else color(idx)
        }
    }

    private fun renderBitmap(mode: Int, dispcnt: Int, out: IntArray) {
        val pa = reg(0x20).toShort().toInt()
        val pc = reg(0x24).toShort().toInt()
        var tx = refX[0]
        var ty = refY[0]
        val page = if (mode != 3 && dispcnt and 0x10 != 0) 0xA000 else 0
        val w = if (mode == 5) 160 else 240
        val h = if (mode == 5) 128 else 160
        val vram = vram
        for (x in 0 until Gba.WIDTH) {
            val ix = tx shr 8
            val iy = ty shr 8
            tx += pa; ty += pc
            if (ix < 0 || iy < 0 || ix >= w || iy >= h) { out[x] = -1; continue }
            out[x] = when (mode) {
                4 -> {
                    val idx = vram[page + iy * 240 + ix].toInt() and 0xFF
                    if (idx == 0) -1 else color(idx)
                }
                else -> GbaBus.get16(vram, page + (iy * w + ix) * 2) and 0x7FFF
            }
        }
    }

    private fun renderSprites(line: Int, dispcnt: Int, mosaic: Int) {
        val oam = gba.bus.oam
        val vram = vram
        val oneD = dispcnt and 0x40 != 0
        val bitmap = dispcnt and 7 >= 3
        val mosH = ((mosaic ushr 8) and 0xF) + 1
        val mosV = ((mosaic ushr 12) and 0xF) + 1
        for (i in 0 until 128) {
            val a0 = GbaBus.get16(oam, i * 8)
            val affine = a0 and 0x100 != 0
            if (!affine && a0 and 0x200 != 0) continue
            val objMode = (a0 ushr 10) and 3
            if (objMode == 3) continue
            val shape = a0 ushr 14
            if (shape == 3) continue
            val a1 = GbaBus.get16(oam, i * 8 + 2)
            val a2 = GbaBus.get16(oam, i * 8 + 4)
            val sizeIdx = a1 ushr 14
            val w = OBJ_W[shape * 4 + sizeIdx]
            val h = OBJ_H[shape * 4 + sizeIdx]
            val double = affine && a0 and 0x200 != 0
            val bw = if (double) w * 2 else w
            val bh = if (double) h * 2 else h
            val y = a0 and 0xFF
            var dy = (line - y) and 0xFF
            if (dy >= bh) continue
            val mos = a0 and 0x1000 != 0
            if (mos && mosV > 1) {
                val md = ((line - line % mosV) - y) and 0xFF
                if (md < bh) dy = md
            }
            var x = a1 and 0x1FF
            if (x and 0x100 != 0) x -= 512
            if (x >= Gba.WIDTH || x + bw <= 0) continue
            val color256 = a0 and 0x2000 != 0
            val tile = a2 and 0x3FF
            if (bitmap && tile < 512) continue
            val prio = (a2 ushr 10) and 3
            val palBase = 256 + (a2 ushr 12) * 16

            var pa = 0x100; var pb = 0; var pc = 0; var pd = 0x100
            if (affine) {
                val g = ((a1 ushr 9) and 0x1F) * 32
                pa = GbaBus.get16(oam, g + 6).toShort().toInt()
                pb = GbaBus.get16(oam, g + 14).toShort().toInt()
                pc = GbaBus.get16(oam, g + 22).toShort().toInt()
                pd = GbaBus.get16(oam, g + 30).toShort().toInt()
            }
            val hflip = !affine && a1 and 0x1000 != 0
            val vflip = !affine && a1 and 0x2000 != 0
            val rowTiles = if (oneD) (if (color256) w / 4 else w / 8) else 32
            val baseTile = if (color256 && !oneD) tile and 1.inv() else tile

            val startX = maxOf(0, x)
            val endX = minOf(Gba.WIDTH, x + bw)
            for (sx in startX until endX) {
                var ix = sx - x
                if (mos && mosH > 1) ix = maxOf(0, (sx - sx % mosH) - x)
                val tx: Int
                val ty: Int
                if (affine) {
                    val rx = ix - bw / 2
                    val ry = dy - bh / 2
                    tx = ((pa * rx + pb * ry) shr 8) + w / 2
                    ty = ((pc * rx + pd * ry) shr 8) + h / 2
                    if (tx < 0 || ty < 0 || tx >= w || ty >= h) continue
                } else {
                    tx = if (hflip) w - 1 - ix else ix
                    ty = if (vflip) h - 1 - dy else dy
                }
                val c: Int
                if (color256) {
                    val tileNum = baseTile + (ty ushr 3) * rowTiles + (tx ushr 3) * 2
                    val a = 0x10000 + ((tileNum * 32) and 0x7FFF) + (ty and 7) * 8 + (tx and 7)
                    val idx = vram[a].toInt() and 0xFF
                    if (idx == 0) continue
                    c = color(256 + idx)
                } else {
                    val tileNum = baseTile + (ty ushr 3) * rowTiles + (tx ushr 3)
                    val a = 0x10000 + ((tileNum * 32) and 0x7FFF) + (ty and 7) * 4 + ((tx and 7) ushr 1)
                    val b = vram[a].toInt()
                    val idx = if (tx and 1 != 0) (b ushr 4) and 0xF else b and 0xF
                    if (idx == 0) continue
                    c = color(palBase + idx)
                }
                if (objMode == 2) {
                    objWin[sx] = true
                } else if (objColor[sx] < 0 || prio < objPrio[sx]) {
                    objColor[sx] = c
                    objPrio[sx] = prio
                    objSemi[sx] = objMode == 1
                }
            }
        }
    }

    private fun buildWindows(line: Int, dispcnt: Int) {
        if (dispcnt and 0xE000 == 0) {
            Arrays.fill(winMask, 0x3F)
            return
        }
        val winIn = reg(0x48)
        val winOut = reg(0x4A)
        Arrays.fill(winMask, winOut and 0x3F)
        if (dispcnt and 0x8000 != 0) {
            val m = (winOut ushr 8) and 0x3F
            for (x in 0 until Gba.WIDTH) if (objWin[x]) winMask[x] = m
        }
        for (w in 1 downTo 0) {
            if (dispcnt and (0x2000 shl w) == 0) continue
            val v = reg(0x44 + w * 2)
            val y1 = v ushr 8
            val y2 = v and 0xFF
            val inY = if (y1 <= y2) line in y1 until y2 else line >= y1 || line < y2
            if (!inY) continue
            val hreg = reg(0x40 + w * 2)
            val x1 = hreg ushr 8
            val x2 = hreg and 0xFF
            val m = (winIn ushr (w * 8)) and 0x3F
            for (x in 0 until Gba.WIDTH) {
                val inside = if (x1 <= x2) x >= x1 && x < x2 else x >= x1 || x < x2
                if (inside) winMask[x] = m
            }
        }
    }

    private fun compose(fbBase: Int, bgMask: Int, objOn: Boolean) {
        // Draw order: for each priority level, OBJ first, then backgrounds by index.
        var n = 0
        for (p in 0 until 4) {
            if (objOn) order[n++] = 4 + p
            for (bg in 0 until 4) {
                if (bgMask and (1 shl bg) != 0 && reg(0x08 + bg * 2) and 3 == p) order[n++] = bg
            }
        }
        val bldcnt = reg(0x50)
        val effect = (bldcnt ushr 6) and 3
        val alpha = reg(0x52)
        val eva = minOf(16, alpha and 0x1F)
        val evb = minOf(16, (alpha ushr 8) and 0x1F)
        val evy = minOf(16, reg(0x54) and 0x1F)
        val backdrop = color(0)
        val fb = frameBuffer
        val bg0 = bgLine[0]; val bg1 = bgLine[1]; val bg2 = bgLine[2]; val bg3 = bgLine[3]

        for (x in 0 until Gba.WIDTH) {
            val mask = winMask[x]
            var c1 = backdrop; var l1 = 5
            var c2 = backdrop; var l2 = 5
            var found = 0
            for (k in 0 until n) {
                val e = order[k]
                val c: Int
                val layer: Int
                if (e < 4) {
                    if (mask and (1 shl e) == 0) continue
                    c = when (e) { 0 -> bg0[x]; 1 -> bg1[x]; 2 -> bg2[x]; else -> bg3[x] }
                    if (c < 0) continue
                    layer = e
                } else {
                    if (mask and 0x10 == 0 || objPrio[x] != e - 4) continue
                    c = objColor[x]
                    if (c < 0) continue
                    layer = 4
                }
                if (found == 0) { c1 = c; l1 = layer; found = 1 } else { c2 = c; l2 = layer; found = 2; break }
            }

            var out = c1
            val semi = l1 == 4 && objSemi[x]
            if (semi && bldcnt and (0x100 shl l2) != 0) {
                out = blend(c1, c2, eva, evb)
            } else if (mask and 0x20 != 0 && bldcnt and (1 shl l1) != 0) {
                when (effect) {
                    1 -> if (bldcnt and (0x100 shl l2) != 0) out = blend(c1, c2, eva, evb)
                    2 -> out = brighten(c1, evy)
                    3 -> out = darken(c1, evy)
                }
            }
            fb[fbBase + x] = lut[out]
        }
    }

    private fun blend(a: Int, b: Int, eva: Int, evb: Int): Int {
        val r = minOf(31, ((a and 0x1F) * eva + (b and 0x1F) * evb) ushr 4)
        val g = minOf(31, (((a ushr 5) and 0x1F) * eva + ((b ushr 5) and 0x1F) * evb) ushr 4)
        val bl = minOf(31, (((a ushr 10) and 0x1F) * eva + ((b ushr 10) and 0x1F) * evb) ushr 4)
        return r or (g shl 5) or (bl shl 10)
    }

    private fun brighten(a: Int, evy: Int): Int {
        val r = a and 0x1F; val g = (a ushr 5) and 0x1F; val b = (a ushr 10) and 0x1F
        return (r + (((31 - r) * evy) ushr 4)) or ((g + (((31 - g) * evy) ushr 4)) shl 5) or
            ((b + (((31 - b) * evy) ushr 4)) shl 10)
    }

    private fun darken(a: Int, evy: Int): Int {
        val r = a and 0x1F; val g = (a ushr 5) and 0x1F; val b = (a ushr 10) and 0x1F
        return (r - ((r * evy) ushr 4)) or ((g - ((g * evy) ushr 4)) shl 5) or ((b - ((b * evy) ushr 4)) shl 10)
    }

    // ------------------------------------------------------------------------------------------

    fun saveState(w: StateWriter) {
        w.tag("gbappu")
        w.int(vcount); w.bool(inHblank); w.long(lineStart); w.long(nextEvent)
        w.ints(refX); w.ints(refY)
        w.ints(frameBuffer)
    }

    fun loadState(r: StateReader) {
        r.tag("gbappu")
        vcount = r.int(); inHblank = r.bool(); lineStart = r.long(); nextEvent = r.long()
        r.intsInto(refX); r.intsInto(refY)
        r.intsInto(frameBuffer)
    }

    companion object {
        const val HDRAW_CYCLES = 960
        const val LINE_CYCLES = 1232
        const val TOTAL_LINES = 228

        // OBJ dimensions indexed by shape * 4 + size.
        private val OBJ_W = intArrayOf(8, 16, 32, 64, 16, 32, 32, 64, 8, 8, 16, 32)
        private val OBJ_H = intArrayOf(8, 16, 32, 64, 8, 8, 16, 32, 16, 32, 32, 64)
    }
}
