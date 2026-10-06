package com.retroemulator.gb.data

import android.content.Context
import com.retroemulator.gb.core.CheatParser
import com.retroemulator.gb.core.CheatPatch
import java.io.File

/** Per-game cheat lists, stored as tab-separated lines in app storage. */
class CheatStore(context: Context) {
    private val dir = File(context.filesDir, "cheats").apply { mkdirs() }

    data class Cheat(val name: String, val code: String, val enabled: Boolean)

    private fun file(key: String) = File(dir, "$key.txt")

    fun load(key: String): MutableList<Cheat> {
        val f = file(key)
        if (!f.exists()) return mutableListOf()
        return f.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 3) Cheat(parts[1], parts[2], parts[0] == "1") else null
        }.toMutableList()
    }

    fun save(key: String, cheats: List<Cheat>) {
        val text = cheats.joinToString("\n") { c ->
            val name = c.name.replace(Regex("[\\t\\n\\r]"), " ")
            val code = c.code.replace(Regex("[\\t\\n\\r]+"), " ")
            "${if (c.enabled) 1 else 0}\t$name\t$code"
        }
        RomLibrary.writeAtomically(file(key), text.toByteArray())
    }

    fun delete(key: String) {
        file(key).delete()
    }

    /** Decoded patches for all enabled cheats; entries that fail to parse are skipped. */
    fun activePatches(key: String): List<CheatPatch> =
        load(key).filter { it.enabled }.flatMap { runCatching { CheatParser.parse(it.code) }.getOrDefault(emptyList()) }
}
