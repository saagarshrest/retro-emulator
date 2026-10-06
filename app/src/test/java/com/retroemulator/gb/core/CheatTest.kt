package com.retroemulator.gb.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CheatTest {

    /** Inverse of the Game Genie decoding, used to build test codes. */
    private fun encodeGenie(address: Int, value: Int, compare: Int): String {
        val x = compare xor 0xBA
        val raw = ((x shl 2) or (x ushr 6)) and 0xFF
        val digits = listOf(
            value ushr 4, value and 0xF,
            (address ushr 8) and 0xF, (address ushr 4) and 0xF, address and 0xF,
            ((address ushr 12) xor 0xF) and 0xF,
            raw ushr 4, 0xA, raw and 0xF,
        ).joinToString("") { it.toString(16).uppercase() }
        return "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6)}"
    }

    @Test
    fun parsesGameShark() {
        val p = CheatParser.parse("010238CD").single()
        assertEquals(CheatPatch.GameShark(0x01, 0x02, 0xCD38), p)
    }

    @Test
    fun parsesGameGenieRoundTrip() {
        for ((address, value, compare) in listOf(Triple(0x4A17, 0x00, 0xC9), Triple(0x0150, 0xFF, 0x00), Triple(0x7FFF, 0x3E, 0x18))) {
            val code = encodeGenie(address, value, compare)
            assertEquals(code, CheatPatch.GameGenie(address, value, compare), CheatParser.parse(code).single())
        }
        // Six-digit codes have no compare value.
        val short = encodeGenie(0x1234, 0x56, 0).substring(0, 7)
        assertEquals(CheatPatch.GameGenie(0x1234, 0x56, null), CheatParser.parse(short).single())
    }

    @Test
    fun parsesMultipleAndRejectsGarbage() {
        assertEquals(2, CheatParser.parse("010238CD + 01FF10C0").size)
        assertThrows(IllegalArgumentException::class.java) { CheatParser.parse("XYZ") }
        assertThrows(IllegalArgumentException::class.java) { CheatParser.parse("12345") }
    }

    @Test
    fun appliesAndUndoesPatches() {
        val rom = ByteArray(0x10000)
        rom[0x147] = 0x19 // MBC5 so several banks exist
        rom[0x4100] = 0x11          // bank 1 at $4100
        rom[0x8100] = 0x22          // bank 2 at $4100
        val gb = GameBoy(rom)
        gb.cheats.set(CheatParser.parse(encodeGenie(0x4100, 0x99, 0x22)))
        assertEquals(0x11, gb.cart.rom[0x4100].toInt())           // compare mismatch: untouched
        assertEquals(0x99, gb.cart.rom[0x8100].toInt() and 0xFF)  // compare match: patched
        gb.cheats.set(emptyList())
        assertEquals(0x22, gb.cart.rom[0x8100].toInt())           // undone

        gb.cheats.set(CheatParser.parse("0142A0C0"))
        gb.runFrame()
        assertEquals(0x42, gb.mmu.read(0xC0A0))
    }
}
