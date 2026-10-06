package com.retroemulator.gb.gba

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * jsmolka's gba-tests (testroms/gba-tests). Each test ROM leaves the number of the first failing
 * test in r12 (0 when everything passed) and draws the result on screen; screenshots are saved for
 * visual inspection. Skipped when the ROMs are not present.
 */
class GbaTestRomsTest {
    private val root = File(System.getProperty("testroms.dir") ?: "testroms", "gba-tests")
    private val outDir = File(System.getProperty("testout.dir") ?: "build/test-screens", "gba").apply { mkdirs() }

    private fun load(path: String): Gba? {
        val f = File(root, path)
        if (!f.exists()) return null
        return Gba(f.readBytes())
    }

    private fun shot(gba: Gba, name: String) {
        val img = BufferedImage(Gba.WIDTH, Gba.HEIGHT, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, Gba.WIDTH, Gba.HEIGHT, gba.frameBuffer, 0, Gba.WIDTH)
        ImageIO.write(img, "png", File(outDir, "$name.png"))
    }

    private fun runResult(path: String, frames: Int = 120): Int {
        val gba = load(path)
        assumeTrue("missing $path", gba != null)
        gba!!
        repeat(frames) { gba.runFrame() }
        shot(gba, File(path).nameWithoutExtension)
        return gba.cpu.r[12]
    }

    @Test fun arm() = assertEquals("first failing ARM test", 0, runResult("arm/arm.gba"))
    @Test fun thumb() = assertEquals("first failing THUMB test", 0, runResult("thumb/thumb.gba"))
    @Test fun memory() = assertEquals("first failing memory test", 0, runResult("memory/memory.gba"))
    @Test fun bios() = assertEquals("first failing BIOS test", 0, runResult("bios/bios.gba"))
    @Test fun nes() = assertEquals("first failing NES test", 0, runResult("nes/nes.gba"))
    @Test fun saveNone() = assertEquals(0, runResult("save/none.gba"))
    @Test fun saveSram() = assertEquals(0, runResult("save/sram.gba"))
    @Test fun saveFlash64() = assertEquals(0, runResult("save/flash64.gba"))
    @Test fun saveFlash128() = assertEquals(0, runResult("save/flash128.gba"))

    @Test
    fun ppuScreens() {
        for (name in listOf("hello", "shades", "stripes")) {
            val gba = load("ppu/$name.gba")
            assumeTrue(gba != null)
            repeat(60) { gba!!.runFrame() }
            shot(gba!!, name)
        }
    }
}
