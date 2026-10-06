package com.retroemulator.gb.data

/** Display palettes for original Game Boy games, lightest shade first. */
object Palettes {
    class Palette(val name: String, val bg: IntArray, val obj0: IntArray = bg, val obj1: IntArray = bg)

    private fun rgb(vararg c: Int) = IntArray(c.size) { 0xFF000000.toInt() or c[it] }

    val ALL = listOf(
        Palette("Classic Green", rgb(0x9BBC0F, 0x8BAC0F, 0x306230, 0x0F380F)),
        Palette("Soft Green", rgb(0xE0F8D0, 0x88C070, 0x346856, 0x081820)),
        Palette("Pocket", rgb(0xC4CFA1, 0x8B956D, 0x4D533C, 0x1F1F1F)),
        Palette("Grayscale", rgb(0xFFFFFF, 0xAAAAAA, 0x555555, 0x000000)),
        Palette("Light", rgb(0x00F2B5, 0x00C08A, 0x007656, 0x002C1E)),
        Palette("Ice Cream", rgb(0xFFF6D3, 0xF9A875, 0xEB6B6F, 0x7C3F58)),
        Palette("Velvet", rgb(0xF0E9F5, 0xB49ACB, 0x6A4E8C, 0x231834)),
        Palette(
            "Super Color",
            bg = rgb(0xFFFFFF, 0x7BFF31, 0x0063C5, 0x000000),
            obj0 = rgb(0xFFFFFF, 0xFF8484, 0x943A3A, 0x000000),
            obj1 = rgb(0xFFFFFF, 0xFF8484, 0x943A3A, 0x000000),
        ),
    )
}
