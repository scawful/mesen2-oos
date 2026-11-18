package ca.mesen.oos

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

private object Pal {
    val BG = Color(0xFF0C121E)
    val SCRIM = Color(0xE00C121E)
    val PANEL = Color(0xFF161F2E)
    val PANEL2 = Color(0xFF1E2A3D)
    val LINE = Color(0xFF344258)
    val GOLD = Color(0xFFE8C35A)
    val TEAL = Color(0xFF4FB3A9)
    val TEXT = Color(0xFFE9EEF5)
    val DIM = Color(0xFF8C98AA)
    val INK = Color(0xFF1A1405)
}

/** One channel card on the Library shelf, with its channel states read from disk. */
private class RomCard(
    val entry: RomLibrary.Entry,
    val saveLabel: String,
    val source: String,
    val states: List<SaveStateSlots.Entry>,
    val canonicalPath: String?,
) {
    val file: File get() = entry.file
    val newest: SaveStateSlots.Entry? get() = states.filter { it.occupied }.maxByOrNull { it.modified }
    val stateCount: Int get() = states.count { it.occupied }
}

private class LibrarySnapshot(
    val cards: List<RomCard>,
    val currentCanonical: String?,
    val hasCurrent: Boolean,
)

class Confirm(val title: String, val body: String, val yes: String, val action: () -> Unit)

enum class Screen { CLOSED, LIBRARY, MENU }

enum class RailItem(val label: String) {
    RESUME("Resume"),
    SAVE("Save state"),
    LOAD("Load state"),
    ITEMS("Items & progress"),
    FAST("Fast forward"),
    SETTINGS("Settings"),
    LIBRARY("Library"),
}

private enum class Key { UP, DOWN, LEFT, RIGHT, A, B, X, Y, L, R, START }

/**
 * Unified Compose UI: channel ROM library + in-game quick menu (save states,
 * SRAM inventory + progression editor, bundled story presets, and settings).
 * All screens are navigated by controller (D-pad / A / B / L1 / R1 / Y / Start).
 */
class OracleUi internal constructor(
    private val host: Host,
    private val postMain: (Runnable) -> Unit,
) {
    constructor(host: Host) : this(host, mainPoster())

    interface Host {
        fun nativeReady(): Boolean
        fun romRoots(): Array<File>
        fun currentRomPath(): String?
        fun batterySaveLabel(rom: File): String
        fun romSourceLabel(rom: File): String
        fun statesDir(): File
        fun savesDir(): File
        fun checkpointData(): CheckpointData
        fun presetData(): PresetData
        fun socketSummary(): String
        /** UI became visible (pause the game) or was closed (resume it). */
        fun uiOpened()
        fun uiClosed()
        /** Seeds the battery save, loads the ROM and remembers it. Safe off main thread. */
        fun bootRom(path: String): Boolean
        fun resetConsole()
        fun requestHomeShortcut()
        fun isStickyFastForward(): Boolean
        fun setStickyFastForward(on: Boolean)
        fun setting(key: String): Boolean
        fun setSetting(key: String, on: Boolean)
        /** Runs a checkpoint or milestone preset warp after the UI has closed. */
        fun startCheckpoint(id: String)
        fun startPreset(id: String)
        fun toast(message: String)
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "oracle-ui").apply { isDaemon = true } }
    private val mismatchWarnings = SaveStateSlots.MismatchWarnings()
    private val cardsGen = AtomicLong(0)
    private val statesGen = AtomicLong(0)
    private val itemsGen = AtomicLong(0)
    private val metaGen = AtomicLong(0)
    private var libNavTick = 0L

    var screen by mutableStateOf(Screen.CLOSED); private set
    private var status by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var confirm by mutableStateOf<Confirm?>(null)
    private var confirmYes by mutableStateOf(false)

    // Library
    private var cards by mutableStateOf(listOf<RomCard>())
    private var loadingLibrary by mutableStateOf(false)
    private var currentRomCanonical by mutableStateOf<String?>(null)
    private var hasCurrentRom by mutableStateOf(false)
    private var libIndex by mutableIntStateOf(0)
    private var libRow by mutableIntStateOf(0)
    private var libAction by mutableIntStateOf(0)

    // Quick menu
    private var rail by mutableIntStateOf(0)
    private var inPanel by mutableStateOf(false)
    private var states by mutableStateOf(listOf<SaveStateSlots.Entry>())
    private var loadingStates by mutableStateOf(false)
    private var slotPage by mutableIntStateOf(0)
    private var slotIndex by mutableIntStateOf(0)
    private var listIndex by mutableIntStateOf(0)
    private var itemTab by mutableIntStateOf(0)
    private var bitIndex by mutableIntStateOf(0)
    private var itemRows by mutableStateOf(listOf<Inventory.Row>())
    private var itemValues by mutableStateOf(listOf<Int>())
    private var loadedItemTab by mutableStateOf("")
    private var loadingItems by mutableStateOf(false)
    private var activeSaveSlot by mutableIntStateOf(1)
    private var menuTitle by mutableStateOf("Oracle of Secrets")
    private var socketMeta by mutableStateOf("")
    private var fastOn by mutableStateOf(false)
    private var settingsTick by mutableIntStateOf(0)

    private val itemTabs = listOf(TAB_PRESETS) + Inventory.tabs()
    private val settingsRows = listOf(
        SettingRow.Toggle("hud", "Keep status bar visible"),
        SettingRow.Toggle("fit", "Fill screen"),
        SettingRow.Toggle("play", "Smooth play"),
        SettingRow.Toggle("gpu", "GPU blit"),
        SettingRow.Action("reset", "Reset console", "Restart the emulated console"),
        SettingRow.Action("home", "Add to Home", "Pin launcher shortcut"),
    )

    private sealed class SettingRow(val key: String, val label: String) {
        class Toggle(key: String, label: String) : SettingRow(key, label)
        class Action(key: String, label: String, val tag: String) : SettingRow(key, label)
    }

    val isOpen: Boolean get() = screen != Screen.CLOSED
    internal val isBusy: Boolean get() = busy
    internal val isLoadingLibrary: Boolean get() = loadingLibrary
    internal val isLoadingStates: Boolean get() = loadingStates
    internal val isLoadingItems: Boolean get() = loadingItems
    internal val currentStatus: String get() = status
    internal val currentMenuTitle: String get() = menuTitle
    internal val currentSocketMeta: String get() = socketMeta
    internal val currentActiveSaveSlot: Int get() = activeSaveSlot
    internal val cardChannels: List<String> get() = cards.map { it.entry.channel }
    internal val stateSlotsCount: Int get() = states.size
    internal val currentItemRows: List<Inventory.Row> get() = itemRows
    internal val currentItemValues: List<Int> get() = itemValues

    fun install(view: ComposeView) {
        view.setContent { Root() }
    }

    // ---- open / close ------------------------------------------------------

    fun openLibrary() {
        val wasClosed = screen == Screen.CLOSED
        if (cards.isNotEmpty() && currentRomCanonical != null) {
            val at = cards.indexOfFirst { it.canonicalPath == currentRomCanonical }
            libIndex = if (at >= 0) at else libIndex.coerceIn(0, cards.size - 1)
        } else if (cards.isEmpty()) {
            libIndex = 0
        }
        libRow = 0
        libAction = 0
        status = ""
        confirm = null
        screen = Screen.LIBRARY
        if (wasClosed) host.uiOpened()
        reloadCards(selectCurrent = true)
    }

    fun openMenu() {
        val wasClosed = screen == Screen.CLOSED
        rail = 0
        inPanel = false
        status = ""
        confirm = null
        fastOn = host.isStickyFastForward()
        screen = Screen.MENU
        if (wasClosed) host.uiOpened()
        refreshMenuMeta()
        refreshStates()
        refreshItems()
    }

    fun close() {
        if (screen == Screen.CLOSED) return
        screen = Screen.CLOSED
        confirm = null
        host.uiClosed()
    }

    // ---- input -------------------------------------------------------------

    /** Back/Mode button. */
    fun onBack() {
        if (!isOpen || busy) return
        if (confirm != null) {
            confirm = null
            return
        }
        handle(Key.B)
    }

    /** Pad key down. D-pad repeats move; other keys act once. */
    fun onKey(code: Int, repeat: Int): Boolean {
        if (!isOpen) return false
        val key = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> Key.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> Key.A
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> Key.B
            KeyEvent.KEYCODE_BUTTON_X -> Key.X
            KeyEvent.KEYCODE_BUTTON_Y -> Key.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> Key.L
            KeyEvent.KEYCODE_BUTTON_R1 -> Key.R
            KeyEvent.KEYCODE_BUTTON_START -> Key.START
            else -> return true
        }
        if (busy) return true
        val isMove = key == Key.UP || key == Key.DOWN || key == Key.LEFT || key == Key.RIGHT
        if (repeat > 0 && !isMove) return true
        handle(key)
        return true
    }

    private fun handle(key: Key) {
        val sheet = confirm
        if (sheet != null) {
            when (key) {
                Key.LEFT, Key.RIGHT -> confirmYes = !confirmYes
                Key.A -> {
                    confirm = null
                    if (confirmYes) sheet.action()
                }
                Key.B -> confirm = null
                else -> {}
            }
            return
        }
        when (screen) {
            Screen.LIBRARY -> libraryKey(key)
            Screen.MENU -> menuKey(key)
            Screen.CLOSED -> {}
        }
    }

    private fun ask(title: String, body: String, yes: String, action: () -> Unit) {
        confirmYes = false
        confirm = Confirm(title, body, yes, action)
    }

    /** Slots 1-10, plus Mesen's auto-save (slot 11) when it exists. */
    private fun slotList(pathFor: (Int) -> String?): List<SaveStateSlots.Entry> {
        val fixed = (1..SaveStateSlots.SLOT_COUNT).map { SaveStateSlots.inspect(it, pathFor(it)) }
        val auto = SaveStateSlots.inspect(SaveStateSlots.AUTO_SLOT, pathFor(SaveStateSlots.AUTO_SLOT))
        return if (auto.occupied) listOf(auto) + fixed else fixed
    }

    private fun slotName(slot: Int) = if (slot == SaveStateSlots.AUTO_SLOT) "auto-save" else "slot $slot"

    private fun currentBuildId(): String {
        val path = host.currentRomPath() ?: return ""
        return RomLibrary.buildId(File(path))
    }

    /** Native loadState() stops at slot 10, so the auto-save loads by file path. */
    private fun loadEntry(entry: SaveStateSlots.Entry): Boolean =
        if (entry.slot == SaveStateSlots.AUTO_SLOT) {
            entry.file != null && MesenNative.loadStateFile(entry.file.path)
        } else {
            MesenNative.loadState(entry.slot)
        }

    // ---- library -----------------------------------------------------------

    private class LibAction(val label: String, val run: () -> Unit)

    private fun reloadCards(selectCurrent: Boolean = false) {
        val gen = cardsGen.incrementAndGet()
        val navTick = libNavTick
        if (cards.isEmpty()) {
            loadingLibrary = true
        }
        worker.execute {
            val snapshot = scanCardsSnapshot()
            postMain(Runnable {
                if (gen != cardsGen.get()) return@Runnable
                applyCardsSnapshot(snapshot, selectCurrent && libNavTick == navTick)
            })
        }
    }

    private fun scanCardsSnapshot(): LibrarySnapshot {
        val dir = host.statesDir()
        val currentPath = host.currentRomPath()
        val currentCanon = canonical(currentPath)
        val scanned = RomLibrary.scan(host.romRoots()).map { entry ->
            val base = RomLibrary.baseName(entry.file)
            val slots = slotList { slot -> File(dir, "${base}_$slot.mss").path }
            RomCard(
                entry,
                host.batterySaveLabel(entry.file),
                host.romSourceLabel(entry.file),
                slots,
                canonical(entry.file.path),
            )
        }
        return LibrarySnapshot(scanned, currentCanon, currentPath != null)
    }

    private fun applyCardsSnapshot(snapshot: LibrarySnapshot, selectCurrent: Boolean) {
        cards = snapshot.cards
        currentRomCanonical = snapshot.currentCanonical
        hasCurrentRom = snapshot.hasCurrent
        loadingLibrary = false
        if (snapshot.cards.isEmpty()) {
            libIndex = 0
        } else if (selectCurrent) {
            val at = snapshot.cards.indexOfFirst { it.canonicalPath == snapshot.currentCanonical }
            libIndex = if (at >= 0) at else libIndex.coerceIn(0, snapshot.cards.size - 1)
        } else {
            libIndex = libIndex.coerceIn(0, snapshot.cards.size - 1)
        }
    }

    private fun isCurrent(card: RomCard): Boolean =
        host.nativeReady() && currentRomCanonical != null && card.canonicalPath == currentRomCanonical

    private fun libActions(card: RomCard): List<LibAction> {
        val out = ArrayList<LibAction>()
        val current = isCurrent(card)
        if (current) out.add(LibAction("Continue") { close() })
        val newest = card.newest
        if (newest != null) out.add(LibAction("Resume last state") { resumeState(card, newest) })
        if (current) {
            out.add(LibAction("Restart from save") {
                ask("Restart ${card.entry.channel}?", "Progress since your last in-game save is lost. Save states stay.", "Restart") {
                    boot(card)
                }
            })
        } else {
            out.add(LibAction("Start from save") { boot(card) })
        }
        if (card.entry.previous != null) {
            val prevLabel = if (card.entry.previousBuild.isNotEmpty()) {
                "Roll back ${card.entry.previousBuild}"
            } else {
                "Roll back"
            }
            out.add(LibAction(prevLabel) { rollbackChannel(card) })
        }
        if (card.entry.olderCopies.isNotEmpty()) {
            out.add(LibAction("Prune ${card.entry.olderCopies.size} old") { pruneOlderCopies(card) })
        }
        return out
    }

    private fun boot(card: RomCard) {
        if (!host.nativeReady()) {
            status = "Emulator is still starting."
            return
        }
        val path = card.file.path
        val canon = card.canonicalPath
        work("Loading ${card.entry.channel}…", {
            if (host.bootRom(path)) null else "Could not load ${card.file.name}."
        }) { problem ->
            if (problem == null) {
                currentRomCanonical = canon
                hasCurrentRom = true
                close()
            } else {
                status = problem
            }
        }
    }

    private fun resumeState(card: RomCard, entry: SaveStateSlots.Entry) {
        if (!host.nativeReady()) {
            status = "Emulator is still starting."
            return
        }
        val needBoot = !isCurrent(card)
        val path = card.file.path
        val canon = card.canonicalPath
        val name = slotName(entry.slot)
        var warning: String? = null
        work("Loading $name…", {
            if (needBoot && !host.bootRom(path)) {
                return@work "Could not load ${card.file.name}."
            }
            warning = mismatchWarnings.check(entry, card.entry.build)
            Thread.sleep(150)
            if (loadEntry(entry)) null else "The $name did not load."
        }) { problem ->
            if (problem == null) {
                if (needBoot) {
                    currentRomCanonical = canon
                    hasCurrentRom = true
                }
                if (warning != null) host.toast(warning!!)
                close()
            } else {
                status = problem
            }
        }
    }

    private fun rollbackChannel(card: RomCard) {
        val wasCurrent = isCurrent(card)
        val cardCanon = card.canonicalPath
        val cardPath = card.file.path
        val gen = cardsGen.incrementAndGet()
        var snapshot: LibrarySnapshot? = null
        var toBuild = "previous build"
        work("Rolling back ${card.entry.channel}…", {
            val problem = RomLibrary.rollback(card.entry)
            if (problem != null) return@work problem
            val scanned = scanCardsSnapshot()
            snapshot = scanned
            val updated = scanned.cards.firstOrNull { it.canonicalPath == cardCanon }
            toBuild = updated?.entry?.build?.ifEmpty { "previous build" } ?: "previous build"
            if (wasCurrent && host.nativeReady()) {
                host.bootRom(cardPath)
            }
            null
        }) { problem ->
            val scanned = snapshot
            if (scanned != null && gen == cardsGen.get()) {
                applyCardsSnapshot(scanned, selectCurrent = false)
            }
            if (problem != null) {
                status = problem
            } else {
                libAction = 0
                status = "Rolled back ${card.entry.channel} to $toBuild."
            }
        }
    }

    private fun pruneOlderCopies(card: RomCard) {
        val gen = cardsGen.incrementAndGet()
        var snapshot: LibrarySnapshot? = null
        var summary = ""
        work("Removing older copies…", {
            val cleanup = RomLibrary.removeOlderCopies(card.entry, host.statesDir(), host.savesDir())
            snapshot = scanCardsSnapshot()
            summary = cleanup.summary(card.entry.channel)
            null
        }) { problem ->
            val scanned = snapshot
            if (scanned != null && gen == cardsGen.get()) {
                applyCardsSnapshot(scanned, selectCurrent = false)
            }
            if (problem != null) {
                status = problem
            } else {
                libAction = 0
                status = summary
            }
        }
    }

    private fun libraryKey(key: Key) {
        val card = cards.getOrNull(libIndex)
        val actions = card?.let { libActions(it) } ?: emptyList()
        when (key) {
            Key.LEFT -> if (libRow == 0) { libNavTick++; libIndex = (libIndex - 1).coerceAtLeast(0) } else libAction = (libAction - 1).coerceAtLeast(0)
            Key.RIGHT -> if (libRow == 0) { libNavTick++; libIndex = (libIndex + 1).coerceAtMost((cards.size - 1).coerceAtLeast(0)) }
                else libAction = (libAction + 1).coerceAtMost((actions.size - 1).coerceAtLeast(0))
            Key.DOWN -> if (actions.isNotEmpty()) { libNavTick++; libRow = 1; libAction = libAction.coerceIn(0, actions.size - 1) }
            Key.UP -> libRow = 0
            Key.A -> actions.getOrNull(if (libRow == 0) 0 else libAction)?.run?.invoke()
            Key.START -> actions.firstOrNull()?.run?.invoke()
            Key.B -> if (libRow == 1) libRow = 0
                else if (host.nativeReady() && hasCurrentRom) close()
            else -> {}
        }
        if (key == Key.LEFT || key == Key.RIGHT) {
            if (libRow == 0) libAction = 0
            status = ""
        }
    }

    // ---- quick menu --------------------------------------------------------

    private fun refreshMenuMeta() {
        val gen = metaGen.incrementAndGet()
        worker.execute {
            val path = host.currentRomPath()
            val title = computeTitle(path)
            val canon = canonical(path)
            val summary = host.socketSummary()
            val slot = if (host.nativeReady()) Progression.activeSlot(Progression.LIVE) else 1
            postMain(Runnable {
                if (gen != metaGen.get()) return@Runnable
                menuTitle = title
                currentRomCanonical = canon
                hasCurrentRom = path != null
                socketMeta = summary
                activeSaveSlot = slot
            })
        }
    }

    private fun loadStatesSnapshot(): List<SaveStateSlots.Entry> {
        if (!host.nativeReady()) {
            return slotList { null }
        }
        // Native saveStatePath() rejects slots above the handheld count, so the
        // auto-save path is derived from slot 1's ("<rom>_1.mss" -> "<rom>_11.mss").
        return slotList { slot ->
            if (slot == SaveStateSlots.AUTO_SLOT) {
                MesenNative.saveStatePath(1)?.replace(Regex("_1\\.mss$"), "_${SaveStateSlots.AUTO_SLOT}.mss")
            } else {
                MesenNative.saveStatePath(slot)
            }
        }
    }

    private fun refreshStates() {
        val gen = statesGen.incrementAndGet()
        if (states.isEmpty()) {
            loadingStates = true
        }
        worker.execute {
            val loaded = loadStatesSnapshot()
            postMain(Runnable {
                if (gen != statesGen.get()) return@Runnable
                states = loaded
                loadingStates = false
            })
        }
    }

    private fun activeItemTabName(): String = itemTabs.getOrElse(itemTab) { TAB_PRESETS }

    private fun refreshItems() {
        val tab = activeItemTabName()
        val gen = itemsGen.incrementAndGet()
        if (tab == TAB_PRESETS) {
            itemRows = emptyList()
            itemValues = emptyList()
            loadedItemTab = TAB_PRESETS
            loadingItems = false
            worker.execute {
                val slot = if (host.nativeReady()) Progression.activeSlot(Progression.LIVE) else 1
                postMain(Runnable {
                    if (gen != itemsGen.get()) return@Runnable
                    activeSaveSlot = slot
                })
            }
            return
        }
        val rows = Inventory.rows(tab, host.checkpointData().names)
        if (loadedItemTab != tab) {
            itemRows = rows
            itemValues = emptyList()
            loadingItems = true
        }
        worker.execute {
            val values = if (host.nativeReady()) {
                rows.map { Inventory.read(it, Inventory.LIVE) }
            } else {
                rows.map { if (it.kind == Inventory.Kind.ACTION) 0 else -1 }
            }
            val slot = if (host.nativeReady()) Progression.activeSlot(Progression.LIVE) else activeSaveSlot
            postMain(Runnable {
                if (gen != itemsGen.get() || activeItemTabName() != tab) return@Runnable
                itemRows = rows
                itemValues = values
                loadedItemTab = tab
                loadingItems = false
                activeSaveSlot = slot
            })
        }
    }

    private val railItem: RailItem get() = RailItem.values()[rail]

    private fun hasPanel(item: RailItem) = when (item) {
        RailItem.SAVE, RailItem.LOAD, RailItem.ITEMS, RailItem.SETTINGS -> true
        else -> false
    }

    private fun enterPanel() {
        val item = railItem
        when (item) {
            RailItem.SAVE, RailItem.LOAD -> { refreshStates(); slotIndex = 0 }
            RailItem.ITEMS -> { listIndex = 0; bitIndex = 0; refreshItems() }
            RailItem.SETTINGS -> { listIndex = 0; refreshMenuMeta() }
            else -> listIndex = 0
        }
        inPanel = true
        status = ""
    }

    private fun activateRail() {
        when (railItem) {
            RailItem.RESUME -> close()
            RailItem.FAST -> {
                fastOn = !fastOn
                host.setStickyFastForward(fastOn)
                status = if (fastOn) "Fast forward stays on after you resume." else "Fast forward off."
            }
            RailItem.LIBRARY -> openLibrary()
            else -> enterPanel()
        }
    }

    private fun menuKey(key: Key) {
        if (key == Key.START) {
            close()
            return
        }
        if (!inPanel) {
            val count = RailItem.values().size
            when (key) {
                Key.UP -> rail = (rail - 1 + count) % count
                Key.DOWN -> rail = (rail + 1) % count
                Key.A -> activateRail()
                Key.RIGHT -> if (hasPanel(railItem)) enterPanel()
                Key.B -> close()
                else -> {}
            }
            if (key == Key.UP || key == Key.DOWN) {
                status = ""
                when (railItem) {
                    RailItem.ITEMS -> refreshItems()
                    RailItem.SAVE, RailItem.LOAD -> refreshStates()
                    RailItem.SETTINGS -> refreshMenuMeta()
                    else -> {}
                }
            }
            return
        }
        if (key == Key.B) {
            inPanel = false
            return
        }
        when (railItem) {
            RailItem.SAVE, RailItem.LOAD -> slotKey(key)
            RailItem.ITEMS -> itemsKey(key)
            RailItem.SETTINGS -> listKey(key, settingsRows.size) {
                activateSetting(settingsRows[listIndex])
            }
            else -> {}
        }
    }

    private fun activateSetting(row: SettingRow) {
        when (row) {
            is SettingRow.Toggle -> {
                host.setSetting(row.key, !host.setting(row.key))
                settingsTick++
            }
            is SettingRow.Action -> when (row.key) {
                "reset" -> {
                    if (!host.nativeReady()) {
                        status = "Emulator is still starting."
                        return
                    }
                    ask("Reset console?", "Unsaved progress since your last in-game save will be lost.", "Reset") {
                        work("Resetting console…", {
                            host.resetConsole()
                            null
                        }) { problem ->
                            if (problem == null) close() else status = problem
                        }
                    }
                }
                "home" -> {
                    host.requestHomeShortcut()
                    status = "Home shortcut requested."
                }
            }
        }
    }

    private fun listKey(key: Key, size: Int, onA: () -> Unit) {
        when (key) {
            Key.UP -> listIndex = (listIndex - 1).coerceAtLeast(0)
            Key.DOWN -> listIndex = (listIndex + 1).coerceAtMost((size - 1).coerceAtLeast(0))
            Key.LEFT -> inPanel = false
            Key.A -> if (size > 0) onA()
            else -> {}
        }
    }

    private val pageCount get() = maxOf(1, (states.size + 3) / 4)
    private val pageSlots get() = states.drop(slotPage * 4).take(4)

    private fun slotKey(key: Key) {
        val n = pageSlots.size
        when (key) {
            Key.LEFT -> if (slotIndex % 2 == 0) inPanel = false else slotIndex--
            Key.RIGHT -> if (slotIndex % 2 == 0 && slotIndex + 1 < n) slotIndex++
            Key.UP -> if (slotIndex >= 2) slotIndex -= 2
            Key.DOWN -> if (slotIndex + 2 < n) slotIndex += 2
            Key.L -> { slotPage = (slotPage - 1 + pageCount) % pageCount; slotIndex = 0 }
            Key.R -> { slotPage = (slotPage + 1) % pageCount; slotIndex = 0 }
            Key.A -> pageSlots.getOrNull(slotIndex)?.let { if (railItem == RailItem.SAVE) saveSlot(it) else loadSlot(it) }
            Key.Y -> pageSlots.getOrNull(slotIndex)?.let { deleteSlot(it) }
            else -> {}
        }
    }

    private fun saveSlot(entry: SaveStateSlots.Entry) {
        if (!host.nativeReady()) {
            status = "Emulator is still starting."
            return
        }
        val slot = entry.slot
        if (slot == SaveStateSlots.AUTO_SLOT) {
            status = "The auto-save is written by the emulator. Pick slot 1–10."
            return
        }
        val doSave = {
            val gen = statesGen.incrementAndGet()
            var loaded: List<SaveStateSlots.Entry>? = null
            var build = ""
            work("Saving slot $slot…", {
                build = currentBuildId()
                if (!MesenNative.saveState(slot)) {
                    "Slot $slot did not save."
                } else {
                    val path = MesenNative.saveStatePath(slot)
                    if (path != null && build.isNotEmpty()) {
                        SaveStateSlots.writeBuild(File(path), build)
                    }
                    loaded = loadStatesSnapshot()
                    null
                }
            }) { problem ->
                val list = loaded
                if (list != null && gen == statesGen.get()) {
                    states = list
                    loadingStates = false
                }
                status = problem ?: if (build.isNotEmpty()) "Saved slot $slot ($build)." else "Saved slot $slot."
            }
        }
        if (entry.occupied) ask("Overwrite slot $slot?", "Replaces ${caption(entry)}.", "Overwrite", doSave) else doSave()
    }

    private fun loadSlot(entry: SaveStateSlots.Entry) {
        if (!host.nativeReady()) {
            status = "Emulator is still starting."
            return
        }
        if (!entry.occupied) {
            status = "Slot ${entry.slot} is empty."
            return
        }
        val name = slotName(entry.slot)
        var warning: String? = null
        work("Loading $name…", {
            warning = mismatchWarnings.check(entry, currentBuildId())
            if (loadEntry(entry)) null else "The $name did not load."
        }) { problem ->
            if (problem == null) {
                if (warning != null) host.toast(warning!!)
                close()
            } else {
                status = problem
            }
        }
    }

    private fun deleteSlot(entry: SaveStateSlots.Entry) {
        val file = entry.file
        if (!entry.occupied || file == null) return
        ask("Delete slot ${entry.slot}?", "Removes ${caption(entry)}. This cannot be undone.", "Delete") {
            val gen = statesGen.incrementAndGet()
            var loaded: List<SaveStateSlots.Entry>? = null
            var ok = false
            work("Deleting slot ${entry.slot}…", {
                ok = SaveStateSlots.delete(file)
                loaded = loadStatesSnapshot()
                null
            }) { problem ->
                val list = loaded
                if (list != null && gen == statesGen.get()) {
                    states = list
                    loadingStates = false
                }
                status = problem ?: if (ok) "Deleted slot ${entry.slot}." else "Could not delete slot ${entry.slot}."
            }
        }
    }

    private fun runCheckpoint(cp: Progression.Checkpoint) {
        if (!host.nativeReady()) {
            status = "Emulator is still starting."
            return
        }
        val slot = activeSaveSlot
        ask(
            "Warp to ${cp.label}?",
            "Replaces save slot $slot progress up to beat ${cp.beat}. Items not named by the preset stay as they are.",
            "Warp",
        ) {
            work("Checking game state…", {
                Progression.idleProblem(Progression.LIVE)
            }) { problem ->
                if (problem != null) {
                    status = problem
                } else {
                    close()
                    host.startCheckpoint(cp.id)
                }
            }
        }
    }

    private fun runPreset(preset: PresetData.Preset) {
        if (!host.nativeReady()) {
            status = "Emulator is still starting."
            return
        }
        if (preset.inPlace) {
            val tab = activeItemTabName()
            val rows = itemRows
            val gen = itemsGen.incrementAndGet()
            var applyMsg = ""
            var refreshedValues: List<Int>? = null
            var slot = activeSaveSlot
            work("Applying ${preset.name}…", {
                applyMsg = PresetData.applyInPlace(preset, Progression.LIVE)
                slot = Progression.activeSlot(Progression.LIVE)
                if (tab != TAB_PRESETS) {
                    refreshedValues = rows.map { Inventory.read(it, Inventory.LIVE) }
                }
                null
            }) { problem ->
                activeSaveSlot = slot
                val vals = refreshedValues
                if (vals != null && gen == itemsGen.get() && activeItemTabName() == tab) {
                    itemValues = vals
                    loadedItemTab = tab
                    loadingItems = false
                }
                status = problem ?: applyMsg
            }
            return
        }
        work("Checking game state…", {
            Progression.idleProblem(Progression.LIVE)
        }) { problem ->
            if (problem != null) {
                status = problem
            } else {
                close()
                host.startPreset(preset.id)
            }
        }
    }

    private fun itemsKey(key: Key) {
        if (key == Key.L || key == Key.R) {
            val n = itemTabs.size
            itemTab = if (key == Key.L) (itemTab - 1 + n) % n else (itemTab + 1) % n
            listIndex = 0
            bitIndex = 0
            status = ""
            refreshItems()
            return
        }
        val tab = activeItemTabName()
        if (tab == TAB_PRESETS) {
            val presets = host.presetData().presets
            val cps = host.checkpointData().checkpoints
            val total = presets.size + cps.size
            listKey(key, total) {
                if (listIndex < presets.size) runPreset(presets[listIndex])
                else runCheckpoint(cps[listIndex - presets.size])
            }
            return
        }
        val rows = itemRows
        when (key) {
            Key.UP -> listIndex = (listIndex - 1).coerceAtLeast(0)
            Key.DOWN -> listIndex = (listIndex + 1).coerceAtMost((rows.size - 1).coerceAtLeast(0))
            Key.LEFT, Key.RIGHT -> {
                val row = rows.getOrNull(listIndex)
                if (row == null || row.kind == Inventory.Kind.ACTION
                        || row.kind == Inventory.Kind.FLAG || row.kind == Inventory.Kind.SONG) {
                    if (key == Key.LEFT) inPanel = false
                    return
                }
                if (row.kind == Inventory.Kind.BITS) {
                    bitIndex = if (key == Key.LEFT) (bitIndex + 1).coerceAtMost(7) else (bitIndex - 1).coerceAtLeast(0)
                    return
                }
                if (!host.nativeReady()) {
                    status = "Emulator is still starting."
                    return
                }
                val dir = if (key == Key.LEFT) -1 else 1
                val gen = itemsGen.incrementAndGet()
                var resultMsg: String? = null
                var refreshedValues: List<Int>? = null
                work("Updating ${row.label}…", {
                    resultMsg = Inventory.step(row, Inventory.LIVE, dir)
                    refreshedValues = rows.map { Inventory.read(it, Inventory.LIVE) }
                    null
                }) { problem ->
                    val vals = refreshedValues
                    if (vals != null && gen == itemsGen.get() && activeItemTabName() == tab) {
                        itemValues = vals
                        loadedItemTab = tab
                        loadingItems = false
                    }
                    if (problem != null) {
                        status = problem
                    } else if (resultMsg != null) {
                        status = resultMsg!!
                    }
                }
            }
            Key.A -> {
                val row = rows.getOrNull(listIndex) ?: return
                if (!host.nativeReady()) {
                    status = "Emulator is still starting."
                    return
                }
                val bit = bitIndex
                val gen = itemsGen.incrementAndGet()
                var toggleMsg = ""
                var refreshedValues: List<Int>? = null
                work("Updating ${row.label}…", {
                    toggleMsg = Inventory.toggle(row, Inventory.LIVE, bit)
                    refreshedValues = rows.map { Inventory.read(it, Inventory.LIVE) }
                    null
                }) { problem ->
                    val vals = refreshedValues
                    if (vals != null && gen == itemsGen.get() && activeItemTabName() == tab) {
                        itemValues = vals
                        loadedItemTab = tab
                        loadingItems = false
                    }
                    status = problem ?: toggleMsg
                }
            }
            else -> {}
        }
    }

    private fun work(progress: String, task: () -> String?, done: (String?) -> Unit) {
        busy = true
        status = progress
        worker.execute {
            val result = try {
                task()
            } catch (e: Exception) {
                "Failed: ${e.message ?: e.javaClass.simpleName}"
            }
            postMain(Runnable {
                busy = false
                status = ""
                done(result)
            })
        }
    }

    // ---- drawing -----------------------------------------------------------

    @Composable
    private fun Root() {
        when (screen) {
            Screen.CLOSED -> return
            Screen.LIBRARY -> LibraryScreen()
            Screen.MENU -> MenuScreen()
        }
        confirm?.let { ConfirmSheet(it) }
    }

    @Composable
    private fun LibraryScreen() {
        val listState = rememberLazyListState()
        LaunchedEffect(libIndex) { listState.animateScrollToItem((libIndex - 1).coerceAtLeast(0)) }
        val card = cards.getOrNull(libIndex)
        val actions = card?.let { libActions(it) } ?: emptyList()
        Column(Modifier.fillMaxSize().background(Pal.BG).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Txt("ORACLE OF SECRETS", 11.sp, Pal.GOLD, bold = true, italic = true)
                    Txt("ROM library", 22.sp, Pal.TEXT, bold = true, italic = true)
                }
                Txt("${cards.size} channels · ${clock(System.currentTimeMillis())}", 12.sp, Pal.DIM, bold = true)
            }
            Spacer(Modifier.height(10.dp))
            if (cards.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(232.dp).clip(RoundedCornerShape(10.dp)).background(Pal.PANEL), Alignment.Center) {
                    Txt(
                        if (loadingLibrary) "Scanning ROMs…" else "No ROMs found in files/Roms/, OracleOfSecrets/, or snes/.",
                        13.sp,
                        Pal.DIM,
                    )
                }
            } else {
                LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(232.dp)) {
                    itemsIndexed(cards) { i, c -> RomCardView(c, i == libIndex, libRow == 0) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp)).background(Pal.PANEL)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (card != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(card.entry.channel, 16.sp, Pal.TEXT, bold = true, italic = true)
                        if (card.entry.build.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Txt(card.entry.build, 16.sp, Pal.GOLD, bold = true, italic = true)
                        }
                        if (card.entry.pinned) {
                            Spacer(Modifier.width(8.dp))
                            Badge("PINNED", Pal.GOLD)
                        }
                        if (isCurrent(card)) {
                            Spacer(Modifier.width(8.dp))
                            Badge("RUNNING", Pal.TEAL)
                        }
                    }
                    Txt(cardDetail(card), 11.sp, Pal.DIM, bold = true)
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        actions.forEachIndexed { i, a ->
                            val on = (libRow == 1 && i == libAction) || (libRow == 0 && i == 0)
                            ActionButton(a.label, on, libRow == 1 && i == libAction, Modifier.weight(1f))
                        }
                    }
                }
            }
            HintBar(
                if (status.isNotEmpty()) status else null,
                listOf("A / START" to "Play", "D-PAD" to "Browse") +
                    if (host.nativeReady() && hasCurrentRom) listOf("B" to "Back to game") else emptyList(),
            )
        }
    }

    @Composable
    private fun RomCardView(card: RomCard, selected: Boolean, rowFocused: Boolean) {
        val newest = card.newest
        val preview = rememberPreview(newest?.file, newest?.modified ?: 0L)
        val borderColor = if (selected) (if (rowFocused) Pal.GOLD else Pal.DIM) else Color.Transparent
        Column(
            Modifier.width(212.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                .background(if (selected) Pal.PANEL2 else Pal.PANEL)
                .border(2.dp, borderColor, RoundedCornerShape(10.dp)).padding(6.dp),
        ) {
            Thumb(preview, Modifier.fillMaxWidth().height(175.dp), "No states yet")
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Txt(card.entry.channel, 15.sp, Pal.TEXT, bold = true, italic = true) }
                if (card.entry.build.isNotEmpty()) Txt(card.entry.build, 12.sp, Pal.GOLD, bold = true, italic = true)
            }
            Txt(cardSubtitle(card), 11.sp, Pal.DIM, bold = true)
        }
    }

    @Composable
    private fun MenuScreen() {
        val item = railItem
        Column(Modifier.fillMaxSize().background(Pal.SCRIM)) {
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(206.dp).fillMaxHeight().background(Pal.PANEL).padding(horizontal = 10.dp)) {
                    Spacer(Modifier.height(12.dp))
                    Txt("PAUSED", 11.sp, Pal.GOLD, bold = true, italic = true)
                    Txt(menuTitle, 17.sp, Pal.TEXT, bold = true)
                    Spacer(Modifier.height(10.dp))
                    RailItem.values().forEachIndexed { i, r -> RailRow(r, i == rail) }
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Txt(item.label, 18.sp, Pal.TEXT, bold = true, italic = true) }
                        Txt(panelMeta(item), 12.sp, Pal.DIM, bold = true)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (item) {
                            RailItem.SAVE, RailItem.LOAD -> SlotGrid(item == RailItem.SAVE)
                            RailItem.ITEMS -> ItemsPanel()
                            RailItem.SETTINGS -> SettingsPanel()
                            else -> Txt(blurb(item), 13.sp, Pal.DIM)
                        }
                    }
                }
            }
            HintBar(if (status.isNotEmpty()) status else null, menuHints(item))
        }
    }

    @Composable
    private fun RailRow(item: RailItem, selected: Boolean) {
        val focused = selected && !inPanel
        Row(
            Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(8.dp))
                .background(if (selected) Pal.PANEL2 else Color.Transparent)
                .border(2.dp, if (focused) Pal.GOLD else Color.Transparent, RoundedCornerShape(8.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).height(22.dp).background(if (selected) Pal.GOLD else Color.Transparent))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) { Txt(item.label, 15.sp, Pal.TEXT, bold = true, italic = selected) }
            if (item == RailItem.FAST) {
                Badge(if (fastOn) "×3" else "OFF", if (fastOn) Pal.TEAL else Pal.LINE)
                Spacer(Modifier.width(8.dp))
            }
        }
    }

    @Composable
    private fun SlotGrid(saving: Boolean) {
        if (states.isEmpty() && loadingStates) {
            Txt("Loading save states…", 13.sp, Pal.DIM)
            return
        }
        val slots = pageSlots
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in 0 until 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (col in 0 until 2) {
                        val i = row * 2 + col
                        val entry = slots.getOrNull(i)
                        if (entry == null) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            SlotCard(entry, inPanel && i == slotIndex, saving, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SlotCard(entry: SaveStateSlots.Entry, selected: Boolean, saving: Boolean, modifier: Modifier) {
        val preview = rememberPreview(if (entry.occupied) entry.file else null, entry.modified)
        Column(
            modifier.height(184.dp).clip(RoundedCornerShape(10.dp)).background(Pal.PANEL)
                .border(2.dp, if (selected) Pal.GOLD else Pal.LINE, RoundedCornerShape(10.dp)).padding(6.dp),
        ) {
            Thumb(preview, Modifier.fillMaxWidth().height(146.dp), if (saving) "+" else "Empty", big = saving)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(if (entry.slot == SaveStateSlots.AUTO_SLOT) "Auto  " else "${entry.slot}  ", 12.sp, Pal.GOLD, bold = true, italic = true)
                Box(Modifier.weight(1f)) {
                    Txt(caption(entry), 12.sp, if (entry.occupied) Pal.TEXT else Pal.DIM, bold = true)
                }
                if (entry.occupied && entry.build.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Txt(entry.build, 11.sp, Pal.TEAL, bold = true)
                }
            }
        }
    }

    @Composable
    private fun ItemsPanel() {
        val state = rememberLazyListState()
        LaunchedEffect(listIndex, inPanel, itemTab) {
            if (inPanel) state.animateScrollToItem((listIndex - 3).coerceAtLeast(0))
        }
        val tab = activeItemTabName()
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemTabs.forEachIndexed { i, g ->
                    val on = i == itemTab
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Pal.GOLD else Pal.PANEL2)
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    ) { Txt(g, 12.sp, if (on) Pal.INK else Pal.DIM, bold = true) }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (tab == TAB_PRESETS) {
                val presetData = host.presetData()
                val data = host.checkpointData()
                if (presetData.presets.isEmpty() && data.checkpoints.isEmpty()) {
                    Txt(presetData.error ?: data.error ?: "No presets available.", 13.sp, Pal.DIM)
                } else {
                    val base = presetData.presets.size
                    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(presetData.presets) { i, preset ->
                            ListRow(
                                preset.label,
                                preset.displayTag(),
                                inPanel && i == listIndex,
                                lead = "${preset.beat}",
                                tagColor = if (preset.inPlace) Pal.TEAL else Pal.GOLD,
                            )
                        }
                        itemsIndexed(data.checkpoints) { i, cp ->
                            ListRow(
                                cp.label,
                                if (cp.kind == Progression.Kind.AREA) "area" else "door",
                                inPanel && (base + i) == listIndex,
                                lead = "${cp.beat}",
                            )
                        }
                    }
                }
            } else {
                LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(itemRows) { i, row ->
                        val value = itemValues.getOrElse(i) { -1 }
                        val pending = loadingItems && i >= itemValues.size
                        val selected = inPanel && i == listIndex
                        if (row.kind == Inventory.Kind.BITS) {
                            BitsRow(row, value, selected, bitIndex, pending)
                        } else {
                            val tag = when {
                                row.kind == Inventory.Kind.ACTION -> "apply"
                                pending -> "…"
                                else -> Inventory.valueText(row, value)
                            }
                            val color = if (row.kind != Inventory.Kind.ACTION && !pending && value > 0) Pal.TEAL else Pal.DIM
                            ListRow(row.label, tag, selected, tagColor = color)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BitsRow(row: Inventory.Row, value: Int, selected: Boolean, focusBit: Int, pending: Boolean = false) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp))
                .background(if (selected) Pal.PANEL2 else Pal.PANEL)
                .border(2.dp, if (selected) Pal.GOLD else Color.Transparent, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { Txt(row.label, 13.sp, Pal.TEXT, bold = true) }
            Txt(if (pending) "…" else Inventory.valueText(row, value), 11.sp, if (!pending && value > 0) Pal.TEAL else Pal.DIM, bold = true)
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (bit in 7 downTo 0) {
                    val on = value >= 0 && ((value shr bit) and 1) != 0
                    val bitFocused = selected && bit == focusBit
                    Box(
                        Modifier.width(18.dp).height(20.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (on) Pal.TEAL else Pal.BG)
                            .border(1.dp, if (bitFocused) Pal.GOLD else Pal.LINE, RoundedCornerShape(4.dp)),
                        Alignment.Center,
                    ) {
                        Txt("$bit", 10.sp, if (on) Pal.INK else Pal.DIM, bold = true)
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsPanel() {
        @Suppress("UNUSED_VARIABLE") val tick = settingsTick
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            settingsRows.forEachIndexed { i, row ->
                val selected = inPanel && i == listIndex
                when (row) {
                    is SettingRow.Toggle -> {
                        val on = host.setting(row.key)
                        ListRow(row.label, if (on) "ON" else "OFF", selected, tagColor = if (on) Pal.TEAL else Pal.DIM)
                    }
                    is SettingRow.Action -> {
                        ListRow(row.label, row.tag, selected, tagColor = Pal.DIM)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            val socket = socketMeta.ifEmpty { "tcp://127.0.0.1:${StatusManifest.PORT}" }
            Txt("ADB socket: $socket", 11.sp, Pal.TEAL, bold = true)
        }
    }

    @Composable
    private fun ListRow(label: String, tag: String, selected: Boolean, lead: String = "", tagColor: Color = Pal.DIM) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp))
                .background(if (selected) Pal.PANEL2 else Pal.PANEL)
                .border(2.dp, if (selected) Pal.GOLD else Color.Transparent, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (lead.isNotEmpty()) {
                Box(Modifier.width(26.dp)) { Txt(lead, 12.sp, Pal.GOLD, bold = true, italic = true) }
            }
            Box(Modifier.weight(1f)) { Txt(label, 13.sp, Pal.TEXT, bold = true) }
            if (tag.isNotEmpty()) Txt(tag, 11.sp, tagColor, bold = true)
        }
    }

    @Composable
    private fun ConfirmSheet(sheet: Confirm) {
        Box(Modifier.fillMaxSize().background(Color(0xAA000000)), Alignment.Center) {
            Column(
                Modifier.width(420.dp).clip(RoundedCornerShape(12.dp)).background(Pal.PANEL)
                    .border(2.dp, Pal.GOLD, RoundedCornerShape(12.dp)).padding(16.dp),
            ) {
                Txt(sheet.title, 17.sp, Pal.TEXT, bold = true, italic = true)
                Spacer(Modifier.height(6.dp))
                Txt(sheet.body, 13.sp, Pal.DIM, maxLines = 4)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(sheet.yes, confirmYes, confirmYes, Modifier.weight(1f))
                    ActionButton("Cancel", !confirmYes, !confirmYes, Modifier.weight(1f))
                }
            }
        }
    }

    @Composable
    private fun ActionButton(label: String, filled: Boolean, outlined: Boolean, modifier: Modifier) {
        Box(
            modifier.height(30.dp).clip(RoundedCornerShape(8.dp))
                .background(if (filled) Pal.GOLD else Pal.PANEL2)
                .border(2.dp, if (outlined) Pal.TEXT else Pal.LINE, RoundedCornerShape(8.dp)),
            Alignment.Center,
        ) { Txt(label, 12.sp, if (filled) Pal.INK else Pal.TEXT, bold = true, italic = true) }
    }

    @Composable
    private fun HintBar(message: String?, hints: List<Pair<String, String>>) {
        Row(Modifier.fillMaxWidth().height(32.dp).background(Pal.BG).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (message != null) {
                Txt(message, 12.sp, Pal.TEAL, bold = true)
                return@Row
            }
            hints.forEach { (button, label) ->
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Pal.PANEL2).border(1.dp, Pal.LINE, RoundedCornerShape(10.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) { Txt(button, 11.sp, Pal.TEXT, bold = true) }
                Spacer(Modifier.width(5.dp))
                Txt(label, 13.sp, Pal.DIM, bold = true)
                Spacer(Modifier.width(14.dp))
            }
        }
    }

    @Composable
    private fun Badge(text: String, color: Color) {
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(color).padding(horizontal = 8.dp, vertical = 1.dp)) {
            Txt(text, 11.sp, Pal.INK, bold = true)
        }
    }

    @Composable
    private fun Thumb(image: ImageBitmap?, modifier: Modifier, empty: String, big: Boolean = false) {
        Box(modifier.clip(RoundedCornerShape(6.dp)).background(Pal.PANEL2), Alignment.Center) {
            if (image != null) {
                Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Txt(empty, if (big) 36.sp else 12.sp, Pal.LINE, bold = true)
            }
        }
    }

    @Composable
    private fun Txt(
        text: String, size: TextUnit, color: Color, bold: Boolean = false, italic: Boolean = false, maxLines: Int = 1,
    ) {
        BasicText(
            text,
            style = TextStyle(
                color = color,
                fontSize = size,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                textAlign = TextAlign.Start,
            ),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }

    // ---- text helpers ------------------------------------------------------

    private fun computeTitle(path: String?): String {
        if (path.isNullOrEmpty()) return "No ROM"
        val file = File(path)
        val parsed = RomLibrary.parse(file)
        val build = RomLibrary.buildId(file)
        return when {
            build.isNotEmpty() -> "${parsed.channel} $build"
            parsed.tag.isNotEmpty() -> "${parsed.channel} ${parsed.tag}"
            else -> parsed.channel
        }
    }

    private fun panelMeta(item: RailItem): String = when (item) {
        RailItem.SAVE, RailItem.LOAD -> {
            val used = states.count { it.occupied }
            "$used saved · page ${slotPage + 1} of $pageCount"
        }
        RailItem.ITEMS -> {
            val tab = activeItemTabName()
            if (tab == TAB_PRESETS) {
                "Save slot $activeSaveSlot"
            } else {
                tab
            }
        }
        RailItem.SETTINGS -> socketMeta.ifEmpty { "tcp://127.0.0.1:${StatusManifest.PORT}" }
        else -> ""
    }

    private fun blurb(item: RailItem): String = when (item) {
        RailItem.RESUME -> "Back to the game."
        RailItem.FAST -> "A toggles ×3 speed. It stays on after you resume. Holding R2 still works."
        RailItem.LIBRARY -> "Switch channels, resume a state, roll back, or start from the battery save."
        else -> ""
    }

    private fun menuHints(item: RailItem): List<Pair<String, String>> {
        if (!inPanel) return listOf("A" to "Select", "B / START" to "Resume", "D-PAD" to "Move")
        return when (item) {
            RailItem.SAVE -> listOf("A" to "Save here", "B" to "Back", "L1 R1" to "Slot page", "Y" to "Delete")
            RailItem.LOAD -> listOf("A" to "Load", "B" to "Back", "L1 R1" to "Slot page", "Y" to "Delete")
            RailItem.ITEMS -> when (activeItemTabName()) {
                TAB_PRESETS -> listOf("A" to "Apply preset", "L1 R1" to "Tab", "B" to "Back")
                else -> listOf("A" to "Toggle / apply", "LEFT RIGHT" to "Adjust / bit", "L1 R1" to "Tab", "B" to "Back")
            }
            else -> listOf("A" to "Select", "B" to "Back")
        }
    }

    private fun cardSubtitle(card: RomCard): String {
        val parts = ArrayList<String>()
        if (card.saveLabel.isNotEmpty()) parts.add(card.saveLabel)
        val n = card.stateCount
        if (n > 0) parts.add(if (n == 1) "1 state" else "$n states")
        return if (parts.isEmpty()) "No save yet" else parts.joinToString(" + ")
    }

    private fun cardDetail(card: RomCard): String {
        val parts = ArrayList<String>()
        val dateStr = SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(card.entry.date))
        if (card.entry.build.isNotEmpty()) {
            parts.add("${card.entry.build} · $dateStr")
        } else {
            parts.add(dateStr)
        }
        card.newest?.let {
            val buildBit = if (it.build.isNotEmpty()) " (${it.build})" else ""
            val labelBit = if (it.label.isNotEmpty()) " · ${it.label}" else ""
            parts.add("last state ${whenText(it.modified)}$buildBit$labelBit")
        }
        if (card.entry.previous != null) {
            val prev = if (card.entry.previousBuild.isNotEmpty()) card.entry.previousBuild else "saved"
            parts.add("prev: $prev")
        }
        if (card.entry.olderCopies.isNotEmpty()) {
            val n = card.entry.olderCopies.size
            parts.add(if (n == 1) "1 older copy" else "$n older copies")
        }
        if (card.source.isNotEmpty()) parts.add(card.source)
        return parts.joinToString(" · ")
    }

    private fun caption(entry: SaveStateSlots.Entry): String {
        if (!entry.occupied) return "Empty"
        return if (entry.label.isNotEmpty()) "${clock(entry.modified)} · ${entry.label}" else whenText(entry.modified)
    }

    companion object {
        private const val TAB_PRESETS = "Presets"

        private fun mainPoster(): (Runnable) -> Unit {
            val main = Handler(Looper.getMainLooper())
            return { r -> main.post(r) }
        }

        private fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

        private fun whenText(ms: Long): String {
            val then = Calendar.getInstance().apply { timeInMillis = ms }
            val now = Calendar.getInstance()
            val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
            return if (sameDay) "today ${clock(ms)}" else SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(ms))
        }

        private fun canonical(path: String?): String? {
            if (path.isNullOrEmpty()) return null
            return try {
                File(path).canonicalPath
            } catch (e: Exception) {
                path
            }
        }

        private val previewCache = HashMap<String, ImageBitmap?>()

        private fun cachedPreview(file: File?, modified: Long): Pair<Boolean, ImageBitmap?> {
            if (file == null) return true to null
            val key = "${file.path}@$modified"
            synchronized(previewCache) {
                if (previewCache.containsKey(key)) return true to previewCache[key]
            }
            return false to null
        }

        private fun loadPreview(file: File, modified: Long): ImageBitmap? {
            val key = "${file.path}@$modified"
            synchronized(previewCache) { if (previewCache.containsKey(key)) return previewCache[key] }
            val image = try {
                val png = MesenNative.statePreviewPng(file.path)
                if (png == null) null else BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
            } catch (e: Throwable) {
                null
            }
            synchronized(previewCache) {
                if (previewCache.size > 64) previewCache.clear()
                previewCache[key] = image
            }
            return image
        }

        @Composable
        private fun rememberPreview(file: File?, modified: Long): ImageBitmap? {
            val (hit, cached) = cachedPreview(file, modified)
            val state = produceState<ImageBitmap?>(cached, file?.path, modified) {
                if (!hit && file != null) {
                    value = withContext(Dispatchers.IO) { loadPreview(file, modified) }
                } else if (hit) {
                    value = cached
                }
            }
            return state.value
        }
    }
}
