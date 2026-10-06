package com.retroemulator.gb.gba

import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-level replacement for the (copyrighted) GBA BIOS. Software interrupts are executed directly
 * in Kotlin; the only code in the BIOS area is a small interrupt dispatcher that calls the game's
 * handler through 0x03007FFC, exactly like the original.
 */
class GbaBios(private val gba: Gba) {
    private var waiting = false

    fun swi(number: Int, returnAddr: Int) {
        val cpu = gba.cpu
        val r = cpu.r
        gba.cycles += 20
        when (number) {
            0x00, 0x26 -> softReset()
            0x01 -> registerRamReset(r[0])
            0x02, 0x03 -> { gba.halted = true; gba.scheduleDirty = true }
            0x04 -> intrWait(r[0] != 0, r[1], returnAddr)
            0x05 -> { r[0] = 1; r[1] = 1; intrWait(true, 1, returnAddr) }
            0x06 -> divide(r[0], r[1])
            0x07 -> divide(r[1], r[0])
            0x08 -> r[0] = isqrt(r[0].toLong() and 0xFFFFFFFFL)
            0x09 -> r[0] = arcTan(r[0])
            0x0A -> r[0] = arcTan2(r[0], r[1])
            0x0B -> cpuSet(r[0], r[1], r[2])
            0x0C -> cpuFastSet(r[0], r[1], r[2])
            0x0D -> r[0] = 0xBAAE187F.toInt()
            0x0E -> bgAffineSet(r[0], r[1], r[2])
            0x0F -> objAffineSet(r[0], r[1], r[2], r[3])
            0x10 -> bitUnPack(r[0], r[1], r[2])
            0x11 -> lz77(r[0], r[1], vram = false)
            0x12 -> lz77(r[0], r[1], vram = true)
            0x13 -> huffman(r[0], r[1])
            0x14 -> runLength(r[0], r[1], vram = false)
            0x15 -> runLength(r[0], r[1], vram = true)
            0x16 -> diff8(r[0], r[1], vram = false)
            0x17 -> diff8(r[0], r[1], vram = true)
            0x18 -> diff16(r[0], r[1])
            0x19 -> gba.bus.ioWrite16(0x088, (gba.bus.ioGet16(0x088) and 0x3FF.inv()) or (if (r[0] != 0) 0x200 else 0))
            0x1F -> r[0] = midiKeyToFreq(r[0], r[1], r[2])
            else -> {} // Sound driver functions and multiboot are not needed by games that bring their own driver.
        }
        // Returning from the real BIOS leaves its last prefetched opcode on the bus and refills the pipeline.
        gba.bus.biosLatch = SWI_RETURN_LATCH
        cpu.flushPipeline()
    }

    // ------------------------------------------------------------------------------------------
    // System calls
    // ------------------------------------------------------------------------------------------

    private fun softReset() {
        val bus = gba.bus
        val toRam = bus.read8(0x03007FFA) != 0
        for (a in 0x7E00 until 0x8000) bus.iwram[a] = 0
        val cpu = gba.cpu
        cpu.setCpsr(Arm7.MODE_SVC); cpu.r[13] = 0x03007FE0; cpu.r[14] = 0
        cpu.setCpsr(Arm7.MODE_IRQ); cpu.r[13] = 0x03007FA0; cpu.r[14] = 0
        cpu.setCpsr(Arm7.MODE_SYS); cpu.r[13] = 0x03007F00
        for (i in 0..12) cpu.r[i] = 0
        cpu.r[14] = 0
        cpu.jump(if (toRam) 0x02000000 else 0x08000000)
    }

    private fun registerRamReset(flags: Int) {
        val bus = gba.bus
        if (flags and 0x01 != 0) bus.ewram.fill(0)
        if (flags and 0x02 != 0) bus.iwram.fill(0, 0, 0x7E00)
        if (flags and 0x04 != 0) bus.palette.fill(0)
        if (flags and 0x08 != 0) bus.vram.fill(0)
        if (flags and 0x10 != 0) bus.oam.fill(0)
        if (flags and 0x40 != 0) for (a in 0x060..0x0A6 step 2) bus.ioWrite16(a, 0)
        if (flags and 0x80 != 0) {
            for (a in 0x000..0x05E step 2) if (a != 0x004 && a != 0x006) bus.ioWrite16(a, 0)
            bus.ioWrite16(0x000, 0x0080)
            // Like the real BIOS, leave BG2/BG3 with an identity affine matrix.
            for (a in intArrayOf(0x020, 0x026, 0x030, 0x036)) bus.ioWrite16(a, 0x100)
        }
    }

    /**
     * IntrWait: return once one of [mask]'s bits is set in the BIOS interrupt flags at 0x03007FF8
     * (the game's IRQ handler sets them). While waiting the CPU halts and re-executes the SWI after
     * each interrupt, which is equivalent to the BIOS's own halt loop.
     */
    private fun intrWait(discardOld: Boolean, mask: Int, returnAddr: Int) {
        val bus = gba.bus
        gba.ime = true
        gba.updateIrq()
        gba.cpu.irqDisabled = false
        val flagsAddr = 0x03007FF8
        var flags = bus.read16(flagsAddr)
        if (discardOld && !waiting) {
            flags = flags and mask.inv()
            bus.write16(flagsAddr, flags)
        } else if (flags and mask != 0) {
            bus.write16(flagsAddr, flags and mask.inv())
            waiting = false
            return
        }
        waiting = true
        gba.halted = true
        gba.scheduleDirty = true
        // Resume at the SWI instruction itself so it runs again after the interrupt.
        gba.cpu.jump(returnAddr - if (gba.cpu.thumb) 2 else 4)
    }

    private fun divide(num: Int, den: Int) {
        val r = gba.cpu.r
        if (den == 0) {
            r[0] = if (num < 0) -1 else 1
            r[1] = num
            r[3] = 1
            return
        }
        val q = num / den
        r[0] = q
        r[1] = num % den
        r[3] = if (q < 0) -q else q
        gba.cycles += 40
    }

    private fun isqrt(v: Long): Int {
        var s = sqrt(v.toDouble()).toLong()
        while (s * s > v) s--
        while ((s + 1) * (s + 1) <= v) s++
        return s.toInt()
    }

    private fun arcTan(value: Int): Int {
        val i = value.toShort().toInt()
        val a = -((i * i) shr 14)
        var b = ((0xA9 * a) shr 14) + 0x390
        b = ((b * a) shr 14) + 0x91C
        b = ((b * a) shr 14) + 0xFB6
        b = ((b * a) shr 14) + 0x16AA
        b = ((b * a) shr 14) + 0x2081
        b = ((b * a) shr 14) + 0x3651
        b = ((b * a) shr 14) + 0xA2F9
        return (i * b) shr 16
    }

    private fun arcTan2(x: Int, y: Int): Int {
        val sx = x.toShort().toInt()
        val sy = y.toShort().toInt()
        if (sx == 0 && sy == 0) return 0
        var angle = atan2(sy.toDouble(), sx.toDouble()) / (2 * Math.PI)
        if (angle < 0) angle += 1.0
        return (angle * 65536).toInt() and 0xFFFF
    }

    private fun cpuSet(srcIn: Int, dstIn: Int, control: Int) {
        val bus = gba.bus
        if (srcIn and 0x0E000000 == 0) return // reads from the BIOS area are refused
        val count = control and 0x1FFFFF
        val fill = control and (1 shl 24) != 0
        var src = srcIn
        var dst = dstIn
        if (control and (1 shl 26) != 0) {
            src = src and 3.inv(); dst = dst and 3.inv()
            val value = bus.read32(src)
            for (i in 0 until count) {
                bus.write32(dst, if (fill) value else bus.read32(src))
                if (!fill) src += 4
                dst += 4
            }
            gba.cycles += count.toLong() * 4
        } else {
            src = src and 1.inv(); dst = dst and 1.inv()
            val value = bus.read16(src)
            for (i in 0 until count) {
                bus.write16(dst, if (fill) value else bus.read16(src))
                if (!fill) src += 2
                dst += 2
            }
            gba.cycles += count.toLong() * 3
        }
    }

    private fun cpuFastSet(srcIn: Int, dstIn: Int, control: Int) {
        val bus = gba.bus
        if (srcIn and 0x0E000000 == 0) return
        val count = ((control and 0x1FFFFF) + 7) and 7.inv()
        val fill = control and (1 shl 24) != 0
        var src = srcIn and 3.inv()
        var dst = dstIn and 3.inv()
        val value = bus.read32(src)
        for (i in 0 until count) {
            bus.write32(dst, if (fill) value else bus.read32(src))
            if (!fill) src += 4
            dst += 4
        }
        gba.cycles += count.toLong() * 2
    }

    private fun bgAffineSet(srcIn: Int, dstIn: Int, count: Int) {
        val bus = gba.bus
        var src = srcIn
        var dst = dstIn
        for (i in 0 until count) {
            val ox = bus.read32(src)
            val oy = bus.read32(src + 4)
            val cx = bus.read16(src + 8).toShort().toInt()
            val cy = bus.read16(src + 10).toShort().toInt()
            val sx = bus.read16(src + 12).toShort().toInt()
            val sy = bus.read16(src + 14).toShort().toInt()
            val theta = (bus.read16(src + 16) ushr 8) and 0xFF
            val s = SIN[theta]
            val c = SIN[(theta + 64) and 0xFF]
            val pa = (sx * c) shr 14
            val pb = -((sx * s) shr 14)
            val pc = (sy * s) shr 14
            val pd = (sy * c) shr 14
            bus.write16(dst, pa); bus.write16(dst + 2, pb); bus.write16(dst + 4, pc); bus.write16(dst + 6, pd)
            bus.write32(dst + 8, ox - (pa * cx + pb * cy))
            bus.write32(dst + 12, oy - (pc * cx + pd * cy))
            src += 20
            dst += 16
        }
    }

    private fun objAffineSet(srcIn: Int, dstIn: Int, count: Int, stride: Int) {
        val bus = gba.bus
        var src = srcIn
        var dst = dstIn
        for (i in 0 until count) {
            val sx = bus.read16(src).toShort().toInt()
            val sy = bus.read16(src + 2).toShort().toInt()
            val theta = (bus.read16(src + 4) ushr 8) and 0xFF
            val s = SIN[theta]
            val c = SIN[(theta + 64) and 0xFF]
            bus.write16(dst, (sx * c) shr 14)
            bus.write16(dst + stride, -((sx * s) shr 14))
            bus.write16(dst + stride * 2, (sy * s) shr 14)
            bus.write16(dst + stride * 3, (sy * c) shr 14)
            src += 8
            dst += stride * 4
        }
    }

    private fun bitUnPack(srcIn: Int, dstIn: Int, info: Int) {
        val bus = gba.bus
        val length = bus.read16(info)
        val srcWidth = bus.read8(info + 2)
        val dstWidth = bus.read8(info + 3)
        val offsetWord = bus.read32(info + 4)
        val offset = offsetWord and 0x7FFFFFFF
        val zeroFlag = offsetWord < 0
        if (srcWidth !in intArrayOf(1, 2, 4, 8) || dstWidth !in intArrayOf(1, 2, 4, 8, 16, 32)) return
        var src = srcIn
        var dst = dstIn
        var out = 0
        var outBits = 0
        val srcMask = (1 shl srcWidth) - 1
        for (i in 0 until length) {
            val b = bus.read8(src++)
            var bit = 0
            while (bit < 8) {
                var unit = (b ushr bit) and srcMask
                if (unit != 0 || zeroFlag) unit += offset
                out = out or ((if (dstWidth == 32) unit else unit and ((1 shl dstWidth) - 1)) shl outBits)
                outBits += dstWidth
                if (outBits >= 32) {
                    bus.write32(dst, out)
                    dst += 4
                    out = 0
                    outBits = 0
                }
                bit += srcWidth
            }
        }
    }

    private fun writeOut(dstIn: Int, data: ByteArray, vram: Boolean) {
        val bus = gba.bus
        if (vram) {
            var i = 0
            while (i + 1 < data.size) {
                bus.write16(dstIn + i, (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8))
                i += 2
            }
            if (i < data.size) bus.write16(dstIn + i, data[i].toInt() and 0xFF)
        } else {
            for (i in data.indices) bus.write8(dstIn + i, data[i].toInt() and 0xFF)
        }
        gba.cycles += data.size.toLong()
    }

    private fun lz77(srcIn: Int, dstIn: Int, vram: Boolean) {
        val bus = gba.bus
        val header = bus.read32(srcIn)
        val size = header ushr 8
        if (size <= 0 || size > 0x1000000) return
        val out = ByteArray(size)
        var src = srcIn + 4
        var n = 0
        while (n < size) {
            val flags = bus.read8(src++)
            for (bit in 7 downTo 0) {
                if (n >= size) break
                if (flags and (1 shl bit) != 0) {
                    val b1 = bus.read8(src++)
                    val b2 = bus.read8(src++)
                    val len = (b1 ushr 4) + 3
                    val disp = (((b1 and 0xF) shl 8) or b2) + 1
                    for (k in 0 until len) {
                        if (n >= size) break
                        val from = n - disp
                        out[n] = if (from >= 0) out[from] else 0
                        n++
                    }
                } else {
                    out[n++] = bus.read8(src++).toByte()
                }
            }
        }
        writeOut(dstIn, out, vram)
    }

    private fun runLength(srcIn: Int, dstIn: Int, vram: Boolean) {
        val bus = gba.bus
        val size = bus.read32(srcIn) ushr 8
        if (size <= 0 || size > 0x1000000) return
        val out = ByteArray(size)
        var src = srcIn + 4
        var n = 0
        while (n < size) {
            val flag = bus.read8(src++)
            if (flag and 0x80 != 0) {
                val len = (flag and 0x7F) + 3
                val v = bus.read8(src++).toByte()
                for (k in 0 until len) { if (n < size) out[n++] = v }
            } else {
                val len = (flag and 0x7F) + 1
                for (k in 0 until len) { if (n < size) out[n++] = bus.read8(src++).toByte() else src++ }
            }
        }
        writeOut(dstIn, out, vram)
    }

    private fun huffman(srcIn: Int, dstIn: Int) {
        val bus = gba.bus
        val header = bus.read32(srcIn)
        val dataBits = header and 0xF
        val size = header ushr 8
        if (size <= 0 || size > 0x1000000 || (dataBits != 4 && dataBits != 8)) return
        val treeSize = (bus.read8(srcIn + 4) + 1) * 2
        val treeStart = srcIn + 5
        var stream = srcIn + 4 + treeSize
        var dst = dstIn
        var outWord = 0
        var outBits = 0
        var written = 0
        var node = treeStart
        var nodeValue = bus.read8(node)
        while (written < size) {
            val word = bus.read32(stream)
            stream += 4
            for (bit in 31 downTo 0) {
                val dir = (word ushr bit) and 1
                val childBase = (node and 1.inv()) + ((nodeValue and 0x3F) shl 1) + 2
                val child = childBase + dir
                val isData = nodeValue and (if (dir == 0) 0x80 else 0x40) != 0
                if (isData) {
                    outWord = outWord or ((bus.read8(child) and ((1 shl dataBits) - 1)) shl outBits)
                    outBits += dataBits
                    if (outBits == 32) {
                        bus.write32(dst, outWord)
                        dst += 4
                        written += 4
                        outWord = 0
                        outBits = 0
                        if (written >= size) break
                    }
                    node = treeStart
                    nodeValue = bus.read8(node)
                } else {
                    node = child
                    nodeValue = bus.read8(node)
                }
            }
        }
    }

    private fun diff8(srcIn: Int, dstIn: Int, vram: Boolean) {
        val bus = gba.bus
        val size = bus.read32(srcIn) ushr 8
        if (size <= 0 || size > 0x1000000) return
        val out = ByteArray(size)
        var acc = 0
        for (i in 0 until size) {
            acc = (acc + bus.read8(srcIn + 4 + i)) and 0xFF
            out[i] = acc.toByte()
        }
        writeOut(dstIn, out, vram)
    }

    private fun diff16(srcIn: Int, dstIn: Int) {
        val bus = gba.bus
        val size = bus.read32(srcIn) ushr 8
        if (size <= 0 || size > 0x1000000) return
        var acc = 0
        var i = 0
        while (i < size) {
            acc = (acc + bus.read16(srcIn + 4 + i)) and 0xFFFF
            bus.write16(dstIn + i, acc)
            i += 2
        }
    }

    private fun midiKeyToFreq(wave: Int, key: Int, fine: Int): Int {
        val freq = gba.bus.read32(wave + 4).toLong() and 0xFFFFFFFFL
        val exponent = (180.0 - (key and 0xFF) - (fine and 0xFF) / 256.0) / 12.0
        return (freq / 2.0.pow(exponent)).toLong().toInt()
    }

    companion object {
        /** sin(2 * pi * i / 256) in 1.14 fixed point. */
        private val SIN = IntArray(256) { (sin(2 * Math.PI * it / 256.0) * 16384).roundToInt() }

        /** Opcode at 0x190 of the original BIOS, prefetched as an SWI returns. */
        private const val SWI_RETURN_LATCH = 0xE3A02004.toInt()

        /** A 16 KB BIOS image containing only the exception vectors and the IRQ dispatcher. */
        fun image(): ByteArray {
            val b = ByteArray(0x4000)
            fun put(addr: Int, op: Int) {
                b[addr] = op.toByte(); b[addr + 1] = (op ushr 8).toByte()
                b[addr + 2] = (op ushr 16).toByte(); b[addr + 3] = (op ushr 24).toByte()
            }
            put(0x00, 0xEAFFFFFE.toInt()) // reset: b .
            put(0x04, 0xE1B0F00E.toInt()) // undefined: movs pc, lr
            put(0x08, 0xE1B0F00E.toInt()) // swi (handled in Kotlin): movs pc, lr
            put(0x0C, 0xE25EF004.toInt()) // prefetch abort: subs pc, lr, #4
            put(0x10, 0xE25EF008.toInt()) // data abort: subs pc, lr, #8
            put(0x18, 0xEA000042.toInt()) // irq: b 0x128
            put(0x1C, 0xE25EF004.toInt()) // fiq: subs pc, lr, #4
            // IRQ dispatcher at 0x128: save scratch registers, call the handler at [0x03FFFFFC].
            put(0x128, 0xE92D500F.toInt()) // stmfd sp!, {r0-r3, r12, lr}
            put(0x12C, 0xE3A00301.toInt()) // mov r0, #0x04000000
            put(0x130, 0xE28FE000.toInt()) // add lr, pc, #0
            put(0x134, 0xE510F004.toInt()) // ldr pc, [r0, #-4]
            put(0x138, 0xE8BD500F.toInt()) // ldmfd sp!, {r0-r3, r12, lr}
            put(0x13C, 0xE25EF004.toInt()) // subs pc, lr, #4
            // Opcodes the original BIOS has next, so open-bus reads after an IRQ or SWI match it.
            put(0x140, 0xE92D5800.toInt()) // stmfd sp!, {r11, r12, lr}
            put(0x144, 0xE55EC002.toInt()) // ldrb r12, [lr, #-2]
            put(0x190, SWI_RETURN_LATCH)
            return b
        }
    }
}
