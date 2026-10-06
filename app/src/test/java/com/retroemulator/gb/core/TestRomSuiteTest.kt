package com.retroemulator.gb.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Runs the public test-ROM suites from https://github.com/c-sp/game-boy-test-roms (extract the release zip
 * into ./testroms). Tests are skipped when the directory is absent.
 */
class TestRomSuiteTest {

    private val root = File(System.getProperty("testroms.dir") ?: "testroms")
    private val outDir = File(System.getProperty("testout.dir") ?: "build/test-screens").apply { mkdirs() }

    private fun requireRoms() = assumeTrue("test ROMs not present in $root", root.isDirectory)

    private fun boot(path: String): GameBoy {
        val gb = GameBoy(File(root, path).readBytes())
        gb.ppu.setDmgPalette(GRAY)
        gb.ppu.setColorCorrection(false)
        return gb
    }

    private fun runSeconds(gb: GameBoy, seconds: Double) {
        val target = (seconds * 4194304).toLong()
        while (gb.totalDots < target) gb.runFrame()
    }

    private fun savePng(gb: GameBoy, name: String): File {
        val img = BufferedImage(160, 144, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, 160, 144, gb.ppu.frameBuffer, 0, 160)
        val f = File(outDir, name.replace('/', '_').replace(' ', '_') + ".png")
        ImageIO.write(img, "png", f)
        return f
    }

    /** Number of pixels that differ from the reference screenshot. */
    private fun diffPixels(gb: GameBoy, reference: String): Int {
        val ref = ImageIO.read(File(root, reference))
        var diff = 0
        for (y in 0 until 144) for (x in 0 until 160) {
            if ((ref.getRGB(x, y) and 0xFFFFFF) != (gb.ppu.frameBuffer[y * 160 + x] and 0xFFFFFF)) diff++
        }
        return diff
    }

    /** Runs a Mooneye-style test until `LD B,B` executes; true when the Fibonacci signature is present. */
    private fun runMooneye(gb: GameBoy, maxSeconds: Double = 30.0): Boolean? {
        val limit = (maxSeconds * 4194304 / 4).toLong()
        var steps = 0L
        while (steps < limit) {
            if (!gb.cpu.halted && gb.mmu.read(gb.cpu.pc) == 0x40) {
                gb.step()
                val c = gb.cpu
                return c.b == 3 && c.c == 5 && c.d == 8 && c.e == 13 && c.h == 21 && c.l == 34
            }
            gb.step()
            steps++
        }
        return null
    }

    private val report = StringBuilder()

    private fun record(name: String, ok: Boolean?, detail: String = "") {
        val status = when (ok) { true -> "PASS"; false -> "FAIL"; null -> "TIMEOUT" }
        report.append(String.format("%-8s %s %s%n", status, name, detail))
    }

    private fun writeReport(file: String) {
        File(outDir, file).writeText(report.toString())
        println(report)
    }

    // ------------------------------------------------------------------------------------------

    @Test
    fun blargg() {
        requireRoms()
        val cases = listOf(
            Triple("blargg/cpu_instrs/cpu_instrs.gb", 55.0, "blargg/cpu_instrs/cpu_instrs-dmg-cgb.png"),
            Triple("blargg/instr_timing/instr_timing.gb", 1.5, "blargg/instr_timing/instr_timing-dmg-cgb.png"),
            Triple("blargg/mem_timing/mem_timing.gb", 3.5, "blargg/mem_timing/mem_timing-dmg-cgb.png"),
            Triple("blargg/mem_timing-2/mem_timing.gb", 4.5, "blargg/mem_timing-2/mem_timing-dmg-cgb.png"),
            Triple("blargg/halt_bug.gb", 2.5, "blargg/halt_bug-dmg-cgb.png"),
            Triple("blargg/dmg_sound/dmg_sound.gb", 36.0, "blargg/dmg_sound/dmg_sound-dmg.png"),
        )
        val failures = mutableListOf<String>()
        for ((rom, secs, ref) in cases) {
            val gb = boot(rom)
            runSeconds(gb, secs)
            savePng(gb, rom)
            val diff = diffPixels(gb, ref)
            record(rom, diff == 0, if (diff != 0) "($diff px differ)" else "")
            if (diff != 0) failures += rom
        }
        writeReport("blargg.txt")
        // dmg_sound fails only sub-tests 09, 10 and 12 (DMG wave RAM access quirks); see blarggSoundSingles.
        val unexpected = failures - setOf("blargg/dmg_sound/dmg_sound.gb")
        assertTrue("Failing: $unexpected", unexpected.isEmpty())
    }

    @Test
    fun blarggSoundSingles() {
        requireRoms()
        val dir = File(root, "blargg/dmg_sound/rom_singles")
        val failures = mutableListOf<String>()
        for (f in dir.listFiles()!!.sortedBy { it.name }) {
            val gb = boot("blargg/dmg_sound/rom_singles/${f.name}")
            val out = StringBuilder()
            runSeconds(gb, 20.0)
            // These ROMs report through cartridge RAM: 0xA000 = status, text from 0xA004.
            val status = gb.cart.readRam(0xA000)
            var i = 0xA004
            while (i < 0xC000) { val ch = gb.cart.readRam(i); if (ch == 0) break; out.append(ch.toChar()); i++ }
            record("dmg_sound/${f.name}", status == 0, "status=$status " + out.toString().replace('\n', ' ').trim().take(80))
            if (status != 0) failures += f.name.substringBefore('-')
        }
        writeReport("blargg_sound.txt")
        // Known gaps: DMG-only wave RAM access timing while the channel plays (needs sub-M-cycle APU timing).
        val unexpected = failures - setOf("09", "10", "12")
        assertTrue("Failing: $unexpected", unexpected.isEmpty())
    }

    @Test
    fun acid2() {
        requireRoms()
        val dmg = boot("dmg-acid2/dmg-acid2.gb")
        val dmgOk = runMooneye(dmg, 10.0) != null
        repeat(2) { dmg.runFrame() }
        savePng(dmg, "dmg-acid2")
        val dmgDiff = diffPixels(dmg, "dmg-acid2/dmg-acid2-dmg.png")
        record("dmg-acid2", dmgOk && dmgDiff == 0, "($dmgDiff px differ)")

        val cgb = boot("cgb-acid2/cgb-acid2.gbc")
        val cgbOk = runMooneye(cgb, 10.0) != null
        repeat(2) { cgb.runFrame() }
        savePng(cgb, "cgb-acid2")
        val cgbDiff = diffPixels(cgb, "cgb-acid2/cgb-acid2.png")
        record("cgb-acid2", cgbOk && cgbDiff == 0, "($cgbDiff px differ)")
        writeReport("acid2.txt")
        assertTrue("dmg-acid2 differs by $dmgDiff px", dmgDiff == 0)
        assertTrue("cgb-acid2 differs by $cgbDiff px", cgbDiff == 0)
    }

    @Test
    fun mooneye() {
        requireRoms()
        val dir = File(root, "mooneye-test-suite")
        // Only tests valid for DMG hardware (no suffix, or a suffix naming DMG ABC / "G" / "S" groups).
        val roms = dir.walkTopDown()
            .filter { it.isFile && it.extension == "gb" }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .filter { it.contains("/acceptance/") || it.contains("/emulator-only/") }
            .filter { path ->
                val name = path.substringAfterLast('/').removeSuffix(".gb")
                val suffix = if (name.contains('-')) name.substringAfterLast('-') else ""
                suffix.isEmpty() || suffix.contains("dmgABC") || suffix == "GS"
            }
            .sorted()
            .toList()
        var passed = 0
        val failures = mutableListOf<String>()
        for (rom in roms) {
            val gb = boot(rom)
            val result = try {
                runMooneye(gb)
            } catch (e: Exception) {
                record(rom, false, e.toString())
                failures += rom.substringAfterLast('/')
                continue
            }
            if (result == true) {
                passed++
            } else {
                failures += rom.substringAfterLast('/')
                // Failing Mooneye tests print diagnostics on screen.
                repeat(10) { gb.runFrame() }
                savePng(gb, "fail_" + rom.substringAfterLast('/'))
            }
            record(rom, result)
        }
        report.append("\n$passed / ${roms.size} passed\n")
        writeReport("mooneye.txt")
        // Known gap: TAC-toggle glitch phase (timer/rapid_toggle).
        val unexpected = failures - setOf("rapid_toggle.gb")
        assertTrue("Failing: $unexpected", unexpected.isEmpty())
    }

    @Test
    fun saveStateRoundTripIsDeterministic() {
        requireRoms()
        val gb = boot("cgb-acid2/cgb-acid2.gbc")
        repeat(30) { gb.runFrame() }
        val state = gb.saveState()
        repeat(20) { gb.runFrame() }
        val expected = gb.ppu.frameBuffer.copyOf()
        val cpuAfter = intArrayOf(gb.cpu.pc, gb.cpu.sp, gb.cpu.a)

        val gb2 = boot("cgb-acid2/cgb-acid2.gbc")
        gb2.loadState(state)
        repeat(20) { gb2.runFrame() }
        assertArrayEquals(expected, gb2.ppu.frameBuffer)
        assertArrayEquals(cpuAfter, intArrayOf(gb2.cpu.pc, gb2.cpu.sp, gb2.cpu.a))

        // Loading a state for a different game must be rejected and leave the emulator untouched.
        val other = boot("dmg-acid2/dmg-acid2.gb")
        val before = other.saveState()
        assertTrue(runCatching { other.loadState(state) }.isFailure)
        assertArrayEquals(before, other.saveState())
    }

    @Test
    fun benchmark() {
        requireRoms()
        val gb = boot("blargg/cpu_instrs/cpu_instrs.gb")
        repeat(120) { gb.runFrame() } // warm up the JIT
        val frames = 1200
        val t0 = System.nanoTime()
        repeat(frames) { gb.runFrame() }
        val secs = (System.nanoTime() - t0) / 1e9
        val fps = frames / secs
        File(outDir, "benchmark.txt").writeText("%.0f frames/s (%.1fx real time)%n".format(fps, fps / 59.73))
        println("Benchmark: %.0f fps".format(fps))
    }

    companion object {
        private val GRAY = intArrayOf(0xFFFFFFFF.toInt(), 0xFFAAAAAA.toInt(), 0xFF555555.toInt(), 0xFF000000.toInt())
    }
}
