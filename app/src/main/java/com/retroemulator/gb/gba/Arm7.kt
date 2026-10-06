package com.retroemulator.gb.gba

import com.retroemulator.gb.core.StateReader
import com.retroemulator.gb.core.StateWriter

/**
 * ARM7TDMI interpreter (ARMv4T: ARM and Thumb instruction sets).
 *
 * While an instruction executes, r15 reads as its address + 8 (ARM) or + 4 (Thumb), matching the
 * pipeline. Writes to r15 go through [branchTo]. Memory accesses add their wait states to the
 * system clock; software interrupts are handled by the high-level BIOS ([GbaBios]).
 */
class Arm7(private val gba: Gba) {
    private val bus = gba.bus
    val r = IntArray(16)

    /** Address of the next instruction to execute. */
    var pc = 0
        private set
    private var nextPc = 0
    private var seqFetch = false
    private var lastOpcode = 0

    // Prefetch queue: the opcodes one and two instructions ahead are fetched before pc executes, so
    // code that overwrites the next two instructions still runs the old ones (as on hardware).
    private var pipe0 = 0
    private var pipe1 = 0
    private var pipeValid = false

    var nf = false
    var zf = false
    var cf = false
    var vf = false
    var irqDisabled = false
    var fiqDisabled = false
    var thumb = false
    var mode = MODE_SYS
        private set

    private val usrHigh = IntArray(5) // r8-r12 of the other modes while in FIQ mode
    private val fiqHigh = IntArray(5)
    private val bankSp = IntArray(6)
    private val bankLr = IntArray(6)
    private val spsrs = IntArray(6)
    private var shifterCarry = false

    // ------------------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------------------

    fun resetToRom() {
        r.fill(0)
        usrHigh.fill(0); fiqHigh.fill(0); bankSp.fill(0); bankLr.fill(0); spsrs.fill(0)
        mode = MODE_SYS
        thumb = false
        irqDisabled = false; fiqDisabled = false
        nf = false; zf = false; cf = false; vf = false
        bankSp[bankOf(MODE_IRQ)] = 0x03007FA0
        bankSp[bankOf(MODE_SVC)] = 0x03007FE0
        r[13] = 0x03007F00
        jump(0x08000000)
    }

    /** Value seen on reads from unmapped memory: the most recently fetched opcode. */
    fun openBus(): Int = if (thumb) (lastOpcode and 0xFFFF) * 0x10001 else lastOpcode

    fun cpsr(): Int =
        (if (nf) 1 shl 31 else 0) or (if (zf) 1 shl 30 else 0) or (if (cf) 1 shl 29 else 0) or
            (if (vf) 1 shl 28 else 0) or (if (irqDisabled) 0x80 else 0) or (if (fiqDisabled) 0x40 else 0) or
            (if (thumb) 0x20 else 0) or mode

    fun setCpsr(value: Int) {
        nf = value < 0
        zf = value and (1 shl 30) != 0
        cf = value and (1 shl 29) != 0
        vf = value and (1 shl 28) != 0
        irqDisabled = value and 0x80 != 0
        fiqDisabled = value and 0x40 != 0
        thumb = value and 0x20 != 0
        switchMode(value and 0x1F)
    }

    private fun currentSpsr(): Int {
        val b = bankOf(mode)
        return if (b == 0) cpsr() else spsrs[b]
    }

    private fun setSpsr(value: Int) {
        val b = bankOf(mode)
        if (b != 0) spsrs[b] = value
    }

    fun switchMode(newMode: Int) {
        val m = if (bankOfOrNull(newMode) < 0) MODE_SYS else newMode
        val oldBank = bankOf(mode)
        val newBank = bankOf(m)
        if (oldBank != newBank) {
            bankSp[oldBank] = r[13]
            bankLr[oldBank] = r[14]
            if (oldBank == BANK_FIQ) {
                for (i in 0 until 5) { fiqHigh[i] = r[8 + i]; r[8 + i] = usrHigh[i] }
            }
            if (newBank == BANK_FIQ) {
                for (i in 0 until 5) { usrHigh[i] = r[8 + i]; r[8 + i] = fiqHigh[i] }
            }
            r[13] = bankSp[newBank]
            r[14] = bankLr[newBank]
        }
        mode = m
    }

    /** User-bank register access for LDM/STM with the S bit. */
    private fun userReg(i: Int): Int = when {
        i < 8 || i == 15 -> r[i]
        i < 13 -> if (mode == MODE_FIQ) usrHigh[i - 8] else r[i]
        bankOf(mode) == 0 -> r[i]
        i == 13 -> bankSp[0]
        else -> bankLr[0]
    }

    private fun setUserReg(i: Int, v: Int) {
        when {
            i < 8 -> r[i] = v
            i < 13 -> if (mode == MODE_FIQ) usrHigh[i - 8] = v else r[i] = v
            i == 15 -> r[15] = v
            bankOf(mode) == 0 -> r[i] = v
            i == 13 -> bankSp[0] = v
            else -> bankLr[0] = v
        }
    }

    /** Continue execution at [target] (used by the HLE BIOS and resets). */
    fun jump(target: Int) {
        pc = target
        nextPc = target
        seqFetch = false
        pipeValid = false
    }

    private fun branchTo(target: Int) {
        nextPc = if (thumb) target and 1.inv() else target and 3.inv()
        seqFetch = false
        pipeValid = false
    }

    /** Refetches the following instructions after an HLE BIOS call, as returning from the BIOS would. */
    fun flushPipeline() {
        pipeValid = false
        seqFetch = false
    }

    private fun writeReg(rd: Int, value: Int) {
        if (rd == 15) branchTo(value) else r[rd] = value
    }

    // ------------------------------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------------------------------

    fun step() {
        if (gba.irqLine && !irqDisabled) irqException()
        if (thumb) stepThumb() else stepArm()
    }

    private fun irqException() {
        val returnAddr = pc + 4
        val saved = cpsr()
        switchMode(MODE_IRQ)
        spsrs[BANK_IRQ] = saved
        r[14] = returnAddr
        irqDisabled = true
        thumb = false
        jump(0x18)
    }

    private fun fetchCost(addr: Int, wide: Boolean): Int {
        val region = (addr ushr 24) and 0xF
        if (seqFetch) {
            if (region in 8..0xD && bus.prefetch) return if (wide) 2 else 1
            return if (wide) bus.s32[region] else bus.s16[region]
        }
        return if (wide) bus.n32[region] else bus.n16[region]
    }

    private fun fetchArm(addr: Int): Int {
        gba.cycles += fetchCost(addr, true)
        seqFetch = true
        val op = if (addr ushr 24 == 0) bus.fetchBios32(addr) else bus.read32(addr)
        lastOpcode = op
        return op
    }

    private fun fetchThumb(addr: Int): Int {
        gba.cycles += fetchCost(addr, false)
        seqFetch = true
        val op = if (addr ushr 24 == 0) bus.fetchBios32(addr and 2.inv()) ushr ((addr and 2) shl 3) and 0xFFFF else bus.read16(addr)
        lastOpcode = op
        return op
    }

    private fun stepArm() {
        val addr = pc
        if (!pipeValid) {
            pipe0 = fetchArm(addr)
            pipe1 = fetchArm(addr + 4)
            pipeValid = true
        }
        val op = pipe0
        pipe0 = pipe1
        pipe1 = fetchArm(addr + 8)
        r[15] = addr + 8
        nextPc = addr + 4
        val cond = op ushr 28
        if (cond == 0xE || conditionPasses(cond)) executeArm(op)
        pc = nextPc
    }

    private fun stepThumb() {
        val addr = pc
        if (!pipeValid) {
            pipe0 = fetchThumb(addr)
            pipe1 = fetchThumb(addr + 2)
            pipeValid = true
        }
        val op = pipe0
        pipe0 = pipe1
        pipe1 = fetchThumb(addr + 4)
        r[15] = addr + 4
        nextPc = addr + 2
        executeThumb(op)
        pc = nextPc
    }

    private fun conditionPasses(cond: Int): Boolean = when (cond) {
        0x0 -> zf
        0x1 -> !zf
        0x2 -> cf
        0x3 -> !cf
        0x4 -> nf
        0x5 -> !nf
        0x6 -> vf
        0x7 -> !vf
        0x8 -> cf && !zf
        0x9 -> !cf || zf
        0xA -> nf == vf
        0xB -> nf != vf
        0xC -> !zf && nf == vf
        0xD -> zf || nf != vf
        0xE -> true
        else -> false
    }

    // ------------------------------------------------------------------------------------------
    // Memory helpers (add access time)
    // ------------------------------------------------------------------------------------------

    private fun idle(n: Int) { gba.cycles += n }

    private fun read32(addr: Int, seq: Boolean = false): Int {
        val region = (addr ushr 24) and 0xF
        gba.cycles += if (seq) bus.s32[region] else bus.n32[region]
        return bus.read32(addr)
    }

    private fun read16(addr: Int): Int {
        gba.cycles += bus.n16[(addr ushr 24) and 0xF]
        return bus.read16(addr)
    }

    private fun read8(addr: Int): Int {
        gba.cycles += bus.n16[(addr ushr 24) and 0xF]
        return bus.read8(addr)
    }

    private fun write32(addr: Int, value: Int, seq: Boolean = false) {
        val region = (addr ushr 24) and 0xF
        gba.cycles += if (seq) bus.s32[region] else bus.n32[region]
        bus.write32(addr, value)
    }

    private fun write16(addr: Int, value: Int) {
        gba.cycles += bus.n16[(addr ushr 24) and 0xF]
        bus.write16(addr, value)
    }

    private fun write8(addr: Int, value: Int) {
        gba.cycles += bus.n16[(addr ushr 24) and 0xF]
        bus.write8(addr, value)
    }

    /** LDR semantics: misaligned word loads rotate the aligned word. */
    private fun loadWord(addr: Int): Int {
        val v = read32(addr)
        val rot = (addr and 3) shl 3
        return if (rot == 0) v else Integer.rotateRight(v, rot)
    }

    // ------------------------------------------------------------------------------------------
    // Barrel shifter
    // ------------------------------------------------------------------------------------------

    private fun shiftImm(type: Int, value: Int, amount: Int): Int {
        when (type) {
            0 -> {
                if (amount == 0) { shifterCarry = cf; return value }
                shifterCarry = (value ushr (32 - amount)) and 1 != 0
                return value shl amount
            }
            1 -> {
                if (amount == 0) { shifterCarry = value < 0; return 0 }
                shifterCarry = (value ushr (amount - 1)) and 1 != 0
                return value ushr amount
            }
            2 -> {
                if (amount == 0) { shifterCarry = value < 0; return value shr 31 }
                shifterCarry = (value shr (amount - 1)) and 1 != 0
                return value shr amount
            }
            else -> {
                if (amount == 0) {
                    shifterCarry = value and 1 != 0
                    return (value ushr 1) or (if (cf) 1 shl 31 else 0)
                }
                shifterCarry = (value ushr (amount - 1)) and 1 != 0
                return Integer.rotateRight(value, amount)
            }
        }
    }

    private fun shiftReg(type: Int, value: Int, amount: Int): Int {
        if (amount == 0) { shifterCarry = cf; return value }
        when (type) {
            0 -> return when {
                amount < 32 -> { shifterCarry = (value ushr (32 - amount)) and 1 != 0; value shl amount }
                amount == 32 -> { shifterCarry = value and 1 != 0; 0 }
                else -> { shifterCarry = false; 0 }
            }
            1 -> return when {
                amount < 32 -> { shifterCarry = (value ushr (amount - 1)) and 1 != 0; value ushr amount }
                amount == 32 -> { shifterCarry = value < 0; 0 }
                else -> { shifterCarry = false; 0 }
            }
            2 -> return if (amount < 32) {
                shifterCarry = (value shr (amount - 1)) and 1 != 0
                value shr amount
            } else {
                shifterCarry = value < 0
                value shr 31
            }
            else -> {
                val a = amount and 31
                if (a == 0) { shifterCarry = value < 0; return value }
                shifterCarry = (value ushr (a - 1)) and 1 != 0
                return Integer.rotateRight(value, a)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // ALU helpers
    // ------------------------------------------------------------------------------------------

    private fun setNZ(v: Int) { nf = v < 0; zf = v == 0 }

    private fun add(a: Int, b: Int, carryIn: Int, setFlags: Boolean): Int {
        val wide = (a.toLong() and MASK32) + (b.toLong() and MASK32) + carryIn
        val res = wide.toInt()
        if (setFlags) {
            setNZ(res)
            cf = wide ushr 32 != 0L
            vf = ((a xor res) and (b xor res)) < 0
        }
        return res
    }

    /** a - b - (1 - carryIn); C means "no borrow". */
    private fun sub(a: Int, b: Int, carryIn: Int, setFlags: Boolean): Int {
        val wide = (a.toLong() and MASK32) - (b.toLong() and MASK32) - (1 - carryIn)
        val res = wide.toInt()
        if (setFlags) {
            setNZ(res)
            cf = wide >= 0L
            vf = ((a xor b) and (a xor res)) < 0
        }
        return res
    }

    private fun mulCycles(rs: Int): Int {
        val v = rs
        return when {
            v and 0xFFFFFF00.toInt() == 0 || v and 0xFFFFFF00.toInt() == 0xFFFFFF00.toInt() -> 1
            v and 0xFFFF0000.toInt() == 0 || v and 0xFFFF0000.toInt() == 0xFFFF0000.toInt() -> 2
            v and 0xFF000000.toInt() == 0 || v and 0xFF000000.toInt() == 0xFF000000.toInt() -> 3
            else -> 4
        }
    }

    // ------------------------------------------------------------------------------------------
    // ARM instructions
    // ------------------------------------------------------------------------------------------

    private fun executeArm(op: Int) {
        when ((op ushr 25) and 7) {
            0 -> {
                if (op and 0x0FFFFFF0 == 0x012FFF10) { armBx(op); return }
                if (op and 0x90 == 0x90) {
                    // Multiplies, swaps and halfword transfers live in the data-processing space.
                    when {
                        op and 0x0FC000F0 == 0x00000090 -> armMul(op)
                        op and 0x0F8000F0 == 0x00800090 -> armMulLong(op)
                        op and 0x0FB00FF0 == 0x01000090 -> armSwap(op)
                        op and 0x60 != 0 -> armHalfword(op)
                        else -> {} // undefined
                    }
                    return
                }
                if (op and 0x0FBF0FFF == 0x010F0000) { armMrs(op); return }
                if (op and 0x0DB0F000 == 0x0120F000) { armMsr(op); return }
                armDataProcessing(op)
            }
            1 -> if (op and 0x0DB0F000 == 0x0120F000) armMsr(op) else armDataProcessing(op)
            2, 3 -> if (op and 0x02000010 == 0x02000010) {} else armSingleTransfer(op)
            4 -> armBlockTransfer(op)
            5 -> {
                val offset = (op shl 8) shr 6
                if (op and (1 shl 24) != 0) r[14] = r[15] - 4
                branchTo(r[15] + offset)
            }
            6 -> {} // coprocessor data transfer: no coprocessors on the GBA
            else -> if (op and (1 shl 24) != 0) {
                gba.bios.swi((op ushr 16) and 0xFF, returnAddr = r[15] - 4)
            }
        }
    }

    private fun armBx(op: Int) {
        val target = r[op and 0xF]
        thumb = target and 1 != 0
        branchTo(target)
    }

    private fun armDataProcessing(op: Int) {
        val opcode = (op ushr 21) and 0xF
        val setFlags = op and (1 shl 20) != 0
        val rn = (op ushr 16) and 0xF
        val rd = (op ushr 12) and 0xF
        val op2: Int
        var rnVal: Int
        if (op and (1 shl 25) != 0) {
            val imm = op and 0xFF
            val rot = (op ushr 7) and 0x1E
            op2 = Integer.rotateRight(imm, rot)
            shifterCarry = if (rot == 0) cf else op2 < 0
            rnVal = r[rn]
        } else {
            val rm = op and 0xF
            val type = (op ushr 5) and 3
            if (op and 0x10 != 0) {
                idle(1)
                val amount = r[(op ushr 8) and 0xF] and 0xFF
                val rmVal = if (rm == 15) r[15] + 4 else r[rm]
                rnVal = if (rn == 15) r[15] + 4 else r[rn]
                op2 = shiftReg(type, rmVal, amount)
            } else {
                op2 = shiftImm(type, r[rm], (op ushr 7) and 0x1F)
                rnVal = r[rn]
            }
        }
        val logical: Boolean
        val result: Int
        val flagsOnly = opcode in 8..11
        // Flags are only computed here when S is set and the destination is not r15.
        val doFlags = setFlags && rd != 15
        when (opcode) {
            0x0 -> { result = rnVal and op2; logical = true }
            0x1 -> { result = rnVal xor op2; logical = true }
            0x2 -> { result = sub(rnVal, op2, 1, doFlags); logical = false }
            0x3 -> { result = sub(op2, rnVal, 1, doFlags); logical = false }
            0x4 -> { result = add(rnVal, op2, 0, doFlags); logical = false }
            0x5 -> { result = add(rnVal, op2, if (cf) 1 else 0, doFlags); logical = false }
            0x6 -> { result = sub(rnVal, op2, if (cf) 1 else 0, doFlags); logical = false }
            0x7 -> { result = sub(op2, rnVal, if (cf) 1 else 0, doFlags); logical = false }
            0x8 -> { result = rnVal and op2; logical = true }
            0x9 -> { result = rnVal xor op2; logical = true }
            0xA -> { result = sub(rnVal, op2, 1, setFlags); logical = false }
            0xB -> { result = add(rnVal, op2, 0, setFlags); logical = false }
            0xC -> { result = rnVal or op2; logical = true }
            0xD -> { result = op2; logical = true }
            0xE -> { result = rnVal and op2.inv(); logical = true }
            else -> { result = op2.inv(); logical = true }
        }
        if (logical && setFlags && (rd != 15 || flagsOnly)) {
            setNZ(result)
            cf = shifterCarry
        }
        if (flagsOnly) {
            // TSTP/TEQP/CMPP/CMNP (Rd = r15) copy SPSR into CPSR without flushing the pipeline.
            if (rd == 15 && setFlags) setCpsr(currentSpsr())
            return
        }
        if (rd == 15) {
            if (setFlags) setCpsr(currentSpsr())
            branchTo(result)
        } else {
            r[rd] = result
        }
    }

    private fun armMrs(op: Int) {
        val rd = (op ushr 12) and 0xF
        r[rd] = if (op and (1 shl 22) != 0) currentSpsr() else cpsr()
    }

    private fun armMsr(op: Int) {
        val operand = if (op and (1 shl 25) != 0) Integer.rotateRight(op and 0xFF, (op ushr 7) and 0x1E) else r[op and 0xF]
        var mask = 0
        if (op and (1 shl 19) != 0) mask = mask or 0xFF000000.toInt()
        if (op and (1 shl 18) != 0) mask = mask or 0x00FF0000
        if (op and (1 shl 17) != 0) mask = mask or 0x0000FF00
        if (op and (1 shl 16) != 0) mask = mask or 0x000000FF
        if (op and (1 shl 22) != 0) {
            setSpsr((currentSpsr() and mask.inv()) or (operand and mask))
        } else {
            if (mode == MODE_USR) mask = mask and 0xFF000000.toInt()
            // The T bit cannot be changed through MSR.
            val newValue = (cpsr() and mask.inv()) or (operand and mask and 0x20.inv()) or (cpsr() and 0x20)
            setCpsr(newValue)
        }
    }

    private fun armMul(op: Int) {
        val rd = (op ushr 16) and 0xF
        val rn = (op ushr 12) and 0xF
        val rs = r[(op ushr 8) and 0xF]
        val rm = op and 0xF
        var result = r[rm] * rs
        idle(mulCycles(rs))
        if (op and (1 shl 21) != 0) { result += r[rn]; idle(1) }
        if (op and (1 shl 20) != 0) setNZ(result)
        writeReg(rd, result)
    }

    private fun armMulLong(op: Int) {
        val hi = (op ushr 16) and 0xF
        val lo = (op ushr 12) and 0xF
        val rsVal = r[(op ushr 8) and 0xF]
        val rmVal = r[op and 0xF]
        val signed = op and (1 shl 22) != 0
        var result = if (signed) rmVal.toLong() * rsVal.toLong() else (rmVal.toLong() and MASK32) * (rsVal.toLong() and MASK32)
        idle(mulCycles(rsVal) + 1)
        if (op and (1 shl 21) != 0) {
            result += (r[hi].toLong() shl 32) or (r[lo].toLong() and MASK32)
            idle(1)
        }
        if (op and (1 shl 20) != 0) { nf = result < 0; zf = result == 0L }
        r[lo] = result.toInt()
        r[hi] = (result ushr 32).toInt()
    }

    private fun armSwap(op: Int) {
        val addr = r[(op ushr 16) and 0xF]
        val rd = (op ushr 12) and 0xF
        val src = r[op and 0xF]
        if (op and (1 shl 22) != 0) {
            val v = read8(addr)
            write8(addr, src and 0xFF)
            writeReg(rd, v)
        } else {
            val v = loadWord(addr)
            write32(addr, src)
            writeReg(rd, v)
        }
        idle(1)
    }

    private fun armHalfword(op: Int) {
        val pre = op and (1 shl 24) != 0
        val up = op and (1 shl 23) != 0
        val writeBack = op and (1 shl 21) != 0
        val load = op and (1 shl 20) != 0
        val rn = (op ushr 16) and 0xF
        val rd = (op ushr 12) and 0xF
        val offset = if (op and (1 shl 22) != 0) ((op ushr 4) and 0xF0) or (op and 0xF) else r[op and 0xF]
        val base = r[rn]
        val moved = if (up) base + offset else base - offset
        val addr = if (pre) moved else base
        val sh = (op ushr 5) and 3
        if (load) {
            val value = when (sh) {
                1 -> { val h = read16(addr); if (addr and 1 != 0) Integer.rotateRight(h, 8) else h }
                2 -> read8(addr).toByte().toInt()
                else -> if (addr and 1 != 0) read8(addr).toByte().toInt() else read16(addr).toShort().toInt()
            }
            if ((!pre || writeBack) && rn != rd) r[rn] = moved
            idle(1)
            writeReg(rd, value)
        } else {
            if (sh == 1) write16(addr, if (rd == 15) r[15] + 4 else r[rd])
            if (!pre || writeBack) writeReg(rn, moved)
        }
    }

    private fun armSingleTransfer(op: Int) {
        val pre = op and (1 shl 24) != 0
        val up = op and (1 shl 23) != 0
        val byte = op and (1 shl 22) != 0
        val writeBack = op and (1 shl 21) != 0
        val load = op and (1 shl 20) != 0
        val rn = (op ushr 16) and 0xF
        val rd = (op ushr 12) and 0xF
        val offset = if (op and (1 shl 25) != 0) {
            val saved = shifterCarry
            val v = shiftImm((op ushr 5) and 3, r[op and 0xF], (op ushr 7) and 0x1F)
            shifterCarry = saved
            v
        } else op and 0xFFF
        val base = r[rn]
        val moved = if (up) base + offset else base - offset
        val addr = if (pre) moved else base
        if (load) {
            val value = if (byte) read8(addr) else loadWord(addr)
            if ((!pre || writeBack) && rn != rd) r[rn] = moved
            idle(1)
            writeReg(rd, value)
        } else {
            val value = if (rd == 15) r[15] + 4 else r[rd]
            if (byte) write8(addr, value and 0xFF) else write32(addr, value)
            if (!pre || writeBack) writeReg(rn, moved)
        }
    }

    private fun armBlockTransfer(op: Int) {
        val pre = op and (1 shl 24) != 0
        val up = op and (1 shl 23) != 0
        val sBit = op and (1 shl 22) != 0
        val writeBack = op and (1 shl 21) != 0
        val load = op and (1 shl 20) != 0
        val rn = (op ushr 16) and 0xF
        var list = op and 0xFFFF
        val base = r[rn]
        var count = Integer.bitCount(list)
        var span = count * 4
        if (list == 0) { list = 0x8000; count = 1; span = 0x40 } // ARMv4 empty list quirk
        val start = if (up) (if (pre) base + 4 else base) else (if (pre) base - span else base - span + 4)
        val newBase = if (up) base + span else base - span
        val userBank = sBit && !(load && list and 0x8000 != 0)

        var addr = start
        var first = true
        if (load) {
            if (writeBack) r[rn] = newBase
            for (i in 0..15) {
                if (list and (1 shl i) == 0) continue
                val v = read32(addr, !first)
                first = false
                addr += 4
                if (userBank) setUserReg(i, v)
                else if (i == 15) {
                    if (sBit) setCpsr(currentSpsr())
                    branchTo(v)
                } else r[i] = v
            }
            idle(1)
        } else {
            for (i in 0..15) {
                if (list and (1 shl i) == 0) continue
                val v = when {
                    i == 15 -> r[15] + 4
                    i == rn -> if (first || !writeBack) base else newBase
                    userBank -> userReg(i)
                    else -> r[i]
                }
                write32(addr, v, !first)
                first = false
                addr += 4
            }
            if (writeBack) r[rn] = newBase
        }
    }

    // ------------------------------------------------------------------------------------------
    // Thumb instructions
    // ------------------------------------------------------------------------------------------

    private fun executeThumb(op: Int) {
        when (op ushr 13) {
            0 -> {
                if ((op ushr 11) and 3 == 3) thumbAddSub(op) else {
                    val rd = op and 7
                    val v = shiftImm((op ushr 11) and 3, r[(op ushr 3) and 7], (op ushr 6) and 0x1F)
                    r[rd] = v
                    setNZ(v)
                    cf = shifterCarry
                }
            }
            1 -> {
                val rd = (op ushr 8) and 7
                val imm = op and 0xFF
                when ((op ushr 11) and 3) {
                    0 -> { r[rd] = imm; setNZ(imm) }
                    1 -> sub(r[rd], imm, 1, true)
                    2 -> r[rd] = add(r[rd], imm, 0, true)
                    else -> r[rd] = sub(r[rd], imm, 1, true)
                }
            }
            2 -> when {
                op and 0xFC00 == 0x4000 -> thumbAlu(op)
                op and 0xFC00 == 0x4400 -> thumbHiReg(op)
                op and 0xF800 == 0x4800 -> {
                    val addr = (r[15] and 3.inv()) + ((op and 0xFF) shl 2)
                    r[(op ushr 8) and 7] = read32(addr)
                    idle(1)
                }
                else -> thumbRegOffset(op)
            }
            3 -> {
                val rd = op and 7
                val rb = r[(op ushr 3) and 7]
                val imm = (op ushr 6) and 0x1F
                val load = op and 0x0800 != 0
                if (op and 0x1000 != 0) {
                    val addr = rb + imm
                    if (load) { r[rd] = read8(addr); idle(1) } else write8(addr, r[rd] and 0xFF)
                } else {
                    val addr = rb + (imm shl 2)
                    if (load) { r[rd] = loadWord(addr); idle(1) } else write32(addr, r[rd])
                }
            }
            4 -> {
                if (op and 0x1000 == 0) {
                    val rd = op and 7
                    val addr = r[(op ushr 3) and 7] + (((op ushr 6) and 0x1F) shl 1)
                    if (op and 0x0800 != 0) {
                        val h = read16(addr)
                        r[rd] = if (addr and 1 != 0) Integer.rotateRight(h, 8) else h
                        idle(1)
                    } else write16(addr, r[rd])
                } else {
                    val rd = (op ushr 8) and 7
                    val addr = r[13] + ((op and 0xFF) shl 2)
                    if (op and 0x0800 != 0) { r[rd] = loadWord(addr); idle(1) } else write32(addr, r[rd])
                }
            }
            5 -> {
                if (op and 0x1000 == 0) {
                    val rd = (op ushr 8) and 7
                    val imm = (op and 0xFF) shl 2
                    r[rd] = if (op and 0x0800 != 0) r[13] + imm else (r[15] and 3.inv()) + imm
                } else if (op and 0x0F00 == 0) {
                    val imm = (op and 0x7F) shl 2
                    r[13] = if (op and 0x80 != 0) r[13] - imm else r[13] + imm
                } else if (op and 0x0600 == 0x0400) {
                    thumbPushPop(op)
                }
            }
            6 -> {
                if (op and 0x1000 == 0) {
                    thumbMultiple(op)
                } else {
                    val cond = (op ushr 8) and 0xF
                    when (cond) {
                        0xF -> gba.bios.swi(op and 0xFF, returnAddr = r[15] - 2)
                        0xE -> {}
                        else -> if (conditionPasses(cond)) branchTo(r[15] + ((op and 0xFF).toByte().toInt() shl 1))
                    }
                }
            }
            else -> when ((op ushr 11) and 3) {
                0 -> branchTo(r[15] + (((op and 0x7FF) shl 21) shr 20))
                2 -> r[14] = r[15] + (((op and 0x7FF) shl 21) shr 9)
                3 -> {
                    val target = r[14] + ((op and 0x7FF) shl 1)
                    r[14] = (r[15] - 2) or 1
                    branchTo(target)
                }
                else -> {}
            }
        }
    }

    private fun thumbAddSub(op: Int) {
        val rd = op and 7
        val rs = r[(op ushr 3) and 7]
        val operand = if (op and 0x0400 != 0) (op ushr 6) and 7 else r[(op ushr 6) and 7]
        r[rd] = if (op and 0x0200 != 0) sub(rs, operand, 1, true) else add(rs, operand, 0, true)
    }

    private fun thumbAlu(op: Int) {
        val rd = op and 7
        val rsVal = r[(op ushr 3) and 7]
        val rdVal = r[rd]
        when ((op ushr 6) and 0xF) {
            0x0 -> { val v = rdVal and rsVal; r[rd] = v; setNZ(v) }
            0x1 -> { val v = rdVal xor rsVal; r[rd] = v; setNZ(v) }
            0x2 -> { val v = shiftReg(0, rdVal, rsVal and 0xFF); r[rd] = v; setNZ(v); cf = shifterCarry; idle(1) }
            0x3 -> { val v = shiftReg(1, rdVal, rsVal and 0xFF); r[rd] = v; setNZ(v); cf = shifterCarry; idle(1) }
            0x4 -> { val v = shiftReg(2, rdVal, rsVal and 0xFF); r[rd] = v; setNZ(v); cf = shifterCarry; idle(1) }
            0x5 -> r[rd] = add(rdVal, rsVal, if (cf) 1 else 0, true)
            0x6 -> r[rd] = sub(rdVal, rsVal, if (cf) 1 else 0, true)
            0x7 -> { val v = shiftReg(3, rdVal, rsVal and 0xFF); r[rd] = v; setNZ(v); cf = shifterCarry; idle(1) }
            0x8 -> setNZ(rdVal and rsVal)
            0x9 -> r[rd] = sub(0, rsVal, 1, true)
            0xA -> sub(rdVal, rsVal, 1, true)
            0xB -> add(rdVal, rsVal, 0, true)
            0xC -> { val v = rdVal or rsVal; r[rd] = v; setNZ(v) }
            0xD -> { val v = rdVal * rsVal; r[rd] = v; setNZ(v); idle(mulCycles(rdVal)) }
            0xE -> { val v = rdVal and rsVal.inv(); r[rd] = v; setNZ(v) }
            else -> { val v = rsVal.inv(); r[rd] = v; setNZ(v) }
        }
    }

    private fun thumbHiReg(op: Int) {
        val rd = (op and 7) or ((op ushr 4) and 8)
        val rs = (op ushr 3) and 0xF
        val rsVal = r[rs]
        when ((op ushr 8) and 3) {
            0 -> writeReg(rd, r[rd] + rsVal)
            1 -> sub(r[rd], rsVal, 1, true)
            2 -> writeReg(rd, rsVal)
            else -> {
                thumb = rsVal and 1 != 0
                branchTo(rsVal)
            }
        }
    }

    private fun thumbRegOffset(op: Int) {
        val rd = op and 7
        val addr = r[(op ushr 3) and 7] + r[(op ushr 6) and 7]
        if (op and 0x0200 == 0) {
            when ((op ushr 10) and 3) {
                0 -> write32(addr, r[rd])
                1 -> write8(addr, r[rd] and 0xFF)
                2 -> { r[rd] = loadWord(addr); idle(1) }
                else -> { r[rd] = read8(addr); idle(1) }
            }
        } else {
            when ((op ushr 10) and 3) {
                0 -> write16(addr, r[rd])
                1 -> { r[rd] = read8(addr).toByte().toInt(); idle(1) }
                2 -> { val h = read16(addr); r[rd] = if (addr and 1 != 0) Integer.rotateRight(h, 8) else h; idle(1) }
                else -> {
                    r[rd] = if (addr and 1 != 0) read8(addr).toByte().toInt() else read16(addr).toShort().toInt()
                    idle(1)
                }
            }
        }
    }

    private fun thumbPushPop(op: Int) {
        val list = op and 0xFF
        val extra = op and 0x100 != 0
        if (op and 0x0800 == 0) {
            // PUSH {list, LR}
            val count = Integer.bitCount(list) + (if (extra) 1 else 0)
            var addr = r[13] - count * 4
            r[13] = addr
            var first = true
            for (i in 0..7) {
                if (list and (1 shl i) == 0) continue
                write32(addr, r[i], !first); first = false; addr += 4
            }
            if (extra) write32(addr, r[14], !first)
        } else {
            // POP {list, PC}
            var addr = r[13]
            var first = true
            for (i in 0..7) {
                if (list and (1 shl i) == 0) continue
                r[i] = read32(addr, !first); first = false; addr += 4
            }
            if (extra) {
                branchTo(read32(addr, !first))
                addr += 4
            }
            r[13] = addr
            idle(1)
        }
    }

    private fun thumbMultiple(op: Int) {
        val rb = (op ushr 8) and 7
        val list = op and 0xFF
        val base = r[rb]
        if (list == 0) {
            // Empty list: transfers r15 and moves the base by 0x40.
            if (op and 0x0800 != 0) branchTo(read32(base)) else write32(base, r[15] + 2)
            r[rb] = base + 0x40
            return
        }
        val newBase = base + Integer.bitCount(list) * 4
        var addr = base
        var first = true
        if (op and 0x0800 != 0) {
            r[rb] = newBase
            for (i in 0..7) {
                if (list and (1 shl i) == 0) continue
                r[i] = read32(addr, !first); first = false; addr += 4
            }
            idle(1)
        } else {
            for (i in 0..7) {
                if (list and (1 shl i) == 0) continue
                val v = if (i == rb) (if (first) base else newBase) else r[i]
                write32(addr, v, !first); first = false; addr += 4
            }
            r[rb] = newBase
        }
    }

    // ------------------------------------------------------------------------------------------

    fun saveState(w: StateWriter) {
        w.tag("arm7")
        w.ints(r); w.ints(usrHigh); w.ints(fiqHigh); w.ints(bankSp); w.ints(bankLr); w.ints(spsrs)
        w.int(pc); w.int(cpsr()); w.int(lastOpcode); w.bool(seqFetch)
        w.int(pipe0); w.int(pipe1); w.bool(pipeValid)
    }

    fun loadState(rd: StateReader) {
        rd.tag("arm7")
        rd.intsInto(r); rd.intsInto(usrHigh); rd.intsInto(fiqHigh); rd.intsInto(bankSp); rd.intsInto(bankLr); rd.intsInto(spsrs)
        pc = rd.int()
        val c = rd.int()
        // Restore flags and mode without re-banking: the saved registers are already for this mode.
        nf = c < 0; zf = c and (1 shl 30) != 0; cf = c and (1 shl 29) != 0; vf = c and (1 shl 28) != 0
        irqDisabled = c and 0x80 != 0; fiqDisabled = c and 0x40 != 0; thumb = c and 0x20 != 0
        mode = c and 0x1F
        lastOpcode = rd.int(); seqFetch = rd.bool()
        pipe0 = rd.int(); pipe1 = rd.int(); pipeValid = rd.bool()
        nextPc = pc
    }

    companion object {
        const val MODE_USR = 0x10
        const val MODE_FIQ = 0x11
        const val MODE_IRQ = 0x12
        const val MODE_SVC = 0x13
        const val MODE_ABT = 0x17
        const val MODE_UND = 0x1B
        const val MODE_SYS = 0x1F
        private const val BANK_FIQ = 1
        private const val BANK_IRQ = 2
        private const val MASK32 = 0xFFFFFFFFL

        private fun bankOfOrNull(mode: Int): Int = when (mode) {
            MODE_USR, MODE_SYS -> 0
            MODE_FIQ -> 1
            MODE_IRQ -> 2
            MODE_SVC -> 3
            MODE_ABT -> 4
            MODE_UND -> 5
            else -> -1
        }

        fun bankOf(mode: Int): Int = bankOfOrNull(mode).coerceAtLeast(0)
    }
}
