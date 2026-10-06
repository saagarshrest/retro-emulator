package com.retroemulator.gb.emu

import com.retroemulator.gb.core.CheatPatch
import com.retroemulator.gb.core.GameBoy
import com.retroemulator.gb.data.Palettes
import com.retroemulator.gb.gba.Gba

/** Frontend button bits: the Game Boy [com.retroemulator.gb.core.Joypad] bits plus the GBA shoulders. */
object Buttons {
    const val L = 0x100
    const val R = 0x200
    const val COUNT = 10
}

/**
 * A console the frontend can run: a Game Boy (Color) or a Game Boy Advance. Not thread-safe; the
 * [EmulatorSession] serialises access.
 */
interface Machine {
    val isGba: Boolean
    val screenWidth: Int
    val screenHeight: Int
    val frameRate: Double
    /** ARGB pixels of the last completed frame, [screenWidth] x [screenHeight]. */
    val frameBuffer: IntArray

    fun setButtons(mask: Int)
    fun runFrame()
    fun drainSamples(dest: ShortArray): Int

    val hasBattery: Boolean
    var batteryDirty: Boolean
    fun batteryData(): ByteArray
    fun loadBattery(data: ByteArray)

    fun saveState(): ByteArray
    fun loadState(data: ByteArray)

    /** True once per rumble pulse requested by the cartridge since the last call. */
    fun consumeRumble(): Boolean = false
    /** Real time that passed while emulation was paused (cartridge clocks). */
    fun advanceClock(seconds: Long) {}
    fun applyVideo(palette: Palettes.Palette, colorCorrection: Boolean)
    val supportsCheats: Boolean get() = false
    fun setCheats(patches: List<CheatPatch>) {}

    companion object {
        /** Builds the right machine for [rom]. */
        fun create(rom: ByteArray, forceDmg: Boolean, sampleRate: Int): Machine =
            if (Gba.isGbaRom(rom)) GbaMachine(Gba(rom, sampleRate)) else GameBoyMachine(GameBoy(rom, forceDmg, sampleRate))
    }
}

class GameBoyMachine(val gb: GameBoy) : Machine {
    override val isGba get() = false
    override val screenWidth get() = 160
    override val screenHeight get() = 144
    override val frameRate get() = GameBoy.FRAME_RATE
    override val frameBuffer: IntArray get() = gb.ppu.frameBuffer

    override fun setButtons(mask: Int) = gb.joypad.setState(mask and 0xFF)
    override fun runFrame() = gb.runFrame()
    override fun drainSamples(dest: ShortArray) = gb.apu.drainSamples(dest)

    override val hasBattery get() = gb.cart.hasBattery
    override var batteryDirty: Boolean
        get() = gb.cart.ramDirty
        set(v) { gb.cart.ramDirty = v }
    override fun batteryData(): ByteArray = gb.cart.saveData()
    override fun loadBattery(data: ByteArray) = gb.cart.loadSaveData(data)

    override fun saveState(): ByteArray = gb.saveState()
    override fun loadState(data: ByteArray) = gb.loadState(data)

    override fun consumeRumble() = gb.cart.hasRumble && gb.cart.consumeRumble()
    override fun advanceClock(seconds: Long) { gb.cart.rtc?.advance(seconds) }
    override fun applyVideo(palette: Palettes.Palette, colorCorrection: Boolean) {
        gb.ppu.setDmgPalette(palette.bg, palette.obj0, palette.obj1)
        gb.ppu.setColorCorrection(colorCorrection)
    }
    override val supportsCheats get() = true
    override fun setCheats(patches: List<CheatPatch>) = gb.cheats.set(patches)
}

class GbaMachine(val gba: Gba) : Machine {
    override val isGba get() = true
    override val screenWidth get() = Gba.WIDTH
    override val screenHeight get() = Gba.HEIGHT
    override val frameRate get() = Gba.CLOCK.toDouble() / Gba.FRAME_CYCLES
    override val frameBuffer: IntArray get() = gba.frameBuffer

    override fun setButtons(mask: Int) = gba.setButtons(mask)
    override fun runFrame() = gba.runFrame()
    override fun drainSamples(dest: ShortArray) = gba.apu.drainSamples(dest)

    override val hasBattery get() = gba.hasBattery
    override var batteryDirty: Boolean
        get() = gba.batteryDirty
        set(v) { gba.batteryDirty = v }
    override fun batteryData(): ByteArray = gba.batteryData()
    override fun loadBattery(data: ByteArray) = gba.loadBattery(data)

    override fun saveState(): ByteArray = gba.saveState()
    override fun loadState(data: ByteArray) = gba.loadState(data)

    override fun applyVideo(palette: Palettes.Palette, colorCorrection: Boolean) =
        gba.ppu.setColorCorrection(colorCorrection)
}
