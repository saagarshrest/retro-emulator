package com.retroemulator.gb.gba

import com.retroemulator.gb.core.StateReader
import com.retroemulator.gb.core.StateWriter
import java.io.IOException

/**
 * A Game Boy Advance. Components are driven by a single cycle counter: the CPU runs until the next
 * scheduled hardware event (PPU line timing, timer overflow, audio sample) and the event is then
 * processed at the right time. Not thread-safe: drive it from one thread.
 */
class Gba(romData: ByteArray, sampleRate: Int = 48000) {
    val rom: ByteArray = romData
    val title: String
    val gameCode: String

    /** Master clock (16.78 MHz cycles since power-on). */
    var cycles = 0L

    var ie = 0
    var iflags = 0
    var ime = false
    /** IME && (IE & IF) != 0: the CPU takes an IRQ when its I flag is clear. */
    var irqLine = false
        private set
    var halted = false

    /** Set by IO writes that move the next event earlier, so the CPU loop re-plans. */
    var scheduleDirty = false

    val backup = GbaBackup.detect(romData)
    val bus = GbaBus(this)
    val timers = GbaTimers(this)
    val dma = GbaDma(this)
    val ppu = GbaPpu(this)
    val apu = GbaApu(this, sampleRate)
    val bios = GbaBios(this)
    val cpu = Arm7(this)

    private var keys = 0x3FF // KEYINPUT, active low

    init {
        if (romData.size < 0xC0) throw IOException("File is too small to be a Game Boy Advance ROM")
        title = String(romData, 0xA0, 12, Charsets.US_ASCII).trim { it <= ' ' }
        gameCode = String(romData, 0xAC, 4, Charsets.US_ASCII).trim { it <= ' ' }
        reset()
    }

    private fun reset() {
        cycles = 0
        ie = 0; iflags = 0; ime = false; irqLine = false; halted = false
        bus.reset()
        ppu.reset()
        cpu.resetToRom()
    }

    fun requestIrq(bit: Int) {
        iflags = iflags or (1 shl bit)
        updateIrq()
    }

    fun updateIrq() {
        val pending = ie and iflags and 0x3FFF
        irqLine = ime && pending != 0
        if (pending != 0) halted = false
    }

    /** Joypad state as a frontend mask (see [com.retroemulator.gb.emu.Buttons]). */
    fun setButtons(mask: Int) {
        var k = 0
        if (mask and 0x10 != 0) k = k or 0x001   // A
        if (mask and 0x20 != 0) k = k or 0x002   // B
        if (mask and 0x40 != 0) k = k or 0x004   // Select
        if (mask and 0x80 != 0) k = k or 0x008   // Start
        if (mask and 0x01 != 0) k = k or 0x010   // Right
        if (mask and 0x02 != 0) k = k or 0x020   // Left
        if (mask and 0x04 != 0) k = k or 0x040   // Up
        if (mask and 0x08 != 0) k = k or 0x080   // Down
        if (mask and 0x200 != 0) k = k or 0x100  // R
        if (mask and 0x100 != 0) k = k or 0x200  // L
        keys = k.inv() and 0x3FF
        checkKeypadIrq()
    }

    val keyInput: Int get() = keys

    fun checkKeypadIrq() {
        val cnt = bus.ioRead16(0x132)
        if (cnt and 0x4000 == 0) return
        val sel = cnt and 0x3FF
        val pressed = keys.inv() and 0x3FF
        val hit = if (cnt and 0x8000 != 0) sel != 0 && pressed and sel == sel else pressed and sel != 0
        if (hit) requestIrq(12)
    }

    // ------------------------------------------------------------------------------------------
    // Frame loop
    // ------------------------------------------------------------------------------------------

    private fun nextEventTime(): Long = minOf(ppu.nextEvent, minOf(timers.nextEvent, apu.nextEvent))

    /** Runs until the PPU enters VBlank (one frame, 280896 cycles). */
    fun runFrame() {
        ppu.frameReady = false
        var guard = 0
        while (!ppu.frameReady && guard++ < 1_000_000) {
            val target = nextEventTime()
            scheduleDirty = false
            if (halted) {
                if (cycles < target) cycles = target
            } else {
                val cpu = cpu
                while (cycles < target && !scheduleDirty && !halted) cpu.step()
            }
            processEvents()
        }
    }

    private fun processEvents() {
        timers.update()
        if (cycles >= apu.nextEvent) apu.onEvent()
        while (cycles >= ppu.nextEvent) ppu.onEvent()
    }

    val frameBuffer: IntArray get() = ppu.frameBuffer

    // ------------------------------------------------------------------------------------------
    // Battery save
    // ------------------------------------------------------------------------------------------

    val hasBattery: Boolean get() = backup.type != GbaBackup.Type.NONE
    var batteryDirty: Boolean
        get() = backup.dirty
        set(v) { backup.dirty = v }

    fun batteryData(): ByteArray = backup.data.copyOf()
    fun loadBattery(data: ByteArray) = backup.load(data)

    // ------------------------------------------------------------------------------------------
    // Save states
    // ------------------------------------------------------------------------------------------

    fun saveState(): ByteArray {
        val w = StateWriter()
        w.int(STATE_MAGIC); w.int(STATE_VERSION); w.int(identity())
        w.long(cycles); w.int(ie); w.int(iflags); w.bool(ime); w.bool(halted)
        cpu.saveState(w)
        bus.saveState(w)
        ppu.saveState(w)
        timers.saveState(w)
        dma.saveState(w)
        apu.saveState(w)
        backup.saveState(w)
        return w.toByteArray()
    }

    fun loadState(data: ByteArray) {
        val backupState = saveState()
        try {
            loadStateInternal(data)
        } catch (e: Exception) {
            loadStateInternal(backupState)
            throw if (e is IOException) e else IOException("Save state could not be loaded", e)
        }
    }

    private fun loadStateInternal(data: ByteArray) {
        val r = StateReader(data)
        if (r.int() != STATE_MAGIC) throw IOException("Not a save state")
        if (r.int() != STATE_VERSION) throw IOException("Save state is from an incompatible version")
        if (r.int() != identity()) throw IOException("Save state belongs to a different game")
        cycles = r.long(); ie = r.int(); iflags = r.int(); ime = r.bool(); halted = r.bool()
        cpu.loadState(r)
        bus.loadState(r)
        ppu.loadState(r)
        timers.loadState(r)
        dma.loadState(r)
        apu.loadState(r)
        backup.loadState(r)
        updateIrq()
    }

    private fun identity(): Int = (gameCode.hashCode() * 31) xor title.hashCode() xor rom.size

    companion object {
        const val WIDTH = 240
        const val HEIGHT = 160
        const val CLOCK = 16777216
        const val FRAME_CYCLES = 280896
        private const val STATE_MAGIC = 0x47424153 // "GBAS"
        private const val STATE_VERSION = 1

        /** True when [data] looks like a Game Boy Advance ROM (fixed header byte and logo start). */
        fun isGbaRom(data: ByteArray): Boolean {
            if (data.size < 0xC0) return false
            if (data[0xB2].toInt() and 0xFF != 0x96) return false
            return data[0x04].toInt() and 0xFF == 0x24 && data[0x05].toInt() and 0xFF == 0xFF &&
                data[0x06].toInt() and 0xFF == 0xAE && data[0x07].toInt() and 0xFF == 0x51
        }
    }
}
