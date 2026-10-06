package com.retroemulator.gb.gba

import com.retroemulator.gb.core.Apu
import com.retroemulator.gb.core.StateReader
import com.retroemulator.gb.core.StateWriter

/**
 * GBA sound: the four Game Boy channels (reused from [Apu]) plus two 8-bit DirectSound FIFOs fed by
 * DMA and clocked by timer 0/1 overflows. Samples are produced at [sampleRate] as interleaved
 * 16-bit stereo through a scheduled event.
 */
class GbaApu(private val gba: Gba, sampleRate: Int) {
    private val psg = Apu(true, sampleRate, gba = true)

    var sampleRate = sampleRate
        private set

    var nextEvent = 0L
        private set
    private var periodWhole = 0L
    private var periodFrac = 0
    private var fracAcc = 0
    private var psgTime = 0L
    private var fsNext = FS_PERIOD

    private var soundcntH = 0
    private var bias = 0x200

    private val fifo = Array(2) { ByteArray(32) }
    private val fifoRead = IntArray(2)
    private val fifoCount = IntArray(2)
    private val fifoSample = IntArray(2)

    private var capLeft = 0.0
    private var capRight = 0.0
    private var hpCharge = 0.0

    private val buffer = ShortArray(16384)
    private var bufferPos = 0

    init {
        setSampleRate(sampleRate)
        nextEvent = periodWhole
    }

    fun setSampleRate(rate: Int) {
        sampleRate = rate
        periodWhole = (Gba.CLOCK / rate).toLong()
        periodFrac = Gba.CLOCK % rate
        hpCharge = Math.pow(0.999958, Apu.CLOCK.toDouble() / rate)
        psg.setSampleRate(rate)
    }

    // ------------------------------------------------------------------------------------------
    // Registers
    // ------------------------------------------------------------------------------------------

    private fun nrAddress(off: Int): Int = when (off) {
        0x60 -> 0xFF10; 0x62 -> 0xFF11; 0x63 -> 0xFF12; 0x64 -> 0xFF13; 0x65 -> 0xFF14
        0x68 -> 0xFF16; 0x69 -> 0xFF17; 0x6C -> 0xFF18; 0x6D -> 0xFF19
        0x70 -> 0xFF1A; 0x72 -> 0xFF1B; 0x73 -> 0xFF1C; 0x74 -> 0xFF1D; 0x75 -> 0xFF1E
        0x78 -> 0xFF20; 0x79 -> 0xFF21; 0x7C -> 0xFF22; 0x7D -> 0xFF23
        0x80 -> 0xFF24; 0x81 -> 0xFF25; 0x84 -> 0xFF26
        in 0x90..0x9F -> 0xFF30 + (off - 0x90)
        else -> -1
    }

    private fun readMask(off: Int): Int = when (off) {
        0x60 -> 0x7F; 0x62, 0x68 -> 0xC0; 0x63, 0x69, 0x79, 0x7C, 0x81 -> 0xFF
        0x65, 0x6D, 0x75, 0x7D -> 0x40; 0x70, 0x73 -> 0xE0; 0x80 -> 0x77; 0x84 -> 0x8F
        in 0x90..0x9F -> 0xFF
        else -> 0
    }

    private fun read8(off: Int): Int = when (off) {
        0x82 -> soundcntH and 0x0F
        0x83 -> (soundcntH ushr 8) and 0x77
        0x88 -> bias and 0xFE
        0x89 -> (bias ushr 8) and 0xC3
        else -> {
            val nr = nrAddress(off)
            if (nr < 0) 0 else psg.read(nr) and readMask(off)
        }
    }

    fun read16(off: Int): Int = read8(off) or (read8(off + 1) shl 8)

    fun write8(off: Int, value: Int) {
        when (off) {
            0x82 -> soundcntH = (soundcntH and 0xFF00) or value
            0x83 -> {
                soundcntH = (soundcntH and 0x00FF) or ((value and 0x77) shl 8)
                if (value and 0x08 != 0) resetFifo(0)
                if (value and 0x80 != 0) resetFifo(1)
            }
            0x88 -> bias = (bias and 0xFF00) or (value and 0xFE)
            0x89 -> bias = (bias and 0x00FF) or ((value and 0xC3) shl 8)
            in 0xA0..0xA3 -> pushFifo(0, value)
            in 0xA4..0xA7 -> pushFifo(1, value)
            else -> {
                val nr = nrAddress(off)
                if (nr >= 0) psg.write(nr, value)
            }
        }
    }

    fun writeFifo32(addr: Int, value: Int) {
        val f = if (addr and 0xFF == 0xA0) 0 else 1
        for (i in 0 until 4) pushFifo(f, (value ushr (i * 8)) and 0xFF)
    }

    private fun resetFifo(f: Int) {
        fifoRead[f] = 0
        fifoCount[f] = 0
    }

    private fun pushFifo(f: Int, value: Int) {
        if (fifoCount[f] >= 32) return
        fifo[f][(fifoRead[f] + fifoCount[f]) and 31] = value.toByte()
        fifoCount[f]++
    }

    /** Timer 0 or 1 overflowed [count] times: each FIFO driven by it plays its next sample(s). */
    fun onTimerOverflow(timer: Int, count: Long) {
        for (f in 0 until 2) {
            val sel = (soundcntH ushr (10 + f * 4)) and 1
            if (sel != timer) continue
            var n = count
            while (n-- > 0 && fifoCount[f] > 0) {
                fifoSample[f] = fifo[f][fifoRead[f]].toInt()
                fifoRead[f] = (fifoRead[f] + 1) and 31
                fifoCount[f]--
            }
            if (fifoCount[f] <= 16) gba.dma.onFifoRequest(if (f == 0) 0x040000A0 else 0x040000A4)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Sample generation
    // ------------------------------------------------------------------------------------------

    fun onEvent() {
        while (nextEvent <= gba.cycles) {
            produceSample(nextEvent)
            fracAcc += periodFrac
            var step = periodWhole
            if (fracAcc >= sampleRate) { fracAcc -= sampleRate; step++ }
            nextEvent += step
        }
    }

    private fun produceSample(now: Long) {
        // Advance the PSG in a few slices and average them (a simple box filter).
        var accL = 0
        var accR = 0
        for (slice in 1..SLICES) {
            val target = psgTime + (now - psgTime) * slice / SLICES
            val dots = ((target - psgTime) ushr 2).toInt()
            if (dots > 0) {
                psg.stepChannels(dots)
                psgTime += dots.toLong() * 4
            }
            if (psgTime >= fsNext) {
                psg.clockFrameSequencer()
                fsNext += FS_PERIOD
            }
            psg.mix()
            accL += psg.mixLeft
            accR += psg.mixRight
        }
        val power = psg.read(0xFF26) and 0x80 != 0
        var left = 0
        var right = 0
        if (power) {
            // PSG: signed -480..480 per side, scaled by SOUNDCNT_H to at most +-240.
            val psgShift = when (soundcntH and 3) { 0 -> 3; 1 -> 2; else -> 1 }
            left = (accL / SLICES) shr psgShift
            right = (accR / SLICES) shr psgShift
            for (f in 0 until 2) {
                val s = fifoSample[f] * (if (soundcntH and (4 shl f) != 0) 4 else 2)
                val ctl = soundcntH ushr (8 + f * 4)
                if (ctl and 1 != 0) right += s
                if (ctl and 2 != 0) left += s
            }
        }
        // The output stage clamps to a 10-bit range around the bias level.
        left = left.coerceIn(-512, 511)
        right = right.coerceIn(-512, 511)
        val inL = left / 512.0
        val inR = right / 512.0
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
        val s = (v * 30000.0).toInt()
        return (if (s > 32767) 32767 else if (s < -32768) -32768 else s).toShort()
    }

    /** Copies pending samples into [dest] and returns the number of shorts written. */
    fun drainSamples(dest: ShortArray): Int {
        val n = minOf(bufferPos, dest.size)
        System.arraycopy(buffer, 0, dest, 0, n)
        bufferPos = 0
        return n
    }

    // ------------------------------------------------------------------------------------------

    fun saveState(w: StateWriter) {
        w.tag("gbaapu")
        psg.saveState(w)
        w.long(nextEvent); w.int(fracAcc); w.long(psgTime); w.long(fsNext)
        w.int(soundcntH); w.int(bias)
        w.bytes(fifo[0]); w.bytes(fifo[1])
        w.ints(fifoRead); w.ints(fifoCount); w.ints(fifoSample)
        w.double(capLeft); w.double(capRight)
    }

    fun loadState(r: StateReader) {
        r.tag("gbaapu")
        psg.loadState(r)
        nextEvent = r.long(); fracAcc = r.int(); psgTime = r.long(); fsNext = r.long()
        soundcntH = r.int(); bias = r.int()
        r.bytesInto(fifo[0]); r.bytesInto(fifo[1])
        r.intsInto(fifoRead); r.intsInto(fifoCount); r.intsInto(fifoSample)
        capLeft = r.double(); capRight = r.double()
        bufferPos = 0
    }

    companion object {
        private const val FS_PERIOD = 32768L // 512 Hz frame sequencer
        private const val SLICES = 4
    }
}
