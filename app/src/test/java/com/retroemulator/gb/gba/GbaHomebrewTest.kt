package com.retroemulator.gb.gba

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Runs freely available GBA demos (the Tonc examples, placed in testroms/gba-homebrew/bin) and saves
 * a screenshot of each for visual inspection, with the average emulation time per frame.
 * Skipped when the demos are not present.
 */
class GbaHomebrewTest {
    private val root = File(System.getProperty("testroms.dir") ?: "testroms", "gba-homebrew/bin")
    private val outDir = File(System.getProperty("testout.dir") ?: "build/test-screens", "gba-homebrew").apply { mkdirs() }

    private fun shot(gba: Gba, name: String) {
        val img = BufferedImage(Gba.WIDTH, Gba.HEIGHT, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, Gba.WIDTH, Gba.HEIGHT, gba.frameBuffer, 0, Gba.WIDTH)
        ImageIO.write(img, "png", File(outDir, "$name.png"))
    }

    @Test
    fun demos() {
        val roms = root.listFiles { f -> f.extension == "gba" }?.sortedBy { it.name } ?: emptyList()
        assumeTrue(roms.isNotEmpty())
        val samples = ShortArray(16384)
        val report = StringBuilder()
        for (f in roms) {
            val gba = Gba(f.readBytes())
            // Warm up, then time a stretch of frames; hold a direction for a while so demos move.
            repeat(60) { gba.runFrame(); gba.apu.drainSamples(samples) }
            val start = System.nanoTime()
            val frames = 240
            for (i in 0 until frames) {
                gba.setButtons(if (i in 60..120) 0x01 else if (i in 150..170) 0x10 else 0)
                gba.runFrame()
                gba.apu.drainSamples(samples)
            }
            val ms = (System.nanoTime() - start) / 1e6 / frames
            shot(gba, f.nameWithoutExtension)
            report.append(String.format("%-14s %6.2f ms/frame\n", f.nameWithoutExtension, ms))
        }
        File(outDir, "timing.txt").writeText(report.toString())
        println(report)
    }
}
