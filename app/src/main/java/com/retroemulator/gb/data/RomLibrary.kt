package com.retroemulator.gb.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.retroemulator.gb.core.Cartridge
import com.retroemulator.gb.core.CartridgeHeader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

/**
 * Stores imported ROMs, battery saves and save states in app-private storage, so games stay
 * playable without holding long-lived permissions on the user's files.
 */
class RomLibrary(private val context: Context) {
    private val romDir = File(context.filesDir, "roms").apply { mkdirs() }
    private val saveDir = File(context.filesDir, "saves").apply { mkdirs() }
    private val stateDir = File(context.filesDir, "states").apply { mkdirs() }
    private val meta = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    class RomEntry(
        val file: File,
        val title: String,
        val cgb: Boolean,
        val cgbOnly: Boolean,
        val mapper: String,
        val lastPlayed: Long,
    ) {
        val key: String get() = file.nameWithoutExtension
    }

    fun list(): List<RomEntry> {
        val files = romDir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: return emptyList()
        return files.mapNotNull { readEntry(it) }
            .sortedWith(compareByDescending<RomEntry> { it.lastPlayed }.thenBy { it.title.lowercase() })
    }

    fun entryFor(file: File): RomEntry? = readEntry(file)

    private fun readEntry(file: File): RomEntry? {
        return try {
            val head = ByteArray(0x150)
            RandomAccessFile(file, "r").use { it.readFully(head) }
            val header = CartridgeHeader(head)
            val name = header.title.ifBlank { file.nameWithoutExtension }
            RomEntry(
                file = file,
                title = prettyTitle(file.nameWithoutExtension, name),
                cgb = header.supportsCgb,
                cgbOnly = header.cgbOnly,
                mapper = header.mapperName,
                lastPlayed = meta.getLong("played:" + file.name, 0L),
            )
        } catch (e: IOException) {
            null
        }
    }

    /** Prefer the file name (usually the full game name) unless it is just a code. */
    private fun prettyTitle(fileName: String, headerTitle: String): String {
        val cleaned = fileName.replace('_', ' ').trim()
        return if (cleaned.length >= 3) cleaned else headerTitle
    }

    fun markPlayed(entry: RomEntry) {
        meta.edit().putLong("played:" + entry.file.name, System.currentTimeMillis()).apply()
    }

    /** Imports a ROM (optionally inside a .zip) from a content Uri. Returns the stored entry. */
    fun import(uri: Uri): RomEntry {
        val resolver = context.contentResolver
        var displayName = queryName(uri) ?: "game.gb"
        var data = resolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            bytes
        } ?: throw IOException("Could not open the selected file")

        if (data.size >= 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()) {
            var found: Pair<String, ByteArray>? = null
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val ext = e.name.substringAfterLast('.', "").lowercase()
                    if (!e.isDirectory && ext in ROM_EXTENSIONS) {
                        found = e.name.substringAfterLast('/') to zip.readBytes()
                        break
                    }
                }
            }
            val (name, bytes) = found ?: throw IOException("The zip file does not contain a .gb or .gbc ROM")
            displayName = name
            data = bytes
        }

        validate(data)
        var safeName = sanitize(displayName)
        val ext = safeName.substringAfterLast('.', "").lowercase()
        if (ext !in ROM_EXTENSIONS) {
            val header = CartridgeHeader(data)
            safeName += if (header.supportsCgb) ".gbc" else ".gb"
        }
        val target = File(romDir, safeName)
        val tmp = File(romDir, "$safeName.tmp")
        tmp.writeBytes(data)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IOException("Could not store the ROM")
        return readEntry(target) ?: throw IOException("Could not read the imported ROM")
    }

    private fun validate(data: ByteArray) {
        if (data.size < 0x150) throw IOException("This file is too small to be a Game Boy ROM")
        if (data.size > 16 * 1024 * 1024) throw IOException("This file is too large to be a Game Boy ROM")
        // Throws for mappers the emulator does not support, with a readable message.
        Cartridge.create(data)
    }

    private fun queryName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sanitize(name: String): String {
        val cleaned = name.map { ch -> if (ch.isLetterOrDigit() || ch in " ._()[]!&,+'-") ch else '_' }.joinToString("")
        return cleaned.trim().trimStart('.').ifBlank { "game.gb" }.take(120)
    }

    fun delete(entry: RomEntry) {
        entry.file.delete()
        saveFile(entry).delete()
        for (slot in 0..STATE_SLOTS) stateFile(entry, slot).delete()
        CheatStore(context).delete(entry.key)
        meta.edit().remove("played:" + entry.file.name).apply()
    }

    fun saveFile(entry: RomEntry) = File(saveDir, entry.key + ".sav")

    /** Slot 0 is the automatic resume state. */
    fun stateFile(entry: RomEntry, slot: Int) = File(stateDir, "${entry.key}.ss$slot")

    fun deleteSaveData(entry: RomEntry) {
        saveFile(entry).delete()
        stateFile(entry, 0).delete()
    }

    fun importSave(entry: RomEntry, uri: Uri) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Could not open the selected file")
        if (bytes.isEmpty() || bytes.size > 1024 * 1024) throw IOException("This does not look like a save file")
        writeAtomically(saveFile(entry), bytes)
        // The resume state embeds the old cartridge RAM and would overwrite the imported save.
        stateFile(entry, 0).delete()
    }

    fun exportSave(entry: RomEntry, uri: Uri) {
        val f = saveFile(entry)
        if (!f.exists()) throw IOException("This game has no save data yet")
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(f.readBytes()) }
            ?: throw IOException("Could not write the file")
    }

    companion object {
        val ROM_EXTENSIONS = setOf("gb", "gbc", "cgb", "sgb", "dmg")
        const val STATE_SLOTS = 5

        fun writeAtomically(target: File, data: ByteArray) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("Could not write ${target.name}")
            }
        }
    }
}
