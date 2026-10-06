package com.retroemulator.gb.core

import java.io.IOException

/**
 * A complete Game Boy / Game Boy Color system. Not thread-safe: drive it from a single thread.
 *
 * @param forceDmg run CGB-enhanced (dual mode) cartridges as an original Game Boy.
 */
class GameBoy(romData: ByteArray, forceDmg: Boolean = false, sampleRate: Int = 48000) {
    val cart: Cartridge = Cartridge.create(romData)
    val cgb: Boolean = cart.header.supportsCgb && !(forceDmg && !cart.header.cgbOnly)

    var intFlag = 0
    var intEnable = 0
    var doubleSpeed = false
    var speedSwitchArmed = false

    val timer = Timer(this)
    val apu = Apu(cgb, sampleRate)
    val ppu = Ppu(this)
    val joypad = Joypad(this)
    val serial = Serial(this)
    val mmu = Mmu(this)
    val cpu = Cpu(this)
    val cheats = CheatEngine(this)

    private var frameDots = 0
    private val hasRtc = cart.rtc != null

    /** Total dots (normal-speed clock cycles) emulated; useful for timing and tests. */
    var totalDots = 0L
        private set

    init {
        reset()
    }

    fun pendingInterrupts(): Int = intEnable and intFlag and 0x1F

    /** Advance every component by one CPU M-cycle. */
    fun tick() {
        timer.tick()
        if (mmu.dmaBusy) mmu.dmaTick()
        val dots = if (doubleSpeed) 2 else 4
        ppu.tick(dots)
        apu.tick(dots)
        if (hasRtc) cart.tick(dots)
        frameDots += dots
    }

    fun switchSpeed() {
        doubleSpeed = !doubleSpeed
        speedSwitchArmed = false
        timer.writeDiv()
    }

    /** Runs until the PPU finishes a frame (or one frame's worth of time passes with the LCD off). */
    fun runFrame() {
        if (cheats.active) cheats.applyFrame()
        ppu.frameReady = false
        frameDots = 0
        while (true) {
            mmu.serviceHdma()
            cpu.step()
            if (ppu.frameReady) break
            if (frameDots >= FRAME_DOTS && (!ppu.lcdOn || frameDots >= FRAME_DOTS * 2)) break
        }
        totalDots += frameDots
    }

    /** Executes a single CPU step (instruction, interrupt dispatch or halted cycle). */
    fun step() {
        mmu.serviceHdma()
        cpu.step()
    }

    private fun reset() {
        intFlag = 0x01
        intEnable = 0
        doubleSpeed = false
        speedSwitchArmed = false
        mmu.reset(cgb)
        ppu.reset(cgb)
        apu.reset(cgb)
        cpu.reset(cgb)
        timer.counter = if (cgb) 0x1EA0 else 0xABC8
        timer.tima = 0
        timer.tma = 0
        timer.tac = 0
        joypad.write(0x00)
        serial.sb = 0
        serial.sc = 0
    }

    // ------------------------------------------------------------------------------------------
    // Save states
    // ------------------------------------------------------------------------------------------

    fun saveState(): ByteArray {
        val w = StateWriter()
        w.int(STATE_MAGIC)
        w.int(STATE_VERSION)
        w.int(stateIdentity())
        w.bool(cgb)
        w.int(intFlag); w.int(intEnable); w.bool(doubleSpeed); w.bool(speedSwitchArmed); w.int(frameDots)
        cpu.saveState(w)
        mmu.saveState(w)
        ppu.saveState(w)
        apu.saveState(w)
        timer.saveState(w)
        joypad.saveState(w)
        serial.saveState(w)
        cart.saveState(w)
        return w.toByteArray()
    }

    /** Restores a state from [saveState]. On failure the current state is left untouched. */
    fun loadState(data: ByteArray) {
        val backup = saveState()
        try {
            loadStateInternal(data)
        } catch (e: Exception) {
            loadStateInternal(backup)
            throw if (e is IOException) e else IOException("Save state could not be loaded", e)
        }
    }

    private fun loadStateInternal(data: ByteArray) {
        val r = StateReader(data)
        if (r.int() != STATE_MAGIC) throw IOException("Not a save state")
        if (r.int() != STATE_VERSION) throw IOException("Save state is from an incompatible version")
        if (r.int() != stateIdentity()) throw IOException("Save state belongs to a different game")
        if (r.bool() != cgb) throw IOException("Save state was made in a different hardware mode")
        intFlag = r.int(); intEnable = r.int(); doubleSpeed = r.bool(); speedSwitchArmed = r.bool(); frameDots = r.int()
        cpu.loadState(r)
        mmu.loadState(r)
        ppu.loadState(r)
        apu.loadState(r)
        timer.loadState(r)
        joypad.loadState(r)
        serial.loadState(r)
        cart.loadState(r)
    }

    private fun stateIdentity(): Int =
        (cart.header.globalChecksum shl 8) xor cart.header.headerChecksum xor cart.header.title.hashCode()

    companion object {
        /** Dots per frame: 154 lines * 456 dots. */
        const val FRAME_DOTS = 70224
        /** Frames per second of real hardware. */
        const val FRAME_RATE = 4194304.0 / FRAME_DOTS
        private const val STATE_MAGIC = 0x52474253 // "RGBS"
        private const val STATE_VERSION = 1
    }
}
