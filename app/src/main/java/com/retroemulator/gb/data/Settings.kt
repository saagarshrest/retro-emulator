package com.retroemulator.gb.data

import android.content.Context

/** User preferences, backed by SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sound: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(v) = prefs.edit().putBoolean(KEY_SOUND, v).apply()

    var volume: Int
        get() = prefs.getInt(KEY_VOLUME, 100)
        set(v) = prefs.edit().putInt(KEY_VOLUME, v.coerceIn(0, 100)).apply()

    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(v) = prefs.edit().putBoolean(KEY_HAPTICS, v).apply()

    var rumble: Boolean
        get() = prefs.getBoolean(KEY_RUMBLE, true)
        set(v) = prefs.edit().putBoolean(KEY_RUMBLE, v).apply()

    var paletteIndex: Int
        get() = prefs.getInt(KEY_PALETTE, 0).coerceIn(0, Palettes.ALL.size - 1)
        set(v) = prefs.edit().putInt(KEY_PALETTE, v).apply()

    var colorCorrection: Boolean
        get() = prefs.getBoolean(KEY_COLOR_CORRECTION, true)
        set(v) = prefs.edit().putBoolean(KEY_COLOR_CORRECTION, v).apply()

    var integerScaling: Boolean
        get() = prefs.getBoolean(KEY_INTEGER_SCALING, false)
        set(v) = prefs.edit().putBoolean(KEY_INTEGER_SCALING, v).apply()

    var smoothScaling: Boolean
        get() = prefs.getBoolean(KEY_SMOOTH, false)
        set(v) = prefs.edit().putBoolean(KEY_SMOOTH, v).apply()

    var autoResume: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESUME, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_RESUME, v).apply()

    var fastForwardSpeed: Int
        get() = prefs.getInt(KEY_FF_SPEED, 4)
        set(v) = prefs.edit().putInt(KEY_FF_SPEED, v).apply()

    var forceDmg: Boolean
        get() = prefs.getBoolean(KEY_FORCE_DMG, false)
        set(v) = prefs.edit().putBoolean(KEY_FORCE_DMG, v).apply()

    var showFps: Boolean
        get() = prefs.getBoolean(KEY_SHOW_FPS, false)
        set(v) = prefs.edit().putBoolean(KEY_SHOW_FPS, v).apply()

    companion object {
        private const val KEY_SOUND = "sound"
        private const val KEY_VOLUME = "volume"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_RUMBLE = "rumble"
        private const val KEY_PALETTE = "palette"
        private const val KEY_COLOR_CORRECTION = "color_correction"
        private const val KEY_INTEGER_SCALING = "integer_scaling"
        private const val KEY_SMOOTH = "smooth_scaling"
        private const val KEY_AUTO_RESUME = "auto_resume"
        private const val KEY_FF_SPEED = "ff_speed"
        private const val KEY_FORCE_DMG = "force_dmg"
        private const val KEY_SHOW_FPS = "show_fps"

        val FAST_FORWARD_SPEEDS = intArrayOf(2, 3, 4, 8)
    }
}
