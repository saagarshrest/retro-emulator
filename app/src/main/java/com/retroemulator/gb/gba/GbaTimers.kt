package com.retroemulator.gb.gba

import com.retroemulator.gb.core.StateReader
import com.retroemulator.gb.core.StateWriter

/**
 * The four 16-bit timers. Counters are evaluated lazily from the master clock (so reads are exact),
 * and the next overflow is scheduled as an event so IRQs, cascades and sound FIFOs fire on time.
 */
class GbaTimers(private val gba: Gba) {
    private val reload = IntArray(4)
    private val control = IntArray(4)
    private val counter = IntArray(4)
    private val base = LongArray(4)

    /** Master-clock time of the next overflow of a free-running timer. */
    var nextEvent = Long.MAX_VALUE
        private set

    private fun shift(c: Int): Int = when (c and 3) { 0 -> 0; 1 -> 6; 2 -> 8; else -> 10 }
    private fun running(i: Int) = control[i] and 0x80 != 0
    private fun cascade(i: Int) = i > 0 && control[i] and 0x04 != 0

    fun readCounter(i: Int): Int {
        update()
        return counter[i]
    }

    fun writeReload(i: Int, value: Int) {
        reload[i] = value and 0xFFFF
    }

    fun writeControl(i: Int, value: Int) {
        update()
        val wasRunning = running(i)
        control[i] = value and 0xC7
        if (running(i) && !wasRunning) {
            counter[i] = reload[i]
            base[i] = gba.cycles
        } else if (running(i)) {
            base[i] = gba.cycles
        }
        reschedule()
        gba.scheduleDirty = true
    }

    /** Brings every free-running timer up to the current time, firing overflows. */
    fun update() {
        val now = gba.cycles
        for (i in 0 until 4) {
            if (!running(i) || cascade(i)) continue
            val s = shift(control[i])
            val ticks = (now ushr s) - (base[i] ushr s)
            if (ticks <= 0) continue
            base[i] = now
            advance(i, ticks)
        }
        reschedule()
    }

    private fun advance(i: Int, ticks: Long) {
        val total = counter[i].toLong() + ticks
        if (total < 0x10000) {
            counter[i] = total.toInt()
            return
        }
        val period = (0x10000 - reload[i]).toLong()
        val excess = total - 0x10000
        val overflows = 1 + excess / period
        counter[i] = (reload[i] + excess % period).toInt()
        if (control[i] and 0x40 != 0) gba.requestIrq(3 + i)
        if (i < 2) gba.apu.onTimerOverflow(i, overflows)
        if (i < 3 && running(i + 1) && cascade(i + 1)) advance(i + 1, overflows)
    }

    private fun reschedule() {
        var next = Long.MAX_VALUE
        for (i in 0 until 4) {
            if (!running(i) || cascade(i)) continue
            val s = shift(control[i])
            val t = ((base[i] ushr s) + (0x10000L - counter[i])) shl s
            if (t < next) next = t
        }
        nextEvent = next
    }

    fun saveState(w: StateWriter) {
        w.tag("gbatimers")
        w.ints(reload); w.ints(control); w.ints(counter)
        for (b in base) w.long(b)
    }

    fun loadState(r: StateReader) {
        r.tag("gbatimers")
        r.intsInto(reload); r.intsInto(control); r.intsInto(counter)
        for (i in 0 until 4) base[i] = r.long()
        reschedule()
    }
}
