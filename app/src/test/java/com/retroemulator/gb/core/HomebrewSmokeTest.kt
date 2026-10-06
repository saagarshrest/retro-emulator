package com.retroemulator.gb.core

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Plays freely licensed homebrew games (placed in testroms/games) with scripted input and saves
 * screenshots for visual inspection. Skipped when the games are not present.
 */
class HomebrewSmokeTest {
    private val root = File(System.getProperty("testroms.dir") ?: "testroms", "games")
    private val outDir = File(System.getProperty("testout.dir") ?: "build/test-screens").apply { mkdirs() }

    private fun shot(gb: GameBoy, name: String) {
        val img = BufferedImage(160, 144, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, 160, 144, gb.ppu.frameBuffer, 0, 160)
        ImageIO.write(img, "png", File(outDir, "$name.png"))
    }

    private fun frames(gb: GameBoy, n: Int, buttons: Int = 0) {
        repeat(n) {
            gb.joypad.setState(buttons)
            gb.runFrame()
        }
    }

    private fun tap(gb: GameBoy, button: Int) {
        frames(gb, 6, button)
        frames(gb, 20)
    }

    /** Distinct colors on screen: a cheap check that something real was drawn. */
    private fun colorCount(gb: GameBoy) = gb.ppu.frameBuffer.toSet().size

    @Test
    fun ucity() {
        val f = File(root, "ucity.gbc")
        assumeTrue(f.exists())
        val gb = GameBoy(f.readBytes())
        // The title screen plays music: check the APU produces a sane, non-clipping stereo signal.
        val buf = ShortArray(16384)
        var peak = 0
        var sumSq = 0.0
        var count = 0
        repeat(240) {
            gb.runFrame()
            val n = gb.apu.drainSamples(buf)
            for (i in 0 until n) {
                val s = buf[i].toInt()
                peak = maxOf(peak, kotlin.math.abs(s))
                sumSq += s.toDouble() * s
            }
            count += n
        }
        // Sample output tracks emulated time exactly (frames vary in length while the LCD is toggled).
        val expected = gb.totalDots * 48000.0 / 4194304
        assertTrue("got ${count / 2} samples for $expected expected", kotlin.math.abs(count / 2 - expected) < 2)
        val rms = kotlin.math.sqrt(sumSq / count)
        File(outDir, "ucity_audio.txt").writeText("peak=$peak rms=%.0f%n".format(rms))
        assertTrue("audio is silent (rms=$rms)", rms > 300)
        assertTrue("audio clips (peak=$peak)", peak < 32767)
        shot(gb, "ucity_1_title")
        tap(gb, Joypad.START)
        frames(gb, 60)
        shot(gb, "ucity_2_menu")
        tap(gb, Joypad.A)
        frames(gb, 120)
        shot(gb, "ucity_3")
        tap(gb, Joypad.A)
        frames(gb, 120)
        shot(gb, "ucity_4")
        assertTrue(colorCount(gb) > 2)
    }

    @Test
    fun libbet() {
        val f = File(root, "libbet.gb")
        assumeTrue(f.exists())
        val gb = GameBoy(f.readBytes())
        gb.ppu.setDmgPalette(intArrayOf(0xFFE0F8D0.toInt(), 0xFF88C070.toInt(), 0xFF346856.toInt(), 0xFF081820.toInt()))
        frames(gb, 200)
        shot(gb, "libbet_1")
        tap(gb, Joypad.START)
        frames(gb, 120)
        shot(gb, "libbet_2")
        tap(gb, Joypad.A)
        frames(gb, 120)
        shot(gb, "libbet_3")
        frames(gb, 30, Joypad.RIGHT)
        frames(gb, 30, Joypad.DOWN)
        shot(gb, "libbet_4")
        assertTrue(colorCount(gb) > 1)
    }

    @Test
    fun gb240p() {
        val f = File(root, "gb240p.gb")
        assumeTrue(f.exists())
        val gb = GameBoy(f.readBytes())
        frames(gb, 200)
        shot(gb, "240p_1")
        tap(gb, Joypad.A)
        frames(gb, 60)
        shot(gb, "240p_2")
        assertTrue(colorCount(gb) > 1)
    }
}
