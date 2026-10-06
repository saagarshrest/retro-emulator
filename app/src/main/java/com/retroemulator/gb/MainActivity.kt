package com.retroemulator.gb

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.retroemulator.gb.data.RomLibrary
import com.retroemulator.gb.data.RomLibrary.RomEntry
import com.retroemulator.gb.data.Settings
import com.retroemulator.gb.ui.EmulatorActivity
import com.retroemulator.gb.ui.SettingsDialog
import com.retroemulator.gb.ui.pixel.PixelBoxDrawable
import com.retroemulator.gb.ui.pixel.SkyDrawable
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Game library: lists imported ROMs and launches the emulator. */
class MainActivity : Activity() {

    private lateinit var library: RomLibrary
    private lateinit var settings: Settings
    private lateinit var listView: ListView
    private lateinit var emptyView: View
    private val adapter = RomAdapter()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var saveTarget: RomEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        library = RomLibrary(this)
        settings = Settings(this)

        val root = findViewById<View>(R.id.root)
        if (Build.VERSION.SDK_INT >= 30) {
            // Edge-to-edge is mandatory from API 35; opt in on 30-34 too so insets are handled the same way.
            if (Build.VERSION.SDK_INT < 35) {
                @Suppress("DEPRECATION")
                window.setDecorFitsSystemWindows(false)
            }
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        applyPixelStyle(root)
        listView = findViewById(R.id.list)
        emptyView = findViewById(R.id.empty)
        listView.adapter = adapter
        findViewById<View>(R.id.fab).setOnClickListener { pickRoms() }
        findViewById<View>(R.id.empty_add).setOnClickListener { pickRoms() }
        findViewById<View>(R.id.settings_button).setOnClickListener {
            SettingsDialog.show(this, settings, onChanged = {})
        }
        handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    /** Size of one art pixel for the UI chrome, rounded to whole screen pixels so edges stay crisp. */
    private val unit: Float by lazy { (3 * resources.displayMetrics.density).roundToInt().toFloat() }

    private fun applyPixelStyle(root: View) {
        root.background = SkyDrawable(unit)
        findViewById<View>(R.id.header_tab).background =
            PixelBoxDrawable(unit, 3, TAB_TOP, TAB_BOTTOM, shadow = SHADOW)
        findViewById<View>(R.id.settings_button).background =
            PixelBoxDrawable(unit, 2, BUTTON_TOP, BUTTON_BOTTOM, SHADOW, BUTTON_PRESSED)
        findViewById<View>(R.id.fab).background =
            PixelBoxDrawable(unit, 3, ORANGE_TOP, ORANGE_BOTTOM, SHADOW, ORANGE_PRESSED)
        findViewById<View>(R.id.empty_add).background =
            PixelBoxDrawable(unit, 2, ORANGE_TOP, ORANGE_BOTTOM, SHADOW, ORANGE_PRESSED)
    }

    private fun refresh() {
        io.execute {
            val items = library.list()
            main.post {
                adapter.items = items
                adapter.notifyDataSetChanged()
                emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                listView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun launch(entry: RomEntry) {
        startActivity(Intent(this, EmulatorActivity::class.java).putExtra(EmulatorActivity.EXTRA_ROM, entry.file.absolutePath))
    }

    // ------------------------------------------------------------------------------------------
    // Importing
    // ------------------------------------------------------------------------------------------

    private fun pickRoms() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(intent, REQ_PICK_ROMS)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        setIntent(Intent(this, MainActivity::class.java))
        importUris(listOf(uri), launchSingle = true)
    }

    private fun importUris(uris: List<Uri>, launchSingle: Boolean = false) {
        io.execute {
            val added = mutableListOf<RomEntry>()
            val errors = mutableListOf<String>()
            for (uri in uris) {
                try {
                    added += library.import(uri)
                } catch (e: Exception) {
                    errors += getString(R.string.import_failed, uri.lastPathSegment?.substringAfterLast('/') ?: "file", e.message ?: e.toString())
                }
            }
            main.post {
                refresh()
                when {
                    errors.isNotEmpty() -> AlertDialog.Builder(this)
                        .setMessage(errors.joinToString("\n\n"))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    added.size == 1 -> Toast.makeText(this, getString(R.string.game_added, added[0].title), Toast.LENGTH_SHORT).show()
                    added.size > 1 -> Toast.makeText(this, getString(R.string.games_added, added.size), Toast.LENGTH_SHORT).show()
                }
                if (launchSingle && added.size == 1) launch(added[0])
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_PICK_ROMS -> {
                val uris = mutableListOf<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
                } else {
                    data.data?.let { uris += it }
                }
                if (uris.isNotEmpty()) importUris(uris)
            }
            REQ_IMPORT_SAVE -> {
                val entry = saveTarget ?: return
                val uri = data.data ?: return
                runFileTask(R.string.save_imported) { library.importSave(entry, uri) }
            }
            REQ_EXPORT_SAVE -> {
                val entry = saveTarget ?: return
                val uri = data.data ?: return
                runFileTask(R.string.save_exported) { library.exportSave(entry, uri) }
            }
        }
    }

    private fun runFileTask(successRes: Int, task: () -> Unit) {
        io.execute {
            val error = try { task(); null } catch (e: Exception) { e.message ?: e.toString() }
            main.post {
                Toast.makeText(this, error ?: getString(successRes), Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Per-game options
    // ------------------------------------------------------------------------------------------

    private fun showOptions(entry: RomEntry, anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.option_play)
        menu.menu.add(0, 2, 1, R.string.option_import_save)
        if (library.saveFile(entry).exists()) {
            menu.menu.add(0, 3, 2, R.string.option_export_save)
            menu.menu.add(0, 4, 3, R.string.option_delete_save)
        }
        menu.menu.add(0, 5, 4, R.string.option_delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> launch(entry)
                2 -> {
                    saveTarget = entry
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                        REQ_IMPORT_SAVE,
                    )
                }
                3 -> {
                    saveTarget = entry
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/octet-stream")
                            .putExtra(Intent.EXTRA_TITLE, entry.key + ".sav"),
                        REQ_EXPORT_SAVE,
                    )
                }
                4 -> AlertDialog.Builder(this)
                    .setTitle(R.string.delete_save_title)
                    .setMessage(getString(R.string.delete_save_message, entry.title))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        library.deleteSaveData(entry)
                        Toast.makeText(this, R.string.save_deleted, Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                5 -> AlertDialog.Builder(this)
                    .setTitle(getString(R.string.delete_title, entry.title))
                    .setMessage(R.string.delete_message)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        library.delete(entry)
                        refresh()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            true
        }
        menu.show()
    }

    private inner class RomAdapter : BaseAdapter() {
        var items: List<RomEntry> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_rom, parent, false).also {
                it.findViewById<View>(R.id.card).background =
                    PixelBoxDrawable(unit, 2, CARD_TOP, CARD_BOTTOM, SHADOW, CARD_PRESSED)
            }
            val entry = items[position]
            view.findViewById<TextView>(R.id.title).text = entry.title

            val system = getString(
                when {
                    entry.cgbOnly -> R.string.system_gbc_only
                    entry.cgb -> R.string.system_gbc
                    else -> R.string.system_gb
                }
            )
            val now = System.currentTimeMillis()
            val played = when {
                entry.lastPlayed <= 0 -> getString(R.string.never_played)
                now - entry.lastPlayed < DateUtils.MINUTE_IN_MILLIS -> getString(R.string.played_just_now)
                else -> getString(
                    R.string.played_ago,
                    DateUtils.getRelativeTimeSpanString(entry.lastPlayed, now, DateUtils.MINUTE_IN_MILLIS),
                )
            }
            view.findViewById<TextView>(R.id.details).text = "$system  ·  $played"

            val badge = view.findViewById<TextView>(R.id.badge)
            badge.text = if (entry.cgb) "GBC" else "GB"
            val badgeColor = getColor(
                when {
                    entry.cgbOnly -> R.color.badge_gbc_only
                    entry.cgb -> R.color.badge_gbc
                    else -> R.color.badge_gb
                }
            )
            badge.background = PixelBoxDrawable(unit, 2, badgeColor, shadow = BADGE_SHADOW)
            badge.setTextColor(getColor(if (entry.cgb) android.R.color.white else R.color.badge_gb_text))

            val card = view.findViewById<View>(R.id.card)
            card.setOnClickListener { launch(entry) }
            card.setOnLongClickListener { showOptions(entry, it); true }
            view.findViewById<ImageButton>(R.id.more).setOnClickListener { showOptions(entry, it) }
            return view
        }
    }

    companion object {
        private const val REQ_PICK_ROMS = 1
        private const val REQ_IMPORT_SAVE = 2
        private const val REQ_EXPORT_SAVE = 3

        // Pixel-art palette shared with the handheld skin.
        private const val SHADOW = 0xFFE9A84E.toInt()
        private const val BADGE_SHADOW = 0x55000000
        private const val TAB_TOP = 0xFFFFEDB0.toInt()
        private const val TAB_BOTTOM = 0xFFFFD45E.toInt()
        private const val CARD_TOP = 0xFFFFF6D2.toInt()
        private const val CARD_BOTTOM = 0xFFFFE39A.toInt()
        private const val CARD_PRESSED = 0xFFFFD06A.toInt()
        private const val BUTTON_TOP = 0xFFFFE89A.toInt()
        private const val BUTTON_BOTTOM = 0xFFFFD45E.toInt()
        private const val BUTTON_PRESSED = 0xFFF6C043.toInt()
        private const val ORANGE_TOP = 0xFFFFC064.toInt()
        private const val ORANGE_BOTTOM = 0xFFFF9A3E.toInt()
        private const val ORANGE_PRESSED = 0xFFF0852A.toInt()
    }
}
