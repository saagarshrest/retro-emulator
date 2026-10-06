package com.retroemulator.gb.gba

import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Save states must capture everything: after loading, emulation has to continue exactly as it did
 * the first time (same frames and same audio).
 */
class GbaSaveStateTest {
    private val roms = File(System.getProperty("testroms.dir") ?: "testroms")

    private fun run(gba: Gba, frames: Int): Pair<IntArray, ShortArray> {
        val audio = ArrayList<Short>()
        val buf = ShortArray(16384)
        for (i in 0 until frames) {
            gba.setButtons(if (i % 40 < 20) 0x01 or 0x10 else 0x04)
            gba.runFrame()
            val n = gba.apu.drainSamples(buf)
            for (k in 0 until n) audio += buf[k]
        }
        return gba.frameBuffer.copyOf() to audio.toShortArray()
    }

    private fun check(path: String) {
        val f = File(roms, path)
        assumeTrue("missing $path", f.exists())
        val gba = Gba(f.readBytes())
        val sink = ShortArray(16384)
        repeat(90) { gba.runFrame(); gba.apu.drainSamples(sink) }
        val state = gba.saveState()
        val (frameA, audioA) = run(gba, 120)
        gba.loadState(state)
        val (frameB, audioB) = run(gba, 120)
        assertArrayEquals("$path frame after reload", frameA, frameB)
        assertArrayEquals("$path audio after reload", audioA, audioB)
    }

    @Test fun mode7Demo() = check("gba-homebrew/bin/m7_ex.gba")
    @Test fun soundDemo() = check("gba-homebrew/bin/snd1_demo.gba")
    @Test fun bigmapDemo() = check("gba-homebrew/bin/bigmap.gba")
    @Test fun armTest() = check("gba-tests/arm/arm.gba")
}
