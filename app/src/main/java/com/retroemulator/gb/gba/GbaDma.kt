package com.retroemulator.gb.gba

import com.retroemulator.gb.core.StateReader
import com.retroemulator.gb.core.StateWriter

/** The four DMA channels: immediate, VBlank, HBlank and sound-FIFO transfers. */
class GbaDma(private val gba: Gba) {
    private val src = IntArray(4)
    private val dst = IntArray(4)
    private val count = IntArray(4)
    private val enabled = BooleanArray(4)

    private fun ioBase(ch: Int) = 0x0B0 + ch * 12
    private fun control(ch: Int) = gba.bus.ioGet16(ioBase(ch) + 10)

    fun writeControl(ch: Int, value: Int) {
        val enable = value and 0x8000 != 0
        if (enable && !enabled[ch]) {
            enabled[ch] = true
            val b = ioBase(ch)
            val sad = gba.bus.ioGet16(b) or (gba.bus.ioGet16(b + 2) shl 16)
            val dad = gba.bus.ioGet16(b + 4) or (gba.bus.ioGet16(b + 6) shl 16)
            src[ch] = sad and (if (ch == 0) 0x07FFFFFF else 0x0FFFFFFF)
            dst[ch] = dad and (if (ch == 3) 0x0FFFFFFF else 0x07FFFFFF)
            count[ch] = reloadCount(ch)
            if ((value ushr 12) and 3 == 0) run(ch)
        } else if (!enable) {
            enabled[ch] = false
        }
    }

    private fun reloadCount(ch: Int): Int {
        val c = gba.bus.ioGet16(ioBase(ch) + 8)
        return if (ch == 3) (if (c == 0) 0x10000 else c) else (c and 0x3FFF).let { if (it == 0) 0x4000 else it }
    }

    fun onVBlank() {
        for (ch in 0 until 4) if (enabled[ch] && (control(ch) ushr 12) and 3 == 1) run(ch)
    }

    fun onHBlank() {
        for (ch in 0 until 4) if (enabled[ch] && (control(ch) ushr 12) and 3 == 2) run(ch)
    }

    /** A sound FIFO is running low: refill it if channel 1 or 2 is set up for it. */
    fun onFifoRequest(fifoAddr: Int) {
        for (ch in 1..2) {
            if (enabled[ch] && (control(ch) ushr 12) and 3 == 3 && dst[ch] and 0xFFFFFFF == fifoAddr) {
                runFifo(ch)
                return
            }
        }
    }

    private fun run(ch: Int) {
        val c = control(ch)
        val word = c and 0x400 != 0
        val size = if (word) 4 else 2
        val dstCtl = (c ushr 5) and 3
        val srcStep = when ((c ushr 7) and 3) { 1 -> -size; 2 -> 0; else -> size }
        val dstStep = when (dstCtl) { 1 -> -size; 2 -> 0; else -> size }
        val n = count[ch]
        var s = src[ch]
        var d = dst[ch]
        val bus = gba.bus

        if (ch == 3) gba.backup.onDmaLength(s, d, n)

        val sRegion = (s ushr 24) and 0xF
        val dRegion = (d ushr 24) and 0xF
        var cost = 2L
        if (word) {
            cost += bus.n32[sRegion] + bus.n32[dRegion] + (n - 1).toLong() * (bus.s32[sRegion] + bus.s32[dRegion])
            for (k in 0 until n) {
                bus.write32(d and 3.inv(), bus.read32(s and 3.inv()))
                s += srcStep; d += dstStep
            }
        } else {
            cost += bus.n16[sRegion] + bus.n16[dRegion] + (n - 1).toLong() * (bus.s16[sRegion] + bus.s16[dRegion])
            for (k in 0 until n) {
                bus.write16(d and 1.inv(), bus.read16(s and 1.inv()))
                s += srcStep; d += dstStep
            }
        }
        gba.cycles += cost
        src[ch] = s
        dst[ch] = d
        finish(ch, c, dstCtl)
    }

    private fun runFifo(ch: Int) {
        val c = control(ch)
        val srcStep = when ((c ushr 7) and 3) { 1 -> -4; 2 -> 0; else -> 4 }
        var s = src[ch]
        val bus = gba.bus
        for (k in 0 until 4) {
            bus.write32(dst[ch], bus.read32(s and 3.inv()))
            s += srcStep
        }
        src[ch] = s
        gba.cycles += 2L + bus.n32[(s ushr 24) and 0xF] * 4
        if (c and 0x4000 != 0) gba.requestIrq(8 + ch)
    }

    private fun finish(ch: Int, c: Int, dstCtl: Int) {
        if (c and 0x4000 != 0) gba.requestIrq(8 + ch)
        val timing = (c ushr 12) and 3
        if (c and 0x200 != 0 && timing != 0) {
            count[ch] = reloadCount(ch)
            if (dstCtl == 3) {
                val b = ioBase(ch)
                val dad = gba.bus.ioGet16(b + 4) or (gba.bus.ioGet16(b + 6) shl 16)
                dst[ch] = dad and (if (ch == 3) 0x0FFFFFFF else 0x07FFFFFF)
            }
        } else {
            enabled[ch] = false
            gba.bus.ioPut16(ioBase(ch) + 10, c and 0x8000.inv())
        }
    }

    fun saveState(w: StateWriter) {
        w.tag("gbadma")
        w.ints(src); w.ints(dst); w.ints(count)
        for (e in enabled) w.bool(e)
    }

    fun loadState(r: StateReader) {
        r.tag("gbadma")
        r.intsInto(src); r.intsInto(dst); r.intsInto(count)
        for (i in 0 until 4) enabled[i] = r.bool()
    }
}
