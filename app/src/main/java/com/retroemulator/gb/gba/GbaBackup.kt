package com.retroemulator.gb.gba

import com.retroemulator.gb.core.StateReader
import com.retroemulator.gb.core.StateWriter
import java.util.Arrays

/**
 * Cartridge save memory, detected from the library ID string games embed in their ROM:
 * SRAM (32 KB), Flash (64 KB / 128 KB) or EEPROM (512 B / 8 KB, a serial chip driven through DMA).
 */
class GbaBackup private constructor(val type: Type, size: Int) {
    enum class Type { NONE, SRAM, FLASH64, FLASH128, EEPROM }

    var data = ByteArray(size).also { Arrays.fill(it, 0xFF.toByte()) }
        private set
    @Volatile var dirty = false

    fun load(saved: ByteArray) {
        if (type == Type.EEPROM && saved.size != data.size && (saved.size == 512 || saved.size == 8192)) {
            data = ByteArray(saved.size)
            eepromAddrBits = if (saved.size == 512) 6 else 14
        }
        System.arraycopy(saved, 0, data, 0, minOf(saved.size, data.size))
    }

    // ------------------------------------------------------------------------------------------
    // SRAM / Flash (0x0E000000)
    // ------------------------------------------------------------------------------------------

    private var flashState = 0
    private var flashIdMode = false
    private var flashBank = 0
    private var flashCommand = 0

    fun read8(addr: Int): Int {
        val off = addr and 0xFFFF
        return when (type) {
            Type.SRAM -> data[off and 0x7FFF].toInt() and 0xFF
            Type.FLASH64, Type.FLASH128 -> {
                if (flashIdMode && off < 2) {
                    // Panasonic (64 KB) and Macronix (128 KB) chip IDs.
                    if (type == Type.FLASH64) (if (off == 0) 0x32 else 0x1B) else (if (off == 0) 0xC2 else 0x09)
                } else {
                    data[flashBank * 0x10000 + off].toInt() and 0xFF
                }
            }
            else -> 0xFF
        }
    }

    fun write8(addr: Int, value: Int) {
        val off = addr and 0xFFFF
        when (type) {
            Type.SRAM -> { data[off and 0x7FFF] = value.toByte(); dirty = true }
            Type.FLASH64, Type.FLASH128 -> flashWrite(off, value)
            else -> {}
        }
    }

    private fun flashWrite(off: Int, value: Int) {
        if (flashCommand == 0xA0) {
            // Program a byte (bits can only be cleared, but games always erase first).
            data[flashBank * 0x10000 + off] = value.toByte()
            dirty = true
            flashCommand = 0
            return
        }
        if (flashCommand == 0xB0 && off == 0) {
            if (type == Type.FLASH128) flashBank = value and 1
            flashCommand = 0
            return
        }
        when (flashState) {
            0 -> if (off == 0x5555 && value == 0xAA) flashState = 1
            1 -> flashState = if (off == 0x2AAA && value == 0x55) 2 else 0
            2 -> {
                flashState = 0
                if (flashCommand == 0x80) {
                    // Second half of an erase sequence.
                    when (value) {
                        0x10 -> if (off == 0x5555) { Arrays.fill(data, 0xFF.toByte()); dirty = true }
                        0x30 -> {
                            val start = flashBank * 0x10000 + (off and 0xF000)
                            Arrays.fill(data, start, start + 0x1000, 0xFF.toByte())
                            dirty = true
                        }
                    }
                    flashCommand = 0
                    return
                }
                if (off != 0x5555) return
                when (value) {
                    0x90 -> flashIdMode = true
                    0xF0 -> flashIdMode = false
                    0x80, 0xA0, 0xB0 -> flashCommand = value
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // EEPROM (0x0D000000, accessed one bit per halfword)
    // ------------------------------------------------------------------------------------------

    private var eepromAddrBits = if (size == 512) 6 else 14
    private var sizeKnown = false
    private val bits = IntArray(96)
    private var bitCount = 0
    private var readAddr = -1
    private var readBit = 0

    fun isEepromAddress(addr: Int, romSize: Int): Boolean {
        if (type != Type.EEPROM) return false
        return if (romSize > 0x1000000) addr and 0x01FFFF00 == 0x01FFFF00 else true
    }

    /** DMA3 transfers tell us the EEPROM's address width before the first access. */
    fun onDmaLength(src: Int, dst: Int, units: Int) {
        if (type != Type.EEPROM || sizeKnown) return
        if (dst ushr 24 == 0x0D) {
            val width = when (units) {
                9, 73 -> 6
                17, 81 -> 14
                else -> return
            }
            setAddressWidth(width)
        }
    }

    private fun setAddressWidth(width: Int) {
        sizeKnown = true
        if (width == eepromAddrBits) return
        eepromAddrBits = width
        val newSize = if (width == 6) 512 else 8192
        data = data.copyOf(newSize).also { if (newSize > data.size) Arrays.fill(it, data.size, newSize, 0xFF.toByte()) }
    }

    fun eepromWrite(value: Int) {
        if (bitCount < bits.size) bits[bitCount++] = value and 1
        val requestLen = 2 + eepromAddrBits + 1
        val writeLen = 2 + eepromAddrBits + 64 + 1
        if (bitCount >= 2 && bits[0] == 1 && bits[1] == 1 && bitCount == requestLen) {
            readAddr = readBits(2, eepromAddrBits) and (data.size / 8 - 1)
            readBit = 0
            bitCount = 0
        } else if (bitCount >= 2 && bits[0] == 1 && bits[1] == 0 && bitCount == writeLen) {
            val a = (readBits(2, eepromAddrBits) and (data.size / 8 - 1)) * 8
            for (i in 0 until 8) data[a + i] = readBits(2 + eepromAddrBits + i * 8, 8).toByte()
            dirty = true
            bitCount = 0
        } else if (bitCount == bits.size) {
            bitCount = 0
        }
    }

    private fun readBits(start: Int, n: Int): Int {
        var v = 0
        for (i in 0 until n) v = (v shl 1) or bits[start + i]
        return v
    }

    fun eepromRead(): Int {
        if (readAddr < 0) return 1 // ready
        val pos = readBit++
        if (pos < 4) return 0
        val i = pos - 4
        val v = (data[readAddr * 8 + (i ushr 3)].toInt() ushr (7 - (i and 7))) and 1
        if (readBit >= 68) readAddr = -1
        return v
    }

    // ------------------------------------------------------------------------------------------

    fun saveState(w: StateWriter) {
        w.tag("gbabackup")
        w.bytes(data)
        w.ints(intArrayOf(flashState, flashBank, flashCommand, eepromAddrBits, bitCount, readAddr, readBit))
        w.bool(flashIdMode); w.bool(sizeKnown)
        w.ints(bits)
    }

    fun loadState(r: StateReader) {
        r.tag("gbabackup")
        val saved = r.bytes()
        if (saved.size != data.size) data = ByteArray(saved.size)
        System.arraycopy(saved, 0, data, 0, saved.size)
        val v = IntArray(7)
        r.intsInto(v)
        flashState = v[0]; flashBank = v[1]; flashCommand = v[2]; eepromAddrBits = v[3]
        bitCount = v[4]; readAddr = v[5]; readBit = v[6]
        flashIdMode = r.bool(); sizeKnown = r.bool()
        r.intsInto(bits)
    }

    companion object {
        fun detect(rom: ByteArray): GbaBackup {
            val type = when {
                contains(rom, "EEPROM_V") -> Type.EEPROM
                contains(rom, "FLASH1M_V") -> Type.FLASH128
                contains(rom, "FLASH512_V") || contains(rom, "FLASH_V") -> Type.FLASH64
                contains(rom, "SRAM_V") || contains(rom, "SRAM_F_V") -> Type.SRAM
                else -> Type.NONE
            }
            val size = when (type) {
                Type.NONE -> 0
                Type.SRAM -> 0x8000
                Type.FLASH64 -> 0x10000
                Type.FLASH128 -> 0x20000
                Type.EEPROM -> 8192
            }
            return GbaBackup(type, size)
        }

        private fun contains(rom: ByteArray, text: String): Boolean {
            val pattern = text.toByteArray(Charsets.US_ASCII)
            val first = pattern[0]
            var i = 0
            val end = rom.size - pattern.size
            while (i <= end) {
                if (rom[i] == first) {
                    var k = 1
                    while (k < pattern.size && rom[i + k] == pattern[k]) k++
                    if (k == pattern.size) return true
                }
                i++
            }
            return false
        }
    }
}
