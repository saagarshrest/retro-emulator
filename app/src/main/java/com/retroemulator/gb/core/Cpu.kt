package com.retroemulator.gb.core

/**
 * Sharp SM83 (LR35902) CPU. Every memory access and internal delay advances the rest of the system by
 * one M-cycle, so instruction timing and mid-instruction access timing match hardware.
 */
class Cpu(private val gb: GameBoy) {
    var a = 0; var f = 0
    var b = 0; var c = 0
    var d = 0; var e = 0
    var h = 0; var l = 0
    var sp = 0
    var pc = 0

    var ime = false
    private var imePending = false
    var halted = false
    var stopped = false
    private var haltBug = false
    /** Set by illegal opcodes, which hang the real CPU. */
    var locked = false

    private val mmu = gb.mmu

    // ---- bus access (each costs one M-cycle) ----

    private fun read(addr: Int): Int {
        gb.tick()
        return mmu.read(addr)
    }

    private fun write(addr: Int, value: Int) {
        gb.tick()
        mmu.write(addr, value)
    }

    private fun idle() = gb.tick()

    private fun fetch(): Int {
        val v = read(pc)
        if (haltBug) haltBug = false else pc = (pc + 1) and 0xFFFF
        return v
    }

    private fun fetch16(): Int {
        val lo = fetch()
        return (fetch() shl 8) or lo
    }

    private fun push(value: Int) {
        idle()
        sp = (sp - 1) and 0xFFFF
        write(sp, value ushr 8)
        sp = (sp - 1) and 0xFFFF
        write(sp, value and 0xFF)
    }

    private fun pop(): Int {
        val lo = read(sp)
        sp = (sp + 1) and 0xFFFF
        val hi = read(sp)
        sp = (sp + 1) and 0xFFFF
        return (hi shl 8) or lo
    }

    // ---- register pairs ----

    private fun bc() = (b shl 8) or c
    private fun de() = (d shl 8) or e
    private fun hl() = (h shl 8) or l
    private fun setBc(v: Int) { b = (v ushr 8) and 0xFF; c = v and 0xFF }
    private fun setDe(v: Int) { d = (v ushr 8) and 0xFF; e = v and 0xFF }
    private fun setHl(v: Int) { h = (v ushr 8) and 0xFF; l = v and 0xFF }

    private fun getR(i: Int): Int = when (i) {
        0 -> b
        1 -> c
        2 -> d
        3 -> e
        4 -> h
        5 -> l
        6 -> read(hl())
        else -> a
    }

    private fun setR(i: Int, v: Int) {
        when (i) {
            0 -> b = v
            1 -> c = v
            2 -> d = v
            3 -> e = v
            4 -> h = v
            5 -> l = v
            6 -> write(hl(), v)
            else -> a = v
        }
    }

    private val carry: Int get() = (f ushr 4) and 1

    private fun cond(i: Int): Boolean = when (i) {
        0 -> f and FLAG_Z == 0
        1 -> f and FLAG_Z != 0
        2 -> f and FLAG_C == 0
        else -> f and FLAG_C != 0
    }

    // ---- execution ----

    fun step() {
        if (halted) {
            idle()
            if (gb.pendingInterrupts() != 0) halted = false
            return
        }
        if (stopped || locked) {
            idle()
            return
        }
        if (ime && gb.pendingInterrupts() != 0) {
            serviceInterrupt()
            return
        }
        if (imePending) {
            imePending = false
            ime = true
        }
        execute(fetch())
    }

    private fun serviceInterrupt() {
        ime = false
        idle()
        idle()
        sp = (sp - 1) and 0xFFFF
        write(sp, pc ushr 8)
        // The vector is chosen after the high byte push, which may itself overwrite IE (0xFFFF).
        val pending = gb.pendingInterrupts()
        sp = (sp - 1) and 0xFFFF
        write(sp, pc and 0xFF)
        if (pending == 0) {
            pc = 0
        } else {
            val bit = Integer.numberOfTrailingZeros(pending)
            gb.intFlag = gb.intFlag and (1 shl bit).inv()
            pc = 0x40 + bit * 8
        }
        idle()
    }

    private fun execute(op: Int) {
        if (op in 0x40..0x7F) {
            if (op == 0x76) halt() else setR((op ushr 3) and 7, getR(op and 7))
            return
        }
        if (op in 0x80..0xBF) {
            alu((op ushr 3) and 7, getR(op and 7))
            return
        }
        when (op) {
            0x00 -> {}
            0x01 -> setBc(fetch16())
            0x02 -> write(bc(), a)
            0x03 -> { setBc((bc() + 1) and 0xFFFF); idle() }
            0x04 -> b = inc(b)
            0x05 -> b = dec(b)
            0x06 -> b = fetch()
            0x07 -> { val cy = a ushr 7; a = ((a shl 1) or cy) and 0xFF; f = cy shl 4 }
            0x08 -> {
                val addr = fetch16()
                write(addr, sp and 0xFF)
                write((addr + 1) and 0xFFFF, sp ushr 8)
            }
            0x09 -> addHl(bc())
            0x0A -> a = read(bc())
            0x0B -> { setBc((bc() - 1) and 0xFFFF); idle() }
            0x0C -> c = inc(c)
            0x0D -> c = dec(c)
            0x0E -> c = fetch()
            0x0F -> { val cy = a and 1; a = (a ushr 1) or (cy shl 7); f = cy shl 4 }

            0x10 -> stop()
            0x11 -> setDe(fetch16())
            0x12 -> write(de(), a)
            0x13 -> { setDe((de() + 1) and 0xFFFF); idle() }
            0x14 -> d = inc(d)
            0x15 -> d = dec(d)
            0x16 -> d = fetch()
            0x17 -> { val cy = a ushr 7; a = ((a shl 1) or carry) and 0xFF; f = cy shl 4 }
            0x18 -> jr(true)
            0x19 -> addHl(de())
            0x1A -> a = read(de())
            0x1B -> { setDe((de() - 1) and 0xFFFF); idle() }
            0x1C -> e = inc(e)
            0x1D -> e = dec(e)
            0x1E -> e = fetch()
            0x1F -> { val cy = a and 1; a = (a ushr 1) or (carry shl 7); f = cy shl 4 }

            0x20 -> jr(cond(0))
            0x21 -> setHl(fetch16())
            0x22 -> { val addr = hl(); write(addr, a); setHl((addr + 1) and 0xFFFF) }
            0x23 -> { setHl((hl() + 1) and 0xFFFF); idle() }
            0x24 -> h = inc(h)
            0x25 -> h = dec(h)
            0x26 -> h = fetch()
            0x27 -> daa()
            0x28 -> jr(cond(1))
            0x29 -> addHl(hl())
            0x2A -> { val addr = hl(); a = read(addr); setHl((addr + 1) and 0xFFFF) }
            0x2B -> { setHl((hl() - 1) and 0xFFFF); idle() }
            0x2C -> l = inc(l)
            0x2D -> l = dec(l)
            0x2E -> l = fetch()
            0x2F -> { a = a xor 0xFF; f = f or FLAG_N or FLAG_H }

            0x30 -> jr(cond(2))
            0x31 -> sp = fetch16()
            0x32 -> { val addr = hl(); write(addr, a); setHl((addr - 1) and 0xFFFF) }
            0x33 -> { sp = (sp + 1) and 0xFFFF; idle() }
            0x34 -> { val addr = hl(); write(addr, inc(read(addr))) }
            0x35 -> { val addr = hl(); write(addr, dec(read(addr))) }
            0x36 -> { val v = fetch(); write(hl(), v) }
            0x37 -> f = (f and FLAG_Z) or FLAG_C
            0x38 -> jr(cond(3))
            0x39 -> addHl(sp)
            0x3A -> { val addr = hl(); a = read(addr); setHl((addr - 1) and 0xFFFF) }
            0x3B -> { sp = (sp - 1) and 0xFFFF; idle() }
            0x3C -> a = inc(a)
            0x3D -> a = dec(a)
            0x3E -> a = fetch()
            0x3F -> f = (f and (FLAG_Z or FLAG_C)) xor FLAG_C

            0xC0 -> retCond(0)
            0xC1 -> setBc(pop())
            0xC2 -> jp(cond(0))
            0xC3 -> jp(true)
            0xC4 -> call(cond(0))
            0xC5 -> push(bc())
            0xC6 -> alu(0, fetch())
            0xC7 -> rst(0x00)
            0xC8 -> retCond(1)
            0xC9 -> { pc = pop(); idle() }
            0xCA -> jp(cond(1))
            0xCB -> executeCb()
            0xCC -> call(cond(1))
            0xCD -> call(true)
            0xCE -> alu(1, fetch())
            0xCF -> rst(0x08)

            0xD0 -> retCond(2)
            0xD1 -> setDe(pop())
            0xD2 -> jp(cond(2))
            0xD4 -> call(cond(2))
            0xD5 -> push(de())
            0xD6 -> alu(2, fetch())
            0xD7 -> rst(0x10)
            0xD8 -> retCond(3)
            0xD9 -> { pc = pop(); idle(); ime = true }
            0xDA -> jp(cond(3))
            0xDC -> call(cond(3))
            0xDE -> alu(3, fetch())
            0xDF -> rst(0x18)

            0xE0 -> { val n = fetch(); write(0xFF00 or n, a) }
            0xE1 -> setHl(pop())
            0xE2 -> write(0xFF00 or c, a)
            0xE5 -> push(hl())
            0xE6 -> alu(4, fetch())
            0xE7 -> rst(0x20)
            0xE8 -> { sp = addSpSigned(fetch()); idle(); idle() }
            0xE9 -> pc = hl()
            0xEA -> { val addr = fetch16(); write(addr, a) }
            0xEE -> alu(5, fetch())
            0xEF -> rst(0x28)

            0xF0 -> { val n = fetch(); a = read(0xFF00 or n) }
            0xF1 -> { val v = pop(); a = v ushr 8; f = v and 0xF0 }
            0xF2 -> a = read(0xFF00 or c)
            0xF3 -> { ime = false; imePending = false }
            0xF5 -> push((a shl 8) or f)
            0xF6 -> alu(6, fetch())
            0xF7 -> rst(0x30)
            0xF8 -> { setHl(addSpSigned(fetch())); idle() }
            0xF9 -> { sp = hl(); idle() }
            0xFA -> { val addr = fetch16(); a = read(addr) }
            0xFB -> imePending = true
            0xFE -> alu(7, fetch())
            0xFF -> rst(0x38)

            else -> locked = true // 0xD3, 0xDB, 0xDD, 0xE3, 0xE4, 0xEB, 0xEC, 0xED, 0xF4, 0xFC, 0xFD
        }
    }

    private fun executeCb() {
        val op = fetch()
        val r = op and 7
        val bit = (op ushr 3) and 7
        when (op ushr 6) {
            0 -> setR(r, rotate(bit, getR(r)))
            1 -> {
                val v = getR(r)
                f = (f and FLAG_C) or FLAG_H or (if (v and (1 shl bit) == 0) FLAG_Z else 0)
            }
            2 -> setR(r, getR(r) and (1 shl bit).inv())
            else -> setR(r, getR(r) or (1 shl bit))
        }
    }

    private fun rotate(kind: Int, v: Int): Int {
        val cy: Int
        val r: Int
        when (kind) {
            0 -> { cy = v ushr 7; r = ((v shl 1) or cy) and 0xFF }          // RLC
            1 -> { cy = v and 1; r = (v ushr 1) or (cy shl 7) }               // RRC
            2 -> { cy = v ushr 7; r = ((v shl 1) or carry) and 0xFF }        // RL
            3 -> { cy = v and 1; r = (v ushr 1) or (carry shl 7) }            // RR
            4 -> { cy = v ushr 7; r = (v shl 1) and 0xFF }                    // SLA
            5 -> { cy = v and 1; r = (v ushr 1) or (v and 0x80) }             // SRA
            6 -> { cy = 0; r = ((v and 0x0F) shl 4) or (v ushr 4) }           // SWAP
            else -> { cy = v and 1; r = v ushr 1 }                             // SRL
        }
        f = (if (r == 0) FLAG_Z else 0) or (cy shl 4)
        return r
    }

    private fun alu(kind: Int, v: Int) {
        when (kind) {
            0 -> add(v, 0)
            1 -> add(v, carry)
            2 -> a = sub(v, 0)
            3 -> a = sub(v, carry)
            4 -> { a = a and v; f = (if (a == 0) FLAG_Z else 0) or FLAG_H }
            5 -> { a = a xor v; f = if (a == 0) FLAG_Z else 0 }
            6 -> { a = a or v; f = if (a == 0) FLAG_Z else 0 }
            else -> sub(v, 0)
        }
    }

    private fun add(v: Int, cy: Int) {
        val r = a + v + cy
        f = (if (r and 0xFF == 0) FLAG_Z else 0) or
            (if ((a and 0x0F) + (v and 0x0F) + cy > 0x0F) FLAG_H else 0) or
            (if (r > 0xFF) FLAG_C else 0)
        a = r and 0xFF
    }

    private fun sub(v: Int, cy: Int): Int {
        val r = a - v - cy
        f = FLAG_N or (if (r and 0xFF == 0) FLAG_Z else 0) or
            (if ((a and 0x0F) - (v and 0x0F) - cy < 0) FLAG_H else 0) or
            (if (r < 0) FLAG_C else 0)
        return r and 0xFF
    }

    private fun inc(v: Int): Int {
        val r = (v + 1) and 0xFF
        f = (f and FLAG_C) or (if (r == 0) FLAG_Z else 0) or (if (v and 0x0F == 0x0F) FLAG_H else 0)
        return r
    }

    private fun dec(v: Int): Int {
        val r = (v - 1) and 0xFF
        f = (f and FLAG_C) or FLAG_N or (if (r == 0) FLAG_Z else 0) or (if (v and 0x0F == 0) FLAG_H else 0)
        return r
    }

    private fun addHl(v: Int) {
        val hl = hl()
        val r = hl + v
        f = (f and FLAG_Z) or
            (if ((hl and 0x0FFF) + (v and 0x0FFF) > 0x0FFF) FLAG_H else 0) or
            (if (r > 0xFFFF) FLAG_C else 0)
        setHl(r and 0xFFFF)
        idle()
    }

    private fun addSpSigned(n: Int): Int {
        f = (if ((sp and 0x0F) + (n and 0x0F) > 0x0F) FLAG_H else 0) or
            (if ((sp and 0xFF) + n > 0xFF) FLAG_C else 0)
        return (sp + n.toByte()) and 0xFFFF
    }

    private fun daa() {
        var v = a
        var fl = f
        if (fl and FLAG_N == 0) {
            if (fl and FLAG_C != 0 || v > 0x99) { v += 0x60; fl = fl or FLAG_C }
            if (fl and FLAG_H != 0 || (v and 0x0F) > 0x09) v += 0x06
        } else {
            if (fl and FLAG_C != 0) v -= 0x60
            if (fl and FLAG_H != 0) v -= 0x06
        }
        v = v and 0xFF
        f = (fl and (FLAG_N or FLAG_C)) or (if (v == 0) FLAG_Z else 0)
        a = v
    }

    private fun jr(taken: Boolean) {
        val off = fetch().toByte().toInt()
        if (taken) {
            pc = (pc + off) and 0xFFFF
            idle()
        }
    }

    private fun jp(taken: Boolean) {
        val addr = fetch16()
        if (taken) {
            pc = addr
            idle()
        }
    }

    private fun call(taken: Boolean) {
        val addr = fetch16()
        if (taken) {
            push(pc)
            pc = addr
        }
    }

    private fun retCond(i: Int) {
        idle()
        if (cond(i)) {
            pc = pop()
            idle()
        }
    }

    private fun rst(vector: Int) {
        push(pc)
        pc = vector
    }

    private fun halt() {
        if (!ime && gb.pendingInterrupts() != 0) {
            // HALT bug: the CPU does not halt and fails to increment PC on the next fetch.
            haltBug = true
        } else {
            halted = true
        }
    }

    private fun stop() {
        fetch()
        if (gb.cgb && gb.speedSwitchArmed) {
            gb.switchSpeed()
        } else {
            stopped = true
            gb.timer.writeDiv()
        }
    }

    fun reset(cgb: Boolean) {
        if (cgb) {
            a = 0x11; f = 0x80; b = 0x00; c = 0x00; d = 0xFF; e = 0x56; h = 0x00; l = 0x0D
        } else {
            a = 0x01; f = 0xB0; b = 0x00; c = 0x13; d = 0x00; e = 0xD8; h = 0x01; l = 0x4D
        }
        sp = 0xFFFE
        pc = 0x0100
        ime = false; imePending = false; halted = false; stopped = false; haltBug = false; locked = false
    }

    fun saveState(w: StateWriter) {
        w.tag("cpu")
        w.ints(intArrayOf(a, f, b, c, d, e, h, l, sp, pc))
        w.bool(ime); w.bool(imePending); w.bool(halted); w.bool(stopped); w.bool(haltBug); w.bool(locked)
    }

    fun loadState(r: StateReader) {
        r.tag("cpu")
        val regs = IntArray(10)
        r.intsInto(regs)
        a = regs[0]; f = regs[1]; b = regs[2]; c = regs[3]; d = regs[4]
        e = regs[5]; h = regs[6]; l = regs[7]; sp = regs[8]; pc = regs[9]
        ime = r.bool(); imePending = r.bool(); halted = r.bool(); stopped = r.bool(); haltBug = r.bool(); locked = r.bool()
    }

    companion object {
        const val FLAG_Z = 0x80
        const val FLAG_N = 0x40
        const val FLAG_H = 0x20
        const val FLAG_C = 0x10
    }
}
