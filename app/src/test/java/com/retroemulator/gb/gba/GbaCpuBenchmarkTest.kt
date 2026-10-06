package com.retroemulator.gb.gba

import org.junit.Test

/**
 * Worst-case speed: the CPU never halts. Prints emulation time per frame for an ARM loop in IWRAM
 * (one cycle per instruction, the most instructions per frame) and a Thumb loop running from ROM.
 */
class GbaCpuBenchmarkTest {
    private fun romWith(code: IntArray, thumb: Boolean): ByteArray {
        val rom = ByteArray(0x400)
        fun put32(at: Int, v: Int) { for (i in 0 until 4) rom[at + i] = (v ushr (i * 8)).toByte() }
        put32(0, 0xEA00002E.toInt()) // b 0xC0
        rom[0xB2] = 0x96.toByte()
        if (thumb) {
            for (i in code.indices) { rom[0xC0 + i * 2] = code[i].toByte(); rom[0xC1 + i * 2] = (code[i] ushr 8).toByte() }
        } else {
            for (i in code.indices) put32(0xC0 + i * 4, code[i])
        }
        return rom
    }

    private fun measure(name: String, gba: Gba) {
        repeat(120) { gba.runFrame() }
        val frames = 600
        val start = System.nanoTime()
        repeat(frames) { gba.runFrame() }
        val ms = (System.nanoTime() - start) / 1e6 / frames
        println(String.format("BENCH %-12s %6.2f ms/frame (%.0f%% of real time)", name, ms, ms / (1000.0 / 59.7275) * 100))
    }

    @Test
    fun armInIwram() {
        // The loop is copied into IWRAM by the test and entered directly.
        val loop = intArrayOf(
            0xE3A00302.toInt(), // mov r0, #0x08000000
            0xE5901000.toInt(), // loop: ldr r1, [r0]
            0xE0822001.toInt(), // add r2, r2, r1
            0xE2833001.toInt(), // add r3, r3, #1
            0xE0244103.toInt(), // eor r4, r4, r3, lsl #2
            0xE50D4004.toInt(), // str r4, [sp, #-4]
            0xE1A05224.toInt(), // mov r5, r4, lsr #4
            0xE3550000.toInt(), // cmp r5, #0
            0xEAFFFFF7.toInt(), // b loop
        )
        val gba = Gba(romWith(intArrayOf(0xEAFFFFFE.toInt()), thumb = false))
        for (i in loop.indices) gba.bus.write32(0x03000000 + i * 4, loop[i])
        gba.cpu.jump(0x03000000)
        measure("ARM/IWRAM", gba)
    }

    @Test
    fun thumbInRom() {
        // ARM stub switches to Thumb, then a Thumb loop runs from ROM with prefetch enabled.
        val gba = Gba(ByteArray(0x400))
        val rom = gba.rom
        fun put32(at: Int, v: Int) { for (i in 0 until 4) rom[at + i] = (v ushr (i * 8)).toByte() }
        fun put16(at: Int, v: Int) { rom[at] = v.toByte(); rom[at + 1] = (v ushr 8).toByte() }
        put32(0x00, 0xE28F0001.toInt()) // add r0, pc, #1   (r0 = 0x08000009 -> thumb at 0x08000008)
        put32(0x04, 0xE12FFF10.toInt()) // bx r0
        val thumb = intArrayOf(
            0x4804,  // ldr r0, [pc, #16] -> literal at 0x1C
            0x6801,  // loop: ldr r1, [r0]
            0x1852,  // add r2, r2, r1
            0x3301,  // add r3, #1
            0x405C,  // eor r4, r3
            0x9400,  // str r4, [sp, #0]
            0xE7F9,  // b loop
        )
        for (i in thumb.indices) put16(0x08 + i * 2, thumb[i])
        put32(0x1C, 0x08000000)
        gba.bus.ioWrite16(0x204, 0x4317) // WAITCNT as most games set it
        gba.cpu.jump(0x08000000)
        measure("Thumb/ROM", gba)
    }
}
