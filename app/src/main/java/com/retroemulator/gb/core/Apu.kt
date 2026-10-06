package com.retroemulator.gb.core

/**
 * Audio processing unit: two square channels (one with sweep), a wave channel and a noise channel.
 * Channel timers run on the 4.19 MHz clock; the 512 Hz frame sequencer is clocked by the DIV counter.
 * Output is box-filtered down to [sampleRate] as interleaved 16-bit stereo.
 *
 * The Game Boy Advance reuses these four channels (with [gba] set): its wave channel gains a second
 * RAM bank, a 64-sample mode and a 75% volume setting, and the GBA mixes [mixLeft]/[mixRight] itself.
 */
class Apu(private val cgb: Boolean, sampleRate: Int, private val gba: Boolean = false) {
    private val ch1 = SquareChannel(true)
    private val ch2 = SquareChannel(false)
    private val ch3 = WaveChannel(!cgb && !gba, gba)
    private val ch4 = NoiseChannel()

    /** Current mixed PSG output per side (each channel -15..15, times the NR50 volume 1..8). */
    var mixLeft = 0
        private set
    var mixRight = 0
        private set

    private val regs = IntArray(0x17) // FF10-FF26 raw values for read-back
    private var power = true
    private var fsStep = 0
    private var nr50 = 0
    private var nr51 = 0

    var sampleRate = sampleRate
        private set
    private var sampleCounter = 0L
    private var accLeft = 0L
    private var accRight = 0L
    private var accCount = 0
    private var capLeft = 0.0
    private var capRight = 0.0
    private var hpCharge = 0.0

    /** Interleaved stereo samples produced since the last [drainSamples]. */
    private val buffer = ShortArray(16384)
    private var bufferPos = 0

    init {
        setSampleRate(sampleRate)
    }

    fun setSampleRate(rate: Int) {
        sampleRate = rate
        hpCharge = Math.pow(0.999958, CLOCK.toDouble() / rate)
    }

    // ------------------------------------------------------------------------------------------
    // Clocking
    // ------------------------------------------------------------------------------------------

    /** Advance by [dots] cycles of the 4.19 MHz clock and accumulate output for resampling. */
    fun tick(dots: Int) {
        stepChannels(dots)
        mix()
        accLeft += (mixLeft * dots).toLong()
        accRight += (mixRight * dots).toLong()
        accCount += dots
        sampleCounter += dots.toLong() * sampleRate
        if (sampleCounter >= CLOCK) {
            sampleCounter -= CLOCK
            emitSample()
        }
    }

    /** Advances the channel timers by [dots] 4.19 MHz cycles without producing output samples. */
    fun stepChannels(dots: Int) {
        if (!power) return
        ch1.step(dots)
        ch2.step(dots)
        ch3.step(dots)
        ch4.step(dots)
    }

    /** Recomputes [mixLeft] and [mixRight] from the channels' current outputs. */
    fun mix() {
        var left = 0
        var right = 0
        val routing = nr51
        if (ch1.dacEnabled) { val s = ch1.output() * 2 - 15; if (routing and 0x10 != 0) left += s; if (routing and 0x01 != 0) right += s }
        if (ch2.dacEnabled) { val s = ch2.output() * 2 - 15; if (routing and 0x20 != 0) left += s; if (routing and 0x02 != 0) right += s }
        if (ch3.dacEnabled) { val s = ch3.output() * 2 - 15; if (routing and 0x40 != 0) left += s; if (routing and 0x04 != 0) right += s }
        if (ch4.dacEnabled) { val s = ch4.output() * 2 - 15; if (routing and 0x80 != 0) left += s; if (routing and 0x08 != 0) right += s }
        mixLeft = left * (((nr50 ushr 4) and 7) + 1)
        mixRight = right * ((nr50 and 7) + 1)
    }

    private fun emitSample() {
        // Full scale per side is 4 channels * 15 * 8 = 480.
        val inL = accLeft.toDouble() / accCount / 480.0
        val inR = accRight.toDouble() / accCount / 480.0
        accLeft = 0; accRight = 0; accCount = 0
        val outL = inL - capLeft
        capLeft = inL - outL * hpCharge
        val outR = inR - capRight
        capRight = inR - outR * hpCharge
        if (bufferPos + 2 <= buffer.size) {
            buffer[bufferPos++] = toPcm(outL)
            buffer[bufferPos++] = toPcm(outR)
        }
    }

    private fun toPcm(v: Double): Short {
        val s = (v * 28000.0).toInt()
        return (if (s > 32767) 32767 else if (s < -32768) -32768 else s).toShort()
    }

    /** Copies pending samples into [dest] and returns the number of shorts written. */
    fun drainSamples(dest: ShortArray): Int {
        val n = minOf(bufferPos, dest.size)
        System.arraycopy(buffer, 0, dest, 0, n)
        bufferPos = 0
        return n
    }

    fun clockFrameSequencer() {
        if (!power) return
        when (fsStep) {
            0, 4 -> clockLength()
            2, 6 -> { clockLength(); ch1.clockSweep() }
            7 -> { ch1.clockEnvelope(); ch2.clockEnvelope(); ch4.clockEnvelope() }
        }
        fsStep = (fsStep + 1) and 7
    }

    private fun clockLength() {
        ch1.clockLength(); ch2.clockLength(); ch3.clockLength(); ch4.clockLength()
    }

    // ------------------------------------------------------------------------------------------
    // Registers
    // ------------------------------------------------------------------------------------------

    fun read(addr: Int): Int {
        if (addr >= 0xFF30) return ch3.readRam(addr - 0xFF30)
        if (addr == 0xFF26) {
            return (if (power) 0x80 else 0) or 0x70 or
                (if (ch1.enabled) 1 else 0) or (if (ch2.enabled) 2 else 0) or
                (if (ch3.enabled) 4 else 0) or (if (ch4.enabled) 8 else 0)
        }
        if (addr > 0xFF26) return 0xFF
        val i = addr - 0xFF10
        if (gba && addr == 0xFF1A) return regs[i] or 0x1F // bank bits are readable on GBA
        if (gba && addr == 0xFF1C) return regs[i] or 0x1F // 75% volume bit is readable on GBA
        return regs[i] or READ_MASK[i]
    }

    fun write(addr: Int, value: Int) {
        if (addr >= 0xFF30) {
            ch3.writeRam(addr - 0xFF30, value)
            return
        }
        if (addr == 0xFF26) {
            writePower(value and 0x80 != 0)
            return
        }
        if (addr > 0xFF26) return
        if (!power) {
            // On DMG the length counters stay writable while powered off.
            if (!cgb) when (addr) {
                0xFF11 -> ch1.length = 64 - (value and 0x3F)
                0xFF16 -> ch2.length = 64 - (value and 0x3F)
                0xFF1B -> ch3.length = 256 - value
                0xFF20 -> ch4.length = 64 - (value and 0x3F)
            }
            return
        }
        regs[addr - 0xFF10] = value
        when (addr) {
            0xFF10 -> ch1.writeSweep(value)
            0xFF11 -> { ch1.duty = value ushr 6; ch1.length = 64 - (value and 0x3F) }
            0xFF12 -> ch1.writeEnvelope(value)
            0xFF13 -> ch1.freq = (ch1.freq and 0x700) or value
            0xFF14 -> { ch1.freq = (ch1.freq and 0xFF) or ((value and 7) shl 8); ch1.writeControl(value, fsStep) }
            0xFF16 -> { ch2.duty = value ushr 6; ch2.length = 64 - (value and 0x3F) }
            0xFF17 -> ch2.writeEnvelope(value)
            0xFF18 -> ch2.freq = (ch2.freq and 0x700) or value
            0xFF19 -> { ch2.freq = (ch2.freq and 0xFF) or ((value and 7) shl 8); ch2.writeControl(value, fsStep) }
            0xFF1A -> {
                ch3.dacEnabled = value and 0x80 != 0
                if (!ch3.dacEnabled) ch3.enabled = false
                if (gba) { ch3.twoBanks = value and 0x20 != 0; ch3.bank = (value ushr 6) and 1 }
            }
            0xFF1B -> ch3.length = 256 - value
            0xFF1C -> { ch3.volumeCode = (value ushr 5) and 3; if (gba) ch3.force75 = value and 0x80 != 0 }
            0xFF1D -> ch3.freq = (ch3.freq and 0x700) or value
            0xFF1E -> { ch3.freq = (ch3.freq and 0xFF) or ((value and 7) shl 8); ch3.writeControl(value, fsStep) }
            0xFF20 -> ch4.length = 64 - (value and 0x3F)
            0xFF21 -> ch4.writeEnvelope(value)
            0xFF22 -> ch4.writePoly(value)
            0xFF23 -> ch4.writeControl(value, fsStep)
            0xFF24 -> nr50 = value
            0xFF25 -> nr51 = value
        }
    }

    private fun writePower(on: Boolean) {
        if (power && !on) {
            val lengths = intArrayOf(ch1.length, ch2.length, ch3.length, ch4.length)
            for (a in 0xFF10..0xFF25) write(a, 0)
            if (!cgb) {
                ch1.length = lengths[0]; ch2.length = lengths[1]; ch3.length = lengths[2]; ch4.length = lengths[3]
            }
            ch1.enabled = false; ch2.enabled = false; ch3.enabled = false; ch4.enabled = false
            power = false
        } else if (!power && on) {
            power = true
            fsStep = 0
            ch1.dutyPos = 0
            ch2.dutyPos = 0
            ch3.sample = 0
        }
    }

    /** CGB PCM12 / PCM34 registers: current digital output of each channel. */
    fun readPcm(addr: Int): Int =
        if (addr == 0xFF76) ch1.output() or (ch2.output() shl 4)
        else ch3.output() or (ch4.output() shl 4)

    /** Register state left behind by the boot ROM. */
    fun reset(cgb: Boolean) {
        power = true
        val init = intArrayOf(
            0xFF10, 0x80, 0xFF11, 0xBF, 0xFF12, 0xF3, 0xFF13, 0xFF,
            0xFF16, 0x3F, 0xFF17, 0x00, 0xFF18, 0xFF,
            0xFF1A, 0x7F, 0xFF1B, 0xFF, 0xFF1C, 0x9F, 0xFF1D, 0xFF,
            0xFF20, 0xFF, 0xFF21, 0x00, 0xFF22, 0x00,
            0xFF24, 0x77, 0xFF25, 0xF3,
        )
        for (i in init.indices step 2) write(init[i], init[i + 1])
        // The boot chime leaves channel 1 running with its envelope decayed to silence (NR52 = $F1).
        ch1.enabled = true
        ch2.enabled = false; ch3.enabled = false; ch4.enabled = false
        val wave = if (cgb) intArrayOf(0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF)
        else intArrayOf(0x84, 0x40, 0x43, 0xAA, 0x2D, 0x78, 0x92, 0x3C, 0x60, 0x59, 0x59, 0xB0, 0x34, 0xB8, 0x2E, 0xDA)
        for (i in 0 until 16) ch3.ram[i] = wave[i].toByte()
    }

    fun saveState(w: StateWriter) {
        w.tag("apu")
        w.ints(regs)
        w.bool(power); w.int(fsStep); w.int(nr50); w.int(nr51)
        w.long(sampleCounter); w.long(accLeft); w.long(accRight); w.int(accCount)
        w.double(capLeft); w.double(capRight)
        ch1.save(w); ch2.save(w); ch3.save(w); ch4.save(w)
    }

    fun loadState(r: StateReader) {
        r.tag("apu")
        r.intsInto(regs)
        power = r.bool(); fsStep = r.int(); nr50 = r.int(); nr51 = r.int()
        sampleCounter = r.long(); accLeft = r.long(); accRight = r.long(); accCount = r.int()
        capLeft = r.double(); capRight = r.double()
        ch1.load(r); ch2.load(r); ch3.load(r); ch4.load(r)
        bufferPos = 0
    }

    companion object {
        const val CLOCK = 4194304

        private val READ_MASK = intArrayOf(
            0x80, 0x3F, 0x00, 0xFF, 0xBF, // NR10-NR14
            0xFF, 0x3F, 0x00, 0xFF, 0xBF, // NR20-NR24
            0x7F, 0xFF, 0x9F, 0xFF, 0xBF, // NR30-NR34
            0xFF, 0xFF, 0x00, 0x00, 0xBF, // NR40-NR44
            0x00, 0x00, 0x70,             // NR50-NR52
        )
    }
}

/** Shared length-counter logic, including the "extra length clock" obscure behaviour. */
private abstract class LengthChannel(private val maxLength: Int) {
    var enabled = false
    var dacEnabled = false
    var length = 0
    var lengthEnabled = false

    fun clockLength() {
        if (lengthEnabled && length > 0) {
            length--
            if (length == 0) enabled = false
        }
    }

    /** Handles NRx4: length enable and trigger. [fsStep] is the next frame sequencer step. */
    fun writeControl(value: Int, fsStep: Int) {
        val trigger = value and 0x80 != 0
        val newEnable = value and 0x40 != 0
        val firstHalf = fsStep and 1 == 1
        if (firstHalf && !lengthEnabled && newEnable && length > 0) {
            length--
            if (length == 0 && !trigger) enabled = false
        }
        lengthEnabled = newEnable
        if (trigger) {
            enabled = true
            if (length == 0) {
                length = maxLength
                if (lengthEnabled && firstHalf) length--
            }
            onTrigger()
            if (!dacEnabled) enabled = false
        }
    }

    protected abstract fun onTrigger()

    protected fun saveBase(w: StateWriter) {
        w.bool(enabled); w.bool(dacEnabled); w.int(length); w.bool(lengthEnabled)
    }

    protected fun loadBase(r: StateReader) {
        enabled = r.bool(); dacEnabled = r.bool(); length = r.int(); lengthEnabled = r.bool()
    }
}

private abstract class EnvelopeChannel(maxLength: Int) : LengthChannel(maxLength) {
    var envInitial = 0
    var envUp = false
    var envPeriod = 0
    var volume = 0
    var envTimer = 0

    fun writeEnvelope(value: Int) {
        envInitial = value ushr 4
        envUp = value and 0x08 != 0
        envPeriod = value and 0x07
        dacEnabled = value and 0xF8 != 0
        if (!dacEnabled) enabled = false
    }

    fun clockEnvelope() {
        if (envPeriod == 0) return
        if (--envTimer <= 0) {
            envTimer = envPeriod
            if (envUp && volume < 15) volume++
            else if (!envUp && volume > 0) volume--
        }
    }

    protected fun triggerEnvelope() {
        volume = envInitial
        envTimer = if (envPeriod == 0) 8 else envPeriod
    }

    protected fun saveEnvelope(w: StateWriter) {
        saveBase(w)
        w.int(envInitial); w.bool(envUp); w.int(envPeriod); w.int(volume); w.int(envTimer)
    }

    protected fun loadEnvelope(r: StateReader) {
        loadBase(r)
        envInitial = r.int(); envUp = r.bool(); envPeriod = r.int(); volume = r.int(); envTimer = r.int()
    }
}

private class SquareChannel(private val hasSweep: Boolean) : EnvelopeChannel(64) {
    var duty = 0
    var dutyPos = 0
    var freq = 0
    private var timer = 8192

    private var sweepPeriod = 0
    private var sweepNegate = false
    private var sweepShift = 0
    private var sweepTimer = 0
    private var sweepEnabled = false
    private var shadowFreq = 0
    private var negateUsed = false

    fun step(cycles: Int) {
        timer -= cycles
        while (timer <= 0) {
            timer += (2048 - freq) * 4
            dutyPos = (dutyPos + 1) and 7
        }
    }

    fun output(): Int = if (enabled && (DUTY[duty] ushr dutyPos) and 1 != 0) volume else 0

    fun writeSweep(value: Int) {
        sweepPeriod = (value ushr 4) and 7
        val negate = value and 0x08 != 0
        if (sweepNegate && !negate && negateUsed) enabled = false
        sweepNegate = negate
        sweepShift = value and 7
    }

    override fun onTrigger() {
        timer = (2048 - freq) * 4
        triggerEnvelope()
        if (hasSweep) {
            shadowFreq = freq
            sweepTimer = if (sweepPeriod == 0) 8 else sweepPeriod
            sweepEnabled = sweepPeriod != 0 || sweepShift != 0
            negateUsed = false
            if (sweepShift != 0) calcSweep()
        }
    }

    fun clockSweep() {
        if (--sweepTimer > 0) return
        sweepTimer = if (sweepPeriod == 0) 8 else sweepPeriod
        if (!sweepEnabled || sweepPeriod == 0) return
        val newFreq = calcSweep()
        if (newFreq <= 2047 && sweepShift != 0) {
            shadowFreq = newFreq
            freq = newFreq
            calcSweep()
        }
    }

    private fun calcSweep(): Int {
        val delta = shadowFreq ushr sweepShift
        val result = if (sweepNegate) {
            negateUsed = true
            shadowFreq - delta
        } else {
            shadowFreq + delta
        }
        if (result > 2047) enabled = false
        return result
    }

    fun save(w: StateWriter) {
        saveEnvelope(w)
        w.ints(intArrayOf(duty, dutyPos, freq, timer, sweepPeriod, sweepShift, sweepTimer, shadowFreq))
        w.bool(sweepNegate); w.bool(sweepEnabled); w.bool(negateUsed)
    }

    fun load(r: StateReader) {
        loadEnvelope(r)
        val v = IntArray(8)
        r.intsInto(v)
        duty = v[0]; dutyPos = v[1]; freq = v[2]; timer = v[3]
        sweepPeriod = v[4]; sweepShift = v[5]; sweepTimer = v[6]; shadowFreq = v[7]
        sweepNegate = r.bool(); sweepEnabled = r.bool(); negateUsed = r.bool()
    }

    companion object {
        // Bit i set = high at duty position i. 12.5%, 25%, 50%, 75%.
        private val DUTY = intArrayOf(0b10000000, 0b10000001, 0b11100001, 0b01111110)
    }
}

private class WaveChannel(private val dmg: Boolean, private val gba: Boolean) : LengthChannel(256) {
    /** Wave RAM (bank 0, the only bank on Game Boy). */
    val ram = ByteArray(16)
    /** Second bank, GBA only. */
    private val ram2 = ByteArray(16)
    var volumeCode = 0
    var freq = 0
    var sample = 0
    /** GBA: play both banks as one 64-sample wave. */
    var twoBanks = false
    /** GBA: bank being played; the CPU sees the other one. */
    var bank = 0
    /** GBA: fixed 75% volume. */
    var force75 = false
    private var position = 0
    private var timer = 4096
    /** True when the channel fetched a sample in the current M-cycle (DMG wave RAM access window). */
    private var justRead = false

    private fun bankRam(b: Int) = if (b == 0) ram else ram2

    fun step(cycles: Int) {
        justRead = false
        if (!enabled) return
        timer -= cycles
        while (timer <= 0) {
            // The access window is the 2-dot APU cycle in which the fetch happened.
            justRead = timer > -2
            timer += (2048 - freq) * 2
            position = (position + 1) and (if (twoBanks) 63 else 31)
            val source = if (gba) bankRam((bank + (position ushr 5)) and 1) else ram
            val byte = source[(position and 31) ushr 1].toInt()
            sample = if (position and 1 == 0) (byte ushr 4) and 0x0F else byte and 0x0F
        }
    }

    fun output(): Int {
        if (!enabled) return 0
        return if (force75) sample * 3 / 4 else sample ushr VOLUME_SHIFT[volumeCode]
    }

    override fun onTrigger() {
        // DMG bug: retriggering just before the channel reads corrupts the start of wave RAM.
        if (dmg && enabled && timer <= 2) {
            val offset = ((position + 1) ushr 1) and 0x0F
            if (offset < 4) ram[0] = ram[offset]
            else System.arraycopy(ram, offset and 0x0C, ram, 0, 4)
        }
        timer = (2048 - freq) * 2 + 6
        position = 0
    }

    // While playing, the CPU sees the byte the channel is currently reading. On DMG that only works in
    // the cycle the channel accesses wave RAM; otherwise reads return 0xFF and writes are dropped.
    fun readRam(i: Int): Int {
        if (gba) return bankRam(bank xor 1)[i].toInt() and 0xFF
        if (!enabled) return ram[i].toInt() and 0xFF
        if (dmg && !justRead) return 0xFF
        return ram[position ushr 1].toInt() and 0xFF
    }

    fun writeRam(i: Int, value: Int) {
        if (gba) {
            bankRam(bank xor 1)[i] = value.toByte()
        } else if (!enabled) {
            ram[i] = value.toByte()
        } else if (!dmg || justRead) {
            ram[position ushr 1] = value.toByte()
        }
    }

    fun save(w: StateWriter) {
        saveBase(w)
        w.bytes(ram)
        w.ints(intArrayOf(volumeCode, freq, sample, position, timer))
        w.bool(justRead)
        if (gba) {
            w.bytes(ram2)
            w.bool(twoBanks); w.int(bank); w.bool(force75)
        }
    }

    fun load(r: StateReader) {
        loadBase(r)
        r.bytesInto(ram)
        val v = IntArray(5)
        r.intsInto(v)
        volumeCode = v[0]; freq = v[1]; sample = v[2]; position = v[3]; timer = v[4]
        justRead = r.bool()
        if (gba) {
            r.bytesInto(ram2)
            twoBanks = r.bool(); bank = r.int(); force75 = r.bool()
        }
    }

    companion object {
        private val VOLUME_SHIFT = intArrayOf(4, 0, 1, 2)
    }
}

private class NoiseChannel : EnvelopeChannel(64) {
    private var clockShift = 0
    private var widthMode = false
    private var divisorCode = 0
    private var lfsr = 0x7FFF
    private var timer = 8

    private fun period(): Int = DIVISORS[divisorCode] shl clockShift

    fun writePoly(value: Int) {
        clockShift = value ushr 4
        widthMode = value and 0x08 != 0
        divisorCode = value and 0x07
    }

    fun step(cycles: Int) {
        if (clockShift >= 14) return
        timer -= cycles
        while (timer <= 0) {
            timer += period()
            val bit = (lfsr xor (lfsr ushr 1)) and 1
            lfsr = (lfsr ushr 1) or (bit shl 14)
            if (widthMode) lfsr = (lfsr and 0x40.inv()) or (bit shl 6)
        }
    }

    fun output(): Int = if (enabled && lfsr and 1 == 0) volume else 0

    override fun onTrigger() {
        lfsr = 0x7FFF
        timer = period()
        triggerEnvelope()
    }

    fun save(w: StateWriter) {
        saveEnvelope(w)
        w.ints(intArrayOf(clockShift, divisorCode, lfsr, timer))
        w.bool(widthMode)
    }

    fun load(r: StateReader) {
        loadEnvelope(r)
        val v = IntArray(4)
        r.intsInto(v)
        clockShift = v[0]; divisorCode = v[1]; lfsr = v[2]; timer = v[3]
        widthMode = r.bool()
    }

    companion object {
        private val DIVISORS = intArrayOf(8, 16, 32, 48, 64, 80, 96, 112)
    }
}
