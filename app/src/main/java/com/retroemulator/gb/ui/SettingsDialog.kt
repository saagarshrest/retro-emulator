package com.retroemulator.gb.ui

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.retroemulator.gb.R
import com.retroemulator.gb.data.Palettes
import com.retroemulator.gb.data.Settings

/** Builds the settings dialog in code; [onChanged] runs after every change so a running game can apply it. */
object SettingsDialog {

    fun show(context: Context, settings: Settings, onChanged: () -> Unit, onDismiss: (() -> Unit)? = null): AlertDialog {
        val d = context.resources.displayMetrics.density
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        }

        fun header(textRes: Int) = content.addView(TextView(context).apply {
            setText(textRes)
            setTextColor(context.getColor(R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isAllCaps = true
            letterSpacing = 0.08f
            setPadding((16 * d).toInt(), (18 * d).toInt(), (16 * d).toInt(), (6 * d).toInt())
        })

        fun switch(textRes: Int, value: Boolean, set: (Boolean) -> Unit) = content.addView(Switch(context).apply {
            setText(textRes)
            isChecked = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(context.getColor(R.color.text_primary))
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            setOnCheckedChangeListener { _, checked -> set(checked); onChanged() }
        })

        fun slider(textRes: Int, min: Int, max: Int, value: Int, set: (Int) -> Unit) {
            val label = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(context.getColor(R.color.text_primary))
                setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), 0)
            }
            fun update(v: Int) { label.text = context.getString(textRes, v) }
            update(value)
            content.addView(label)
            content.addView(SeekBar(context).apply {
                this.max = max - min
                progress = value - min
                setPadding((24 * d).toInt(), (10 * d).toInt(), (24 * d).toInt(), (10 * d).toInt())
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        update(p + min)
                        if (fromUser) { set(p + min); onChanged() }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            })
        }

        fun choice(textRes: Int, summary: () -> String, onClick: (TextView) -> Unit) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
                isClickable = true
                isFocusable = true
                val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = ta.getDrawable(0)
                ta.recycle()
            }
            row.addView(TextView(context).apply {
                setText(textRes)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(context.getColor(R.color.text_primary))
            })
            val sub = TextView(context).apply {
                text = summary()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(context.getColor(R.color.text_secondary))
            }
            row.addView(sub)
            row.setOnClickListener { onClick(sub) }
            content.addView(row)
        }

        header(R.string.settings_audio)
        switch(R.string.settings_sound, settings.sound) { settings.sound = it }
        slider(R.string.settings_volume, 0, 100, settings.volume) { settings.volume = it }

        header(R.string.settings_display)
        choice(R.string.settings_palette, { Palettes.ALL[settings.paletteIndex].name }) { sub ->
            showPalettePicker(context, settings.paletteIndex) { idx ->
                settings.paletteIndex = idx
                sub.text = Palettes.ALL[idx].name
                onChanged()
            }
        }
        switch(R.string.settings_color_correction, settings.colorCorrection) { settings.colorCorrection = it }
        switch(R.string.settings_integer_scaling, settings.integerScaling) { settings.integerScaling = it }
        switch(R.string.settings_smooth, settings.smoothScaling) { settings.smoothScaling = it }
        switch(R.string.settings_show_fps, settings.showFps) { settings.showFps = it }

        header(R.string.settings_controls)
        switch(R.string.settings_haptics, settings.haptics) { settings.haptics = it }
        switch(R.string.settings_rumble, settings.rumble) { settings.rumble = it }

        header(R.string.settings_emulation)
        switch(R.string.settings_auto_resume, settings.autoResume) { settings.autoResume = it }
        choice(R.string.settings_ff_speed, { "${settings.fastForwardSpeed}×" }) { sub ->
            val speeds = Settings.FAST_FORWARD_SPEEDS
            val labels = speeds.map { "$it×" }.toTypedArray()
            AlertDialog.Builder(context)
                .setTitle(R.string.settings_ff_speed)
                .setSingleChoiceItems(labels, speeds.indexOf(settings.fastForwardSpeed)) { dlg, which ->
                    settings.fastForwardSpeed = speeds[which]
                    sub.text = labels[which]
                    onChanged()
                    dlg.dismiss()
                }
                .show()
        }
        switch(R.string.settings_force_dmg, settings.forceDmg) { settings.forceDmg = it }
        content.addView(TextView(context).apply {
            setText(R.string.settings_force_dmg_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), (8 * d).toInt())
        })

        val scroll = ScrollView(context).apply { addView(content) }
        return AlertDialog.Builder(context)
            .setTitle(R.string.settings)
            .setView(scroll)
            .setPositiveButton(R.string.done, null)
            .setOnDismissListener(DialogInterface.OnDismissListener { onDismiss?.invoke() })
            .show()
    }

    private fun showPalettePicker(context: Context, current: Int, onPick: (Int) -> Unit) {
        val d = context.resources.displayMetrics.density
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }
        lateinit var dialog: AlertDialog
        Palettes.ALL.forEachIndexed { index, palette ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (12 * d).toInt())
                val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = ta.getDrawable(0)
                ta.recycle()
                setOnClickListener { onPick(index); dialog.dismiss() }
            }
            for (c in palette.bg) {
                row.addView(View(context).apply {
                    background = GradientDrawable().apply { setColor(c); cornerRadius = 4 * d }
                }, LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()).apply { marginEnd = (4 * d).toInt() })
            }
            row.addView(TextView(context).apply {
                text = palette.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(context.getColor(if (index == current) R.color.accent else R.color.text_primary))
                setPadding((12 * d).toInt(), 0, 0, 0)
            })
            list.addView(row)
        }
        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_palette)
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
