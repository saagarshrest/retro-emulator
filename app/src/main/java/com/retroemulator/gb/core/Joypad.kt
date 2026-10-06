package com.retroemulator.gb.core

/** P1/JOYP register. Button bits match the hardware lines: low nibble = d-pad, high nibble = buttons. */
class Joypad(private val gb: GameBoy) {
    private var select = 0x30
    var state = 0
        private set

    fun read(): Int {
        var low = 0x0F
        if (select and 0x10 == 0) low = low and (state and 0x0F).inv()
        if (select and 0x20 == 0) low = low and (state ushr 4).inv()
        return 0xC0 or select or (low and 0x0F)
    }

    fun write(value: Int) {
        select = value and 0x30
    }

    /** Apply a new pressed-button mask, raising the joypad interrupt for newly pressed, selected lines. */
    fun setState(newState: Int) {
        val pressed = newState and state.inv()
        state = newState
        if (pressed == 0) return
        val dirs = select and 0x10 == 0 && pressed and 0x0F != 0
        val btns = select and 0x20 == 0 && pressed and 0xF0 != 0
        if (dirs || btns) gb.intFlag = gb.intFlag or 0x10
        gb.cpu.stopped = false
    }

    fun saveState(w: StateWriter) {
        w.tag("joypad")
        w.int(select)
    }

    fun loadState(r: StateReader) {
        r.tag("joypad")
        select = r.int()
    }

    companion object {
        const val RIGHT = 0x01
        const val LEFT = 0x02
        const val UP = 0x04
        const val DOWN = 0x08
        const val A = 0x10
        const val B = 0x20
        const val SELECT = 0x40
        const val START = 0x80
    }
}
