package com.retroemulator.gb.core

/**
 * Serial port with no link partner attached: internally clocked transfers shift in 1s and complete
 * with an interrupt, externally clocked transfers never complete (as on hardware with no cable).
 *
 * The internal shift clock is derived from the system counter (falling edges of bit 8, or bit 3 in CGB
 * fast mode), so it stays phase-aligned with DIV exactly like the hardware.
 */
class Serial(private val gb: GameBoy) {
    var sb = 0
    var sc = 0
    var active = false
        private set
    private var bitsLeft = 0

    /** Receives each byte the game sends; used by the test-ROM harness. */
    var listener: ((Int) -> Unit)? = null

    fun read(): Int = sc or (if (gb.cgb) 0x7C else 0x7E)

    fun write(value: Int) {
        sc = value
        if (value and 0x81 == 0x81) {
            listener?.invoke(sb)
            active = true
            bitsLeft = 8
        } else {
            active = false
        }
    }

    private fun clockMask(): Int = if (gb.cgb && sc and 0x02 != 0) 1 shl 3 else 1 shl 8

    /**
     * Called by the timer each M-cycle while a transfer is active. The shift happens one M-cycle before
     * the counter bit actually falls (measured by Mooneye's boot_sclk_align).
     */
    fun clock(counter: Int) {
        val next = (counter + 4) and 0xFFFF
        if (counter and next.inv() and clockMask() == 0) return
        sb = ((sb shl 1) or 1) and 0xFF
        if (--bitsLeft == 0) {
            active = false
            sc = sc and 0x7F
            gb.intFlag = gb.intFlag or 0x08
        }
    }

    fun saveState(w: StateWriter) {
        w.tag("serial")
        w.int(sb); w.int(sc); w.bool(active); w.int(bitsLeft)
    }

    fun loadState(r: StateReader) {
        r.tag("serial")
        sb = r.int(); sc = r.int(); active = r.bool(); bitsLeft = r.int()
    }
}
