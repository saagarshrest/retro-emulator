package com.retroemulator.gb.core

import java.util.Arrays

/** Address decoding, work RAM, high RAM, IO register dispatch, OAM DMA and CGB HDMA. */
class Mmu(private val gb: GameBoy) {
    val wram = ByteArray(0x8000)
    val hram = ByteArray(0x7F)
    private var wramBank = 1
    val currentWramBank: Int get() = wramBank

    // OAM DMA
    private var dmaReg = 0xFF
    private var dmaSource = 0
    private var dmaIndex = 0
    private var dmaDelay = 0
    var dmaActive = false
        private set

    // CGB HDMA
    private var hdmaSource = 0
    private var hdmaDest = 0
    private var hdmaBlocks = 0
    private var hdmaActive = false
    private var hdmaPending = false

    // Undocumented CGB registers FF72-FF75
    private var ff72 = 0; private var ff73 = 0; private var ff74 = 0; private var ff75 = 0
    private var rp = 0

    private val cart get() = gb.cart
    private val ppu get() = gb.ppu

    fun read(addr: Int): Int {
        when (addr ushr 12) {
            0x0, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7 -> return cart.readRom(addr)
            0x8, 0x9 -> return ppu.readVram(addr)
            0xA, 0xB -> return cart.readRam(addr)
            0xC, 0xE -> return wram[addr and 0x0FFF].toInt() and 0xFF
            0xD -> return wram[(wramBank shl 12) or (addr and 0x0FFF)].toInt() and 0xFF
        }
        // 0xF000-0xFFFF
        if (addr < 0xFE00) return wram[(wramBank shl 12) or (addr and 0x0FFF)].toInt() and 0xFF
        if (addr < 0xFEA0) {
            if (dmaBlocking() || !ppu.oamReadable()) return 0xFF
            return ppu.oam[addr - 0xFE00].toInt() and 0xFF
        }
        if (addr < 0xFF00) return if (gb.cgb) 0x00 else 0xFF
        if (addr >= 0xFF80) {
            return if (addr == 0xFFFF) gb.intEnable else hram[addr - 0xFF80].toInt() and 0xFF
        }
        return readIo(addr)
    }

    fun write(addr: Int, value: Int) {
        when (addr ushr 12) {
            0x0, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7 -> { cart.writeRom(addr, value); return }
            0x8, 0x9 -> { ppu.writeVram(addr, value); return }
            0xA, 0xB -> { cart.writeRam(addr, value); return }
            0xC, 0xE -> { wram[addr and 0x0FFF] = value.toByte(); return }
            0xD -> { wram[(wramBank shl 12) or (addr and 0x0FFF)] = value.toByte(); return }
        }
        if (addr < 0xFE00) {
            wram[(wramBank shl 12) or (addr and 0x0FFF)] = value.toByte()
            return
        }
        if (addr < 0xFEA0) {
            if (!dmaBlocking() && ppu.oamWritable()) ppu.oam[addr - 0xFE00] = value.toByte()
            return
        }
        if (addr < 0xFF00) return
        if (addr >= 0xFF80) {
            if (addr == 0xFFFF) gb.intEnable = value else hram[addr - 0xFF80] = value.toByte()
            return
        }
        writeIo(addr, value)
    }

    private fun readIo(addr: Int): Int {
        if (addr in 0xFF10..0xFF3F) return gb.apu.read(addr)
        val cgb = gb.cgb
        return when (addr) {
            0xFF00 -> gb.joypad.read()
            0xFF01 -> gb.serial.sb
            0xFF02 -> gb.serial.read()
            0xFF04 -> gb.timer.div
            0xFF05 -> gb.timer.tima
            0xFF06 -> gb.timer.tma
            0xFF07 -> gb.timer.tac or 0xF8
            0xFF0F -> gb.intFlag or 0xE0
            0xFF40 -> ppu.lcdc
            0xFF41 -> ppu.readStat()
            0xFF42 -> ppu.scy
            0xFF43 -> ppu.scx
            0xFF44 -> ppu.ly
            0xFF45 -> ppu.lyc
            0xFF46 -> dmaReg
            0xFF47 -> ppu.bgp
            0xFF48 -> ppu.obp0
            0xFF49 -> ppu.obp1
            0xFF4A -> ppu.wy
            0xFF4B -> ppu.wx
            0xFF4D -> if (cgb) 0x7E or (if (gb.doubleSpeed) 0x80 else 0) or (if (gb.speedSwitchArmed) 1 else 0) else 0xFF
            0xFF4F -> if (cgb) 0xFE or ppu.vbk else 0xFF
            0xFF55 -> if (cgb) (if (hdmaActive) 0 else 0x80) or ((hdmaBlocks - 1) and 0x7F) else 0xFF
            0xFF56 -> if (cgb) (rp and 0xC1) or 0x3E or 0x02 else 0xFF
            0xFF68, 0xFF69, 0xFF6A, 0xFF6B -> if (cgb) ppu.readPalette(addr) else 0xFF
            0xFF6C -> if (cgb) 0xFE or ppu.opri else 0xFF
            0xFF70 -> if (cgb) 0xF8 or wramBank else 0xFF
            0xFF72 -> if (cgb) ff72 else 0xFF
            0xFF73 -> if (cgb) ff73 else 0xFF
            0xFF74 -> if (cgb) ff74 else 0xFF
            0xFF75 -> if (cgb) ff75 or 0x8F else 0xFF
            0xFF76, 0xFF77 -> if (cgb) gb.apu.readPcm(addr) else 0xFF
            else -> 0xFF
        }
    }

    private fun writeIo(addr: Int, value: Int) {
        if (addr in 0xFF10..0xFF3F) {
            gb.apu.write(addr, value)
            return
        }
        val cgb = gb.cgb
        when (addr) {
            0xFF00 -> gb.joypad.write(value)
            0xFF01 -> gb.serial.sb = value
            0xFF02 -> gb.serial.write(value)
            0xFF04 -> gb.timer.writeDiv()
            0xFF05 -> gb.timer.writeTima(value)
            0xFF06 -> gb.timer.writeTma(value)
            0xFF07 -> gb.timer.writeTac(value)
            0xFF0F -> gb.intFlag = value and 0x1F
            0xFF40 -> ppu.writeLcdc(value)
            0xFF41 -> ppu.writeStat(value)
            0xFF42, 0xFF43, 0xFF47, 0xFF48, 0xFF49, 0xFF4A, 0xFF4B -> ppu.writeScroll(addr, value)
            0xFF45 -> ppu.writeLyc(value)
            0xFF46 -> startOamDma(value)
            0xFF4D -> if (cgb) gb.speedSwitchArmed = value and 1 != 0
            0xFF4F -> if (cgb) ppu.vbk = value and 1
            0xFF51 -> if (cgb) hdmaSource = (value shl 8) or (hdmaSource and 0xFF)
            0xFF52 -> if (cgb) hdmaSource = (hdmaSource and 0xFF00) or (value and 0xF0)
            0xFF53 -> if (cgb) hdmaDest = ((value and 0x1F) shl 8) or (hdmaDest and 0xFF)
            0xFF54 -> if (cgb) hdmaDest = (hdmaDest and 0x1F00) or (value and 0xF0)
            0xFF55 -> if (cgb) writeHdmaControl(value)
            0xFF56 -> if (cgb) rp = value
            0xFF68, 0xFF69, 0xFF6A, 0xFF6B -> if (cgb) ppu.writePalette(addr, value)
            0xFF6C -> if (cgb) ppu.opri = value and 1
            0xFF70 -> if (cgb) { wramBank = value and 7; if (wramBank == 0) wramBank = 1 }
            0xFF72 -> if (cgb) ff72 = value
            0xFF73 -> if (cgb) ff73 = value
            0xFF74 -> if (cgb) ff74 = value
            0xFF75 -> if (cgb) ff75 = value and 0x70
        }
    }

    // ------------------------------------------------------------------------------------------
    // OAM DMA: 160 bytes, one per M-cycle, after a one-cycle startup delay.
    // ------------------------------------------------------------------------------------------

    private fun startOamDma(value: Int) {
        dmaReg = value
        dmaSource = value shl 8
        if (dmaSource >= 0xE000) dmaSource -= 0x2000
        dmaIndex = 0
        dmaDelay = 1
        dmaActive = true
    }

    /**
     * OAM is locked in exactly the M-cycles in which a byte is transferred. The setup cycle after the
     * FF46 write leaves it accessible, except on a restart where the previous transfer still holds it.
     */
    private var oamLocked = false

    private fun dmaBlocking(): Boolean = oamLocked

    /** True while the DMA engine needs ticking (transferring, or releasing the OAM lock). */
    val dmaBusy: Boolean get() = dmaActive || oamLocked

    fun dmaTick() {
        if (!dmaActive) {
            oamLocked = false
            return
        }
        if (dmaDelay > 0) {
            dmaDelay--
            return
        }
        ppu.oam[dmaIndex] = dmaRead(dmaSource + dmaIndex).toByte()
        oamLocked = true
        if (++dmaIndex == 0xA0) dmaActive = false
    }

    /** Bus read used by DMA engines: bypasses the PPU's CPU access locks. */
    private fun dmaRead(addr: Int): Int = when (addr ushr 13) {
        4 -> ppu.vram[(ppu.vbk shl 13) + (addr and 0x1FFF)].toInt() and 0xFF
        else -> if (addr >= 0xE000) wram[(wramBank shl 12) or (addr and 0x0FFF)].toInt() and 0xFF else read(addr)
    }

    // ------------------------------------------------------------------------------------------
    // CGB HDMA
    // ------------------------------------------------------------------------------------------

    private fun writeHdmaControl(value: Int) {
        if (hdmaActive && value and 0x80 == 0) {
            hdmaActive = false
            hdmaPending = false
            return
        }
        hdmaBlocks = (value and 0x7F) + 1
        if (value and 0x80 != 0) {
            hdmaActive = true
            // Starting during HBlank copies the first block right away.
            if (ppu.lcdOn && ppu.mode == 0) hdmaPending = true
        } else {
            // General purpose DMA: copy everything now, CPU stalled meanwhile.
            while (hdmaBlocks > 0) copyHdmaBlock()
        }
    }

    private fun copyHdmaBlock() {
        val bank = ppu.vbk shl 13
        for (i in 0 until 16) {
            val v = dmaRead((hdmaSource + i) and 0xFFFF)
            ppu.vram[bank + ((hdmaDest + i) and 0x1FFF)] = v.toByte()
        }
        hdmaSource = (hdmaSource + 16) and 0xFFFF
        hdmaDest = (hdmaDest + 16) and 0x1FFF
        hdmaBlocks--
        // 16 bytes take 32 dots: 8 M-cycles at normal speed, 16 in double speed.
        repeat(if (gb.doubleSpeed) 16 else 8) { gb.tick() }
    }

    fun onHBlank() {
        if (hdmaActive) hdmaPending = true
    }

    /** Called between CPU instructions: performs a pending HBlank DMA block. */
    fun serviceHdma() {
        if (!hdmaPending) return
        hdmaPending = false
        if (!hdmaActive) return
        copyHdmaBlock()
        if (hdmaBlocks == 0) hdmaActive = false
    }

    fun reset(cgb: Boolean) {
        Arrays.fill(wram, 0)
        Arrays.fill(hram, 0)
        wramBank = 1
        dmaReg = 0xFF
        dmaActive = false
        oamLocked = false
        hdmaActive = false
        hdmaPending = false
        hdmaBlocks = 0
        ff72 = 0; ff73 = 0; ff74 = 0; ff75 = 0
        rp = 0
    }

    fun saveState(w: StateWriter) {
        w.tag("mmu")
        w.bytes(wram); w.bytes(hram)
        w.ints(intArrayOf(wramBank, dmaReg, dmaSource, dmaIndex, dmaDelay, hdmaSource, hdmaDest, hdmaBlocks,
            ff72, ff73, ff74, ff75, rp))
        w.bool(dmaActive); w.bool(hdmaActive); w.bool(hdmaPending); w.bool(oamLocked)
    }

    fun loadState(r: StateReader) {
        r.tag("mmu")
        r.bytesInto(wram); r.bytesInto(hram)
        val v = IntArray(13)
        r.intsInto(v)
        wramBank = v[0]; dmaReg = v[1]; dmaSource = v[2]; dmaIndex = v[3]; dmaDelay = v[4]
        hdmaSource = v[5]; hdmaDest = v[6]; hdmaBlocks = v[7]
        ff72 = v[8]; ff73 = v[9]; ff74 = v[10]; ff75 = v[11]; rp = v[12]
        dmaActive = r.bool(); hdmaActive = r.bool(); hdmaPending = r.bool(); oamLocked = r.bool()
    }
}
