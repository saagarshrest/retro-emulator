package com.retroemulator.gb.core

/**
 * DIV/TIMA timer. Modelled on the 16-bit internal counter: TIMA increments on the falling edge of the
 * counter bit selected by TAC, and overflow reloads TMA one M-cycle late (with the documented
 * write-cancellation quirks). The same counter clocks the APU frame sequencer.
 */
class Timer(private val gb: GameBoy) {
    var counter = 0
    var tima = 0
    var tma = 0
    var tac = 0
    private var overflowPending = false
    private var reloading = false

    val div: Int get() = counter ushr 8

    private fun mask(t: Int): Int = when (t and 3) {
        0 -> 1 shl 9
        1 -> 1 shl 3
        2 -> 1 shl 5
        else -> 1 shl 7
    }

    private fun apuMask(): Int = if (gb.doubleSpeed) 1 shl 13 else 1 shl 12

    /** Advance one M-cycle (4 CPU clocks). */
    fun tick() {
        reloading = false
        if (overflowPending) {
            overflowPending = false
            tima = tma
            gb.intFlag = gb.intFlag or 0x04
            reloading = true
        }
        val old = counter
        counter = (counter + 4) and 0xFFFF
        val falling = old and counter.inv()
        if (tac and 4 != 0 && falling and mask(tac) != 0) incrementTima()
        if (falling and apuMask() != 0) gb.apu.clockFrameSequencer()
        if (gb.serial.active) gb.serial.clock(counter)
    }

    private fun incrementTima() {
        if (tima == 0xFF) {
            tima = 0
            overflowPending = true
        } else {
            tima++
        }
    }

    fun writeDiv() {
        if (tac and 4 != 0 && counter and mask(tac) != 0) incrementTima()
        if (counter and apuMask() != 0) gb.apu.clockFrameSequencer()
        counter = 0
    }

    fun writeTima(value: Int) {
        if (reloading) return
        tima = value
        overflowPending = false
    }

    fun writeTma(value: Int) {
        tma = value
        if (reloading) tima = value
    }

    fun writeTac(value: Int) {
        val oldSignal = tac and 4 != 0 && counter and mask(tac) != 0
        tac = value and 7
        val newSignal = tac and 4 != 0 && counter and mask(tac) != 0
        if (oldSignal && !newSignal) incrementTima()
    }

    fun saveState(w: StateWriter) {
        w.tag("timer")
        w.int(counter); w.int(tima); w.int(tma); w.int(tac)
        w.bool(overflowPending); w.bool(reloading)
    }

    fun loadState(r: StateReader) {
        r.tag("timer")
        counter = r.int(); tima = r.int(); tma = r.int(); tac = r.int()
        overflowPending = r.bool(); reloading = r.bool()
    }
}
