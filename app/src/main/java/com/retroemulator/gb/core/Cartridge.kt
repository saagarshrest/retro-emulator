package com.retroemulator.gb.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

class UnsupportedCartridgeException(message: String) : Exception(message)

/** Parsed cartridge header (0x0100-0x014F). */
class CartridgeHeader(rom: ByteArray) {
    val title: String
    val cgbFlag: Int = rom.u8(0x143)
    val sgbFlag: Int = rom.u8(0x146)
    val cartType: Int = rom.u8(0x147)
    val romSizeCode: Int = rom.u8(0x148)
    val ramSizeCode: Int = rom.u8(0x149)
    val headerChecksum: Int = rom.u8(0x14D)
    val headerChecksumValid: Boolean
    val globalChecksum: Int = (rom.u8(0x14E) shl 8) or rom.u8(0x14F)

    val supportsCgb: Boolean get() = (cgbFlag and 0x80) != 0
    val cgbOnly: Boolean get() = cgbFlag == 0xC0

    init {
        val end = if (supportsCgb) 0x143 else 0x144
        val sb = StringBuilder()
        for (i in 0x134 until end) {
            val ch = rom.u8(i)
            if (ch == 0) break
            sb.append(if (ch in 0x20..0x7E) ch.toChar() else ' ')
        }
        title = sb.toString().trim()
        var sum = 0
        for (i in 0x134..0x14C) sum = (sum - rom.u8(i) - 1) and 0xFF
        headerChecksumValid = sum == headerChecksum
    }

    val ramSize: Int
        get() = when (ramSizeCode) {
            1 -> 0x800
            2 -> 0x2000
            3 -> 0x8000
            4 -> 0x20000
            5 -> 0x10000
            else -> 0
        }

    val mapperName: String
        get() = when (cartType) {
            0x00, 0x08, 0x09 -> "ROM"
            in 0x01..0x03 -> "MBC1"
            0x05, 0x06 -> "MBC2"
            in 0x0F..0x13 -> "MBC3"
            in 0x19..0x1E -> "MBC5"
            0x20 -> "MBC6"
            0x22 -> "MBC7"
            0x0B, 0x0C, 0x0D -> "MMM01"
            0xFC -> "Pocket Camera"
            0xFD -> "TAMA5"
            0xFE -> "HuC3"
            0xFF -> "HuC1"
            else -> "Unknown (0x%02X)".format(cartType)
        }

    val hasBattery: Boolean
        get() = cartType in intArrayOf(0x03, 0x06, 0x09, 0x0D, 0x0F, 0x10, 0x13, 0x1B, 0x1E, 0x22, 0xFF)

    companion object {
        private fun ByteArray.u8(i: Int): Int = if (i < size) this[i].toInt() and 0xFF else 0
    }
}

/**
 * Base class for cartridge mappers. ROM is padded to a power-of-two number of 16 KiB banks so bank
 * numbers can simply be masked.
 */
abstract class Cartridge(romData: ByteArray, val header: CartridgeHeader) {
    val rom: ByteArray
    val romBankCount: Int
    open val ram: ByteArray = ByteArray(header.ramSize)
    open val hasBattery: Boolean get() = header.hasBattery
    open val rtc: Rtc? get() = null
    /** Set whenever battery-backed memory changes so the frontend knows to flush it to disk. */
    @Volatile var ramDirty = false
    /** True for MBC5 rumble cartridges. */
    open val hasRumble: Boolean get() = false

    /** Whether the rumble motor was switched on since the last call (games pulse it rapidly). */
    open fun consumeRumble(): Boolean = false

    protected var romOffset0 = 0
    protected var romOffsetX = 0x4000

    init {
        var size = 0x8000
        while (size < romData.size) size = size shl 1
        rom = if (size == romData.size) romData else romData.copyOf(size).also {
            for (i in romData.size until size) it[i] = 0xFF.toByte()
        }
        romBankCount = size / 0x4000
    }

    fun readRom(addr: Int): Int =
        if (addr < 0x4000) rom[romOffset0 + addr].toInt() and 0xFF
        else rom[romOffsetX + (addr - 0x4000)].toInt() and 0xFF

    abstract fun writeRom(addr: Int, value: Int)
    abstract fun readRam(addr: Int): Int
    abstract fun writeRam(addr: Int, value: Int)

    /** Advance real-time components by [dots] normal-speed clock cycles. */
    open fun tick(dots: Int) {}

    /** Battery file contents: RAM followed by an RTC footer when present. */
    open fun saveData(): ByteArray = ram.copyOf()

    open fun loadSaveData(data: ByteArray) {
        System.arraycopy(data, 0, ram, 0, minOf(data.size, ram.size))
    }

    open fun saveState(w: StateWriter) {
        w.tag("cart")
        w.bytes(ram)
        w.int(romOffset0)
        w.int(romOffsetX)
    }

    open fun loadState(r: StateReader) {
        r.tag("cart")
        r.bytesInto(ram)
        romOffset0 = r.int()
        romOffsetX = r.int()
    }

    protected fun bankOffset(bank: Int): Int = (bank and (romBankCount - 1)) * 0x4000

    companion object {
        fun create(romData: ByteArray): Cartridge {
            if (romData.size < 0x150) throw UnsupportedCartridgeException("File is too small to be a Game Boy ROM")
            val header = CartridgeHeader(romData)
            return when (header.cartType) {
                0x00, 0x08, 0x09 -> RomOnly(romData, header)
                0x01, 0x02, 0x03 -> Mbc1(romData, header)
                0x05, 0x06 -> Mbc2(romData, header)
                0x0F, 0x10, 0x11, 0x12, 0x13 -> Mbc3(romData, header)
                0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E -> Mbc5(romData, header)
                0xFF -> HuC1(romData, header)
                else -> throw UnsupportedCartridgeException("Unsupported cartridge type: ${header.mapperName}")
            }
        }
    }
}

class RomOnly(romData: ByteArray, header: CartridgeHeader) : Cartridge(romData, header) {
    override fun writeRom(addr: Int, value: Int) {}
    override fun readRam(addr: Int): Int = if (ram.isEmpty()) 0xFF else ram[(addr - 0xA000) % ram.size].toInt() and 0xFF
    override fun writeRam(addr: Int, value: Int) {
        if (ram.isNotEmpty()) {
            ram[(addr - 0xA000) % ram.size] = value.toByte()
            ramDirty = true
        }
    }
}

class Mbc1(romData: ByteArray, header: CartridgeHeader) : Cartridge(romData, header) {
    private var ramEnabled = false
    private var bank1 = 1
    private var bank2 = 0
    private var mode = 0
    private var ramOffset = 0

    /** MBC1M multicarts wire only 4 bits of the low bank register. Detected by a second Nintendo logo at bank 0x10. */
    private val multicart: Boolean = rom.size == 0x100000 && run {
        var logos = 0
        for (b in 0 until 4) {
            val base = b * 0x40000 + 0x104
            if (rom[base].toInt() and 0xFF == 0xCE && rom[base + 1].toInt() and 0xFF == 0xED &&
                rom[base + 2].toInt() and 0xFF == 0x66 && rom[base + 3].toInt() and 0xFF == 0x66) logos++
        }
        logos > 1
    }

    init { update() }

    private fun update() {
        val shift = if (multicart) 4 else 5
        val low = if (multicart) bank1 and 0x0F else bank1
        romOffsetX = bankOffset((bank2 shl shift) or low)
        romOffset0 = if (mode == 1) bankOffset(bank2 shl shift) else 0
        ramOffset = if (mode == 1 && ram.size >= 0x8000) bank2 * 0x2000 else 0
    }

    override fun writeRom(addr: Int, value: Int) {
        when (addr shr 13) {
            0 -> ramEnabled = (value and 0x0F) == 0x0A
            1 -> { bank1 = value and 0x1F; if (bank1 == 0) bank1 = 1 }
            2 -> bank2 = value and 0x03
            else -> mode = value and 0x01
        }
        update()
    }

    override fun readRam(addr: Int): Int {
        if (!ramEnabled || ram.isEmpty()) return 0xFF
        return ram[(ramOffset + (addr and 0x1FFF)) % ram.size].toInt() and 0xFF
    }

    override fun writeRam(addr: Int, value: Int) {
        if (!ramEnabled || ram.isEmpty()) return
        ram[(ramOffset + (addr and 0x1FFF)) % ram.size] = value.toByte()
        ramDirty = true
    }

    override fun saveState(w: StateWriter) {
        super.saveState(w)
        w.bool(ramEnabled); w.int(bank1); w.int(bank2); w.int(mode)
    }

    override fun loadState(r: StateReader) {
        super.loadState(r)
        ramEnabled = r.bool(); bank1 = r.int(); bank2 = r.int(); mode = r.int()
        update()
    }
}

class Mbc2(romData: ByteArray, header: CartridgeHeader) : Cartridge(romData, header) {
    override val ram = ByteArray(512)
    private var ramEnabled = false
    private var romBank = 1

    init { romOffsetX = bankOffset(1) }

    override fun writeRom(addr: Int, value: Int) {
        if (addr >= 0x4000) return
        if (addr and 0x100 == 0) {
            ramEnabled = (value and 0x0F) == 0x0A
        } else {
            romBank = value and 0x0F
            if (romBank == 0) romBank = 1
            romOffsetX = bankOffset(romBank)
        }
    }

    override fun readRam(addr: Int): Int =
        if (!ramEnabled) 0xFF else 0xF0 or (ram[addr and 0x1FF].toInt() and 0x0F)

    override fun writeRam(addr: Int, value: Int) {
        if (!ramEnabled) return
        ram[addr and 0x1FF] = (value and 0x0F).toByte()
        ramDirty = true
    }

    override fun saveState(w: StateWriter) {
        super.saveState(w)
        w.bool(ramEnabled); w.int(romBank)
    }

    override fun loadState(r: StateReader) {
        super.loadState(r)
        ramEnabled = r.bool(); romBank = r.int()
    }
}

/** MBC3 real-time clock. Counts emulated time; the frontend adds wall-clock time spent outside the emulator. */
class Rtc {
    var seconds = 0; var minutes = 0; var hours = 0; var days = 0
    var halted = false; var dayCarry = false
    private val latched = IntArray(5)
    private var subSecond = 0
    private var latchPrev = 0xFF

    fun tick(dots: Int) {
        if (halted) return
        subSecond += dots
        while (subSecond >= CLOCK) {
            subSecond -= CLOCK
            incrementSecond()
        }
    }

    private fun incrementSecond() {
        seconds = (seconds + 1) and 0x3F
        if (seconds != 60) return
        seconds = 0
        minutes = (minutes + 1) and 0x3F
        if (minutes != 60) return
        minutes = 0
        hours = (hours + 1) and 0x1F
        if (hours != 24) return
        hours = 0
        days++
        if (days >= 512) { days = 0; dayCarry = true }
    }

    /** Fast-forward the clock by a number of real seconds (used for time spent while the app was closed). */
    fun advance(totalSeconds: Long) {
        if (halted || totalSeconds <= 0) return
        var s = totalSeconds
        // Step one second at a time until registers are normalized, then jump in bulk.
        while (s > 0 && (seconds >= 60 || minutes >= 60 || hours >= 24)) { incrementSecond(); s-- }
        if (s <= 0) return
        var total = seconds + s
        seconds = (total % 60).toInt()
        total = minutes + total / 60
        minutes = (total % 60).toInt()
        total = hours + total / 60
        hours = (total % 24).toInt()
        total = days + total / 24
        if (total >= 512) dayCarry = true
        days = (total % 512).toInt()
    }

    fun writeLatch(value: Int) {
        if (latchPrev == 0 && value == 1) {
            latched[0] = seconds; latched[1] = minutes; latched[2] = hours
            latched[3] = days and 0xFF
            latched[4] = ((days shr 8) and 1) or (if (halted) 0x40 else 0) or (if (dayCarry) 0x80 else 0)
        }
        latchPrev = value
    }

    fun read(reg: Int): Int = when (reg) {
        0x08 -> latched[0] and 0x3F
        0x09 -> latched[1] and 0x3F
        0x0A -> latched[2] and 0x1F
        0x0B -> latched[3]
        else -> latched[4] and 0xC1
    }

    fun write(reg: Int, value: Int) {
        when (reg) {
            0x08 -> { seconds = value and 0x3F; subSecond = 0 }
            0x09 -> minutes = value and 0x3F
            0x0A -> hours = value and 0x1F
            0x0B -> days = (days and 0x100) or (value and 0xFF)
            else -> {
                days = (days and 0xFF) or ((value and 1) shl 8)
                halted = value and 0x40 != 0
                dayCarry = value and 0x80 != 0
            }
        }
        // Writes are immediately visible to the latched copy in practice; games re-latch before reading anyway.
        latched[reg - 0x08] = when (reg) {
            0x0B -> days and 0xFF
            0x0C -> ((days shr 8) and 1) or (if (halted) 0x40 else 0) or (if (dayCarry) 0x80 else 0)
            else -> value
        }
    }

    /** 48-byte footer compatible with VBA-M / BGB / mGBA battery files. */
    fun toFooter(unixSeconds: Long): ByteArray {
        val bb = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        val dh = ((days shr 8) and 1) or (if (halted) 0x40 else 0) or (if (dayCarry) 0x80 else 0)
        for (v in intArrayOf(seconds, minutes, hours, days and 0xFF, dh)) bb.putInt(v)
        for (v in latched) bb.putInt(v)
        bb.putLong(unixSeconds)
        return bb.array()
    }

    /** Restores the clock and returns the timestamp stored in the footer (0 if absent). */
    fun fromFooter(data: ByteArray, offset: Int): Long {
        val len = data.size - offset
        if (len < 44) return 0
        val bb = ByteBuffer.wrap(data, offset, len).order(ByteOrder.LITTLE_ENDIAN)
        seconds = bb.int and 0x3F; minutes = bb.int and 0x3F; hours = bb.int and 0x1F
        val dl = bb.int and 0xFF
        val dh = bb.int
        days = dl or ((dh and 1) shl 8)
        halted = dh and 0x40 != 0
        dayCarry = dh and 0x80 != 0
        for (i in 0 until 5) latched[i] = bb.int and 0xFF
        return if (len >= 48) bb.long else (bb.int.toLong() and 0xFFFFFFFFL)
    }

    fun saveState(w: StateWriter) {
        w.tag("rtc")
        w.int(seconds); w.int(minutes); w.int(hours); w.int(days)
        w.bool(halted); w.bool(dayCarry); w.ints(latched); w.int(subSecond); w.int(latchPrev)
    }

    fun loadState(r: StateReader) {
        r.tag("rtc")
        seconds = r.int(); minutes = r.int(); hours = r.int(); days = r.int()
        halted = r.bool(); dayCarry = r.bool(); r.intsInto(latched); subSecond = r.int(); latchPrev = r.int()
    }

    companion object {
        const val CLOCK = 4194304
    }
}

class Mbc3(romData: ByteArray, header: CartridgeHeader) : Cartridge(romData, header) {
    private val hasRtc = header.cartType == 0x0F || header.cartType == 0x10
    private val clock = Rtc()
    override val rtc: Rtc? get() = if (hasRtc) clock else null
    private var ramEnabled = false
    private var romBank = 1
    private var ramSelect = 0

    init { romOffsetX = bankOffset(1) }

    override fun writeRom(addr: Int, value: Int) {
        when (addr shr 13) {
            0 -> ramEnabled = (value and 0x0F) == 0x0A
            1 -> {
                // MBC30 (Pokemon Crystal JP) uses 8 bits for ROM banks larger than 2 MiB.
                romBank = if (romBankCount > 128) value and 0xFF else value and 0x7F
                if (romBank == 0) romBank = 1
                romOffsetX = bankOffset(romBank)
            }
            2 -> ramSelect = value and 0x0F
            else -> if (hasRtc) clock.writeLatch(value)
        }
    }

    override fun readRam(addr: Int): Int {
        if (!ramEnabled) return 0xFF
        if (ramSelect <= 7) {
            if (ram.isEmpty()) return 0xFF
            return ram[(ramSelect * 0x2000 + (addr and 0x1FFF)) % ram.size].toInt() and 0xFF
        }
        if (hasRtc && ramSelect in 0x08..0x0C) return clock.read(ramSelect)
        return 0xFF
    }

    override fun writeRam(addr: Int, value: Int) {
        if (!ramEnabled) return
        if (ramSelect <= 7) {
            if (ram.isEmpty()) return
            ram[(ramSelect * 0x2000 + (addr and 0x1FFF)) % ram.size] = value.toByte()
            ramDirty = true
        } else if (hasRtc && ramSelect in 0x08..0x0C) {
            clock.write(ramSelect, value)
            ramDirty = true
        }
    }

    override fun tick(dots: Int) {
        if (hasRtc) clock.tick(dots)
    }

    override fun saveData(): ByteArray {
        if (!hasRtc) return ram.copyOf()
        return ram + clock.toFooter(System.currentTimeMillis() / 1000)
    }

    override fun loadSaveData(data: ByteArray) {
        super.loadSaveData(data)
        if (hasRtc && data.size >= ram.size + 44) {
            val saved = clock.fromFooter(data, ram.size)
            val now = System.currentTimeMillis() / 1000
            if (saved in 1 until now) clock.advance(now - saved)
        }
    }

    override fun saveState(w: StateWriter) {
        super.saveState(w)
        w.bool(ramEnabled); w.int(romBank); w.int(ramSelect)
        clock.saveState(w)
    }

    override fun loadState(r: StateReader) {
        super.loadState(r)
        ramEnabled = r.bool(); romBank = r.int(); ramSelect = r.int()
        clock.loadState(r)
    }
}

class Mbc5(romData: ByteArray, header: CartridgeHeader) : Cartridge(romData, header) {
    override val hasRumble = header.cartType in 0x1C..0x1E
    private var ramEnabled = false
    private var romBank = 1
    private var ramBank = 0
    private var rumble = false
    private var rumbleSeen = false

    override fun consumeRumble(): Boolean {
        val seen = rumbleSeen || rumble
        rumbleSeen = false
        return seen
    }

    init { romOffsetX = bankOffset(1) }

    override fun writeRom(addr: Int, value: Int) {
        when (addr shr 12) {
            0, 1 -> ramEnabled = (value and 0x0F) == 0x0A
            2 -> { romBank = (romBank and 0x100) or value; romOffsetX = bankOffset(romBank) }
            3 -> { romBank = (romBank and 0xFF) or ((value and 1) shl 8); romOffsetX = bankOffset(romBank) }
            4, 5 -> {
                if (hasRumble) {
                    rumble = value and 0x08 != 0
                    if (rumble) rumbleSeen = true
                    ramBank = value and 0x07
                } else {
                    ramBank = value and 0x0F
                }
            }
        }
    }

    override fun readRam(addr: Int): Int {
        if (!ramEnabled || ram.isEmpty()) return 0xFF
        return ram[(ramBank * 0x2000 + (addr and 0x1FFF)) % ram.size].toInt() and 0xFF
    }

    override fun writeRam(addr: Int, value: Int) {
        if (!ramEnabled || ram.isEmpty()) return
        ram[(ramBank * 0x2000 + (addr and 0x1FFF)) % ram.size] = value.toByte()
        ramDirty = true
    }

    override fun saveState(w: StateWriter) {
        super.saveState(w)
        w.bool(ramEnabled); w.int(romBank); w.int(ramBank); w.bool(rumble)
    }

    override fun loadState(r: StateReader) {
        super.loadState(r)
        ramEnabled = r.bool(); romBank = r.int(); ramBank = r.int(); rumble = r.bool()
    }
}

/** Hudson HuC1: MBC1-like banking plus an infrared port (reported as "no light"). */
class HuC1(romData: ByteArray, header: CartridgeHeader) : Cartridge(romData, header) {
    private var irMode = false
    private var romBank = 1
    private var ramBank = 0

    init { romOffsetX = bankOffset(1) }

    override fun writeRom(addr: Int, value: Int) {
        when (addr shr 13) {
            0 -> irMode = (value and 0x0F) == 0x0E
            1 -> { romBank = value and 0x3F; romOffsetX = bankOffset(romBank) }
            2 -> ramBank = value and 0x03
        }
    }

    override fun readRam(addr: Int): Int {
        if (irMode) return 0xC0
        if (ram.isEmpty()) return 0xFF
        return ram[(ramBank * 0x2000 + (addr and 0x1FFF)) % ram.size].toInt() and 0xFF
    }

    override fun writeRam(addr: Int, value: Int) {
        if (irMode || ram.isEmpty()) return
        ram[(ramBank * 0x2000 + (addr and 0x1FFF)) % ram.size] = value.toByte()
        ramDirty = true
    }

    override fun saveState(w: StateWriter) {
        super.saveState(w)
        w.bool(irMode); w.int(romBank); w.int(ramBank)
    }

    override fun loadState(r: StateReader) {
        super.loadState(r)
        irMode = r.bool(); romBank = r.int(); ramBank = r.int()
    }
}
