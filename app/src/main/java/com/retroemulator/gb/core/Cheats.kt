package com.retroemulator.gb.core

/** A decoded cheat patch. */
sealed class CheatPatch {
    /** GameShark: writes [value] to [address] every frame. Types 0x80-0x8F select a RAM bank. */
    data class GameShark(val type: Int, val value: Int, val address: Int) : CheatPatch()

    /** Game Genie: replaces the ROM byte at [address] (only where it equals [compare], if given). */
    data class GameGenie(val address: Int, val value: Int, val compare: Int?) : CheatPatch()
}

object CheatParser {
    /**
     * Parses one or more codes separated by whitespace, commas, semicolons or '+'.
     * Accepts GameShark (8 hex digits, e.g. 010238CD) and Game Genie (ABC-DEF or ABC-DEF-GHI).
     */
    fun parse(text: String): List<CheatPatch> {
        val tokens = text.split(Regex("[\\s,;+]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) throw IllegalArgumentException("Enter a cheat code")
        return tokens.map { parseOne(it) }
    }

    private fun parseOne(token: String): CheatPatch {
        val hex = token.replace("-", "").uppercase()
        if (!hex.all { it in '0'..'9' || it in 'A'..'F' }) throw IllegalArgumentException("\"$token\" is not a valid code")
        return when (hex.length) {
            8 -> {
                val type = hex.substring(0, 2).toInt(16)
                val value = hex.substring(2, 4).toInt(16)
                // The address is stored little-endian: 010238CD writes $02 to $CD38.
                val address = (hex.substring(6, 8).toInt(16) shl 8) or hex.substring(4, 6).toInt(16)
                CheatPatch.GameShark(type, value, address)
            }
            6, 9 -> {
                val d = IntArray(hex.length) { hex[it].digitToInt(16) }
                val value = (d[0] shl 4) or d[1]
                val address = ((d[5] xor 0xF) shl 12) or (d[2] shl 8) or (d[3] shl 4) or d[4]
                if (address >= 0x8000) throw IllegalArgumentException("\"$token\" does not target ROM")
                val compare = if (hex.length == 9) {
                    val raw = (d[6] shl 4) or d[8]
                    (((raw ushr 2) or (raw shl 6)) and 0xFF) xor 0xBA
                } else null
                CheatPatch.GameGenie(address, value, compare)
            }
            else -> throw IllegalArgumentException("\"$token\" is not a GameShark or Game Genie code")
        }
    }
}

/** Applies active cheats to a running [GameBoy]. */
class CheatEngine(private val gb: GameBoy) {
    private var sharks: List<CheatPatch.GameShark> = emptyList()
    /** ROM offset -> original byte, for undoing Game Genie patches. */
    private val originals = HashMap<Int, Byte>()

    val active: Boolean get() = sharks.isNotEmpty()

    fun set(patches: List<CheatPatch>) {
        val rom = gb.cart.rom
        for ((offset, byte) in originals) rom[offset] = byte
        originals.clear()
        val banks = rom.size / 0x4000
        for (p in patches.filterIsInstance<CheatPatch.GameGenie>()) {
            // Game Genie patches the CPU address in whatever bank is mapped, so patch every candidate bank.
            val offsets = if (p.address < 0x4000) listOf(p.address)
            else (1 until banks).map { it * 0x4000 + (p.address - 0x4000) }
            for (off in offsets) {
                val current = originals[off] ?: rom[off]
                if (p.compare == null || (current.toInt() and 0xFF) == p.compare) {
                    originals.putIfAbsent(off, rom[off])
                    rom[off] = p.value.toByte()
                }
            }
        }
        sharks = patches.filterIsInstance<CheatPatch.GameShark>()
    }

    /** Re-applies GameShark writes; called once per frame. */
    fun applyFrame() {
        for (c in sharks) poke(c)
    }

    private fun poke(c: CheatPatch.GameShark) {
        val a = c.address
        val v = c.value.toByte()
        val bankOverride = if (c.type and 0xF0 == 0x80) c.type and 0x0F else -1
        when (a ushr 12) {
            0x8, 0x9 -> gb.ppu.vram[(gb.ppu.vbk shl 13) + (a and 0x1FFF)] = v
            0xA, 0xB -> {
                val ram = gb.cart.ram
                if (ram.isEmpty()) return
                if (bankOverride >= 0) ram[(bankOverride * 0x2000 + (a and 0x1FFF)) % ram.size] = v
                else gb.cart.writeRam(a, c.value)
            }
            0xC -> gb.mmu.wram[a and 0x0FFF] = v
            0xD -> {
                val bank = if (bankOverride > 0 && gb.cgb) bankOverride and 7 else gb.mmu.currentWramBank
                gb.mmu.wram[(bank shl 12) or (a and 0x0FFF)] = v
            }
            0xF -> if (a in 0xFF80..0xFFFE) gb.mmu.hram[a - 0xFF80] = v
        }
    }
}
