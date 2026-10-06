package com.retroemulator.gb.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.text.format.DateFormat
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.retroemulator.gb.R
import com.retroemulator.gb.core.CheatParser
import com.retroemulator.gb.core.GameBoy
import com.retroemulator.gb.core.Joypad
import com.retroemulator.gb.data.CheatStore
import com.retroemulator.gb.data.Palettes
import com.retroemulator.gb.data.RomLibrary
import com.retroemulator.gb.data.Settings
import com.retroemulator.gb.emu.EmulatorSession
import java.io.File
import java.util.Date

class EmulatorActivity : Activity(), ControllerView.Listener, EmulatorSession.Listener {

    private lateinit var settings: Settings
    private lateinit var library: RomLibrary
    private lateinit var cheatStore: CheatStore
    private lateinit var entry: RomLibrary.RomEntry
    private lateinit var romData: ByteArray
    private var session: EmulatorSession? = null
    private lateinit var root: FrameLayout
    private lateinit var skin: ConsoleSkinView
    private lateinit var gameView: GameView
    private lateinit var controller: ControllerView
    private var safeInsets = Rect()

    private var touchMask = 0
    private var keyMask = 0
    private var axisMask = 0
    private var holdFastForward = false
    private var openDialogs = 0
    private var resumed = false
    private var sampleRate = 48000
    private var vibrator: Vibrator? = null
    private val batteryLock = Any()
    private var backCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        library = RomLibrary(this)
        cheatStore = CheatStore(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val file = intent.getStringExtra(EXTRA_ROM)?.let { File(it) }
        val found = file?.let { library.entryFor(it) }
        if (file == null || found == null) {
            Toast.makeText(this, R.string.error_rom_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        entry = found

        skin = ConsoleSkinView(this)
        gameView = GameView(this)
        controller = ControllerView(this)
        controller.listener = this
        root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.emu_background))
            addView(skin, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(gameView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(controller, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setOnApplyWindowInsetsListener { _, insets ->
                safeInsets = cutoutInsets(insets)
                relayout()
                insets
            }
            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) relayout()
            }
        }
        setContentView(root)

        val gb = try {
            romData = file.readBytes()
            createGameBoy().also { restore(it); applyCheats(it) }
        } catch (e: Exception) {
            showFatalError(e.message ?: getString(R.string.error_rom_load))
            return
        }

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        sessionStart(gb)
        library.markPlayed(entry)
        registerBack()
    }

    private fun sessionStart(gb: GameBoy) {
        session = EmulatorSession(gb, sampleRate, this).also { it.start() }
        applySettings()
    }

    private fun createGameBoy(): GameBoy {
        sampleRate = EmulatorSession.preferredSampleRate(this)
        return GameBoy(romData, settings.forceDmg, sampleRate)
    }

    /** Loads the battery save and, if enabled, the automatic resume state. */
    private fun restore(gb: GameBoy) {
        val save = library.saveFile(entry)
        if (gb.cart.hasBattery && save.exists()) {
            try {
                gb.cart.loadSaveData(save.readBytes())
            } catch (e: Exception) {
                Toast.makeText(this, R.string.error_save_load, Toast.LENGTH_LONG).show()
            }
        }
        val auto = library.stateFile(entry, 0)
        if (settings.autoResume && auto.exists()) {
            try {
                gb.loadState(auto.readBytes())
            } catch (e: Exception) {
                auto.delete()
            }
        }
    }

    private fun applySettings() {
        val s = session ?: return
        s.soundEnabled = settings.sound
        s.volume = settings.volume / 100f
        s.fastForwardSpeed = settings.fastForwardSpeed
        val palette = Palettes.ALL[settings.paletteIndex]
        s.withGameBoy { gb ->
            gb.ppu.setDmgPalette(palette.bg, palette.obj0, palette.obj1)
            gb.ppu.setColorCorrection(settings.colorCorrection)
        }
        gameView.smoothScaling = settings.smoothScaling
        if (!settings.showFps) gameView.fpsText = null
        controller.hapticsEnabled = settings.haptics
        relayout()
    }

    /** Recomputes the handheld geometry for the current size, cut-outs and scaling setting. */
    private fun relayout() {
        if (!::root.isInitialized || root.width == 0 || root.height == 0) return
        val layout = ConsoleLayout()
        layout.compute(root.width, root.height, safeInsets, settings.integerScaling)
        skin.consoleLayout = layout
        gameView.setScreen(layout.screen)
        controller.consoleLayout = layout
    }

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        if (openDialogs == 0) session?.resume()
    }

    override fun onPause() {
        resumed = false
        persist()
        super.onPause()
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        if (Build.VERSION.SDK_INT >= 33) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() else releaseInputs()
    }

    /** Pauses emulation and writes the battery save and resume state. */
    private fun persist() {
        val s = session ?: return
        s.pause()
        try {
            s.withGameBoy { gb ->
                if (gb.cart.hasBattery) {
                    writeBattery(gb.cart.saveData())
                    gb.cart.ramDirty = false
                }
                if (settings.autoResume) {
                    RomLibrary.writeAtomically(library.stateFile(entry, 0), gb.saveState())
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_save_write, Toast.LENGTH_LONG).show()
        }
    }

    private fun writeBattery(data: ByteArray) {
        synchronized(batteryLock) { RomLibrary.writeAtomically(library.saveFile(entry), data) }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Build.VERSION.SDK_INT < 35) {
                @Suppress("DEPRECATION")
                window.setDecorFitsSystemWindows(false)
            }
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    private fun cutoutInsets(insets: WindowInsets): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.displayCutout())
            return Rect(i.left, i.top, i.right, i.bottom)
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val c = insets.displayCutout ?: return Rect()
            return Rect(c.safeInsetLeft, c.safeInsetTop, c.safeInsetRight, c.safeInsetBottom)
        }
        return Rect()
    }

    private fun registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { openMenu() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        }
    }

    @Deprecated("Used below API 33")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        openMenu()
    }

    // ------------------------------------------------------------------------------------------
    // EmulatorSession.Listener (emulator thread)
    // ------------------------------------------------------------------------------------------

    override fun onFrame(pixels: IntArray) = gameView.submitFrame(pixels)

    override fun onFps(fps: Int) {
        if (settings.showFps) runOnUiThread { gameView.fpsText = "$fps fps" }
    }

    override fun onRumble() {
        if (!settings.rumble) return
        vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onBatterySave(data: ByteArray) {
        try {
            writeBattery(data)
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, R.string.error_save_write, Toast.LENGTH_SHORT).show() }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------

    override fun onButtonsChanged(mask: Int) {
        touchMask = mask
        pushInput()
    }

    override fun onMenu() = openMenu()

    override fun onFastForward() = setFastForward(!(session?.fastForward ?: false))

    private fun setFastForward(on: Boolean) {
        session?.fastForward = on
        controller.fastForwardActive = on
    }

    private fun pushInput() {
        session?.input = touchMask or keyMask or axisMask
    }

    private fun releaseInputs() {
        controller.releaseAll()
        touchMask = 0
        keyMask = 0
        axisMask = 0
        pushInput()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val bit = keyToButton(code)
        if (bit != 0) {
            keyMask = when (event.action) {
                KeyEvent.ACTION_DOWN -> keyMask or bit
                KeyEvent.ACTION_UP -> keyMask and bit.inv()
                else -> keyMask
            }
            pushInput()
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> {
                // Hold to fast-forward.
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    holdFastForward = true
                    setFastForward(true)
                } else if (event.action == KeyEvent.ACTION_UP && holdFastForward) {
                    holdFastForward = false
                    setFastForward(false)
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                if (event.action == KeyEvent.ACTION_UP) openMenu()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun keyToButton(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> Joypad.UP
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> Joypad.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> Joypad.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> Joypad.RIGHT
        // Positional mapping: the right face button is A, the bottom/left one is B (as on a Game Boy).
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_K,
        KeyEvent.KEYCODE_DPAD_CENTER -> Joypad.A
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_J -> Joypad.B
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> Joypad.START
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_DEL -> Joypad.SELECT
        else -> 0
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val joystick = event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (joystick && event.action == MotionEvent.ACTION_MOVE) {
            val x = event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y)
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var m = 0
            if (x < -0.5f || hx < -0.5f) m = m or Joypad.LEFT
            if (x > 0.5f || hx > 0.5f) m = m or Joypad.RIGHT
            if (y < -0.5f || hy < -0.5f) m = m or Joypad.UP
            if (y > 0.5f || hy > 0.5f) m = m or Joypad.DOWN
            axisMask = m
            pushInput()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ------------------------------------------------------------------------------------------
    // Menus
    // ------------------------------------------------------------------------------------------

    private fun dialogOpened() {
        openDialogs++
        releaseInputs()
        session?.pause()
    }

    private fun dialogClosed() {
        openDialogs = maxOf(0, openDialogs - 1)
        if (openDialogs == 0 && resumed && !isFinishing) {
            hideSystemBars()
            session?.resume()
        }
    }

    private fun showDialog(builder: AlertDialog.Builder): AlertDialog {
        dialogOpened()
        builder.setOnDismissListener { dialogClosed() }
        return builder.show()
    }

    private fun openMenu() {
        if (openDialogs > 0 || session == null) return
        val ff = session?.fastForward ?: false
        val items = arrayOf(
            getString(R.string.menu_resume),
            getString(R.string.menu_save_state),
            getString(R.string.menu_load_state),
            getString(if (ff) R.string.menu_ff_off else R.string.menu_ff_on),
            getString(R.string.menu_cheats),
            getString(R.string.menu_reset),
            getString(R.string.settings),
            getString(R.string.menu_quit),
        )
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(entry.title)
                .setItems(items) { _, which ->
                    when (which) {
                        1 -> showSlots(save = true)
                        2 -> showSlots(save = false)
                        3 -> setFastForward(!ff)
                        4 -> showCheats()
                        5 -> confirmReset()
                        6 -> showSettings()
                        7 -> finish()
                    }
                }
        )
    }

    // ------------------------------------------------------------------------------------------
    // Cheats
    // ------------------------------------------------------------------------------------------

    private fun applyCheats(gb: GameBoy) {
        gb.cheats.set(cheatStore.activePatches(entry.key))
    }

    private fun applyCheats() {
        session?.withGameBoy { applyCheats(it) }
    }

    private fun showCheats() {
        val cheats = cheatStore.load(entry.key)
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.menu_cheats)
            .setPositiveButton(R.string.done, null)
            .setNeutralButton(R.string.cheat_add) { _, _ -> showAddCheat() }
        if (cheats.isEmpty()) {
            builder.setMessage(R.string.cheats_empty)
        } else {
            val labels = cheats.map { if (it.name == it.code) it.code else "${it.name}  ·  ${it.code}" }.toTypedArray()
            val checked = cheats.map { it.enabled }.toBooleanArray()
            builder.setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                cheats[which] = cheats[which].copy(enabled = isChecked)
                cheatStore.save(entry.key, cheats)
                applyCheats()
            }
        }
        val dialog = showDialog(builder)
        dialog.listView?.setOnItemLongClickListener { _, _, position, _ ->
            val cheat = cheats[position]
            showDialog(
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.cheat_delete_title, cheat.name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        cheats.removeAt(position)
                        cheatStore.save(entry.key, cheats)
                        applyCheats()
                        dialog.dismiss()
                        showCheats()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
            )
            true
        }
    }

    private fun showAddCheat() {
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val name = EditText(this).apply {
            setHint(R.string.cheat_name_hint)
            setSingleLine()
        }
        val code = EditText(this).apply {
            setHint(R.string.cheat_code_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, 0)
            addView(name)
            addView(code)
        }
        val dialog = showDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.cheat_add)
                .setView(layout)
                .setPositiveButton(R.string.cheat_add, null)
                .setNegativeButton(android.R.string.cancel, null)
        )
        // Validate before closing.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = code.text.toString().trim()
            try {
                CheatParser.parse(text)
            } catch (e: IllegalArgumentException) {
                code.error = e.message
                return@setOnClickListener
            }
            val cheats = cheatStore.load(entry.key)
            cheats += CheatStore.Cheat(name.text.toString().trim().ifEmpty { text }, text, true)
            cheatStore.save(entry.key, cheats)
            applyCheats()
            dialog.dismiss()
            showCheats()
        }
    }

    private fun slotLabel(slot: Int): String {
        val f = library.stateFile(entry, slot)
        val time = if (f.exists()) {
            val date = Date(f.lastModified())
            DateFormat.getMediumDateFormat(this).format(date) + "  " + DateFormat.getTimeFormat(this).format(date)
        } else {
            getString(R.string.slot_empty)
        }
        return getString(R.string.slot_label, slot, time)
    }

    private fun showSlots(save: Boolean) {
        val labels = (1..RomLibrary.STATE_SLOTS).map { slotLabel(it) }.toTypedArray()
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(if (save) R.string.menu_save_state else R.string.menu_load_state)
                .setItems(labels) { _, which -> if (save) saveSlot(which + 1) else loadSlot(which + 1) }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun saveSlot(slot: Int) {
        val s = session ?: return
        try {
            val data = s.withGameBoy { it.saveState() }
            RomLibrary.writeAtomically(library.stateFile(entry, slot), data)
            Toast.makeText(this, getString(R.string.state_saved, slot), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_save_write, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSlot(slot: Int) {
        val s = session ?: return
        val f = library.stateFile(entry, slot)
        if (!f.exists()) {
            Toast.makeText(this, R.string.slot_is_empty, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val data = f.readBytes()
            s.withGameBoy { it.loadState(data) }
            Toast.makeText(this, getString(R.string.state_loaded, slot), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: getString(R.string.error_state_load), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmReset() {
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_message)
                .setPositiveButton(R.string.menu_reset) { _, _ -> resetGame() }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun resetGame() {
        val s = session ?: return
        try {
            s.withGameBoy { old ->
                val battery = if (old.cart.hasBattery) old.cart.saveData() else null
                val fresh = createGameBoy()
                battery?.let { fresh.cart.loadSaveData(it) }
                applyCheats(fresh)
                s.replaceGameBoy(fresh)
            }
            library.stateFile(entry, 0).delete()
            applySettings()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: getString(R.string.error_rom_load), Toast.LENGTH_LONG).show()
        }
    }

    private fun showSettings() {
        dialogOpened()
        SettingsDialog.show(this, settings, onChanged = { applySettings() }, onDismiss = { dialogClosed() })
    }

    private fun showFatalError(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object {
        const val EXTRA_ROM = "rom_path"
    }
}
