package com.retroemulator.gb.core

import java.util.Arrays

/**
 * Picture processing unit.
 *
 * Mode timing is tracked per dot (mode 3 length includes the SCX, window and sprite penalties), while
 * pixels are produced lazily: whenever a register that affects rendering is written during mode 3 the
 * line is rendered up to the current beam position first, so mid-scanline raster effects work.
 */
class Ppu(private val gb: GameBoy) {
    val vram = ByteArray(0x4000)
    val oam = ByteArray(0xA0)
    var vbk = 0

    var lcdc = 0
        private set
    private var statSelect = 0
    var scy = 0; var scx = 0
    var ly = 0
        private set
    var lyc = 0
    var bgp = 0; var obp0 = 0; var obp1 = 0
    var wy = 0; var wx = 0
    var mode = 0
        private set
    /**
     * Mode as seen by the CPU (STAT bits and VRAM/OAM locks). It lags the internal mode, which drives
     * interrupts, by one M-cycle: e.g. the HBlank interrupt fires 4 dots before STAT reports mode 0.
     */
    private var visibleMode = 0

    private var line = 0
    private var lineDot = 0
    private var nextEvent = 80
    private var mode3Length = 172
    private var statLine = false
    private var windowLine = 0
    private var wyTriggered = false
    private var windowDrawn = false
    private var renderX = 0
    private var mode3Scx = 0
    /** STAT bit 2. Latched while the LCD is off. */
    private var lycMatch = false
    /** LY just changed: the comparison reads false for one M-cycle. */
    private var lycBlank = false
    /** First line after LCD enable: starts in mode 0 and goes straight to mode 3. */
    private var firstLine = false
    /** Mode 2 STAT source active during the first M-cycle of line 144. */
    private var vblankOamIrq = false
    /** Mode 3 of the first line after LCD enable (VRAM lock starts late). */
    private var firstLineMode3 = false

    /** Set when a complete frame is in [frameBuffer]; cleared by the frame loop. */
    var frameReady = false

    /** ARGB pixels, 160x144. */
    val frameBuffer = IntArray(WIDTH * HEIGHT)

    // CGB palette memory
    val bgPalRam = ByteArray(64)
    val objPalRam = ByteArray(64)
    private var bcps = 0
    private var ocps = 0
    var opri = 0
    private val bgColors = IntArray(32)
    private val objColors = IntArray(32)

    // Display palettes chosen by the frontend
    private val dmgBg = intArrayOf(0xFFE0F8D0.toInt(), 0xFF88C070.toInt(), 0xFF346856.toInt(), 0xFF081820.toInt())
    private val dmgObj0 = dmgBg.copyOf()
    private val dmgObj1 = dmgBg.copyOf()
    private val cgbLut = IntArray(32768)

    // Per-line sprite rasterization
    private val objColor = IntArray(WIDTH)
    private val objAttr = IntArray(WIDTH)
    private val lineSprites = IntArray(10)
    private var lineSpriteCount = 0

    val lcdOn: Boolean get() = lcdc and 0x80 != 0

    init {
        setColorCorrection(true)
    }

    fun setDmgPalette(bg: IntArray, obj0: IntArray = bg, obj1: IntArray = bg) {
        bg.copyInto(dmgBg); obj0.copyInto(dmgObj0); obj1.copyInto(dmgObj1)
    }

    /** Builds the 15-bit to ARGB table, optionally simulating the washed-out GBC LCD. */
    fun setColorCorrection(enabled: Boolean) {
        for (c in 0 until 32768) {
            val r = c and 0x1F
            val g = (c ushr 5) and 0x1F
            val b = (c ushr 10) and 0x1F
            val rr: Int; val gg: Int; val bb: Int
            if (enabled) {
                rr = minOf(255, (r * 26 + g * 4 + b * 2) * 255 / (32 * 31))
                gg = minOf(255, (g * 24 + b * 8) * 255 / (32 * 31))
                bb = minOf(255, (r * 6 + g * 4 + b * 22) * 255 / (32 * 31))
            } else {
                rr = (r shl 3) or (r ushr 2)
                gg = (g shl 3) or (g ushr 2)
                bb = (b shl 3) or (b ushr 2)
            }
            cgbLut[c] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        for (i in 0 until 32) {
            bgColors[i] = paletteColor(bgPalRam, i)
            objColors[i] = paletteColor(objPalRam, i)
        }
    }

    private fun paletteColor(ram: ByteArray, index: Int): Int {
        val lo = ram[index * 2].toInt() and 0xFF
        val hi = ram[index * 2 + 1].toInt() and 0xFF
        return cgbLut[((hi shl 8) or lo) and 0x7FFF]
    }

    // ------------------------------------------------------------------------------------------
    // Timing
    // ------------------------------------------------------------------------------------------

    fun tick(dots: Int) {
        if (lcdc and 0x80 == 0) return
        visibleMode = mode
        if (lycBlank) {
            // The LY=LYC comparison resumes one M-cycle after LY changes.
            lycBlank = false
            updateStat()
        }
        lineDot += dots
        while (lineDot >= nextEvent) handleEvent()
    }

    private fun handleEvent() {
        when (mode) {
            2 -> enterMode3()
            3 -> enterMode0()
            0 -> {
                if (firstLine) {
                    // The first line after the LCD is switched on skips the OAM scan.
                    firstLine = false
                    firstLineMode3 = true
                    enterMode3()
                } else {
                    lineDot -= LINE_DOTS
                    nextLine()
                }
            }
            else -> when {
                line == 144 && vblankOamIrq -> {
                    vblankOamIrq = false
                    nextEvent = LINE_DOTS
                    updateStat()
                }
                line == 153 && ly == 153 -> {
                    // LY reads 0 for most of line 153.
                    setLy(0)
                    nextEvent = LINE_DOTS
                    updateStat()
                }
                else -> {
                    lineDot -= LINE_DOTS
                    nextLine()
                }
            }
        }
    }

    private fun setLy(value: Int) {
        ly = value
        lycBlank = true
    }

    private fun nextLine() {
        line++
        firstLineMode3 = false
        when {
            line == 144 -> {
                setLy(144)
                mode = 1
                // On DMG the mode 2 STAT source also fires as VBlank begins.
                vblankOamIrq = true
                nextEvent = 4
                gb.intFlag = gb.intFlag or 0x01
                frameReady = true
            }
            line > 153 -> {
                line = 0
                setLy(0)
                windowLine = 0
                wyTriggered = false
                startMode2()
            }
            line > 144 -> {
                setLy(line)
                nextEvent = if (line == 153) 8 else LINE_DOTS
            }
            else -> {
                setLy(line)
                startMode2()
            }
        }
        updateStat()
    }

    private fun startMode2() {
        mode = 2
        nextEvent = 80
        if (ly == wy) wyTriggered = true
    }

    private fun enterMode3() {
        mode = 3
        renderX = 0
        windowDrawn = false
        mode3Scx = scx
        selectSprites()
        var len = 172 + (scx and 7)
        if (lcdc and 0x20 != 0 && wyTriggered && wx <= 166) len += 6
        // Sprite fetches: 6 dots each, plus a wait for the first sprite on each background tile that
        // depends on its alignment. Fitted to Mooneye's intr_2_mode0_timing_sprites measurements.
        var seenTiles = 0L
        var counted = 0
        for (i in 0 until lineSpriteCount) {
            val sx = oam[lineSprites[i] * 4 + 1].toInt() and 0xFF
            if (sx >= 168) continue
            val pos = sx + (scx and 7)
            val tileBit = 1L shl (pos ushr 3)
            if (seenTiles and tileBit == 0L) {
                len += maxOf(0, 5 - (pos and 7))
                seenTiles = seenTiles or tileBit
            }
            len += 6
            counted++
        }
        if (counted > 0) len -= 3
        mode3Length = minOf(len, 289)
        nextEvent = 80 + mode3Length
        updateStat()
    }

    private fun enterMode0() {
        renderTo(WIDTH)
        if (windowDrawn) windowLine++
        mode = 0
        nextEvent = LINE_DOTS
        updateStat()
        gb.mmu.onHBlank()
    }

    /** Recomputes the LY=LYC flag and the STAT interrupt line, raising IF on a rising edge. */
    private fun updateStat() {
        lycMatch = !lycBlank && ly == lyc
        val s = statSelect
        val active = (s and 0x40 != 0 && lycMatch) ||
            (s and 0x08 != 0 && mode == 0) ||
            (s and 0x10 != 0 && mode == 1) ||
            (s and 0x20 != 0 && (mode == 2 || vblankOamIrq))
        if (active && !statLine) gb.intFlag = gb.intFlag or 0x02
        statLine = active
    }

    /** Renders the current line up to the pixel the beam has reached, before a register changes. */
    private fun catchUp() {
        if (mode != 3 || lcdc and 0x80 == 0) return
        val x = lineDot - 80 - 12 - (mode3Scx and 7)
        if (x > renderX) renderTo(minOf(x, WIDTH))
    }

    // ------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------

    private fun selectSprites() {
        lineSpriteCount = 0
        Arrays.fill(objColor, 0)
        if (lcdc and 0x02 == 0) return
        val height = if (lcdc and 0x04 != 0) 16 else 8
        val cgbMode = gb.cgb
        for (i in 0 until 40) {
            val row = ly + 16 - (oam[i * 4].toInt() and 0xFF)
            if (row in 0 until height) {
                lineSprites[lineSpriteCount++] = i
                if (lineSpriteCount == 10) break
            }
        }
        // DMG (and CGB with OPRI=1): lower X wins, ties broken by OAM order. CGB: OAM order only.
        if (!cgbMode || opri and 1 != 0) {
            for (i in 1 until lineSpriteCount) {
                val s = lineSprites[i]
                val sx = oam[s * 4 + 1].toInt() and 0xFF
                var j = i - 1
                while (j >= 0 && (oam[lineSprites[j] * 4 + 1].toInt() and 0xFF) > sx) {
                    lineSprites[j + 1] = lineSprites[j]
                    j--
                }
                lineSprites[j + 1] = s
            }
        }
        for (k in 0 until lineSpriteCount) {
            val base = lineSprites[k] * 4
            val sy = oam[base].toInt() and 0xFF
            val sx = oam[base + 1].toInt() and 0xFF
            var tile = oam[base + 2].toInt() and 0xFF
            val attr = oam[base + 3].toInt() and 0xFF
            var row = ly + 16 - sy
            if (attr and 0x40 != 0) row = height - 1 - row
            if (height == 16) tile = tile and 0xFE
            val addr = (tile shl 4) + (row shl 1) + (if (cgbMode && attr and 0x08 != 0) 0x2000 else 0)
            val lo = vram[addr].toInt()
            val hi = vram[addr + 1].toInt()
            for (p in 0 until 8) {
                val x = sx - 8 + p
                if (x < 0 || x >= WIDTH || objColor[x] != 0) continue
                val bit = if (attr and 0x20 != 0) p else 7 - p
                val color = ((lo ushr bit) and 1) or (((hi ushr bit) and 1) shl 1)
                if (color != 0) {
                    objColor[x] = color
                    objAttr[x] = attr
                }
            }
        }
    }

    private fun renderTo(xEnd: Int) {
        if (renderX >= xEnd || ly >= HEIGHT) return
        val cgbMode = gb.cgb
        val control = lcdc
        val bgEnabled = cgbMode || control and 0x01 != 0
        val winEnabled = bgEnabled && control and 0x20 != 0 && wyTriggered && wx <= 166
        val winStart = wx - 7
        val objEnabled = control and 0x02 != 0
        val signedTiles = control and 0x10 == 0
        val bgMap = if (control and 0x08 != 0) 0x1C00 else 0x1800
        val winMap = if (control and 0x40 != 0) 0x1C00 else 0x1800
        val bgY = (ly + scy) and 0xFF
        val rowBase = ly * WIDTH
        val fb = frameBuffer

        var x = renderX
        while (x < xEnd) {
            var colorIndex = 0
            var attr = 0
            if (bgEnabled) {
                val mapBase: Int
                val px: Int
                val py: Int
                if (winEnabled && x >= winStart) {
                    windowDrawn = true
                    mapBase = winMap; px = x - winStart; py = windowLine
                } else {
                    mapBase = bgMap; px = (x + scx) and 0xFF; py = bgY
                }
                val mapAddr = mapBase + ((py and 0xF8) shl 2) + (px ushr 3)
                val tile = vram[mapAddr].toInt() and 0xFF
                if (cgbMode) attr = vram[0x2000 + mapAddr].toInt() and 0xFF
                var tileRow = py and 7
                if (attr and 0x40 != 0) tileRow = 7 - tileRow
                var addr = if (signedTiles) 0x1000 + (tile.toByte().toInt() shl 4) else tile shl 4
                addr += tileRow shl 1
                if (attr and 0x08 != 0) addr += 0x2000
                val bit = if (attr and 0x20 != 0) px and 7 else 7 - (px and 7)
                colorIndex = ((vram[addr].toInt() ushr bit) and 1) or (((vram[addr + 1].toInt() ushr bit) and 1) shl 1)
            }

            var color = if (cgbMode) bgColors[((attr and 7) shl 2) + colorIndex]
            else if (bgEnabled) dmgBg[(bgp ushr (colorIndex shl 1)) and 3]
            else dmgBg[0]

            if (objEnabled) {
                val oc = objColor[x]
                if (oc != 0) {
                    val oa = objAttr[x]
                    if (cgbMode) {
                        val objOnTop = control and 0x01 == 0 || colorIndex == 0 ||
                            (attr and 0x80 == 0 && oa and 0x80 == 0)
                        if (objOnTop) color = objColors[((oa and 7) shl 2) + oc]
                    } else if (oa and 0x80 == 0 || colorIndex == 0) {
                        color = if (oa and 0x10 != 0) dmgObj1[(obp1 ushr (oc shl 1)) and 3]
                        else dmgObj0[(obp0 ushr (oc shl 1)) and 3]
                    }
                }
            }
            fb[rowBase + x] = color
            x++
        }
        renderX = xEnd
    }

    /** Fill the screen with the "LCD off" color. */
    fun blankScreen() {
        Arrays.fill(frameBuffer, if (gb.cgb) 0xFFFFFFFF.toInt() else dmgBg[0])
    }

    // ------------------------------------------------------------------------------------------
    // CPU-facing registers
    // ------------------------------------------------------------------------------------------

    /**
     * VRAM reads lock when mode 3 starts and unlock one M-cycle after it ends. On the first line after
     * the LCD is switched on the PPU runs slightly late, so the lock also starts one M-cycle later.
     */
    private val vramReadLocked: Boolean
        get() = lcdOn && (visibleMode == 3 || (mode == 3 && !firstLineMode3))

    /** Writes still land in the first M-cycle of mode 3. */
    private val vramWriteLocked: Boolean
        get() = lcdOn && visibleMode == 3

    fun readVram(addr: Int): Int {
        if (vramReadLocked) return 0xFF
        return vram[(vbk shl 13) + (addr and 0x1FFF)].toInt() and 0xFF
    }

    fun writeVram(addr: Int, value: Int) {
        if (vramWriteLocked) return
        vram[(vbk shl 13) + (addr and 0x1FFF)] = value.toByte()
    }

    /** OAM reads lock as soon as the OAM scan starts; otherwise they follow the (lagging) visible mode. */
    fun oamReadable(): Boolean = !lcdOn || (mode != 2 && visibleMode < 2)

    /** OAM writes still land in the first M-cycle of the OAM scan and of mode 3. */
    fun oamWritable(): Boolean = !lcdOn || !((mode == 2 && visibleMode == 2) || visibleMode == 3)

    fun readStat(): Int {
        val m = if (lcdOn) visibleMode else 0
        return 0x80 or statSelect or (if (lycMatch) 0x04 else 0) or m
    }

    fun writeLcdc(value: Int) {
        catchUp()
        val wasOn = lcdOn
        lcdc = value
        if (wasOn && !lcdOn) {
            mode = 0
            visibleMode = 0
            ly = 0
            line = 0
            lineDot = 0
            firstLine = false
            firstLineMode3 = false
            vblankOamIrq = false
            lycBlank = false
            // The LY=LYC flag (and its interrupt line) keep their last value while the PPU is off.
            statLine = statSelect and 0x40 != 0 && lycMatch
            blankScreen()
        } else if (!wasOn && lcdOn) {
            // Line 0 after enabling is 4 dots short and has no OAM scan.
            line = 0
            ly = 0
            lycBlank = false
            lineDot = 4
            mode = 0
            visibleMode = 0
            firstLine = true
            nextEvent = 80
            windowLine = 0
            wyTriggered = wy == 0
            updateStat()
        }
    }

    fun writeStat(value: Int) {
        statSelect = value and 0x78
        if (lcdOn) updateStat()
    }

    fun writeLyc(value: Int) {
        lyc = value
        if (lcdOn) updateStat()
    }

    fun writeScroll(addr: Int, value: Int) {
        catchUp()
        when (addr) {
            0xFF42 -> scy = value
            0xFF43 -> scx = value
            0xFF47 -> bgp = value
            0xFF48 -> obp0 = value
            0xFF49 -> obp1 = value
            0xFF4A -> wy = value
            0xFF4B -> wx = value
        }
    }

    fun readPalette(addr: Int): Int = when (addr) {
        0xFF68 -> bcps or 0x40
        0xFF69 -> if (vramReadLocked) 0xFF else bgPalRam[bcps and 0x3F].toInt() and 0xFF
        0xFF6A -> ocps or 0x40
        else -> if (vramReadLocked) 0xFF else objPalRam[ocps and 0x3F].toInt() and 0xFF
    }

    fun writePalette(addr: Int, value: Int) {
        when (addr) {
            0xFF68 -> bcps = value and 0xBF
            0xFF69 -> {
                val i = bcps and 0x3F
                if (!vramWriteLocked) {
                    bgPalRam[i] = value.toByte()
                    bgColors[i ushr 1] = paletteColor(bgPalRam, i ushr 1)
                }
                if (bcps and 0x80 != 0) bcps = (bcps and 0x80) or ((i + 1) and 0x3F)
            }
            0xFF6A -> ocps = value and 0xBF
            else -> {
                val i = ocps and 0x3F
                if (!vramWriteLocked) {
                    objPalRam[i] = value.toByte()
                    objColors[i ushr 1] = paletteColor(objPalRam, i ushr 1)
                }
                if (ocps and 0x80 != 0) ocps = (ocps and 0x80) or ((i + 1) and 0x3F)
            }
        }
    }

    fun reset(cgb: Boolean) {
        lcdc = 0x91
        statSelect = 0
        scy = 0; scx = 0; lyc = 0; wy = 0; wx = 0
        bgp = 0xFC; obp0 = 0xFF; obp1 = 0xFF
        line = 0; ly = 0; lineDot = 0
        windowLine = 0; wyTriggered = false
        statLine = false
        lycMatch = true
        lycBlank = false
        firstLine = false
        vblankOamIrq = false
        vbk = 0
        opri = if (cgb) 0 else 1
        Arrays.fill(bgPalRam, 0xFF.toByte())
        Arrays.fill(objPalRam, 0xFF.toByte())
        for (i in 0 until 32) {
            bgColors[i] = paletteColor(bgPalRam, i)
            objColors[i] = paletteColor(objPalRam, i)
        }
        startMode2()
        blankScreen()
    }

    fun saveState(w: StateWriter) {
        w.tag("ppu")
        w.bytes(vram); w.bytes(oam); w.bytes(bgPalRam); w.bytes(objPalRam)
        w.ints(intArrayOf(vbk, lcdc, statSelect, scy, scx, ly, lyc, bgp, obp0, obp1, wy, wx, mode,
            line, lineDot, nextEvent, mode3Length, windowLine, renderX, mode3Scx, bcps, ocps, opri, visibleMode))
        w.bool(statLine); w.bool(wyTriggered); w.bool(windowDrawn)
        w.bool(lycMatch); w.bool(lycBlank); w.bool(firstLine); w.bool(vblankOamIrq); w.bool(firstLineMode3)
        w.ints(frameBuffer)
        w.ints(objColor); w.ints(objAttr)
    }

    fun loadState(r: StateReader) {
        r.tag("ppu")
        r.bytesInto(vram); r.bytesInto(oam); r.bytesInto(bgPalRam); r.bytesInto(objPalRam)
        val v = IntArray(24)
        r.intsInto(v)
        vbk = v[0]; lcdc = v[1]; statSelect = v[2]; scy = v[3]; scx = v[4]; ly = v[5]; lyc = v[6]
        bgp = v[7]; obp0 = v[8]; obp1 = v[9]; wy = v[10]; wx = v[11]; mode = v[12]
        line = v[13]; lineDot = v[14]; nextEvent = v[15]; mode3Length = v[16]; windowLine = v[17]
        renderX = v[18]; mode3Scx = v[19]; bcps = v[20]; ocps = v[21]; opri = v[22]; visibleMode = v[23]
        statLine = r.bool(); wyTriggered = r.bool(); windowDrawn = r.bool()
        lycMatch = r.bool(); lycBlank = r.bool(); firstLine = r.bool(); vblankOamIrq = r.bool(); firstLineMode3 = r.bool()
        r.intsInto(frameBuffer)
        r.intsInto(objColor); r.intsInto(objAttr)
        for (i in 0 until 32) {
            bgColors[i] = paletteColor(bgPalRam, i)
            objColors[i] = paletteColor(objPalRam, i)
        }
    }

    companion object {
        const val WIDTH = 160
        const val HEIGHT = 144
        const val LINE_DOTS = 456
    }
}
