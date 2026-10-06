package com.retroemulator.gb.gba

import com.retroemulator.gb.core.StateReader
import com.retroemulator.gb.core.StateWriter
import java.util.Arrays

/** GBA memory map, IO register dispatch and wait-state tables. */
class GbaBus(private val gba: Gba) {
    val bios = GbaBios.image()
    val ewram = ByteArray(0x40000)
    val iwram = ByteArray(0x8000)
    val io = ByteArray(0x400)
    val palette = ByteArray(0x400)
    val vram = ByteArray(0x18000)
    val oam = ByteArray(0x400)
    private val rom = gba.rom
    private val romSize = rom.size
    /**
     * Classic NES Series cartridges (game codes starting with F) mirror their ROM across the whole
     * cartridge space, and the games check for it as copy protection.
     */
    private val romMirror = romSize > 0xAC && rom[0xAC] == 'F'.code.toByte() && romSize and (romSize - 1) == 0
    private val romMask = if (romMirror) romSize - 1 else 0x1FFFFFF

    /** Last opcode fetched from the BIOS; returned for BIOS reads made from outside it. */
    var biosLatch = 0xE129F000.toInt()

    // Access costs in cycles (1 + wait states), indexed by address bits 24-27.
    val n16 = IntArray(16) { 1 }
    val s16 = IntArray(16) { 1 }
    val n32 = IntArray(16) { 1 }
    val s32 = IntArray(16) { 1 }
    var prefetch = false
        private set

    fun reset() {
        Arrays.fill(ewram, 0); Arrays.fill(iwram, 0); Arrays.fill(io, 0)
        Arrays.fill(palette, 0); Arrays.fill(vram, 0); Arrays.fill(oam, 0)
        biosLatch = 0xE129F000.toInt()
        // The BIOS leaves BG2/BG3 with an identity affine matrix.
        ioPut16(0x020, 0x100); ioPut16(0x026, 0x100)
        ioPut16(0x030, 0x100); ioPut16(0x036, 0x100)
        ioPut16(0x088, 0x200) // SOUNDBIAS
        ioPut16(0x130, 0x3FF) // KEYINPUT
        ioPut16(0x134, 0x8000) // RCNT
        io[0x300] = 1 // POSTFLG
        updateWaitstates(0)
    }

    fun updateWaitstates(waitcnt: Int) {
        val nTable = intArrayOf(4, 3, 2, 8)
        // EWRAM: 2 wait states, 16-bit bus. IWRAM, IO, OAM: none. Palette/VRAM: 16-bit bus.
        n16[2] = 3; s16[2] = 3; n32[2] = 6; s32[2] = 6
        n16[5] = 1; s16[5] = 1; n32[5] = 2; s32[5] = 2
        n16[6] = 1; s16[6] = 1; n32[6] = 2; s32[6] = 2
        val sram = nTable[waitcnt and 3] + 1
        n16[0xE] = sram; s16[0xE] = sram; n32[0xE] = sram; s32[0xE] = sram
        n16[0xF] = sram; s16[0xF] = sram; n32[0xF] = sram; s32[0xF] = sram
        val ws = intArrayOf(
            nTable[(waitcnt ushr 2) and 3], if (waitcnt and 0x10 != 0) 1 else 2,
            nTable[(waitcnt ushr 5) and 3], if (waitcnt and 0x80 != 0) 1 else 4,
            nTable[(waitcnt ushr 8) and 3], if (waitcnt and 0x400 != 0) 1 else 8,
        )
        for (w in 0 until 3) {
            val n = ws[w * 2] + 1
            val s = ws[w * 2 + 1] + 1
            for (region in intArrayOf(8 + w * 2, 9 + w * 2)) {
                n16[region] = n; s16[region] = s
                n32[region] = n + s; s32[region] = 2 * s
            }
        }
        prefetch = waitcnt and 0x4000 != 0
    }

    // ------------------------------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------------------------------

    fun read8(addr: Int): Int = when (addr ushr 24) {
        0x00 -> if (addr < 0x4000) readBios32(addr and 3.inv()) ushr ((addr and 3) shl 3) and 0xFF else openBus8(addr)
        0x02 -> ewram[addr and 0x3FFFF].toInt() and 0xFF
        0x03 -> iwram[addr and 0x7FFF].toInt() and 0xFF
        0x04 -> ioRead8(addr and 0xFFFFFF)
        0x05 -> palette[addr and 0x3FF].toInt() and 0xFF
        0x06 -> vram[vramIndex(addr)].toInt() and 0xFF
        0x07 -> oam[addr and 0x3FF].toInt() and 0xFF
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D -> romRead8(addr)
        0x0E, 0x0F -> gba.backup.read8(addr)
        else -> openBus8(addr)
    }

    fun read16(addr: Int): Int = when (addr ushr 24) {
        0x00 -> if (addr < 0x4000) readBios32(addr and 3.inv()) ushr ((addr and 2) shl 3) and 0xFFFF else openBus16(addr)
        0x02 -> get16(ewram, addr and 0x3FFFE)
        0x03 -> get16(iwram, addr and 0x7FFE)
        0x04 -> ioRead16(addr and 0xFFFFFE)
        0x05 -> get16(palette, addr and 0x3FE)
        0x06 -> get16(vram, vramIndex(addr) and 1.inv())
        0x07 -> get16(oam, addr and 0x3FE)
        0x08, 0x09, 0x0A, 0x0B, 0x0C -> romRead16(addr)
        0x0D -> if (gba.backup.isEepromAddress(addr, romSize)) gba.backup.eepromRead() else romRead16(addr)
        0x0E, 0x0F -> gba.backup.read8(addr) * 0x0101
        else -> openBus16(addr)
    }

    fun read32(addr: Int): Int = when (addr ushr 24) {
        0x00 -> if (addr < 0x4000) readBios32(addr and 3.inv()) else gba.cpu.openBus()
        0x02 -> get32(ewram, addr and 0x3FFFC)
        0x03 -> get32(iwram, addr and 0x7FFC)
        0x04 -> ioRead16(addr and 0xFFFFFC) or (ioRead16((addr and 0xFFFFFC) + 2) shl 16)
        0x05 -> get32(palette, addr and 0x3FC)
        0x06 -> get32(vram, vramIndex(addr) and 3.inv())
        0x07 -> get32(oam, addr and 0x3FC)
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D -> romRead16(addr and 3.inv()) or (romRead16((addr and 3.inv()) + 2) shl 16)
        0x0E, 0x0F -> gba.backup.read8(addr) * 0x01010101
        else -> gba.cpu.openBus()
    }

    /** BIOS reads only work while executing inside the BIOS; otherwise the last fetched opcode is seen. */
    private fun readBios32(aligned: Int): Int =
        if (gba.cpu.pc < 0x4000) get32(bios, aligned) else biosLatch

    /** Instruction fetch from the BIOS updates the latch. */
    fun fetchBios32(addr: Int): Int {
        val v = get32(bios, addr and 0x3FFC)
        biosLatch = v
        return v
    }

    private fun openBus16(addr: Int): Int = gba.cpu.openBus() ushr ((addr and 2) shl 3) and 0xFFFF
    private fun openBus8(addr: Int): Int = gba.cpu.openBus() ushr ((addr and 3) shl 3) and 0xFF

    private fun romRead16(addr: Int): Int {
        val off = addr and 0x1FFFFFE and romMask
        if (off < romSize - 1) return get16(rom, off)
        return (off ushr 1) and 0xFFFF // empty cartridge bus returns the address
    }

    private fun romRead8(addr: Int): Int {
        val off = addr and 0x1FFFFFF and romMask
        if (off < romSize) return rom[off].toInt() and 0xFF
        return ((off ushr 1) ushr ((off and 1) shl 3)) and 0xFF
    }

    fun vramIndex(addr: Int): Int {
        val off = addr and 0x1FFFF
        return if (off >= 0x18000) off - 0x8000 else off
    }

    // ------------------------------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------------------------------

    fun write8(addr: Int, value: Int) {
        when (addr ushr 24) {
            0x02 -> ewram[addr and 0x3FFFF] = value.toByte()
            0x03 -> iwram[addr and 0x7FFF] = value.toByte()
            0x04 -> ioWrite8(addr and 0xFFFFFF, value and 0xFF)
            // Byte writes to palette and background VRAM fill the whole halfword; OBJ VRAM and OAM ignore them.
            0x05 -> { val i = addr and 0x3FE; palette[i] = value.toByte(); palette[i + 1] = value.toByte() }
            0x06 -> {
                val i = vramIndex(addr) and 1.inv()
                val bgLimit = if (gba.ppu.bitmapMode) 0x14000 else 0x10000
                if (i < bgLimit) { vram[i] = value.toByte(); vram[i + 1] = value.toByte() }
            }
            0x0E, 0x0F -> gba.backup.write8(addr, value and 0xFF)
        }
    }

    fun write16(addr: Int, value: Int) {
        when (addr ushr 24) {
            0x02 -> put16(ewram, addr and 0x3FFFE, value)
            0x03 -> put16(iwram, addr and 0x7FFE, value)
            0x04 -> ioWrite16(addr and 0xFFFFFE, value and 0xFFFF)
            0x05 -> put16(palette, addr and 0x3FE, value)
            0x06 -> put16(vram, vramIndex(addr) and 1.inv(), value)
            0x07 -> put16(oam, addr and 0x3FE, value)
            0x0D -> if (gba.backup.isEepromAddress(addr, romSize)) gba.backup.eepromWrite(value)
            0x0E, 0x0F -> gba.backup.write8(addr, (value ushr ((addr and 1) shl 3)) and 0xFF)
        }
    }

    fun write32(addr: Int, value: Int) {
        when (addr ushr 24) {
            0x02 -> put32(ewram, addr and 0x3FFFC, value)
            0x03 -> put32(iwram, addr and 0x7FFC, value)
            0x04 -> {
                val a = addr and 0xFFFFFC
                if (a == 0x0A0 || a == 0x0A4) {
                    gba.apu.writeFifo32(a, value)
                } else {
                    ioWrite16(a, value and 0xFFFF)
                    ioWrite16(a + 2, value ushr 16)
                }
            }
            0x05 -> put32(palette, addr and 0x3FC, value)
            0x06 -> put32(vram, vramIndex(addr) and 3.inv(), value)
            0x07 -> put32(oam, addr and 0x3FC, value)
            0x0D -> if (gba.backup.isEepromAddress(addr, romSize)) gba.backup.eepromWrite(value)
            0x0E, 0x0F -> gba.backup.write8(addr, (value ushr ((addr and 3) shl 3)) and 0xFF)
        }
    }

    // ------------------------------------------------------------------------------------------
    // IO registers
    // ------------------------------------------------------------------------------------------

    fun ioGet16(off: Int): Int = get16(io, off)
    fun ioPut16(off: Int, value: Int) = put16(io, off, value)

    fun ioRead8(off: Int): Int {
        if (off >= 0x400) return 0
        return ioRead16(off and 1.inv()) ushr ((off and 1) shl 3) and 0xFF
    }

    fun ioRead16(off: Int): Int {
        if (off >= 0x400) return 0
        return when (off) {
            0x004 -> gba.ppu.readDispstat()
            0x006 -> gba.ppu.vcount
            in 0x060..0x0A6 -> gba.apu.read16(off)
            0x100, 0x104, 0x108, 0x10C -> gba.timers.readCounter((off - 0x100) ushr 2)
            0x130 -> gba.keyInput
            0x200 -> gba.ie
            0x202 -> gba.iflags
            0x208 -> if (gba.ime) 1 else 0
            // Write-only registers read as zero.
            in 0x010..0x03E, 0x040, 0x042, 0x044, 0x046, 0x04C, 0x054 -> 0
            in 0x0B0..0x0DE -> if ((off - 0x0B0) % 12 == 10) get16(io, off) else 0
            else -> get16(io, off)
        }
    }

    fun ioWrite8(off: Int, value: Int) {
        if (off >= 0x400) return
        when (off) {
            in 0x060..0x0A7 -> { gba.apu.write8(off, value); return }
            0x202 -> { gba.iflags = gba.iflags and value.inv(); gba.updateIrq(); return }
            0x203 -> { gba.iflags = gba.iflags and (value shl 8).inv(); gba.updateIrq(); return }
            0x300 -> { io[0x300] = value.toByte(); return }
            0x301 -> { gba.halted = true; gba.scheduleDirty = true; return }
        }
        val aligned = off and 1.inv()
        val current = get16(io, aligned)
        val merged = if (off and 1 == 0) (current and 0xFF00) or value else (current and 0x00FF) or (value shl 8)
        ioWrite16(aligned, merged)
    }

    fun ioWrite16(off: Int, value: Int) {
        if (off >= 0x400) return
        when (off) {
            in 0x060..0x0A6 -> {
                gba.apu.write8(off, value and 0xFF)
                gba.apu.write8(off + 1, value ushr 8)
            }
            0x000, 0x008, 0x00A, 0x00C, 0x00E, 0x048, 0x04A, 0x050, 0x052 -> { gba.ppu.syncBeforeWrite(); put16(io, off, value) }
            0x004 -> put16(io, off, (get16(io, off) and 0x0007) or (value and 0xFFF8))
            0x006 -> {}
            in 0x010..0x01E, 0x040, 0x042, 0x044, 0x046, 0x04C, 0x054 -> { gba.ppu.syncBeforeWrite(); put16(io, off, value) }
            in 0x020..0x03E -> { gba.ppu.syncBeforeWrite(); put16(io, off, value); gba.ppu.onAffineWrite(off) }
            in 0x0B0..0x0DE -> {
                put16(io, off, value)
                val ch = (off - 0x0B0) / 12
                if ((off - 0x0B0) % 12 == 10) gba.dma.writeControl(ch, value)
            }
            0x100, 0x104, 0x108, 0x10C -> gba.timers.writeReload((off - 0x100) ushr 2, value)
            0x102, 0x106, 0x10A, 0x10E -> { gba.timers.writeControl((off - 0x102) ushr 2, value); put16(io, off, value) }
            0x128 -> {
                // SIOCNT: no link cable. Internally clocked transfers complete immediately.
                var v = value
                if (v and 0x80 != 0 && v and 1 != 0) {
                    v = v and 0x80.inv()
                    if (v and 0x4000 != 0) gba.requestIrq(7)
                }
                put16(io, off, v)
            }
            0x130 -> {}
            0x132 -> { put16(io, off, value); gba.checkKeypadIrq() }
            0x200 -> { gba.ie = value and 0x3FFF; gba.updateIrq() }
            0x202 -> { gba.iflags = gba.iflags and value.inv(); gba.updateIrq() }
            0x204 -> { put16(io, off, value and 0x7FFF); updateWaitstates(value) }
            0x208 -> { gba.ime = value and 1 != 0; gba.updateIrq() }
            0x300 -> {
                // POSTFLG in the low byte; writing HALTCNT (the high byte) halts the CPU.
                io[0x300] = value.toByte()
                gba.halted = true
                gba.scheduleDirty = true
            }
            else -> put16(io, off, value)
        }
    }

    // ------------------------------------------------------------------------------------------

    fun saveState(w: StateWriter) {
        w.tag("gbabus")
        w.bytes(ewram); w.bytes(iwram); w.bytes(io); w.bytes(palette); w.bytes(vram); w.bytes(oam)
        w.int(biosLatch)
    }

    fun loadState(r: StateReader) {
        r.tag("gbabus")
        r.bytesInto(ewram); r.bytesInto(iwram); r.bytesInto(io); r.bytesInto(palette); r.bytesInto(vram); r.bytesInto(oam)
        biosLatch = r.int()
        updateWaitstates(get16(io, 0x204))
    }

    companion object {
        fun get16(a: ByteArray, i: Int): Int = (a[i].toInt() and 0xFF) or ((a[i + 1].toInt() and 0xFF) shl 8)

        fun get32(a: ByteArray, i: Int): Int =
            (a[i].toInt() and 0xFF) or ((a[i + 1].toInt() and 0xFF) shl 8) or
                ((a[i + 2].toInt() and 0xFF) shl 16) or (a[i + 3].toInt() shl 24)

        fun put16(a: ByteArray, i: Int, v: Int) {
            a[i] = v.toByte(); a[i + 1] = (v ushr 8).toByte()
        }

        fun put32(a: ByteArray, i: Int, v: Int) {
            a[i] = v.toByte(); a[i + 1] = (v ushr 8).toByte(); a[i + 2] = (v ushr 16).toByte(); a[i + 3] = (v ushr 24).toByte()
        }
    }
}
