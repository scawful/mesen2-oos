package ca.mesen.oos

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
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

/** Subtle low-glare dark-console palette. */
private object Pal {
    val BG = Color(0xFF0D1117)
    val SCRIM = Color(0xEB0D1117)
    val PANEL = Color(0xFF151B23)
    val PANEL2 = Color(0xFF1E2633)
    val LINE = Color(0xFF2C3645)
    val ACCENT = Color(0xFF63B3A0)
    val ICE = Color(0xFFA7C0D6)
    val STONE = Color(0xFFB8AD96)
    val RED = Color(0xFFC96060)
    val TEXT = Color(0xFFE6ECF2)
    val DIM = Color(0xFF8A97A8)
    val INK = Color(0xFF0D1117)
}

/** One channel card on the Library shelf / ROM strip, with its channel states read from disk. */
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
    PLAY("Play & Warps"),
    STATES("States"),
    CHEATS("Cheats & Gear"),
    CAPTURE("Capture"),
    SETTINGS("ROMs & Settings"),
}

enum class WarpCategory(val label: String) {
    FEATURED("Featured"),
    OVERWORLD("Overworld"),
    DUNGEONS("Dungeons"),
    BOSSES("Bosses"),
    ROOMS("Rooms"),
}

private enum class Key { UP, DOWN, LEFT, RIGHT, A, B, X, Y, L, R, START }

/**
 * Unified Compose UI: Oracle Hub + compact 6-item pause menu (Play & Warps visual grid,
 * unified Save/Load States, Cheats & Gear chip/stepper console, Capture thumbnails, and
 * ROMs & Settings). Navigable via controller and touch.
 */
class OracleUi internal constructor(
    private val host: Host,
    private val postMain: (Runnable) -> Unit,
) {
    constructor(host: Host) : this(host, mainPoster())

    interface Host {
        fun nativeReady(): Boolean
        fun romRoots(): Array<File>
        fun playtestRoots(): Array<File>
        fun launchPlaytestShortcut(card: PlaytestShortcuts.Card): PlaytestShortcuts.LaunchResult
        fun currentRomPath(): String?
        fun batterySaveLabel(rom: File): String
        fun romSourceLabel(rom: File): String
        fun statesDir(): File
        fun savesDir(): File
        fun checkpointData(): CheckpointData
        fun presetData(): PresetData
        fun socketSummary(): String
        fun uiOpened()
        fun uiClosed()
        fun bootRom(path: String): Boolean
        fun resetConsole()
        fun requestHomeShortcut()
        fun isStickyFastForward(): Boolean
        fun setStickyFastForward(on: Boolean)
        fun turboSpeedPercent(): Int
        fun turboSpeedLabel(): String
        fun setTurboSpeedPercent(percent: Int)
        fun cycleTurboSpeed(delta: Int): Int
        fun setting(key: String): Boolean
        fun setSetting(key: String, on: Boolean)
        fun startCheckpoint(id: String)
        fun startPreset(id: String)
        fun toast(message: String)
        fun capturesDir(): File?
        fun recentCaptures(): List<MediaCapture.CaptureItem>
        fun takeScreenshot(): String
        fun isVideoRecording(): Boolean
        fun videoRecordingElapsedSeconds(): Long
        fun toggleVideoRecording(): String
        fun isReplayBufferActive(): Boolean
        fun replayBufferedSeconds(): Int
        fun dumpReplayClip(): String
        fun debugOverlayMask(): Int
        fun toggleDebugOverlayBit(bit: Int): String
        fun captureBugSnapshot(category: String): String
        fun testingTools(): TestingTools
        fun activeSaveSlot(): Int
        fun saveStatePath(slot: Int): String?
        fun toggleCheat(cheat: TestingTools.Cheat): String
        fun clearCheats(): String
        fun runLiveAction(action: TestingTools.LiveAction): String
        fun loadCaptureState(stateFile: File): Boolean
        fun stepFrames(frames: Int): Boolean
        fun startWarp(target: Progression.Checkpoint)
        fun fallToDungeonRoom(roomId: Int)
        fun featureStates(): List<PlaySession.Jump> = emptyList()
        fun loadFeatureState(statePath: String): Boolean = false
        fun previewAssetNames(): Set<String> = emptySet()
        fun loadPreviewAsset(fileName: String): ByteArray? = null
        fun readWramByte(address: Int): Int = -1
        fun writeWramByte(address: Int, value: Int): Boolean = false
        fun syncWramToSram(): Boolean = false
        fun isStudySessionActive(): Boolean = false
        fun studySessionSummary(): String = ""
        fun toggleStudySession(): String = "Study Session unavailable"
        fun addStudyBookmark(tag: String): String = "No active Study Session"
    }

    private val hostMemory = object : Inventory.Memory {
        override fun readByte(address: Int): Int = host.readWramByte(address)
        override fun writeByte(address: Int, value: Int): Boolean = host.writeWramByte(address, value)
        override fun writeWord(address: Int, value: Int): Boolean =
            host.writeWramByte(address, value and 0xFF) &&
                host.writeWramByte(address + 1, (value shr 8) and 0xFF)
        override fun syncSram(): Boolean = host.syncWramToSram()
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "oracle-ui").apply { isDaemon = true } }
    private val mismatchWarnings = SaveStateSlots.MismatchWarnings()
    private val cardsGen = AtomicLong(0)
    private val shortcutsGen = AtomicLong(0)
    private val statesGen = AtomicLong(0)
    private val capturesGen = AtomicLong(0)
    private val itemsGen = AtomicLong(0)
    private val metaGen = AtomicLong(0)
    private var libNavTick = 0L

    var screen by mutableStateOf(Screen.CLOSED); private set
    private var status by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var confirm by mutableStateOf<Confirm?>(null)
    private var confirmYes by mutableStateOf(false)

    // Library / Oracle Hub
    private var cards by mutableStateOf(listOf<RomCard>())
    private var loadingLibrary by mutableStateOf(false)
    private var currentRomCanonical by mutableStateOf<String?>(null)
    private var hasCurrentRom by mutableStateOf(false)
    private var hubTab by mutableIntStateOf(0)
    private var libIndex by mutableIntStateOf(0)
    private var libRow by mutableIntStateOf(0)
    private var libAction by mutableIntStateOf(0)
    private var hubSaveIndex by mutableIntStateOf(0)

    // Quick Menu Rail
    private var rail by mutableIntStateOf(0)
    private var inPanel by mutableStateOf(false)

    // Play & Warps (shared across Menu & Library)
    private var shortcuts by mutableStateOf(listOf<PlaytestShortcuts.Card>())
    private var loadingShortcuts by mutableStateOf(false)
    private var warpCategory by mutableStateOf(WarpCategory.FEATURED)
    private var warpIndex by mutableIntStateOf(0)
    private var warpCols by mutableIntStateOf(3)
    private var fallRoomId by mutableIntStateOf(0)

    // Unified States (2x4 Load/Save grid)
    private var states by mutableStateOf(listOf<SaveStateSlots.Entry>())
    private var loadingStates by mutableStateOf(false)
    private var statesSaveMode by mutableStateOf(false)
    private var statesPage by mutableIntStateOf(0)
    private var slotIndex by mutableIntStateOf(0)

    // Cheats & Gear
    private var cheatTabIndex by mutableIntStateOf(0)
    private var cheatRow by mutableIntStateOf(0)
    private var cheatCol by mutableIntStateOf(0)
    private var cheatsTick by mutableIntStateOf(0)
    private var liveGearSnapshot by mutableStateOf(emptyMap<Int, Int>())
    private var listIndex by mutableIntStateOf(0)
    private var bitIndex by mutableIntStateOf(0)
    private var itemRows by mutableStateOf(listOf<Inventory.Row>())
    private var itemValues by mutableStateOf(listOf<Int>())
    private var loadedItemTab by mutableStateOf("")
    private var loadingItems by mutableStateOf(false)
    private var activeSaveSlot by mutableIntStateOf(1)

    // Capture
    private var captures by mutableStateOf(listOf<MediaCapture.CaptureItem>())
    private var loadingCaptures by mutableStateOf(false)
    private var bugCategoryIndex by mutableIntStateOf(0)
    private var captureTick by mutableIntStateOf(0)
    private var captureSection by mutableIntStateOf(0) // 0=action row 0, 1=action row 1, 2=recent grid
    private var captureCol by mutableIntStateOf(0)
    private var captureGridIndex by mutableIntStateOf(0)
    private var captureCols by mutableIntStateOf(3)

    // ROMs & Settings
    private var menuTitle by mutableStateOf("Oracle of Secrets")
    private var socketMeta by mutableStateOf("")
    private var fastOn by mutableStateOf(false)
    private var settingsTick by mutableIntStateOf(0)
    private var settingsSection by mutableIntStateOf(0) // 0=quick actions, 1=ROM strip, 2=toggles grid
    private var settingsActionCol by mutableIntStateOf(0)
    private var settingsRomIdx by mutableIntStateOf(0)
    private var settingsGridIdx by mutableIntStateOf(0)

    private sealed class WarpAction {
        class Shortcut(val card: PlaytestShortcuts.Card) : WarpAction()
        class Feature(val jump: PlaySession.Jump) : WarpAction()
        class Preset(val preset: PresetData.Preset) : WarpAction()
        class Milestone(val cp: Progression.Checkpoint) : WarpAction()
        class Area(val target: Progression.Checkpoint) : WarpAction()
        class Entrance(val target: Progression.Checkpoint) : WarpAction()
        object FallRoom : WarpAction()
    }

    private class WarpCardItem(
        val id: String,
        val title: String,
        val tag: String,
        val badge: String,
        val code: String,
        val previewCandidates: List<String>,
        val category: WarpCategory,
        val featured: Boolean,
        val action: WarpAction,
    )

    private sealed class HubSaveRow {
        class State(val card: RomCard, val entry: SaveStateSlots.Entry) : HubSaveRow()
        class Battery(val card: RomCard) : HubSaveRow()
    }

    private class CheatCell(
        val label: String,
        val tag: String = "",
        val active: Boolean = false,
        val accentTone: Boolean = false,
        val onActivate: () -> Unit,
    )

    private sealed class SettingCell(val key: String, val label: String) {
        object StickyFf : SettingCell("sticky_ff", "Sticky FF")
        object Speed : SettingCell("speed", "FF Speed")
        class Toggle(key: String, label: String) : SettingCell(key, label)
    }

    private val settingsCells: List<SettingCell> = listOf(
        SettingCell.StickyFf,
        SettingCell.Speed,
        SettingCell.Toggle("r2_capture", "ZR Mode"),
        SettingCell.Toggle("hotkeys", "L3/R3 Keys"),
        SettingCell.Toggle("hud", "Status Bar"),
        SettingCell.Toggle("fit", "Fill Screen"),
        SettingCell.Toggle("play", "Smooth Play"),
        SettingCell.Toggle("gpu", "GPU Blit"),
    )

    private val cheatTabs = listOf(TAB_LIVE) + Inventory.tabs()

    val isOpen: Boolean get() = screen != Screen.CLOSED
    internal val isBusy: Boolean get() = busy
    internal val isLoadingLibrary: Boolean get() = loadingLibrary
    internal val isLoadingShortcuts: Boolean get() = loadingShortcuts
    internal val isLoadingStates: Boolean get() = loadingStates
    internal val isLoadingCaptures: Boolean get() = loadingCaptures
    internal val isLoadingItems: Boolean get() = loadingItems
    internal val currentStatus: String get() = status
    internal val currentMenuTitle: String get() = menuTitle
    internal val currentSocketMeta: String get() = socketMeta
    internal val currentActiveSaveSlot: Int get() = activeSaveSlot
    internal val cardChannels: List<String> get() = cards.map { it.entry.channel }
    internal val currentHubTab: Int get() = hubTab
    internal val hubSaveCount: Int get() = hubSaveRows().size
    internal val hubWarpCount: Int get() = filteredWarpCards().size
    internal val currentShortcuts: List<PlaytestShortcuts.Card> get() = shortcuts
    internal val currentWarpCategory: WarpCategory get() = warpCategory
    internal val currentWarpIndex: Int get() = warpIndex
    internal val currentWarpCount: Int get() = filteredWarpCards().size
    internal val currentWarpTitles: List<String> get() = filteredWarpCards().map { it.title }
    internal val currentWarpTags: List<String> get() = filteredWarpCards().map { it.tag }
    internal val currentFallRoomId: Int get() = fallRoomId
    internal val currentStatesSaveMode: Boolean get() = statesSaveMode
    internal val stateSlotsCount: Int get() = states.size
    internal val currentCaptures: List<MediaCapture.CaptureItem> get() = captures
    internal val currentCheatTabName: String get() = activeCheatTabName()
    internal val currentItemRows: List<Inventory.Row> get() = itemRows
    internal val currentItemValues: List<Int> get() = itemValues

    internal fun resolvedPreviewAsset(id: String): String? {
        val available = host.previewAssetNames()
        val item = buildAllWarpCards().firstOrNull { it.id == id }
        val candidates = item?.previewCandidates ?: previewCandidatesForId(id)
        return candidates.firstNotNullOfOrNull { key ->
            val file = if (key.endsWith(".png", ignoreCase = true)) key else "$key.png"
            if (available.contains(file)) file else null
        }
    }

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
        hubTab = 0
        libRow = 0
        libAction = 0
        hubSaveIndex = 0
        warpCategory = WarpCategory.FEATURED
        warpIndex = 0
        status = ""
        confirm = null
        screen = Screen.LIBRARY
        if (wasClosed) host.uiOpened()
        reloadCards(selectCurrent = true)
        refreshShortcuts()
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
        reloadCards(selectCurrent = false)
        refreshShortcuts()
        refreshStates()
        refreshCaptures()
        refreshItems()
    }

    fun close() {
        if (screen == Screen.CLOSED) return
        screen = Screen.CLOSED
        confirm = null
        host.uiClosed()
    }

    // ---- input -------------------------------------------------------------

    fun onBack() {
        if (!isOpen || busy) return
        if (confirm != null) {
            confirm = null
            return
        }
        handle(Key.B)
    }

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

    private fun slotList(pathFor: (Int) -> String?): List<SaveStateSlots.Entry> {
        val fixed = (1..SaveStateSlots.SLOT_COUNT).map { SaveStateSlots.inspect(it, pathFor(it)) }
        val auto = SaveStateSlots.inspect(SaveStateSlots.AUTO_SLOT, pathFor(SaveStateSlots.AUTO_SLOT))
        return if (auto.occupied) listOf(auto) + fixed else fixed
    }

    private fun slotName(slot: Int) = if (slot == SaveStateSlots.AUTO_SLOT) "Auto" else "Slot $slot"

    private fun currentBuildId(): String {
        val path = host.currentRomPath() ?: return ""
        return RomLibrary.buildId(File(path))
    }

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
            settingsRomIdx = 0
        } else if (selectCurrent) {
            val at = snapshot.cards.indexOfFirst { it.canonicalPath == snapshot.currentCanonical }
            libIndex = if (at >= 0) at else libIndex.coerceIn(0, snapshot.cards.size - 1)
            settingsRomIdx = libIndex
        } else {
            libIndex = libIndex.coerceIn(0, snapshot.cards.size - 1)
            settingsRomIdx = settingsRomIdx.coerceIn(0, snapshot.cards.size - 1)
        }
    }

    private fun isCurrent(card: RomCard): Boolean =
        host.nativeReady() && currentRomCanonical != null && card.canonicalPath == currentRomCanonical

    private fun libActions(card: RomCard): List<LibAction> {
        val out = ArrayList<LibAction>()
        val current = isCurrent(card)
        if (current) out.add(LibAction("Continue") { close() })
        val newest = card.newest
        if (newest != null) out.add(LibAction("Resume State") { resumeState(card, newest) })
        if (current) {
            out.add(LibAction("Restart") {
                ask("Restart ${card.entry.channel}?", "Resets to last in-game save.", "Restart") {
                    boot(card)
                }
            })
        } else {
            out.add(LibAction("Play ROM") { boot(card) })
        }
        if (card.entry.previous != null) {
            val prevLabel = if (card.entry.previousBuild.isNotEmpty()) {
                "Rollback ${card.entry.previousBuild}"
            } else {
                "Rollback"
            }
            out.add(LibAction(prevLabel) { rollbackChannel(card) })
        }
        if (card.entry.olderCopies.isNotEmpty()) {
            out.add(LibAction("Prune ${card.entry.olderCopies.size}") { pruneOlderCopies(card) })
        }
        return out
    }

    private fun boot(card: RomCard) {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
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
            status = "Emulator starting…"
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
            if (loadEntry(entry)) null else "$name did not load."
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
        var toBuild = "previous"
        work("Rolling back ${card.entry.channel}…", {
            val problem = RomLibrary.rollback(card.entry)
            if (problem != null) return@work problem
            val scanned = scanCardsSnapshot()
            snapshot = scanned
            val updated = scanned.cards.firstOrNull { it.canonicalPath == cardCanon }
            toBuild = updated?.entry?.build?.ifEmpty { "previous" } ?: "previous"
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
                status = "Rolled back to $toBuild"
            }
        }
    }

    private fun pruneOlderCopies(card: RomCard) {
        val gen = cardsGen.incrementAndGet()
        var snapshot: LibrarySnapshot? = null
        var summary = ""
        work("Pruning old builds…", {
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

    private fun hubSaveRows(): List<HubSaveRow> {
        val states = cards.flatMap { card ->
            card.states.filter { it.occupied }.map { HubSaveRow.State(card, it) }
        }.sortedByDescending { it.entry.modified }
        val battery = cards.map { HubSaveRow.Battery(it) }
        return states + battery
    }

    private fun activateHubSave(row: HubSaveRow) {
        when (row) {
            is HubSaveRow.State -> resumeState(row.card, row.entry)
            is HubSaveRow.Battery -> boot(row.card)
        }
    }

    private fun withHubRomBooted(onReady: () -> Unit) {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        if (!host.currentRomPath().isNullOrEmpty()) {
            onReady()
            return
        }
        val target = cards.getOrNull(libIndex) ?: cards.firstOrNull()
        if (target == null) {
            status = "No Oracle ROM found"
            return
        }
        val path = target.file.path
        val canon = target.canonicalPath
        work("Booting ${target.entry.channel}…", {
            if (host.bootRom(path)) null else "Could not load ${target.file.name}."
        }) { problem ->
            if (problem != null) {
                status = problem
            } else {
                currentRomCanonical = canon
                hasCurrentRom = true
                onReady()
            }
        }
    }

    private fun libraryKey(key: Key) {
        if (key == Key.L || key == Key.R) {
            val n = HUB_TABS.size
            hubTab = if (key == Key.L) (hubTab - 1 + n) % n else (hubTab + 1) % n
            status = ""
            return
        }
        when (hubTab) {
            HUB_TAB_WARPS -> {
                playWarpKey(key, exitPanelOnEdge = false)
                return
            }
            HUB_TAB_SAVES -> {
                val rows = hubSaveRows()
                when (key) {
                    Key.UP -> hubSaveIndex = (hubSaveIndex - 1).coerceAtLeast(0)
                    Key.DOWN -> hubSaveIndex = (hubSaveIndex + 1).coerceAtMost((rows.size - 1).coerceAtLeast(0))
                    Key.A, Key.START -> rows.getOrNull(hubSaveIndex)?.let { activateHubSave(it) }
                    Key.Y -> reloadCards(selectCurrent = false)
                    Key.B -> if (host.nativeReady() && hasCurrentRom) close()
                    else -> {}
                }
                return
            }
        }
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

    // ---- Play & Warps catalog ----------------------------------------------

    private fun buildAllWarpCards(): List<WarpCardItem> {
        val out = ArrayList<WarpCardItem>()
        val cpData = host.checkpointData()
        val presetData = host.presetData()

        // 1. User/agent Playtest shortcuts (always Featured)
        shortcuts.forEach { card ->
            val cat = inferShortcutCategory(card)
            out.add(
                WarpCardItem(
                    id = card.id,
                    title = crispTitle(card.title),
                    tag = crispTitle(card.subtitle).ifEmpty { "Shortcut" },
                    badge = card.badge.ifEmpty { "CARD" },
                    code = card.id.take(6).uppercase(Locale.US),
                    previewCandidates = previewCandidatesForId(card.id),
                    category = cat,
                    featured = true,
                    action = WarpAction.Shortcut(card),
                )
            )
        }

        // 2. Story / Dungeon / In-place Presets
        presetData.presets.forEach { preset ->
            val cat = when {
                preset.inPlace -> WarpCategory.OVERWORLD
                preset.id in setOf("post-intro") -> WarpCategory.OVERWORLD
                else -> WarpCategory.DUNGEONS
            }
            val code = preset.id.uppercase(Locale.US).take(6)
            out.add(
                WarpCardItem(
                    id = preset.id,
                    title = crispPresetTitle(preset),
                    tag = if (preset.inPlace) "In-Place" else "Beat ${preset.beat}",
                    badge = if (preset.inPlace) "KIT" else "PRESET",
                    code = code,
                    previewCandidates = previewCandidatesForId(preset.id),
                    category = cat,
                    featured = true,
                    action = WarpAction.Preset(preset),
                )
            )
        }

        // 3. Checkpoints (story beats, dungeons, bosses, editor jumps)
        cpData.checkpoints.forEach { cp ->
            val cat = classifyCheckpoint(cp)
            val featured = cp.id in FEATURED_CHECKPOINT_IDS
            val code = checkpointCode(cp)
            out.add(
                WarpCardItem(
                    id = cp.id,
                    title = crispCheckpointTitle(cp),
                    tag = checkpointTag(cp),
                    badge = when (cat) {
                        WarpCategory.BOSSES -> "BOSS"
                        WarpCategory.DUNGEONS -> "DGN"
                        WarpCategory.ROOMS -> "ROOM"
                        else -> if (cp.beat > 0) "B${cp.beat}" else "WARP"
                    },
                    code = code,
                    previewCandidates = previewCandidatesForCheckpoint(cp),
                    category = cat,
                    featured = featured,
                    action = WarpAction.Milestone(cp),
                )
            )
        }

        // 4. Feature savestates (skip deprecated clutter)
        host.featureStates().filter { !it.status.equals("deprecated", ignoreCase = true) }.forEach { jump ->
            val cat = classifyFeatureJump(jump)
            out.add(
                WarpCardItem(
                    id = jump.sourceId,
                    title = crispTitle(jump.label),
                    tag = crispTitle(jump.location).ifEmpty { "Savestate" },
                    badge = "MSS",
                    code = "MSS",
                    previewCandidates = previewCandidatesForId(jump.sourceId, jump.location),
                    category = cat,
                    featured = jump.status.equals("canon", ignoreCase = true),
                    action = WarpAction.Feature(jump),
                )
            )
        }

        // 5. Overworld Area Warps
        cpData.areaWarps.forEach { area ->
            val hex = String.format(Locale.US, "%02X", area.target and 0xFF)
            val clean = stripWarpName(area.label)
            val worldTag = if ((area.target and 0xFF) >= 0x40) "Abyss \$$hex" else "OW \$$hex"
            out.add(
                WarpCardItem(
                    id = area.id,
                    title = clean,
                    tag = worldTag,
                    badge = "\$$hex",
                    code = "\$$hex",
                    previewCandidates = previewCandidatesForArea(area.target),
                    category = WarpCategory.OVERWORLD,
                    featured = (area.target and 0xFF) in FEATURED_AREA_IDS,
                    action = WarpAction.Area(area),
                )
            )
        }

        // 6. Entrance Warps (dungeon entrances -> Dungeons; caves/houses/doors -> Rooms)
        cpData.entranceWarps.forEach { ent ->
            val hex = String.format(Locale.US, "%02X", ent.target and 0xFF)
            val clean = stripWarpName(ent.label)
            val isDungeon = isDungeonEntrance(clean, ent.target)
            val roomBit = if (ent.room >= 0) String.format(Locale.US, "Rm $%03X", ent.room) else "Ent \$$hex"
            out.add(
                WarpCardItem(
                    id = ent.id,
                    title = clean,
                    tag = roomBit,
                    badge = if (isDungeon) "DGN" else "DOOR",
                    code = "\$$hex",
                    previewCandidates = previewCandidatesForEntrance(ent.target, ent.area),
                    category = if (isDungeon) WarpCategory.DUNGEONS else WarpCategory.ROOMS,
                    featured = false,
                    action = WarpAction.Entrance(ent),
                )
            )
        }

        return out
    }

    private fun filteredWarpCards(category: WarpCategory = warpCategory): List<WarpCardItem> {
        val all = buildAllWarpCards()
        val list = when (category) {
            WarpCategory.FEATURED -> {
                val feat = all.filter { it.featured }
                if (feat.isNotEmpty()) feat else all
            }
            WarpCategory.ROOMS -> {
                val fall = WarpCardItem(
                    id = "fall-room",
                    title = "Fall to Room",
                    tag = String.format(Locale.US, "$%04X", fallRoomId),
                    badge = "DROP",
                    code = String.format(Locale.US, "$%04X", fallRoomId),
                    previewCandidates = emptyList(),
                    category = WarpCategory.ROOMS,
                    featured = false,
                    action = WarpAction.FallRoom,
                )
                listOf(fall) + all.filter { it.category == WarpCategory.ROOMS }
            }
            else -> all.filter { it.category == category }
        }
        return list
    }

    private fun activateWarpCard(item: WarpCardItem) {
        when (val act = item.action) {
            is WarpAction.Shortcut -> launchShortcut(act.card)
            is WarpAction.Feature -> withHubRomBooted { runFeature(act.jump) }
            is WarpAction.Preset -> withHubRomBooted { runPreset(act.preset) }
            is WarpAction.Milestone -> withHubRomBooted { runCheckpoint(act.cp) }
            is WarpAction.Area -> withHubRomBooted {
                close()
                host.startWarp(act.target)
            }
            is WarpAction.Entrance -> withHubRomBooted {
                close()
                host.startWarp(act.target)
            }
            WarpAction.FallRoom -> withHubRomBooted {
                val targetRoom = fallRoomId
                close()
                host.fallToDungeonRoom(targetRoom)
            }
        }
    }

    private fun checkIdleProblem(): String? {
        val module = hostMemory.readByte(Inventory.MODULE)
        val sub = hostMemory.readByte(Inventory.SUBMODULE)
        if (module < 0 || sub < 0) return "Could not read game state"
        if (!Progression.isPlayModule(module) || sub != 0) {
            return String.format(Locale.ROOT, "Resume normal play first (module $%02X sub $%02X)", module, sub)
        }
        return null
    }

    private fun runCheckpoint(cp: Progression.Checkpoint) {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        val slot = activeSaveSlot
        ask(
            "Warp to ${crispCheckpointTitle(cp)}?",
            "Sets slot $slot progress to beat ${cp.beat}.",
            "Warp",
        ) {
            work("Checking state…", {
                checkIdleProblem()
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

    private fun runFeature(jump: PlaySession.Jump) {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        ask(
            "Load ${crispTitle(jump.label)}?",
            "Loads savestate from OracleLibrary.",
            "Load",
        ) {
            work("Loading ${crispTitle(jump.label)}…", {
                if (host.loadFeatureState(jump.statePath)) null
                else "State not found: ${jump.statePath}"
            }) { problem ->
                if (problem == null) close()
                status = problem ?: "Loaded ${jump.label}"
            }
        }
    }

    private fun runPreset(preset: PresetData.Preset) {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        if (preset.inPlace) {
            val tab = activeCheatTabName()
            val rows = itemRows
            val gen = itemsGen.incrementAndGet()
            var applyMsg = ""
            var refreshedValues: List<Int>? = null
            var gear: Map<Int, Int>? = null
            var slot = activeSaveSlot
            work("Applying ${preset.name}…", {
                applyMsg = PresetData.applyInPlace(preset, Progression.LIVE)
                slot = Progression.activeSlot(Progression.LIVE)
                gear = readGearSnapshot()
                if (tab != TAB_LIVE) {
                    refreshedValues = rows.map { Inventory.read(it, hostMemory) }
                }
                null
            }) { problem ->
                activeSaveSlot = slot
                gear?.let { liveGearSnapshot = it }
                val vals = refreshedValues
                if (vals != null && gen == itemsGen.get() && activeCheatTabName() == tab) {
                    itemValues = vals
                    loadedItemTab = tab
                    loadingItems = false
                }
                status = problem ?: applyMsg
            }
            return
        }
        work("Checking state…", {
            checkIdleProblem()
        }) { problem ->
            if (problem != null) {
                status = problem
            } else {
                close()
                host.startPreset(preset.id)
            }
        }
    }

    private fun playWarpKey(key: Key, exitPanelOnEdge: Boolean) {
        if (key == Key.L || key == Key.R) {
            val cats = WarpCategory.values()
            val next = if (key == Key.L) (warpCategory.ordinal - 1 + cats.size) % cats.size
                else (warpCategory.ordinal + 1) % cats.size
            warpCategory = cats[next]
            warpIndex = 0
            status = ""
            return
        }
        if (key == Key.Y) {
            status = "Rescanning Playtest…"
            refreshShortcuts(announce = true)
            return
        }
        val items = filteredWarpCards()
        if (items.isEmpty()) {
            if (key == Key.LEFT && exitPanelOnEdge) inPanel = false
            return
        }
        val idx = warpIndex.coerceIn(0, items.size - 1)
        val hasFallBar = warpCategory == WarpCategory.ROOMS && items.firstOrNull()?.action is WarpAction.FallRoom
        val cols = warpCols.coerceAtLeast(1)

        if (hasFallBar && idx == 0) {
            when (key) {
                Key.LEFT -> {
                    val n = Progression.MAX_ROOM_ID + 1
                    fallRoomId = (fallRoomId - 1 + n) % n
                }
                Key.RIGHT -> {
                    val n = Progression.MAX_ROOM_ID + 1
                    fallRoomId = (fallRoomId + 1) % n
                }
                Key.DOWN -> if (items.size > 1) warpIndex = 1
                Key.A, Key.START -> activateWarpCard(items[0])
                Key.B -> if (exitPanelOnEdge) inPanel = false else if (host.nativeReady() && hasCurrentRom) close()
                else -> {}
            }
            return
        }

        val gridOffset = if (hasFallBar) 1 else 0
        val gridCount = items.size - gridOffset
        val gridIdx = idx - gridOffset

        when (key) {
            Key.LEFT -> {
                if (gridIdx % cols == 0) {
                    if (exitPanelOnEdge) inPanel = false
                } else {
                    warpIndex = idx - 1
                }
            }
            Key.RIGHT -> {
                if ((gridIdx % cols) < cols - 1 && idx + 1 < items.size) {
                    warpIndex = idx + 1
                }
            }
            Key.UP -> {
                if (gridIdx >= cols) {
                    warpIndex = idx - cols
                } else if (hasFallBar) {
                    warpIndex = 0
                }
            }
            Key.DOWN -> {
                if (gridIdx + cols < gridCount) {
                    warpIndex = idx + cols
                } else if (gridIdx / cols < (gridCount - 1) / cols) {
                    warpIndex = items.size - 1
                }
            }
            Key.A, Key.START -> activateWarpCard(items[idx])
            Key.B -> if (exitPanelOnEdge) inPanel = false else if (host.nativeReady() && hasCurrentRom) close()
            else -> {}
        }
    }

    // ---- quick menu meta & data loading ------------------------------------

    private fun refreshMenuMeta() {
        val gen = metaGen.incrementAndGet()
        worker.execute {
            val path = host.currentRomPath()
            val title = computeTitle(path)
            val canon = canonical(path)
            val summary = host.socketSummary()
            val slot = if (host.nativeReady()) host.activeSaveSlot() else 1
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
        return slotList { slot ->
            if (slot == SaveStateSlots.AUTO_SLOT) {
                host.saveStatePath(1)?.replace(Regex("_1\\.mss$"), "_${SaveStateSlots.AUTO_SLOT}.mss")
            } else {
                host.saveStatePath(slot)
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

    private fun activeCheatTabName(): String = cheatTabs.getOrElse(cheatTabIndex) { TAB_LIVE }

    private fun refreshCaptures() {
        val gen = capturesGen.incrementAndGet()
        if (captures.isEmpty()) {
            loadingCaptures = true
        }
        worker.execute {
            val loaded = try {
                host.recentCaptures()
            } catch (e: Exception) {
                emptyList()
            }
            postMain(Runnable {
                if (gen != capturesGen.get()) return@Runnable
                captures = loaded
                loadingCaptures = false
            })
        }
    }

    private fun readGearSnapshot(): Map<Int, Int> {
        if (!host.nativeReady()) return emptyMap()
        val map = LinkedHashMap<Int, Int>(GEAR_SNAPSHOT_ADDRS.size)
        for (addr in GEAR_SNAPSHOT_ADDRS) {
            map[addr] = hostMemory.readByte(addr)
        }
        return map
    }

    private fun refreshItems() {
        val tab = activeCheatTabName()
        val gen = itemsGen.incrementAndGet()
        if (tab == TAB_LIVE) {
            itemRows = emptyList()
            itemValues = emptyList()
            loadedItemTab = tab
            loadingItems = false
            worker.execute {
                val slot = if (host.nativeReady()) host.activeSaveSlot() else 1
                val gear = readGearSnapshot()
                postMain(Runnable {
                    if (gen != itemsGen.get()) return@Runnable
                    activeSaveSlot = slot
                    liveGearSnapshot = gear
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
                rows.map { Inventory.read(it, hostMemory) }
            } else {
                rows.map { if (it.kind == Inventory.Kind.ACTION) 0 else -1 }
            }
            val slot = if (host.nativeReady()) host.activeSaveSlot() else activeSaveSlot
            postMain(Runnable {
                if (gen != itemsGen.get() || activeCheatTabName() != tab) return@Runnable
                itemRows = rows
                itemValues = values
                loadedItemTab = tab
                loadingItems = false
                activeSaveSlot = slot
            })
        }
    }

    private fun refreshShortcuts(announce: Boolean = false) {
        val gen = shortcutsGen.incrementAndGet()
        if (shortcuts.isEmpty()) {
            loadingShortcuts = true
        }
        worker.execute {
            val loaded = try {
                PlaytestShortcuts.listCards(host.playtestRoots())
            } catch (e: Exception) {
                emptyList()
            }
            postMain(Runnable {
                if (gen != shortcutsGen.get()) return@Runnable
                shortcuts = loaded
                loadingShortcuts = false
                val n = filteredWarpCards().size
                warpIndex = if (n == 0) 0 else warpIndex.coerceIn(0, n - 1)
                if (announce) {
                    status = if (loaded.isEmpty()) {
                        "No Playtest cards"
                    } else {
                        "${loaded.size} Playtest ${if (loaded.size == 1) "card" else "cards"}"
                    }
                }
            })
        }
    }

    private fun reloadCurrentRom() {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        val path = host.currentRomPath()
        if (path.isNullOrEmpty() || !File(path).isFile) {
            status = "No ROM loaded"
            return
        }
        val file = File(path)
        val canon = canonical(path)
        work("Reloading ${file.name}…", {
            if (host.bootRom(path)) null else "Could not reload ${file.name}."
        }) { problem ->
            if (problem == null) {
                currentRomCanonical = canon
                hasCurrentRom = true
                host.toast("Reloaded ${file.name}")
                close()
            } else {
                status = problem
            }
        }
    }

    private fun launchShortcut(card: PlaytestShortcuts.Card) {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        var outcome: PlaytestShortcuts.LaunchResult? = null
        work("Launching ${card.title}…", {
            val res = host.launchPlaytestShortcut(card)
            outcome = res
            if (res.ok) null else res.message
        }) { problem ->
            val res = outcome
            if (problem == null && res != null && res.ok) {
                if (!res.romPath.isNullOrEmpty()) {
                    currentRomCanonical = canonical(res.romPath)
                    hasCurrentRom = true
                }
                host.toast(res.message)
                close()
            } else {
                status = problem ?: "Could not launch ${card.title}."
            }
        }
    }

    // ---- Rail & panel dispatch ---------------------------------------------

    private val railItem: RailItem get() = RailItem.values()[rail]

    private fun hasPanel(item: RailItem) = item != RailItem.RESUME

    private fun enterPanel() {
        val item = railItem
        when (item) {
            RailItem.PLAY -> {
                refreshShortcuts()
                val n = filteredWarpCards().size
                warpIndex = if (n == 0) 0 else warpIndex.coerceIn(0, n - 1)
            }
            RailItem.STATES -> {
                refreshStates()
                slotIndex = 0
            }
            RailItem.CHEATS -> {
                cheatRow = 0
                cheatCol = 0
                listIndex = 0
                bitIndex = 0
                refreshItems()
            }
            RailItem.CAPTURE -> {
                captureSection = 0
                captureCol = 0
                captureGridIndex = 0
                refreshCaptures()
            }
            RailItem.SETTINGS -> {
                settingsSection = 0
                settingsActionCol = 0
                settingsGridIdx = 0
                fastOn = host.isStickyFastForward()
                refreshMenuMeta()
                reloadCards(selectCurrent = false)
            }
            RailItem.RESUME -> {}
        }
        inPanel = true
        status = ""
    }

    private fun activateRail() {
        when (railItem) {
            RailItem.RESUME -> close()
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
                    RailItem.PLAY -> refreshShortcuts()
                    RailItem.STATES -> refreshStates()
                    RailItem.CHEATS -> refreshItems()
                    RailItem.CAPTURE -> refreshCaptures()
                    RailItem.SETTINGS -> { refreshMenuMeta(); reloadCards(selectCurrent = false) }
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
            RailItem.PLAY -> playWarpKey(key, exitPanelOnEdge = true)
            RailItem.STATES -> slotKey(key)
            RailItem.CHEATS -> cheatsKey(key)
            RailItem.CAPTURE -> captureKey(key)
            RailItem.SETTINGS -> settingsKey(key)
            RailItem.RESUME -> {}
        }
    }

    // ---- States panel (2x4 unified Load / Save) ----------------------------

    private fun visibleSlots(): List<SaveStateSlots.Entry> =
        if (statesSaveMode) states.filter { it.slot != SaveStateSlots.AUTO_SLOT } else states

    private val statePageCount: Int
        get() = maxOf(1, (visibleSlots().size + SLOTS_PER_PAGE - 1) / SLOTS_PER_PAGE)

    private fun currentPageSlots(): List<SaveStateSlots.Entry> {
        val vis = visibleSlots()
        val page = statesPage.coerceIn(0, maxOf(0, (vis.size + SLOTS_PER_PAGE - 1) / SLOTS_PER_PAGE - 1))
        return vis.drop(page * SLOTS_PER_PAGE).take(SLOTS_PER_PAGE)
    }

    private fun setStatesMode(save: Boolean) {
        if (statesSaveMode == save) return
        statesSaveMode = save
        statesPage = 0
        slotIndex = 0
        status = ""
    }

    private fun slotKey(key: Key) {
        val slots = currentPageSlots()
        val n = slots.size
        when (key) {
            Key.L, Key.R -> setStatesMode(!statesSaveMode)
            Key.X -> {
                statesPage = (statesPage + 1) % statePageCount
                slotIndex = 0
            }
            Key.LEFT -> if (slotIndex % 4 == 0) inPanel = false else slotIndex--
            Key.RIGHT -> if (slotIndex % 4 < 3 && slotIndex + 1 < n) slotIndex++
            Key.UP -> if (slotIndex >= 4) slotIndex -= 4
            Key.DOWN -> {
                if (slotIndex + 4 < n) slotIndex += 4
                else if (slotIndex / 4 < (n - 1) / 4) slotIndex = n - 1
            }
            Key.A -> slots.getOrNull(slotIndex)?.let {
                if (statesSaveMode) saveSlot(it) else loadSlot(it)
            }
            Key.Y -> slots.getOrNull(slotIndex)?.let { deleteSlot(it) }
            else -> {}
        }
    }

    private fun saveSlot(entry: SaveStateSlots.Entry) {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        val slot = entry.slot
        if (slot == SaveStateSlots.AUTO_SLOT) {
            status = "Auto-save is read-only"
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
                    val path = host.saveStatePath(slot)
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
                status = problem ?: if (build.isNotEmpty()) "Saved slot $slot ($build)" else "Saved slot $slot"
            }
        }
        if (entry.occupied) ask("Overwrite Slot $slot?", "Replaces ${caption(entry)}.", "Overwrite", doSave) else doSave()
    }

    private fun loadSlot(entry: SaveStateSlots.Entry) {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        if (!entry.occupied) {
            status = "Slot ${entry.slot} empty"
            return
        }
        val name = slotName(entry.slot)
        var warning: String? = null
        work("Loading $name…", {
            warning = mismatchWarnings.check(entry, currentBuildId())
            if (loadEntry(entry)) null else "$name failed to load."
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
        ask("Delete Slot ${entry.slot}?", "Removes ${caption(entry)}.", "Delete") {
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
                status = problem ?: if (ok) "Deleted slot ${entry.slot}" else "Delete failed"
            }
        }
    }

    // ---- Cheats & Gear panel -----------------------------------------------

    private fun toggleLiveCheat(cheat: TestingTools.Cheat) {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        var msg = ""
        work("Updating ${cheat.label}…", {
            msg = host.toggleCheat(cheat)
            null
        }) { problem ->
            cheatsTick++
            status = problem ?: msg
        }
    }

    private fun clearAllLiveCheats() {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        var msg = ""
        work("Clearing cheats…", {
            msg = host.clearCheats()
            null
        }) { problem ->
            cheatsTick++
            status = problem ?: msg
        }
    }

    private fun triggerLiveAction(action: TestingTools.LiveAction) {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        var msg = ""
        work("${action.label}…", {
            msg = host.runLiveAction(action)
            null
        }) { problem ->
            cheatsTick++
            status = problem ?: msg
        }
    }

    private fun toggleOverlayBit(label: String, bit: Int) {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        var msg = ""
        work("Updating $label…", {
            msg = host.toggleDebugOverlayBit(bit)
            null
        }) { problem ->
            cheatsTick++
            status = problem ?: msg
        }
    }

    private fun runInventoryAction(label: String) {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        var msg = ""
        var gear: Map<Int, Int>? = null
        work("$label…", {
            val problem = Inventory.gameplayProblem(hostMemory)
            if (problem != null) {
                msg = problem
            } else {
                msg = Inventory.runAction(label, hostMemory)
                gear = readGearSnapshot()
            }
            null
        }) { problem ->
            gear?.let { liveGearSnapshot = it }
            cheatsTick++
            status = problem ?: msg
        }
    }

    private fun cycleLiveGear(label: String, addr: Int, maxVal: Int, names: Array<String>? = null) {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        var msg = ""
        var gear: Map<Int, Int>? = null
        work("Updating $label…", {
            val problem = Inventory.gameplayProblem(hostMemory)
            if (problem != null) {
                msg = problem
            } else {
                val cur = hostMemory.readByte(addr).coerceAtLeast(0)
                val next = (cur + 1) % (maxVal + 1)
                val ok = hostMemory.writeByte(addr, next)
                if (ok) {
                    if (addr == Inventory.BOOTS && next > 0) {
                        val ab = hostMemory.readByte(Inventory.ABILITY)
                        if (ab >= 0) hostMemory.writeByte(Inventory.ABILITY, ab or 0x04)
                    } else if (addr == Inventory.FLIPPERS && next > 0) {
                        val ab = hostMemory.readByte(Inventory.ABILITY)
                        if (ab >= 0) hostMemory.writeByte(Inventory.ABILITY, ab or 0x02)
                    }
                    hostMemory.syncSram()
                    val vLabel = if (names != null && next < names.size) names[next]
                    else if (maxVal == 1) (if (next > 0) "ON" else "OFF")
                    else "Lv $next"
                    msg = "$label: $vLabel"
                } else {
                    msg = "Write failed: $label"
                }
                gear = readGearSnapshot()
            }
            null
        }) { problem ->
            gear?.let { liveGearSnapshot = it }
            cheatsTick++
            status = problem ?: msg
        }
    }

    private fun liveCheatGridRows(): List<List<CheatCell>> {
        val tools = host.testingTools()
        val ovMask = host.debugOverlayMask()
        val gear = liveGearSnapshot

        val row0 = listOf(
            TestingTools.Cheat.NO_DAMAGE,
            TestingTools.Cheat.INF_HEALTH,
            TestingTools.Cheat.INF_MAGIC,
            TestingTools.Cheat.INF_RUPEES,
        ).map { c ->
            val on = tools.isCheatActive(c)
            CheatCell(c.label, if (on) "ON" else "", on) { toggleLiveCheat(c) }
        }

        val row1 = listOf(
            TestingTools.Cheat.INF_AMMO,
            TestingTools.Cheat.ONE_HIT,
            TestingTools.Cheat.WALK_WALLS,
        ).map { c ->
            val on = tools.isCheatActive(c)
            CheatCell(c.label, if (on) "ON" else "", on) { toggleLiveCheat(c) }
        } + CheatCell(
            "Clear All",
            if (tools.activeCheatCount() > 0) "${tools.activeCheatCount()}" else "",
            false,
        ) { clearAllLiveCheats() }

        val timeShort = tools.timePhaseText().substringAfter(' ', tools.timePhaseText())
        val row2 = listOf(
            CheatCell("Time: $timeShort", "", false, accentTone = true) {
                triggerLiveAction(TestingTools.LiveAction.TIME_OF_DAY)
            },
            CheatCell("Sprite -1", "", false) {
                tools.stepSpawnSpriteId(-1)
                cheatsTick++
                status = "Sprite ${tools.spawnSpriteText()}"
            },
            CheatCell("Spawn ${tools.spawnSpriteText()}", "", false, accentTone = true) {
                triggerLiveAction(TestingTools.LiveAction.SPAWN_SPRITE)
            },
            CheatCell("Sprite +1", "", false) {
                tools.stepSpawnSpriteId(1)
                cheatsTick++
                status = "Sprite ${tools.spawnSpriteText()}"
            },
        )

        val row3 = listOf(
            CheatCell("Step +1f", "", false, accentTone = true) {
                triggerLiveAction(TestingTools.LiveAction.STEP_1)
            },
            CheatCell("Step +10f", "", false, accentTone = true) {
                triggerLiveAction(TestingTools.LiveAction.STEP_10)
            },
            CheatCell("Clear Room", "", false) {
                triggerLiveAction(TestingTools.LiveAction.CLEAR_ENEMIES)
            },
            CheatCell("Swap Layer", "", false) {
                triggerLiveAction(TestingTools.LiveAction.TOGGLE_LAYER)
            },
        )

        val freezeOn = tools.isCheatActive(TestingTools.Cheat.FREEZE_SPRITES)
        val row4 = listOf(
            CheatCell("Hitboxes", if ((ovMask and 0x01) != 0) "ON" else "", (ovMask and 0x01) != 0) {
                toggleOverlayBit("Hitboxes", 0x01)
            },
            CheatCell("Collision", if ((ovMask and 0x02) != 0) "ON" else "", (ovMask and 0x02) != 0) {
                toggleOverlayBit("Collision", 0x02)
            },
            CheatCell("16x16 Grid", if ((ovMask and 0x04) != 0) "ON" else "", (ovMask and 0x04) != 0) {
                toggleOverlayBit("16x16 Grid", 0x04)
            },
            CheatCell("Freeze AI", if (freezeOn) "ON" else "", freezeOn) {
                toggleLiveCheat(TestingTools.Cheat.FREEZE_SPRITES)
            },
        )

        val row5 = listOf(
            CheatCell("Max Kit", "ALL", false, accentTone = true) { runInventoryAction(Inventory.MAX_EVERYTHING) },
            CheatCell("Full Heal", "HP", false) { runInventoryAction(Inventory.FULL_HEAL) },
            CheatCell("999 Rupees", "R", false) { runInventoryAction(Inventory.RUPEES_999) },
            CheatCell("All Masks", "9", false) { runInventoryAction(Inventory.ALL_MASKS) },
        )

        val swordNames = arrayOf("None", "L1", "L2", "L3", "L4")
        val shieldNames = arrayOf("None", "L1", "L2", "L3")
        val mailNames = arrayOf("Green", "Blue", "Red")
        val gloveNames = arrayOf("None", "Power", "Titan")
        val sw = (gear[Inventory.SWORD] ?: 0).coerceAtLeast(0)
        val sh = (gear[Inventory.SHIELD] ?: 0).coerceAtLeast(0)
        val ml = (gear[Inventory.ARMOR] ?: 0).coerceAtLeast(0)
        val gl = (gear[Inventory.GLOVES] ?: 0).coerceAtLeast(0)
        val row6 = listOf(
            CheatCell("Sword", swordNames.getOrElse(sw) { "$sw" }, sw > 0) {
                cycleLiveGear("Sword", Inventory.SWORD, 4, swordNames)
            },
            CheatCell("Shield", shieldNames.getOrElse(sh) { "$sh" }, sh > 0) {
                cycleLiveGear("Shield", Inventory.SHIELD, 3, shieldNames)
            },
            CheatCell("Mail", mailNames.getOrElse(ml) { "$ml" }, ml > 0) {
                cycleLiveGear("Mail", Inventory.ARMOR, 2, mailNames)
            },
            CheatCell("Gloves", gloveNames.getOrElse(gl) { "$gl" }, gl > 0) {
                cycleLiveGear("Gloves", Inventory.GLOVES, 2, gloveNames)
            },
        )

        val boots = (gear[Inventory.BOOTS] ?: 0) > 0
        val flippers = (gear[Inventory.FLIPPERS] ?: 0) > 0
        val pearl = (gear[Inventory.MOON_PEARL] ?: 0) > 0
        val zora = (gear[Inventory.ZORA_MASK] ?: 0) > 0
        val row7 = listOf(
            CheatCell("Boots", if (boots) "ON" else "", boots) { cycleLiveGear("Boots", Inventory.BOOTS, 1) },
            CheatCell("Flippers", if (flippers) "ON" else "", flippers) { cycleLiveGear("Flippers", Inventory.FLIPPERS, 1) },
            CheatCell("Moon Pearl", if (pearl) "ON" else "", pearl) { cycleLiveGear("Moon Pearl", Inventory.MOON_PEARL, 1) },
            CheatCell("Zora Mask", if (zora) "ON" else "", zora) { cycleLiveGear("Zora Mask", Inventory.ZORA_MASK, 1) },
        )

        val bunny = (gear[Inventory.BUNNY_HOOD] ?: 0) > 0
        val deku = (gear[Inventory.DEKU_MASK] ?: 0) > 0
        val stone = (gear[Inventory.STONE_MASK] ?: 0) > 0
        val wolf = (gear[Inventory.WOLF_MASK] ?: 0) > 0
        val row8 = listOf(
            CheatCell("Bunny Hood", if (bunny) "ON" else "", bunny) { cycleLiveGear("Bunny Hood", Inventory.BUNNY_HOOD, 1) },
            CheatCell("Deku Mask", if (deku) "ON" else "", deku) { cycleLiveGear("Deku Mask", Inventory.DEKU_MASK, 1) },
            CheatCell("Stone Mask", if (stone) "ON" else "", stone) { cycleLiveGear("Stone Mask", Inventory.STONE_MASK, 1) },
            CheatCell("Wolf Mask", if (wolf) "ON" else "", wolf) { cycleLiveGear("Wolf Mask", Inventory.WOLF_MASK, 1) },
        )

        val garo = (gear[Inventory.GARO_MASK_OWNED] ?: 0) > 0
        val crystals = Integer.bitCount((gear[Inventory.CRYSTALS] ?: 0) and 0xFF)
        val pendants = Integer.bitCount((gear[Inventory.PENDANTS] ?: 0) and 0x07)
        val row9 = listOf(
            CheatCell("Garo Mask", if (garo) "ON" else "", garo) { cycleLiveGear("Garo Mask", Inventory.GARO_MASK_OWNED, 1) },
            CheatCell("4 Bottles", "FILL", false) { runInventoryAction(Inventory.FILL_BOTTLES) },
            CheatCell("Crystals", "$crystals/8", crystals > 0) {
                cheatTabIndex = cheatTabs.indexOf(Inventory.TAB_DUNGEONS).coerceAtLeast(1)
                listIndex = 0
                refreshItems()
            },
            CheatCell("Pendants", "$pendants/3", pendants > 0) {
                cheatTabIndex = cheatTabs.indexOf(Inventory.TAB_DUNGEONS).coerceAtLeast(1)
                listIndex = 0
                refreshItems()
            },
        )

        val stalfos = (gear[Inventory.STALFOS_MASK_OWNED] ?: 0) > 0
        val frog = (gear[Inventory.FROG_MASK_OWNED] ?: 0) > 0
        val fierceDeity = (gear[Inventory.FIERCE_DEITY_MASK_OWNED] ?: 0) > 0
        val fdActive = (gear[Inventory.ACTIVE_FORM] ?: 0) == Inventory.FORM_FIERCE_DEITY
        val row10 = listOf(
            CheatCell("Stalfos Mask", if (stalfos) "ON" else "", stalfos) { cycleLiveGear("Stalfos Mask", Inventory.STALFOS_MASK_OWNED, 1) },
            CheatCell("Frog Mask", if (frog) "ON" else "", frog) { cycleLiveGear("Frog Mask", Inventory.FROG_MASK_OWNED, 1) },
            CheatCell("Fierce Deity", if (fierceDeity) "ON" else "", fierceDeity) { cycleLiveGear("Fierce Deity", Inventory.FIERCE_DEITY_MASK_OWNED, 1) },
            CheatCell("Equip FD", if (fdActive) "ON" else "NOW", fdActive, accentTone = true) { runInventoryAction(Inventory.EQUIP_FIERCE_DEITY) },
        )

        return listOf(row0, row1, row2, row3, row4, row5, row6, row7, row8, row9, row10)
    }

    private fun cheatsKey(key: Key) {
        if (key == Key.L || key == Key.R) {
            val n = cheatTabs.size
            cheatTabIndex = if (key == Key.L) (cheatTabIndex - 1 + n) % n else (cheatTabIndex + 1) % n
            cheatRow = 0
            cheatCol = 0
            listIndex = 0
            bitIndex = 0
            status = ""
            refreshItems()
            return
        }
        val tab = activeCheatTabName()
        if (tab == TAB_LIVE) {
            val grid = liveCheatGridRows()
            val row = grid.getOrElse(cheatRow) { emptyList() }
            when (key) {
                Key.UP -> {
                    cheatRow = (cheatRow - 1).coerceAtLeast(0)
                    cheatCol = cheatCol.coerceIn(0, (grid[cheatRow].size - 1).coerceAtLeast(0))
                }
                Key.DOWN -> {
                    cheatRow = (cheatRow + 1).coerceAtMost((grid.size - 1).coerceAtLeast(0))
                    cheatCol = cheatCol.coerceIn(0, (grid[cheatRow].size - 1).coerceAtLeast(0))
                }
                Key.LEFT -> if (cheatCol == 0) inPanel = false else cheatCol--
                Key.RIGHT -> if (cheatCol + 1 < row.size) cheatCol++
                Key.A -> row.getOrNull(cheatCol)?.onActivate?.invoke()
                else -> {}
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
                    status = "Emulator starting…"
                    return
                }
                val dir = if (key == Key.LEFT) -1 else 1
                val gen = itemsGen.incrementAndGet()
                var resultMsg: String? = null
                var refreshedValues: List<Int>? = null
                work("Updating ${row.label}…", {
                    resultMsg = Inventory.step(row, hostMemory, dir)
                    refreshedValues = rows.map { Inventory.read(it, hostMemory) }
                    null
                }) { problem ->
                    val vals = refreshedValues
                    if (vals != null && gen == itemsGen.get() && activeCheatTabName() == tab) {
                        itemValues = vals
                        loadedItemTab = tab
                        loadingItems = false
                    }
                    if (problem != null) status = problem
                    else if (resultMsg != null) status = resultMsg!!
                }
            }
            Key.A -> {
                val row = rows.getOrNull(listIndex) ?: return
                if (!host.nativeReady()) {
                    status = "Emulator starting…"
                    return
                }
                val bit = bitIndex
                val gen = itemsGen.incrementAndGet()
                var toggleMsg = ""
                var refreshedValues: List<Int>? = null
                work("Updating ${row.label}…", {
                    toggleMsg = Inventory.toggle(row, hostMemory, bit)
                    refreshedValues = rows.map { Inventory.read(it, hostMemory) }
                    null
                }) { problem ->
                    val vals = refreshedValues
                    if (vals != null && gen == itemsGen.get() && activeCheatTabName() == tab) {
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

    // ---- Capture panel -----------------------------------------------------

    private fun triggerScreenshot() {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        val gen = capturesGen.incrementAndGet()
        var msg = ""
        var loaded: List<MediaCapture.CaptureItem>? = null
        work("Taking screenshot…", {
            msg = host.takeScreenshot()
            loaded = host.recentCaptures()
            null
        }) { problem ->
            val items = loaded
            if (items != null && gen == capturesGen.get()) {
                captures = items
                loadingCaptures = false
            }
            captureTick++
            status = problem ?: msg
        }
    }

    private fun triggerVideoToggle() {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        val gen = capturesGen.incrementAndGet()
        var msg = ""
        var loaded: List<MediaCapture.CaptureItem>? = null
        work("Updating recording…", {
            msg = host.toggleVideoRecording()
            loaded = host.recentCaptures()
            null
        }) { problem ->
            val items = loaded
            if (items != null && gen == capturesGen.get()) {
                captures = items
                loadingCaptures = false
            }
            captureTick++
            status = problem ?: msg
        }
    }

    private fun triggerDumpReplay() {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        val gen = capturesGen.incrementAndGet()
        var msg = ""
        var loaded: List<MediaCapture.CaptureItem>? = null
        work("Saving 20s replay…", {
            msg = host.dumpReplayClip()
            loaded = host.recentCaptures()
            null
        }) { problem ->
            val items = loaded
            if (items != null && gen == capturesGen.get()) {
                captures = items
                loadingCaptures = false
            }
            captureTick++
            status = problem ?: msg
        }
    }

    private fun triggerStudySessionToggle() {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        val gen = capturesGen.incrementAndGet()
        var msg = ""
        var loaded: List<MediaCapture.CaptureItem>? = null
        work("Updating Study Session…", {
            msg = host.toggleStudySession()
            loaded = host.recentCaptures()
            null
        }) { problem ->
            val items = loaded
            if (items != null && gen == capturesGen.get()) {
                captures = items
                loadingCaptures = false
            }
            captureTick++
            status = problem ?: msg
        }
    }

    private fun triggerStudyBookmark(tag: String) {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        var msg = ""
        work("Saving bookmark…", {
            msg = host.addStudyBookmark(tag)
            null
        }) { problem ->
            captureTick++
            status = problem ?: msg
        }
    }

    private fun triggerBugSnapshot() {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        val category = MediaCapture.BUG_CATEGORIES.getOrElse(bugCategoryIndex) { "Bug" }
        val gen = capturesGen.incrementAndGet()
        var msg = ""
        var loaded: List<MediaCapture.CaptureItem>? = null
        work("Saving bug snapshot…", {
            msg = host.captureBugSnapshot(category)
            loaded = host.recentCaptures()
            null
        }) { problem ->
            val items = loaded
            if (items != null && gen == capturesGen.get()) {
                captures = items
                loadingCaptures = false
            }
            captureTick++
            status = problem ?: msg
        }
    }

    private fun activateCaptureItem(item: MediaCapture.CaptureItem) {
        if (item.kind == MediaCapture.Kind.BUG_SNAPSHOT && item.stateFile != null) {
            if (!host.nativeReady()) {
                status = "Emulator starting…"
                return
            }
            work("Loading ${item.name}…", {
                if (host.loadCaptureState(item.stateFile)) null else "Failed to load ${item.name}"
            }) { problem ->
                if (problem == null) close() else status = problem
            }
        } else {
            status = "${item.name} · ${item.detail}"
        }
    }

    private fun captureKey(key: Key) {
        val recent = captures
        val cols = captureCols.coerceAtLeast(1)
        when (captureSection) {
            0 -> { // Study Session row (menu-only): Start/Stop(0), Mark Moment(1), Mark Bug(2)
                when (key) {
                    Key.LEFT -> if (captureCol == 0) inPanel = false else captureCol--
                    Key.RIGHT -> if (captureCol < 2) captureCol++
                    Key.DOWN -> { captureSection = 1; captureCol = captureCol.coerceIn(0, 2) }
                    Key.A -> when (captureCol) {
                        0 -> triggerStudySessionToggle()
                        1 -> triggerStudyBookmark("moment")
                        2 -> triggerStudyBookmark("bug")
                    }
                    else -> {}
                }
            }
            1 -> { // Row 0: Screenshot(0), 20s Replay(1), MP4 Rec(2)
                when (key) {
                    Key.LEFT -> if (captureCol == 0) inPanel = false else captureCol--
                    Key.RIGHT -> if (captureCol < 2) captureCol++
                    Key.UP -> { captureSection = 0; captureCol = captureCol.coerceIn(0, 2) }
                    Key.DOWN -> { captureSection = 2; captureCol = captureCol.coerceIn(0, 3) }
                    Key.A -> when (captureCol) {
                        0 -> triggerScreenshot()
                        1 -> triggerDumpReplay()
                        2 -> triggerVideoToggle()
                    }
                    else -> {}
                }
            }
            2 -> { // Row 1: Bug Snap(0), Cat(1), Replay Buf(2), Rec HUD(3)
                when (key) {
                    Key.UP -> { captureSection = 1; captureCol = captureCol.coerceIn(0, 2) }
                    Key.DOWN -> if (recent.isNotEmpty()) {
                        captureSection = 3
                        captureGridIndex = captureGridIndex.coerceIn(0, recent.size - 1)
                    }
                    Key.LEFT -> if (captureCol == 0) inPanel = false else captureCol--
                    Key.RIGHT -> if (captureCol < 3) captureCol++
                    Key.A -> when (captureCol) {
                        0 -> triggerBugSnapshot()
                        1 -> {
                            val cats = MediaCapture.BUG_CATEGORIES
                            bugCategoryIndex = (bugCategoryIndex + 1) % cats.size
                            captureTick++
                            status = "Bug category: ${cats[bugCategoryIndex]}"
                        }
                        2 -> {
                            val next = !host.setting("replay")
                            host.setSetting("replay", next)
                            captureTick++
                            status = if (next) "20s replay buffer ON" else "Replay buffer OFF"
                        }
                        3 -> {
                            val next = !host.setting("rec_hud")
                            host.setSetting("rec_hud", next)
                            captureTick++
                            status = if (next) "Input HUD ON" else "Input HUD OFF"
                        }
                    }
                    else -> {}
                }
            }
            else -> { // Recent visual thumbnail grid
                if (recent.isEmpty()) {
                    captureSection = 2
                    return
                }
                val idx = captureGridIndex.coerceIn(0, recent.size - 1)
                when (key) {
                    Key.LEFT -> if (idx % cols == 0) inPanel = false else captureGridIndex = idx - 1
                    Key.RIGHT -> if ((idx % cols) < cols - 1 && idx + 1 < recent.size) captureGridIndex = idx + 1
                    Key.UP -> {
                        if (idx >= cols) {
                            captureGridIndex = idx - cols
                        } else {
                            captureSection = 2
                            captureCol = (idx % 4).coerceIn(0, 3)
                        }
                    }
                    Key.DOWN -> {
                        if (idx + cols < recent.size) captureGridIndex = idx + cols
                        else if (idx / cols < (recent.size - 1) / cols) captureGridIndex = recent.size - 1
                    }
                    Key.A -> activateCaptureItem(recent[idx])
                    else -> {}
                }
            }
        }
    }

    // ---- ROMs & Settings panel ---------------------------------------------

    private fun activateSettingCell(cell: SettingCell) {
        when (cell) {
            SettingCell.StickyFf -> {
                fastOn = !fastOn
                host.setStickyFastForward(fastOn)
                settingsTick++
                status = if (fastOn) "Sticky FF ${host.turboSpeedLabel()}" else "Sticky FF OFF"
            }
            SettingCell.Speed -> {
                host.cycleTurboSpeed(1)
                settingsTick++
                status = "FF speed: ${host.turboSpeedLabel()}"
            }
            is SettingCell.Toggle -> {
                val next = !host.setting(cell.key)
                host.setSetting(cell.key, next)
                settingsTick++
                if (cell.key == "r2_capture") {
                    status = if (next) "ZR Mode: Capture" else "ZR Mode: Fast Forward (${host.turboSpeedLabel()})"
                } else {
                    status = "${cell.label}: ${if (next) "ON" else "OFF"}"
                }
            }
        }
    }

    private fun triggerResetConsole() {
        if (!host.nativeReady()) {
            status = "Emulator starting…"
            return
        }
        ask("Reset console?", "Unsaved progress since last save will be lost.", "Reset") {
            work("Resetting…", {
                host.resetConsole()
                null
            }) { problem ->
                if (problem == null) close() else status = problem
            }
        }
    }

    private fun settingsKey(key: Key) {
        val romCount = cards.size
        when (settingsSection) {
            0 -> { // Quick action row: Reload ROM(0), Reset(1), Pin Home(2), Oracle Hub(3)
                when (key) {
                    Key.LEFT -> if (settingsActionCol == 0) inPanel = false else settingsActionCol--
                    Key.RIGHT -> if (settingsActionCol < 3) settingsActionCol++
                    Key.DOWN -> {
                        if (romCount > 0) {
                            settingsSection = 1
                            settingsRomIdx = settingsRomIdx.coerceIn(0, romCount - 1)
                        } else {
                            settingsSection = 2
                            settingsGridIdx = 0
                        }
                    }
                    Key.A -> when (settingsActionCol) {
                        0 -> reloadCurrentRom()
                        1 -> triggerResetConsole()
                        2 -> {
                            host.requestHomeShortcut()
                            status = "Home shortcut requested"
                        }
                        3 -> openLibrary()
                    }
                    else -> {}
                }
            }
            1 -> { // Horizontal Test ROMs channel strip
                if (romCount == 0) {
                    settingsSection = 2
                    return
                }
                when (key) {
                    Key.UP -> settingsSection = 0
                    Key.DOWN -> { settingsSection = 2; settingsGridIdx = 0 }
                    Key.LEFT -> if (settingsRomIdx == 0) inPanel = false else settingsRomIdx--
                    Key.RIGHT -> if (settingsRomIdx + 1 < romCount) settingsRomIdx++
                    Key.A -> cards.getOrNull(settingsRomIdx)?.let { card ->
                        if (isCurrent(card)) close() else boot(card)
                    }
                    else -> {}
                }
            }
            else -> { // 2-column Settings grid
                val n = settingsCells.size
                val idx = settingsGridIdx.coerceIn(0, n - 1)
                val cell = settingsCells[idx]
                when (key) {
                    Key.UP -> {
                        if (idx >= 2) settingsGridIdx = idx - 2
                        else if (romCount > 0) settingsSection = 1
                        else settingsSection = 0
                    }
                    Key.DOWN -> if (idx + 2 < n) settingsGridIdx = idx + 2
                    Key.LEFT -> {
                        if (cell is SettingCell.Speed && idx % 2 != 0) {
                            host.cycleTurboSpeed(-1)
                            settingsTick++
                            status = "FF speed: ${host.turboSpeedLabel()}"
                        } else if (idx % 2 == 0) {
                            inPanel = false
                        } else {
                            settingsGridIdx = idx - 1
                        }
                    }
                    Key.RIGHT -> {
                        if (cell is SettingCell.Speed) {
                            host.cycleTurboSpeed(1)
                            settingsTick++
                            status = "FF speed: ${host.turboSpeedLabel()}"
                        } else if (idx % 2 == 0 && idx + 1 < n) {
                            settingsGridIdx = idx + 1
                        }
                    }
                    Key.A -> activateSettingCell(cell)
                    else -> {}
                }
            }
        }
    }

    // ---- worker helper -----------------------------------------------------

    private fun work(progress: String, task: () -> String?, done: (String?) -> Unit) {
        busy = true
        status = progress
        worker.execute {
            val result = try {
                task()
            } catch (t: Throwable) {
                "Failed: ${t.message ?: t.javaClass.simpleName}"
            }
            postMain(Runnable {
                busy = false
                status = ""
                done(result)
            })
        }
    }

    // ---- UI drawing --------------------------------------------------------

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
        val savesState = rememberLazyListState()
        val saveRows = hubSaveRows()
        LaunchedEffect(libIndex) { listState.animateScrollToItem((libIndex - 1).coerceAtLeast(0)) }
        LaunchedEffect(hubSaveIndex, hubTab) {
            if (hubTab == HUB_TAB_SAVES && saveRows.isNotEmpty()) {
                savesState.animateScrollToItem((hubSaveIndex - 3).coerceAtLeast(0))
            }
        }
        val card = cards.getOrNull(libIndex)
        val actions = card?.let { libActions(it) } ?: emptyList()
        val headerMeta = when (hubTab) {
            HUB_TAB_WARPS -> "${filteredWarpCards().size} warps · ${clock(System.currentTimeMillis())}"
            HUB_TAB_SAVES -> "${saveRows.size} saves · ${clock(System.currentTimeMillis())}"
            else -> "${cards.size} ROMs · ${clock(System.currentTimeMillis())}"
        }
        Column(Modifier.fillMaxSize().background(Pal.BG).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt("ORACLE OF SECRETS", 10.sp, Pal.STONE, bold = true)
                    Txt("Oracle Hub", 19.sp, Pal.TEXT, bold = true)
                }
                Txt(headerMeta, 11.5.sp, Pal.DIM, bold = true)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HUB_TABS.forEachIndexed { idx, label ->
                    FilterPill(label, idx == hubTab) {
                        hubTab = idx
                        status = ""
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            when (hubTab) {
                HUB_TAB_WARPS -> PlayWarpsGrid(focused = true, modifier = Modifier.weight(1f))
                HUB_TAB_SAVES -> HubSavesPanel(saveRows, savesState, Modifier.weight(1f))
                else -> {
                    if (cards.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(Pal.PANEL).border(1.dp, Pal.LINE, RoundedCornerShape(10.dp)),
                            Alignment.Center,
                        ) {
                            Txt(
                                if (loadingLibrary) "Scanning ROMs…" else "No Oracle ROMs found",
                                13.sp,
                                Pal.DIM,
                            )
                        }
                    } else {
                        LazyRow(
                            state = listState,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.height(208.dp),
                        ) {
                            itemsIndexed(cards) { i, c ->
                                RomCardView(c, i == libIndex, libRow == 0) {
                                    if (busy) return@RomCardView
                                    libIndex = i
                                    libRow = 0
                                    if (isCurrent(c)) close() else boot(c)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Column(
                            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(Pal.PANEL).border(1.dp, Pal.LINE, RoundedCornerShape(10.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            if (card != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Txt(card.entry.channel, 16.sp, Pal.TEXT, bold = true)
                                    if (card.entry.build.isNotEmpty()) {
                                        Spacer(Modifier.width(8.dp))
                                        Badge(card.entry.build, Pal.PANEL2, Pal.STONE)
                                    }
                                    if (card.entry.pinned) {
                                        Spacer(Modifier.width(6.dp))
                                        Badge("PINNED", Pal.PANEL2, Pal.ICE)
                                    }
                                    if (isCurrent(card)) {
                                        Spacer(Modifier.width(6.dp))
                                        Badge("RUNNING", Pal.ACCENT, Pal.INK)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Txt(cardCompactMeta(card), 11.sp, Pal.DIM, bold = true)
                                }
                                Spacer(Modifier.weight(1f))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    actions.forEachIndexed { i, a ->
                                        val on = (libRow == 1 && i == libAction) || (libRow == 0 && i == 0)
                                        ActionButton(a.label, on, libRow == 1 && i == libAction, Modifier.weight(1f)) {
                                            if (!busy) a.run()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            HintBar(if (status.isNotEmpty()) status else null, hubHints())
        }
    }

    @Composable
    private fun HubSavesPanel(
        rows: List<HubSaveRow>,
        listState: androidx.compose.foundation.lazy.LazyListState,
        modifier: Modifier,
    ) {
        if (rows.isEmpty()) {
            Box(
                modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Pal.PANEL).border(1.dp, Pal.LINE, RoundedCornerShape(10.dp)),
                Alignment.Center,
            ) {
                Txt(if (loadingLibrary) "Scanning saves…" else "No saves yet", 13.sp, Pal.DIM)
            }
            return
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier.fillMaxWidth()) {
            itemsIndexed(rows) { idx, row ->
                val selected = idx == hubSaveIndex
                val touch = remember { MutableInteractionSource() }
                when (row) {
                    is HubSaveRow.State -> {
                        val preview = rememberPreview(row.entry.file, row.entry.modified)
                        val slotTag = if (row.entry.slot == SaveStateSlots.AUTO_SLOT) "Auto" else "Slot ${row.entry.slot}"
                        val buildBit = if (row.card.entry.build.isNotEmpty()) " ${row.card.entry.build}" else ""
                        val detail = listOfNotNull(whenText(row.entry.modified), row.entry.label.ifEmpty { null }).joinToString(" · ")
                        Row(
                            Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Pal.PANEL2 else Pal.PANEL)
                                .border(1.5.dp, if (selected) Pal.ACCENT else Pal.LINE, RoundedCornerShape(10.dp))
                                .clickable(interactionSource = touch, indication = null) {
                                    if (busy) return@clickable
                                    hubSaveIndex = idx
                                    activateHubSave(row)
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Thumb(preview, Modifier.width(66.dp).fillMaxHeight(), "STATE")
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Txt("${row.card.entry.channel}$buildBit · $slotTag", 13.sp, Pal.TEXT, bold = true)
                                Txt(detail, 11.sp, Pal.DIM, bold = true)
                            }
                            Badge("STATE", Pal.PANEL2, Pal.ACCENT)
                        }
                    }
                    is HubSaveRow.Battery -> {
                        val buildBit = if (row.card.entry.build.isNotEmpty()) " ${row.card.entry.build}" else ""
                        val saveText = row.card.saveLabel.ifEmpty { "Battery .srm" }
                        Row(
                            Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Pal.PANEL2 else Pal.PANEL)
                                .border(1.5.dp, if (selected) Pal.ACCENT else Pal.LINE, RoundedCornerShape(10.dp))
                                .clickable(interactionSource = touch, indication = null) {
                                    if (busy) return@clickable
                                    hubSaveIndex = idx
                                    activateHubSave(row)
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Badge("SRAM", Pal.PANEL2, Pal.STONE)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Txt("${row.card.entry.channel}$buildBit", 13.sp, Pal.TEXT, bold = true)
                                Txt(saveText, 11.sp, Pal.DIM, bold = true)
                            }
                            Txt("Boot Save", 11.sp, if (selected) Pal.ACCENT else Pal.DIM, bold = true)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RomCardView(card: RomCard, selected: Boolean, rowFocused: Boolean, onClick: () -> Unit) {
        val newest = card.newest
        val liveThumb = rememberPreview(newest?.file, newest?.modified ?: 0L)
        val fallbackAsset = rememberAssetPreview(romFallbackCandidates(card))
        val preview = liveThumb ?: fallbackAsset
        val borderColor = if (selected) (if (rowFocused) Pal.ACCENT else Pal.ICE) else Pal.LINE
        val touch = remember { MutableInteractionSource() }
        Column(
            Modifier.width(206.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                .background(if (selected) Pal.PANEL2 else Pal.PANEL)
                .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
                .clickable(interactionSource = touch, indication = null, onClick = onClick)
                .padding(6.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().height(152.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0A0E14))
                    .border(1.dp, Pal.LINE, RoundedCornerShape(6.dp)),
                Alignment.Center,
            ) {
                if (preview != null) {
                    Image(preview, card.entry.channel, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    CategoryTile("ROM", card.entry.channel.take(6).uppercase(Locale.US), Pal.ICE)
                }
                if (isCurrent(card)) {
                    Box(Modifier.align(Alignment.TopEnd).padding(5.dp)) {
                        Badge("LIVE", Pal.ACCENT, Pal.INK)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Txt(card.entry.channel, 14.sp, Pal.TEXT, bold = true) }
                if (card.entry.build.isNotEmpty()) Txt(card.entry.build, 11.5.sp, Pal.STONE, bold = true)
            }
            Txt(cardSubtitle(card), 11.sp, Pal.DIM, bold = true)
        }
    }

    @Composable
    private fun MenuScreen() {
        val item = railItem
        val railState = rememberLazyListState()
        LaunchedEffect(rail) { railState.animateScrollToItem((rail - 2).coerceAtLeast(0)) }
        val menuTouch = remember { MutableInteractionSource() }
        Column(
            Modifier.fillMaxSize().background(Pal.SCRIM)
                .clickable(interactionSource = menuTouch, indication = null) {},
        ) {
            Row(Modifier.weight(1f)) {
                Column(
                    Modifier.width(194.dp).fillMaxHeight().background(Pal.PANEL)
                        .border(1.dp, Pal.LINE)
                        .padding(horizontal = 10.dp),
                ) {
                    Spacer(Modifier.height(10.dp))
                    Txt("ORACLE OF SECRETS", 9.5.sp, Pal.STONE, bold = true)
                    Txt(menuTitle, 15.5.sp, Pal.TEXT, bold = true)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.LINE))
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        state = railState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        itemsIndexed(RailItem.values()) { i, r ->
                            RailRow(r, i == rail) {
                                if (busy) return@RailRow
                                rail = i
                                status = ""
                                activateRail()
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Txt("ZL Menu · ZR ${host.turboSpeedLabel()}", 10.sp, Pal.DIM, bold = true)
                    Spacer(Modifier.height(8.dp))
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Txt(item.label, 17.sp, Pal.TEXT, bold = true) }
                        Txt(panelMeta(item), 11.5.sp, Pal.DIM, bold = true)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (item) {
                            RailItem.RESUME -> ResumePreviewPanel()
                            RailItem.PLAY -> PlayWarpsGrid(focused = inPanel, modifier = Modifier.fillMaxSize())
                            RailItem.STATES -> StatesPanel()
                            RailItem.CHEATS -> CheatsPanel()
                            RailItem.CAPTURE -> CapturePanel()
                            RailItem.SETTINGS -> SettingsPanel()
                        }
                    }
                }
            }
            HintBar(if (status.isNotEmpty()) status else null, menuHints(item))
        }
    }

    @Composable
    private fun RailRow(item: RailItem, selected: Boolean, onClick: () -> Unit) {
        val focused = selected && !inPanel
        val touch = remember { MutableInteractionSource() }
        @Suppress("UNUSED_VARIABLE") val tick = captureTick + cheatsTick + settingsTick
        Row(
            Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp))
                .background(if (selected) Pal.PANEL2 else Color.Transparent)
                .border(
                    width = if (focused) 1.5.dp else if (selected) 1.dp else 0.dp,
                    color = when {
                        focused -> Pal.ACCENT
                        selected -> Pal.LINE
                        else -> Color.Transparent
                    },
                    shape = RoundedCornerShape(8.dp),
                )
                .clickable(interactionSource = touch, indication = null, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(3.dp).height(18.dp).background(
                    when {
                        focused -> Pal.ACCENT
                        selected -> Pal.ICE
                        else -> Color.Transparent
                    }
                ),
            )
            Spacer(Modifier.width(9.dp))
            Box(Modifier.weight(1f)) {
                Txt(item.label, 13.sp, if (selected) Pal.TEXT else Pal.DIM, bold = true)
            }
            when (item) {
                RailItem.PLAY -> {
                    val n = shortcuts.size
                    if (n > 0) {
                        Badge("$n", Pal.PANEL, Pal.ICE)
                        Spacer(Modifier.width(6.dp))
                    }
                }
                RailItem.STATES -> {
                    val used = states.count { it.occupied }
                    if (used > 0) {
                        Badge("$used", Pal.PANEL, Pal.STONE)
                        Spacer(Modifier.width(6.dp))
                    }
                }
                RailItem.CHEATS -> {
                    val total = host.testingTools().activeCheatCount() + Integer.bitCount(host.debugOverlayMask() and 0x07)
                    if (total > 0) {
                        Badge("$total", Pal.ACCENT, Pal.INK)
                        Spacer(Modifier.width(6.dp))
                    }
                }
                RailItem.CAPTURE -> when {
                    host.isVideoRecording() -> {
                        Badge("REC", Pal.RED, Color.White)
                        Spacer(Modifier.width(6.dp))
                    }
                    host.isReplayBufferActive() -> {
                        Badge("20s", Pal.ACCENT, Pal.INK)
                        Spacer(Modifier.width(6.dp))
                    }
                }
                RailItem.SETTINGS -> {
                    Badge(host.turboSpeedLabel(), Pal.PANEL, Pal.ICE)
                    Spacer(Modifier.width(6.dp))
                }
                RailItem.RESUME -> {}
            }
        }
    }

    @Composable
    private fun ResumePreviewPanel() {
        val currentCard = cards.firstOrNull { isCurrent(it) } ?: cards.firstOrNull()
        val newest = currentCard?.newest
        val liveThumb = rememberPreview(newest?.file, newest?.modified ?: 0L)
        val fallback = rememberAssetPreview(listOf("showcase_main"))
        val preview = liveThumb ?: fallback
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(Pal.PANEL).border(1.dp, Pal.LINE, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.width(220.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0A0E14)).border(1.dp, Pal.LINE, RoundedCornerShape(8.dp)),
                    Alignment.Center,
                ) {
                    if (preview != null) {
                        Image(preview, menuTitle, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        CategoryTile("ACTIVE", menuTitle.take(8).uppercase(Locale.US), Pal.ACCENT)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(menuTitle, 18.sp, Pal.TEXT, bold = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Badge("Slot $activeSaveSlot", Pal.PANEL2, Pal.ICE)
                        if (fastOn) Badge("FF ${host.turboSpeedLabel()}", Pal.ACCENT, Pal.INK)
                        val activeCheats = host.testingTools().activeCheatCount()
                        if (activeCheats > 0) Badge("$activeCheats Cheats", Pal.PANEL2, Pal.STONE)
                    }
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("Resume", filled = true, outlined = true, Modifier.weight(1f)) { close() }
                        ActionButton("Reload ROM", filled = false, outlined = false, Modifier.weight(1f)) {
                            if (!busy) reloadCurrentRom()
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlChip("ZL", "Menu", Modifier.weight(1f))
                ControlChip("ZR", if (host.setting("r2_capture")) "Capture" else "Fast Fwd", Modifier.weight(1f))
                ControlChip("L3", "Rec MP4", Modifier.weight(1f))
                ControlChip("R3", "Screenshot", Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun ControlChip(button: String, action: String, modifier: Modifier) {
        Row(
            modifier.height(34.dp).clip(RoundedCornerShape(8.dp))
                .background(Pal.PANEL).border(1.dp, Pal.LINE, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt(button, 11.5.sp, Pal.ICE, bold = true)
            Txt(action, 11.5.sp, Pal.DIM, bold = true)
        }
    }

    @Composable
    private fun PlayWarpsGrid(focused: Boolean, modifier: Modifier) {
        val items = filteredWarpCards()
        Column(modifier) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WarpCategory.values().forEach { cat ->
                    FilterPill(cat.label, cat == warpCategory) {
                        warpCategory = cat
                        warpIndex = 0
                        status = ""
                    }
                }
                Spacer(Modifier.weight(1f))
                val touch = remember { MutableInteractionSource() }
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Pal.PANEL)
                        .border(1.dp, Pal.LINE, RoundedCornerShape(10.dp))
                        .clickable(interactionSource = touch, indication = null) {
                            if (!busy) refreshShortcuts(announce = true)
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Txt("Rescan", 11.sp, Pal.DIM, bold = true)
                }
            }
            Spacer(Modifier.height(8.dp))

            val hasFallBar = warpCategory == WarpCategory.ROOMS && items.firstOrNull()?.action is WarpAction.FallRoom
            if (hasFallBar) {
                val fallSelected = focused && warpIndex == 0
                FallRoomBar(fallSelected, items[0])
                Spacer(Modifier.height(8.dp))
            }

            val gridItems = if (hasFallBar) items.drop(1) else items
            val gridOffset = if (hasFallBar) 1 else 0

            if (gridItems.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(Pal.PANEL).border(1.dp, Pal.LINE, RoundedCornerShape(10.dp)),
                    Alignment.Center,
                ) {
                    Txt(if (loadingShortcuts) "Loading…" else "No targets here", 13.sp, Pal.DIM)
                }
                return
            }

            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val cols = if (maxWidth >= 500.dp) 3 else 2
                LaunchedEffect(cols) { warpCols = cols }
                val rows = gridItems.chunked(cols)
                val gridState = rememberLazyListState()
                LaunchedEffect(warpIndex, focused, cols, gridItems.size, warpCategory) {
                    if (focused && gridItems.isNotEmpty() && warpIndex >= gridOffset) {
                        val rowIdx = ((warpIndex - gridOffset).coerceIn(0, gridItems.size - 1)) / cols
                        gridState.animateScrollToItem(rowIdx)
                    }
                }
                LazyColumn(
                    state = gridState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(rows) { rowIdx, rowCards ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            for (col in 0 until cols) {
                                val card = rowCards.getOrNull(col)
                                if (card == null) {
                                    Spacer(Modifier.weight(1f))
                                } else {
                                    val globalIdx = gridOffset + rowIdx * cols + col
                                    WarpCardView(
                                        item = card,
                                        selected = focused && globalIdx == warpIndex,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        if (busy) return@WarpCardView
                                        if (screen == Screen.MENU) inPanel = true
                                        warpIndex = globalIdx
                                        activateWarpCard(card)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FallRoomBar(selected: Boolean, item: WarpCardItem) {
        val roomHex = String.format(Locale.US, "$%04X", fallRoomId)
        val n = Progression.MAX_ROOM_ID + 1
        Row(
            Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(8.dp))
                .background(if (selected) Pal.PANEL2 else Pal.PANEL)
                .border(if (selected) 1.5.dp else 1.dp, if (selected) Pal.ACCENT else Pal.LINE, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Txt("Fall Room", 12.5.sp, Pal.TEXT, bold = true)
            Badge(roomHex, Pal.BG, Pal.ICE)
            Spacer(Modifier.weight(1f))
            SmallStepBtn("-10") { fallRoomId = (fallRoomId - 10 + n) % n }
            SmallStepBtn("-1") { fallRoomId = (fallRoomId - 1 + n) % n }
            SmallStepBtn("+1") { fallRoomId = (fallRoomId + 1) % n }
            SmallStepBtn("+10") { fallRoomId = (fallRoomId + 10) % n }
            SmallStepBtn("Drop", highlight = true) {
                if (!busy) activateWarpCard(item)
            }
        }
    }

    @Composable
    private fun SmallStepBtn(label: String, highlight: Boolean = false, onClick: () -> Unit) {
        val touch = remember { MutableInteractionSource() }
        Box(
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(if (highlight) Pal.ACCENT else Pal.PANEL2)
                .border(1.dp, if (highlight) Pal.ACCENT else Pal.LINE, RoundedCornerShape(6.dp))
                .clickable(interactionSource = touch, indication = null, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Txt(label, 11.sp, if (highlight) Pal.INK else Pal.TEXT, bold = true)
        }
    }

    @Composable
    private fun WarpCardView(
        item: WarpCardItem,
        selected: Boolean,
        modifier: Modifier,
        onClick: () -> Unit,
    ) {
        val shortcutImg = (item.action as? WarpAction.Shortcut)?.let { rememberShortcutThumb(it.card) }
        val assetImg = if (shortcutImg == null) rememberAssetPreview(item.previewCandidates) else null
        val preview = shortcutImg ?: assetImg
        val touch = remember { MutableInteractionSource() }
        Column(
            modifier.height(148.dp).clip(RoundedCornerShape(10.dp))
                .background(if (selected) Pal.PANEL2 else Pal.PANEL)
                .border(if (selected) 1.5.dp else 1.dp, if (selected) Pal.ACCENT else Pal.LINE, RoundedCornerShape(10.dp))
                .clickable(interactionSource = touch, indication = null, onClick = onClick)
                .padding(6.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0A0E14))
                    .border(1.dp, if (selected) Pal.ACCENT.copy(alpha = 0.5f) else Pal.LINE, RoundedCornerShape(6.dp)),
                Alignment.Center,
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    CategoryTile(
                        category = item.category.label.uppercase(Locale.US),
                        code = item.code,
                        accent = categoryAccent(item.category),
                    )
                }
                if (item.badge.isNotEmpty()) {
                    val (bg, fg) = warpBadgeColors(item.badge)
                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                        Badge(item.badge, bg, fg)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Txt(item.title, 12.5.sp, if (selected) Pal.ACCENT else Pal.TEXT, bold = true)
            Txt(item.tag, 10.sp, Pal.DIM, bold = true)
        }
    }

    @Composable
    private fun CategoryTile(category: String, code: String, accent: Color) {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF10161F)).padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Badge(category, Pal.PANEL2, accent)
            Spacer(Modifier.height(5.dp))
            Txt(code, 15.sp, Pal.TEXT, bold = true)
        }
    }

    @Composable
    private fun StatesPanel() {
        val slots = currentPageSlots()
        val used = states.count { it.occupied }
        val pageCount = statePageCount
        val page = statesPage.coerceIn(0, pageCount - 1)

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterPill("Load", !statesSaveMode) { setStatesMode(false) }
                FilterPill("Save", statesSaveMode) { setStatesMode(true) }
                Spacer(Modifier.weight(1f))
                if (pageCount > 1) {
                    FilterPill("Page ${page + 1}/$pageCount", true, mutedStyle = true) {
                        statesPage = (page + 1) % pageCount
                        slotIndex = 0
                    }
                }
                Txt("$used saved", 11.sp, Pal.DIM, bold = true)
            }
            Spacer(Modifier.height(8.dp))

            if (states.isEmpty() && loadingStates) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Txt("Loading states…", 13.sp, Pal.DIM)
                }
                return
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in 0 until 2) {
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (col in 0 until 4) {
                            val i = row * 4 + col
                            val entry = slots.getOrNull(i)
                            if (entry == null) {
                                Spacer(Modifier.weight(1f))
                            } else {
                                SlotCard(
                                    entry = entry,
                                    selected = inPanel && i == slotIndex,
                                    saving = statesSaveMode,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                ) {
                                    if (busy) return@SlotCard
                                    inPanel = true
                                    slotIndex = i
                                    if (statesSaveMode) saveSlot(entry) else loadSlot(entry)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SlotCard(
        entry: SaveStateSlots.Entry,
        selected: Boolean,
        saving: Boolean,
        modifier: Modifier,
        onClick: () -> Unit,
    ) {
        val preview = rememberPreview(if (entry.occupied) entry.file else null, entry.modified)
        val touch = remember { MutableInteractionSource() }
        val slotLabel = if (entry.slot == SaveStateSlots.AUTO_SLOT) "Auto" else "Slot ${entry.slot}"
        val shortTime = if (!entry.occupied) (if (saving) "Tap Save" else "Empty") else clock(entry.modified)
        Column(
            modifier.clip(RoundedCornerShape(10.dp))
                .background(if (selected) Pal.PANEL2 else Pal.PANEL)
                .border(if (selected) 1.5.dp else 1.dp, if (selected) Pal.ACCENT else Pal.LINE, RoundedCornerShape(10.dp))
                .clickable(interactionSource = touch, indication = null, onClick = onClick)
                .padding(5.dp),
        ) {
            Thumb(
                image = preview,
                modifier = Modifier.fillMaxWidth().weight(1f),
                empty = if (saving) "+" else "—",
                big = saving,
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(slotLabel, 11.5.sp, if (selected) Pal.ACCENT else Pal.STONE, bold = true)
                Spacer(Modifier.weight(1f))
                Txt(shortTime, 10.5.sp, if (entry.occupied) Pal.TEXT else Pal.DIM, bold = true)
            }
        }
    }

    @Composable
    private fun CheatsPanel() {
        @Suppress("UNUSED_VARIABLE") val tick = cheatsTick
        val tab = activeCheatTabName()
        val state = rememberLazyListState()
        val tabRowState = rememberLazyListState()
        LaunchedEffect(cheatTabIndex) {
            tabRowState.animateScrollToItem((cheatTabIndex - 1).coerceAtLeast(0))
        }
        LaunchedEffect(listIndex, cheatRow, inPanel, cheatTabIndex) {
            if (inPanel) {
                val target = if (tab == TAB_LIVE) cheatRow else listIndex
                state.animateScrollToItem((target - 2).coerceAtLeast(0))
            }
        }

        Column(Modifier.fillMaxSize()) {
            LazyRow(state = tabRowState, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(cheatTabs) { i, g ->
                    FilterPill(g, i == cheatTabIndex) {
                        cheatTabIndex = i
                        cheatRow = 0
                        cheatCol = 0
                        listIndex = 0
                        bitIndex = 0
                        status = ""
                        refreshItems()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            if (tab == TAB_LIVE) {
                val rows = liveCheatGridRows()
                val sectionHeaders = mapOf(
                    0 to "LIVE CHEATS",
                    2 to "TOOLS & DEBUG HUD",
                    5 to "GEAR, MASKS & PROGRESS",
                )
                LazyColumn(
                    state = state,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    itemsIndexed(rows) { rIdx, rowCells ->
                        sectionHeaders[rIdx]?.let { hdr ->
                            Txt(hdr, 9.5.sp, Pal.STONE, bold = true)
                            Spacer(Modifier.height(2.dp))
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            for (cIdx in 0 until 4) {
                                val cell = rowCells.getOrNull(cIdx)
                                if (cell == null) {
                                    Spacer(Modifier.weight(1f))
                                } else {
                                    val focused = inPanel && rIdx == cheatRow && cIdx == cheatCol
                                    ChipTile(
                                        cell = cell,
                                        focused = focused,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        if (busy) return@ChipTile
                                        inPanel = true
                                        cheatRow = rIdx
                                        cheatCol = cIdx
                                        cell.onActivate()
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    itemsIndexed(itemRows) { i, row ->
                        val value = itemValues.getOrElse(i) { -1 }
                        val pending = loadingItems && i >= itemValues.size
                        val selected = inPanel && i == listIndex
                        if (row.kind == Inventory.Kind.BITS) {
                            BitsRow(row, value, selected, bitIndex, pending)
                        } else {
                            val tag = when {
                                row.kind == Inventory.Kind.ACTION -> "Apply"
                                pending -> "…"
                                else -> Inventory.valueText(row, value)
                            }
                            val color = if (row.kind != Inventory.Kind.ACTION && !pending && value > 0) Pal.ACCENT else Pal.DIM
                            ListRow(row.label, tag, selected, tagColor = color) {
                                if (!busy) {
                                    inPanel = true
                                    listIndex = i
                                    cheatsKey(Key.A)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ChipTile(cell: CheatCell, focused: Boolean, modifier: Modifier, onClick: () -> Unit) {
        val touch = remember { MutableInteractionSource() }
        val bg = when {
            cell.active -> Pal.ACCENT.copy(alpha = 0.22f)
            focused -> Pal.PANEL2
            else -> Pal.PANEL
        }
        val borderColor = when {
            focused -> Pal.ACCENT
            cell.active -> Pal.ACCENT.copy(alpha = 0.7f)
            cell.accentTone -> Pal.ICE.copy(alpha = 0.45f)
            else -> Pal.LINE
        }
        Row(
            modifier.height(34.dp).clip(RoundedCornerShape(8.dp))
                .background(bg)
                .border(if (focused) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
                .clickable(interactionSource = touch, indication = null, onClick = onClick)
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (cell.active) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Pal.ACCENT))
                Spacer(Modifier.width(5.dp))
            }
            Box(Modifier.weight(1f)) {
                Txt(cell.label, 10.5.sp, if (cell.active || focused) Pal.TEXT else Pal.DIM, bold = true)
            }
            if (cell.tag.isNotEmpty()) {
                Txt(cell.tag, 10.sp, if (cell.active) Pal.ACCENT else Pal.ICE, bold = true)
            }
        }
    }

    @Composable
    private fun CapturePanel() {
        @Suppress("UNUSED_VARIABLE") val tick = captureTick
        val studyActive = host.isStudySessionActive()
        val studySummary = host.studySessionSummary()
        val recording = host.isVideoRecording()
        val elapsed = host.videoRecordingElapsedSeconds()
        val replayOn = host.setting("replay")
        val replayActive = host.isReplayBufferActive()
        val replaySecs = host.replayBufferedSeconds()
        val category = MediaCapture.BUG_CATEGORIES.getOrElse(bugCategoryIndex) { "Bug" }
        val shortCat = category.substringBefore(" /").trim()
        val hudOn = host.setting("rec_hud")

        val studyRow = listOf(
            CheatCell(
                if (studyActive) "Stop Study" else "Start Study",
                if (studyActive) "ON" else "10m",
                studyActive,
                accentTone = true,
            ) { triggerStudySessionToggle() },
            CheatCell("Mark Moment", "Note", false) { triggerStudyBookmark("moment") },
            CheatCell("Mark Bug", "Snap", false) { triggerStudyBookmark("bug") },
        )
        val row0 = listOf(
            CheatCell("Screenshot", "PNG", false, accentTone = true) { triggerScreenshot() },
            CheatCell("20s Replay", if (replayActive) "${replaySecs}s" else "Off", replayActive && replaySecs > 0) {
                triggerDumpReplay()
            },
            CheatCell(
                if (recording) "Stop Rec" else "MP4 Rec",
                if (recording) MediaCapture.formatDuration(elapsed) else "Start",
                recording,
            ) { triggerVideoToggle() },
        )
        val row1 = listOf(
            CheatCell("Bug Snap", "Save", false, accentTone = true) { triggerBugSnapshot() },
            CheatCell("Cat: $shortCat", "↻", false) {
                val cats = MediaCapture.BUG_CATEGORIES
                bugCategoryIndex = (bugCategoryIndex + 1) % cats.size
                captureTick++
                status = "Bug category: ${cats[bugCategoryIndex]}"
            },
            CheatCell("Replay Buf", if (replayOn) "ON" else "OFF", replayOn) {
                val next = !host.setting("replay")
                host.setSetting("replay", next)
                captureTick++
                status = if (next) "20s replay buffer ON" else "Replay buffer OFF"
            },
            CheatCell("Rec HUD", if (hudOn) "ON" else "OFF", hudOn) {
                val next = !host.setting("rec_hud")
                host.setSetting("rec_hud", next)
                captureTick++
            },
        )

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Txt("STUDY SESSION (MENU-ONLY)", 9.5.sp, if (studyActive) Pal.ACCENT else Pal.STONE, bold = true)
                Spacer(Modifier.weight(1f))
                if (studyActive && studySummary.isNotEmpty()) {
                    Txt(studySummary, 10.sp, Pal.ICE, bold = true)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                studyRow.forEachIndexed { col, cell ->
                    ChipTile(cell, inPanel && captureSection == 0 && captureCol == col, Modifier.weight(1f)) {
                        if (busy) return@ChipTile
                        inPanel = true
                        captureSection = 0
                        captureCol = col
                        cell.onActivate()
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row0.forEachIndexed { col, cell ->
                    ChipTile(cell, inPanel && captureSection == 1 && captureCol == col, Modifier.weight(1f)) {
                        if (busy) return@ChipTile
                        inPanel = true
                        captureSection = 1
                        captureCol = col
                        cell.onActivate()
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row1.forEachIndexed { col, cell ->
                    ChipTile(cell, inPanel && captureSection == 2 && captureCol == col, Modifier.weight(1f)) {
                        if (busy) return@ChipTile
                        inPanel = true
                        captureSection = 2
                        captureCol = col
                        cell.onActivate()
                    }
                }
            }

            Txt("RECENT CAPTURES", 9.5.sp, Pal.STONE, bold = true)
            if (loadingCaptures && captures.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                    Txt("Scanning Captures…", 12.sp, Pal.DIM)
                }
            } else if (captures.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(Pal.PANEL).border(1.dp, Pal.LINE, RoundedCornerShape(10.dp)),
                    Alignment.Center,
                ) {
                    Txt("No captures yet", 12.sp, Pal.DIM)
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val cols = if (maxWidth >= 500.dp) 3 else 2
                    LaunchedEffect(cols) { captureCols = cols }
                    val rows = captures.chunked(cols)
                    val gridState = rememberLazyListState()
                    LaunchedEffect(captureGridIndex, captureSection, cols, captures.size) {
                        if (inPanel && captureSection == 3 && captures.isNotEmpty()) {
                            val rIdx = captureGridIndex.coerceIn(0, captures.size - 1) / cols
                            gridState.animateScrollToItem(rIdx)
                        }
                    }
                    LazyColumn(state = gridState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(rows) { rIdx, rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                for (col in 0 until cols) {
                                    val cap = rowItems.getOrNull(col)
                                    if (cap == null) {
                                        Spacer(Modifier.weight(1f))
                                    } else {
                                        val globalIdx = rIdx * cols + col
                                        CaptureThumbCard(
                                            item = cap,
                                            selected = inPanel && captureSection == 3 && globalIdx == captureGridIndex,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            if (busy) return@CaptureThumbCard
                                            inPanel = true
                                            captureSection = 3
                                            captureGridIndex = globalIdx
                                            activateCaptureItem(cap)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CaptureThumbCard(
        item: MediaCapture.CaptureItem,
        selected: Boolean,
        modifier: Modifier,
        onClick: () -> Unit,
    ) {
        val thumb = rememberCaptureThumb(item)
        val touch = remember { MutableInteractionSource() }
        val kindBadge = when (item.kind) {
            MediaCapture.Kind.SCREENSHOT -> "PNG"
            MediaCapture.Kind.VIDEO -> "MP4"
            MediaCapture.Kind.BUG_SNAPSHOT -> "BUG"
        }
        val badgeFg = if (item.kind == MediaCapture.Kind.BUG_SNAPSHOT) Pal.STONE else Pal.ICE
        Column(
            modifier.height(126.dp).clip(RoundedCornerShape(10.dp))
                .background(if (selected) Pal.PANEL2 else Pal.PANEL)
                .border(if (selected) 1.5.dp else 1.dp, if (selected) Pal.ACCENT else Pal.LINE, RoundedCornerShape(10.dp))
                .clickable(interactionSource = touch, indication = null, onClick = onClick)
                .padding(5.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0A0E14)).border(1.dp, Pal.LINE, RoundedCornerShape(6.dp)),
                Alignment.Center,
            ) {
                if (thumb != null) {
                    Image(thumb, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.None)
                } else {
                    CategoryTile(kindBadge, item.detail.take(8), badgeFg)
                }
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    Badge(kindBadge, Pal.PANEL, badgeFg)
                }
            }
            Spacer(Modifier.height(4.dp))
            Txt(item.name, 11.sp, if (selected) Pal.ACCENT else Pal.TEXT, bold = true)
            Txt(item.detail, 9.5.sp, Pal.DIM)
        }
    }

    @Composable
    private fun SettingsPanel() {
        @Suppress("UNUSED_VARIABLE") val tick = settingsTick
        val quickActions = listOf(
            CheatCell("Reload ROM", "Disk", false, accentTone = true) { reloadCurrentRom() },
            CheatCell("Reset", "Boot", false) { triggerResetConsole() },
            CheatCell("Pin Home", "Icon", false) {
                host.requestHomeShortcut()
                status = "Home shortcut requested"
            },
            CheatCell("Oracle Hub", "Full", false) { openLibrary() },
        )

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                quickActions.forEachIndexed { col, cell ->
                    ChipTile(cell, inPanel && settingsSection == 0 && settingsActionCol == col, Modifier.weight(1f)) {
                        if (busy) return@ChipTile
                        inPanel = true
                        settingsSection = 0
                        settingsActionCol = col
                        cell.onActivate()
                    }
                }
            }

            Txt("TEST ROM CHANNELS", 9.5.sp, Pal.STONE, bold = true)
            if (cards.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(8.dp))
                        .background(Pal.PANEL).border(1.dp, Pal.LINE, RoundedCornerShape(8.dp)),
                    Alignment.Center,
                ) {
                    Txt(if (loadingLibrary) "Scanning ROMs…" else "No ROMs scanned", 11.5.sp, Pal.DIM)
                }
            } else {
                val romRowState = rememberLazyListState()
                LaunchedEffect(settingsRomIdx, settingsSection) {
                    if (inPanel && settingsSection == 1) romRowState.animateScrollToItem((settingsRomIdx - 1).coerceAtLeast(0))
                }
                LazyRow(
                    state = romRowState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().height(92.dp),
                ) {
                    itemsIndexed(cards) { idx, card ->
                        CompactRomTile(
                            card = card,
                            selected = inPanel && settingsSection == 1 && idx == settingsRomIdx,
                            current = isCurrent(card),
                        ) {
                            if (busy) return@CompactRomTile
                            inPanel = true
                            settingsSection = 1
                            settingsRomIdx = idx
                            if (isCurrent(card)) close() else boot(card)
                        }
                    }
                }
            }

            Txt("SPEED & CONTROLS", 9.5.sp, Pal.STONE, bold = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val cellRows = settingsCells.chunked(2)
                cellRows.forEachIndexed { rIdx, pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEachIndexed { cIdx, cell ->
                            val globalIdx = rIdx * 2 + cIdx
                            val (valueLabel, isOn) = when (cell) {
                                SettingCell.StickyFf -> (if (fastOn) "ON" else "OFF") to fastOn
                                SettingCell.Speed -> host.turboSpeedLabel() to true
                                is SettingCell.Toggle -> {
                                    val on = host.setting(cell.key)
                                    val txt = if (cell.key == "r2_capture") {
                                        if (on) "Capture" else "Fast Fwd"
                                    } else {
                                        if (on) "ON" else "OFF"
                                    }
                                    txt to (if (cell.key == "r2_capture") !on else on)
                                }
                            }
                            val focused = inPanel && settingsSection == 2 && settingsGridIdx == globalIdx
                            ChipTile(
                                CheatCell(cell.label, valueLabel, isOn) { activateSettingCell(cell) },
                                focused = focused,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (busy) return@ChipTile
                                inPanel = true
                                settingsSection = 2
                                settingsGridIdx = globalIdx
                                activateSettingCell(cell)
                            }
                        }
                    }
                }
            }

            val socket = socketMeta.ifEmpty { "tcp://127.0.0.1:${StatusManifest.PORT}" }
            Txt("ZL Menu · ZR Fast Fwd · L3 Rec · R3 Shot · $socket", 10.5.sp, Pal.ICE, bold = true)
        }
    }

    @Composable
    private fun CompactRomTile(card: RomCard, selected: Boolean, current: Boolean, onClick: () -> Unit) {
        val newest = card.newest
        val liveThumb = rememberPreview(newest?.file, newest?.modified ?: 0L)
        val fallback = rememberAssetPreview(romFallbackCandidates(card))
        val preview = liveThumb ?: fallback
        val touch = remember { MutableInteractionSource() }
        Row(
            Modifier.width(196.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                .background(if (selected) Pal.PANEL2 else Pal.PANEL)
                .border(if (selected) 1.5.dp else 1.dp, if (selected) Pal.ACCENT else Pal.LINE, RoundedCornerShape(8.dp))
                .clickable(interactionSource = touch, indication = null, onClick = onClick)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(76.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0A0E14)).border(1.dp, Pal.LINE, RoundedCornerShape(6.dp)),
                Alignment.Center,
            ) {
                if (preview != null) {
                    Image(preview, card.entry.channel, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Txt("ROM", 11.sp, Pal.ICE, bold = true)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(card.entry.channel, 13.sp, Pal.TEXT, bold = true)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (card.entry.build.isNotEmpty()) Txt(card.entry.build, 10.5.sp, Pal.STONE, bold = true)
                    if (current) Badge("LIVE", Pal.ACCENT, Pal.INK)
                }
                Txt(cardSubtitle(card), 10.sp, Pal.DIM)
            }
        }
    }

    // ---- Shared small composables ------------------------------------------

    @Composable
    private fun FilterPill(label: String, active: Boolean, mutedStyle: Boolean = false, onClick: () -> Unit) {
        val touch = remember { MutableInteractionSource() }
        val bg = when {
            active && !mutedStyle -> Pal.ACCENT
            active -> Pal.PANEL2
            else -> Pal.PANEL
        }
        val fg = when {
            active && !mutedStyle -> Pal.INK
            active -> Pal.ICE
            else -> Pal.DIM
        }
        val borderColor = if (active) (if (mutedStyle) Pal.ICE else Pal.ACCENT) else Pal.LINE
        Box(
            Modifier.clip(RoundedCornerShape(10.dp))
                .background(bg)
                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                .clickable(interactionSource = touch, indication = null, onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 4.dp),
        ) {
            Txt(label, 11.5.sp, fg, bold = true)
        }
    }

    @Composable
    private fun BitsRow(row: Inventory.Row, value: Int, selected: Boolean, focusBit: Int, pending: Boolean = false) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp))
                .background(if (selected) Pal.PANEL2 else Pal.PANEL)
                .border(if (selected) 1.5.dp else 1.dp, if (selected) Pal.ACCENT else Pal.LINE, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { Txt(row.label, 12.5.sp, Pal.TEXT, bold = true) }
            Txt(if (pending) "…" else Inventory.valueText(row, value), 11.sp, if (!pending && value > 0) Pal.ACCENT else Pal.DIM, bold = true)
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (bit in 7 downTo 0) {
                    val on = value >= 0 && ((value shr bit) and 1) != 0
                    val bitFocused = selected && bit == focusBit
                    Box(
                        Modifier.width(18.dp).height(20.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (on) Pal.ACCENT else Pal.BG)
                            .border(1.dp, if (bitFocused) Pal.ICE else Pal.LINE, RoundedCornerShape(4.dp)),
                        Alignment.Center,
                    ) {
                        Txt("$bit", 10.sp, if (on) Pal.INK else Pal.DIM, bold = true)
                    }
                }
            }
        }
    }

    @Composable
    private fun ListRow(
        label: String,
        tag: String,
        selected: Boolean,
        lead: String = "",
        tagColor: Color = Pal.DIM,
        onClick: (() -> Unit)? = null,
    ) {
        val touch = remember { MutableInteractionSource() }
        val base = Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) Pal.PANEL2 else Pal.PANEL)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Pal.ACCENT else Pal.LINE, RoundedCornerShape(8.dp))
        val mod = if (onClick != null) base.clickable(interactionSource = touch, indication = null, onClick = onClick) else base
        Row(mod.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (lead.isNotEmpty()) {
                Box(Modifier.width(28.dp)) { Txt(lead, 11.sp, Pal.STONE, bold = true) }
            }
            Box(Modifier.weight(1f)) { Txt(label, 12.5.sp, Pal.TEXT, bold = true) }
            if (tag.isNotEmpty()) Txt(tag, 11.sp, tagColor, bold = true)
        }
    }

    @Composable
    private fun ConfirmSheet(sheet: Confirm) {
        val touch = remember { MutableInteractionSource() }
        Box(
            Modifier.fillMaxSize().background(Color(0xAA000000))
                .clickable(interactionSource = touch, indication = null) {},
            Alignment.Center,
        ) {
            Column(
                Modifier.width(400.dp).clip(RoundedCornerShape(12.dp)).background(Pal.PANEL)
                    .border(1.5.dp, Pal.ACCENT, RoundedCornerShape(12.dp)).padding(16.dp),
            ) {
                Txt(sheet.title, 16.sp, Pal.TEXT, bold = true)
                Spacer(Modifier.height(6.dp))
                Txt(sheet.body, 12.5.sp, Pal.DIM, maxLines = 3)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(sheet.yes, confirmYes, confirmYes, Modifier.weight(1f)) {
                        if (!busy) {
                            confirm = null
                            sheet.action()
                        }
                    }
                    ActionButton("Cancel", !confirmYes, !confirmYes, Modifier.weight(1f)) {
                        if (!busy) confirm = null
                    }
                }
            }
        }
    }

    @Composable
    private fun ActionButton(
        label: String,
        filled: Boolean,
        outlined: Boolean,
        modifier: Modifier,
        onClick: (() -> Unit)? = null,
    ) {
        val touch = remember { MutableInteractionSource() }
        val base = modifier.height(32.dp).clip(RoundedCornerShape(8.dp))
            .background(if (filled) Pal.ACCENT else Pal.PANEL2)
            .border(1.5.dp, if (outlined) Pal.ICE else Pal.LINE, RoundedCornerShape(8.dp))
        val finalMod = if (onClick != null) {
            base.clickable(interactionSource = touch, indication = null, onClick = onClick)
        } else {
            base
        }
        Box(finalMod, Alignment.Center) {
            Txt(label, 12.sp, if (filled) Pal.INK else Pal.TEXT, bold = true)
        }
    }

    @Composable
    private fun HintBar(message: String?, hints: List<Pair<String, String>>) {
        Row(
            Modifier.fillMaxWidth().height(30.dp).background(Pal.BG).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (message != null) {
                Txt(message, 11.5.sp, Pal.ACCENT, bold = true)
                return@Row
            }
            hints.forEach { (button, label) ->
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Pal.PANEL)
                        .border(1.dp, Pal.LINE, RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) { Txt(button, 10.sp, Pal.ICE, bold = true) }
                Spacer(Modifier.width(5.dp))
                Txt(label, 11.5.sp, Pal.DIM, bold = true)
                Spacer(Modifier.width(12.dp))
            }
        }
    }

    @Composable
    private fun Badge(text: String, color: Color, textColor: Color = Pal.INK) {
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Txt(text, 9.5.sp, textColor, bold = true)
        }
    }

    @Composable
    private fun Thumb(image: ImageBitmap?, modifier: Modifier, empty: String, big: Boolean = false) {
        Box(
            modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF0A0E14))
                .border(1.dp, Pal.LINE, RoundedCornerShape(6.dp)),
            Alignment.Center,
        ) {
            if (image != null) {
                Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.None)
            } else {
                Txt(empty, if (big) 24.sp else 12.sp, Pal.DIM, bold = true)
            }
        }
    }

    @Composable
    private fun Txt(
        text: String,
        size: TextUnit,
        color: Color,
        bold: Boolean = false,
        maxLines: Int = 1,
    ) {
        BasicText(
            text,
            style = TextStyle(
                color = color,
                fontSize = size,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Start,
            ),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }

    // ---- Image loading composables -----------------------------------------

    @Composable
    private fun rememberAssetPreview(candidates: List<String>): ImageBitmap? {
        if (candidates.isEmpty()) return null
        val available = host.previewAssetNames()
        val matched = candidates.firstNotNullOfOrNull { key ->
            val file = if (key.endsWith(".png", ignoreCase = true)) key else "$key.png"
            if (available.contains(file)) file else null
        } ?: return null

        val key = "asset:$matched"
        val (hit, cached) = cachedKey(key)
        val state = produceState<ImageBitmap?>(cached, key) {
            if (!hit) {
                value = withContext(Dispatchers.IO) {
                    loadKeyed(key) {
                        val bytes = host.loadPreviewAsset(matched) ?: return@loadKeyed null
                        val opts = BitmapFactory.Options().apply { inScaled = false }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
                    }
                }
            } else {
                value = cached
            }
        }
        return state.value
    }

    @Composable
    private fun rememberCaptureThumb(item: MediaCapture.CaptureItem): ImageBitmap? {
        val file = item.previewImage ?: return null
        if (!file.isFile) return null
        val key = "cap:${file.path}@${item.modifiedMs}"
        val (hit, cached) = cachedKey(key)
        val state = produceState<ImageBitmap?>(cached, key) {
            if (!hit) {
                value = withContext(Dispatchers.IO) {
                    loadKeyed(key) {
                        val opts = BitmapFactory.Options().apply { inScaled = false }
                        BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
                    }
                }
            } else {
                value = cached
            }
        }
        return state.value
    }

    // ---- Text & formatting helpers -----------------------------------------

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
        RailItem.RESUME -> menuTitle
        RailItem.PLAY -> "${filteredWarpCards().size} targets"
        RailItem.STATES -> "${states.count { it.occupied }} saved · ${if (statesSaveMode) "Save" else "Load"}"
        RailItem.CHEATS -> {
            val active = host.testingTools().activeCheatCount()
            if (active > 0) "$active cheats on" else activeCheatTabName()
        }
        RailItem.CAPTURE -> {
            val rec = if (host.isVideoRecording()) "REC ${MediaCapture.formatDuration(host.videoRecordingElapsedSeconds())} · " else ""
            "$rec${captures.size} recent"
        }
        RailItem.SETTINGS -> "${cards.size} ROMs · ${host.turboSpeedLabel()}"
    }

    private fun hubHints(): List<Pair<String, String>> {
        val resume = if (host.nativeReady() && hasCurrentRom) listOf("B / ZL" to "Resume") else emptyList()
        return when (hubTab) {
            HUB_TAB_WARPS -> listOf("A" to "Warp", "L1/R1" to "Tab", "Y" to "Rescan") + resume
            HUB_TAB_SAVES -> listOf("A" to "Load", "L1/R1" to "Tab", "Y" to "Rescan") + resume
            else -> listOf("A" to "Play", "L1/R1" to "Tab", "D-PAD" to "Browse") + resume
        }
    }

    private fun menuHints(item: RailItem): List<Pair<String, String>> {
        if (!inPanel) {
            return listOf("A / RIGHT" to "Open", "B / ZL / START" to "Resume", "D-PAD" to "Move")
        }
        return when (item) {
            RailItem.PLAY -> listOf("A" to "Launch", "L1/R1" to "Filter", "Y" to "Rescan", "B" to "Rail")
            RailItem.STATES -> listOf("A" to (if (statesSaveMode) "Save" else "Load"), "L1/R1" to "Mode", "X" to "Page", "Y" to "Delete", "B" to "Rail")
            RailItem.CHEATS -> listOf("A" to "Toggle", "L1/R1" to "Tab", "B" to "Rail")
            RailItem.CAPTURE -> listOf("A" to "Capture", "D-PAD" to "Move", "B" to "Rail")
            RailItem.SETTINGS -> listOf("A" to "Select", "D-PAD" to "Move", "B" to "Rail")
            RailItem.RESUME -> listOf("A" to "Resume", "B" to "Close")
        }
    }

    private fun cardSubtitle(card: RomCard): String {
        val parts = ArrayList<String>()
        if (card.saveLabel.isNotEmpty()) parts.add(card.saveLabel)
        val n = card.stateCount
        if (n > 0) parts.add("$n ${if (n == 1) "state" else "states"}")
        return if (parts.isEmpty()) "No save" else parts.joinToString(" · ")
    }

    private fun cardCompactMeta(card: RomCard): String {
        val parts = ArrayList<String>()
        parts.add(SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(card.entry.date)))
        if (card.saveLabel.isNotEmpty()) parts.add(card.saveLabel)
        card.newest?.let { parts.add(whenText(it.modified)) }
        return parts.joinToString(" · ")
    }

    private fun caption(entry: SaveStateSlots.Entry): String {
        if (!entry.occupied) return "Empty"
        return if (entry.label.isNotEmpty()) "${clock(entry.modified)} · ${entry.label}" else whenText(entry.modified)
    }

    private fun warpBadgeColors(badge: String): Pair<Color, Color> = when (badge.uppercase(Locale.ROOT)) {
        "NEW", "KIT" -> Pal.ACCENT to Pal.INK
        "BOSS", "FIX", "BUG" -> Pal.RED to Color.White
        "DGN", "MSS" -> Pal.PANEL2 to Pal.ICE
        else -> Pal.PANEL2 to Pal.STONE
    }

    private fun categoryAccent(cat: WarpCategory): Color = when (cat) {
        WarpCategory.FEATURED -> Pal.ACCENT
        WarpCategory.OVERWORLD -> Pal.ACCENT
        WarpCategory.DUNGEONS -> Pal.ICE
        WarpCategory.BOSSES -> Pal.RED
        WarpCategory.ROOMS -> Pal.STONE
    }

    companion object {
        private const val HUB_TAB_ROMS = 0
        private const val HUB_TAB_WARPS = 1
        private const val HUB_TAB_SAVES = 2
        private val HUB_TABS = listOf("Test ROMs", "Play & Warps", "States")
        private const val TAB_LIVE = "Live"
        private const val SLOTS_PER_PAGE = 8

        private val GEAR_SNAPSHOT_ADDRS = intArrayOf(
            Inventory.SWORD, Inventory.SHIELD, Inventory.ARMOR, Inventory.GLOVES,
            Inventory.BOOTS, Inventory.FLIPPERS, Inventory.MOON_PEARL,
            Inventory.ZORA_MASK, Inventory.BUNNY_HOOD, Inventory.DEKU_MASK,
            Inventory.STONE_MASK, Inventory.WOLF_MASK, Inventory.GARO_MASK_OWNED,
            Inventory.STALFOS_MASK_OWNED, Inventory.FROG_MASK_OWNED, Inventory.FIERCE_DEITY_MASK_OWNED,
            Inventory.ACTIVE_FORM,
            Inventory.CRYSTALS, Inventory.PENDANTS,
        )

        private val FEATURED_CHECKPOINT_IDS = setOf(
            "loom-beach", "village-impa", "abyss-arrival", "abyss-owl",
            "kalyxo-return", "maku-seed-ready", "jump-horse-archery", "kydreeok-fight",
        )
        private val FEATURED_AREA_IDS = setOf(0x30, 0x00, 0x23, 0x38, 0x40, 0x70)

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

        private fun cachedKey(key: String): Pair<Boolean, ImageBitmap?> {
            synchronized(previewCache) {
                if (previewCache.containsKey(key)) return true to previewCache[key]
            }
            return false to null
        }

        private fun loadKeyed(key: String, loader: () -> ImageBitmap?): ImageBitmap? {
            synchronized(previewCache) {
                if (previewCache.containsKey(key)) return previewCache[key]
            }
            val image = try {
                loader()
            } catch (e: Throwable) {
                null
            }
            synchronized(previewCache) {
                if (previewCache.size > 96) previewCache.clear()
                previewCache[key] = image
            }
            return image
        }

        private fun cachedPreview(file: File?, modified: Long): Pair<Boolean, ImageBitmap?> {
            if (file == null) return true to null
            return cachedKey("${file.path}@$modified")
        }

        private fun loadPreview(file: File, modified: Long): ImageBitmap? {
            return loadKeyed("${file.path}@$modified") {
                val png = MesenNative.statePreviewPng(file.path)
                if (png == null) null else BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
            }
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

        private fun shortcutThumbKey(card: PlaytestShortcuts.Card): String? {
            val source = card.thumbFile ?: card.stateFile ?: return null
            val kind = if (card.thumbFile != null) "png" else "mss"
            return "$kind:${source.path}@${card.thumbModified}"
        }

        @Composable
        private fun rememberShortcutThumb(card: PlaytestShortcuts.Card): ImageBitmap? {
            val key = shortcutThumbKey(card) ?: return null
            val (hit, cached) = cachedKey(key)
            val state = produceState<ImageBitmap?>(cached, key) {
                if (!hit) {
                    value = withContext(Dispatchers.IO) {
                        loadKeyed(key) {
                            val thumbFile = card.thumbFile
                            if (thumbFile != null && thumbFile.isFile) {
                                val opts = BitmapFactory.Options().apply { inScaled = false }
                                BitmapFactory.decodeFile(thumbFile.path, opts)?.asImageBitmap()
                            } else {
                                val stateFile = card.stateFile
                                if (stateFile != null && stateFile.isFile) {
                                    val png = MesenNative.statePreviewPng(stateFile.path)
                                    if (png == null) null else {
                                        val opts = BitmapFactory.Options().apply { inScaled = false }
                                        BitmapFactory.decodeByteArray(png, 0, png.size, opts)?.asImageBitmap()
                                    }
                                } else null
                            }
                        }
                    }
                } else {
                    value = cached
                }
            }
            return state.value
        }

        // ---- Preview & label classification helpers ------------------------

        private fun romFallbackCandidates(card: RomCard): List<String> {
            val ch = card.entry.channel.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_")
            return listOf("rom_$ch", ch, "showcase_main")
        }

        private fun previewCandidatesForId(id: String, location: String = ""): List<String> {
            val clean = id.lowercase(Locale.US).replace('-', '_').replace(':', '_')
            val out = ArrayList<String>()
            out.add(clean)
            if (id != clean) out.add(id)
            PRESET_PREVIEW_MAP[id.lowercase(Locale.US)]?.let { out.add(it) }
            val text = "$clean ${location.lowercase(Locale.US)}"
            when {
                text.contains("korl") || text.contains("lyra") -> out.add("lyra_sea_korl")
                text.contains("korok") -> out.add("sw_81_korok_cove")
                text.contains("d1") || text.contains("mushroom") -> out.add("d1_grotto")
                text.contains("d4") || text.contains("zora") -> out.add("d4_zora")
                text.contains("d5") || text.contains("glacia") -> out.add("d5_glacia")
                text.contains("d6") || text.contains("goron") -> out.add("d6_goron")
                text.contains("d7") || text.contains("dragon") -> out.add("d7_dragon_ship")
                text.contains("d8") || text.contains("fortress") || text.contains("spire") -> out.add("d8_fractured_spire")
                text.contains("windmill") || text.contains("temporal") -> out.add("abyss_40_windmill")
                text.contains("docks") -> out.add("ow_30_docks")
                text.contains("wayward") || text.contains("village") -> out.add("ow_23_wayward")
                text.contains("ranch") -> out.add("ow_00_ranch")
                text.contains("church") || text.contains("hall of secrets") -> out.add("ow_0e_church")
                text.contains("maku") -> out.add("ow_50_maku")
            }
            return out.distinct()
        }

        private fun previewCandidatesForArea(area: Int): List<String> {
            val id = area and 0xFF
            val hex = String.format(Locale.US, "%02x", id)
            val list = ArrayList<String>()
            list.add("area_$hex")
            AREA_PREVIEW_MAP[id]?.let { list.addAll(it) }
            return list
        }

        private fun previewCandidatesForEntrance(entrance: Int, area: Int): List<String> {
            val eid = entrance and 0xFF
            val hex = String.format(Locale.US, "%02x", eid)
            val list = ArrayList<String>()
            list.add("entrance_$hex")
            ENTRANCE_PREVIEW_MAP[eid]?.let { list.addAll(it) }
            if (area >= 0) {
                AREA_PREVIEW_MAP[area and 0xFF]?.let { list.addAll(it) }
            }
            return list.distinct()
        }

        private fun previewCandidatesForCheckpoint(cp: Progression.Checkpoint): List<String> {
            val list = ArrayList<String>()
            list.addAll(previewCandidatesForId(cp.id))
            if (cp.kind == Progression.Kind.ENTRANCE) {
                list.addAll(previewCandidatesForEntrance(cp.target, cp.area))
            } else {
                list.addAll(previewCandidatesForArea(cp.target))
            }
            return list.distinct()
        }

        private val PRESET_PREVIEW_MAP = mapOf(
            "full" to "showcase_main",
            "post-intro" to "ow_0e_church",
            "d1" to "d1_grotto",
            "d4" to "d4_zora",
            "zora" to "d4_zora",
            "d5" to "d5_glacia",
            "d6" to "d6_goron",
            "goron" to "d6_goron",
            "d7" to "d7_dragon_ship",
            "d8" to "d8_fractured_spire",
            "village-impa" to "ow_23_wayward",
            "abyss-arrival" to "abyss_40_windmill",
            "abyss-owl" to "ow_50_maku",
            "kalyxo-return" to "ow_0e_church",
            "maku-seed-ready" to "ow_50_maku",
            "jump-horse-archery" to "ow_00_ranch",
            "kydreeok-fight" to "d8_fractured_spire",
        )

        private val AREA_PREVIEW_MAP = mapOf(
            0x00 to listOf("ow_00_ranch"),
            0x0E to listOf("ow_0e_church"),
            0x10 to listOf("d1_grotto"),
            0x1E to listOf("d4_zora"),
            0x23 to listOf("ow_23_wayward"),
            0x30 to listOf("ow_30_docks", "lyra_sea_korl"),
            0x36 to listOf("d6_goron"),
            0x38 to listOf("lyra_sea_korl", "d7_dragon_ship"),
            0x40 to listOf("abyss_40_windmill"),
            0x4B to listOf("d8_fractured_spire"),
            0x50 to listOf("ow_50_maku"),
            0x57 to listOf("d8_fractured_spire"),
            0x63 to listOf("abyss_40_windmill"),
            0x70 to listOf("sw_81_korok_cove"),
            0x7A to listOf("sw_81_korok_cove"),
            0x81 to listOf("sw_81_korok_cove"),
        )

        private val ENTRANCE_PREVIEW_MAP = mapOf(
            0x02 to listOf("ow_0e_church"),
            0x1A to listOf("lyra_sea_korl", "ow_30_docks"),
            0x25 to listOf("d4_zora"),
            0x26 to listOf("d1_grotto"),
            0x27 to listOf("d6_goron"),
            0x29 to listOf("d8_fractured_spire"),
            0x34 to listOf("d5_glacia"),
            0x35 to listOf("d7_dragon_ship"),
            0x37 to listOf("d8_fractured_spire"),
            0x4E to listOf("d4_zora"),
            0x76 to listOf("abyss_40_windmill"),
        )

        private fun inferShortcutCategory(card: PlaytestShortcuts.Card): WarpCategory {
            val s = "${card.id} ${card.title} ${card.subtitle}".lowercase(Locale.US)
            return when {
                s.contains("boss") -> WarpCategory.BOSSES
                s.contains("dungeon") || Regex("\\bd[1-8]\\b").containsMatchIn(s) -> WarpCategory.DUNGEONS
                s.contains("room") || s.contains("house") || s.contains("tavern") -> WarpCategory.ROOMS
                else -> WarpCategory.OVERWORLD
            }
        }

        private fun classifyCheckpoint(cp: Progression.Checkpoint): WarpCategory {
            val id = cp.id.lowercase(Locale.US)
            return when {
                id.contains("boss") || id == "kydreeok-fight" -> WarpCategory.BOSSES
                id.endsWith("-entrance") || id == "origins" || id == "fortress-open"
                    || id.startsWith("jump-shrine-") || id == "jump-d8-sky" -> WarpCategory.DUNGEONS
                id == "intro-bed" || id.contains("tavern") || id.contains("bar-back") -> WarpCategory.ROOMS
                else -> WarpCategory.OVERWORLD
            }
        }

        private fun classifyFeatureJump(jump: PlaySession.Jump): WarpCategory {
            val s = "${jump.label} ${jump.location}".lowercase(Locale.US)
            return when {
                s.contains("boss") -> WarpCategory.BOSSES
                s.contains("inside:") || s.contains("room") -> WarpCategory.ROOMS
                s.contains("dungeon") || s.contains("goron mines") || s.contains("zora temple") -> WarpCategory.DUNGEONS
                else -> WarpCategory.OVERWORLD
            }
        }

        private fun isDungeonEntrance(cleanName: String, target: Int): Boolean {
            val s = cleanName.lowercase(Locale.US)
            return Regex("^d[1-8]\\b").containsMatchIn(s) || s.contains("shrine of") || (target in setOf(0x15, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x32, 0x34, 0x35, 0x37, 0x4E))
        }

        private fun stripWarpName(raw: String): String {
            var s = raw.replace(Regex("^\\$[0-9A-Fa-f]{2}\\s*·\\s*"), "")
            s = s.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
            return crispTitle(s)
        }

        private fun crispTitle(raw: String): String {
            val trimmed = raw.substringBefore(" — ").substringBefore(": ").trim()
            return if (trimmed.length <= 24) trimmed else trimmed.take(22).trimEnd() + "…"
        }

        private fun crispPresetTitle(preset: PresetData.Preset): String = when (preset.id) {
            "full" -> "Full Kit"
            "post-intro" -> "Post-Intro"
            "d1" -> "D1 Grotto"
            "d2" -> "D2 Tail Palace"
            "d3" -> "D3 Castle"
            "zora" -> "D4 Zora Temple"
            "d5" -> "D5 Glacia"
            "goron" -> "D6 Goron Mines"
            "d7" -> "D7 Dragon Ship"
            "d8" -> "D8 Fortress"
            else -> crispTitle(preset.label)
        }

        private fun crispCheckpointTitle(cp: Progression.Checkpoint): String = when (cp.id) {
            "intro-bed" -> "Link's House"
            "loom-beach" -> "Loom Beach"
            "village-impa" -> "Wayward Impa"
            "abyss-arrival" -> "Abyss Arrival"
            "origins" -> "Shrine Origins"
            "abyss-owl" -> "Eon Owl"
            "kalyxo-return" -> "Hall Secrets"
            "d1-entrance" -> "D1 Entrance"
            "d1-boss-ready" -> "Manhandla"
            "d2-entrance" -> "D2 Entrance"
            "d2-boss-ready" -> "Moldorm"
            "d3-entrance" -> "D3 Entrance"
            "d3-boss-ready" -> "Armos Knights"
            "d4-entrance" -> "D4 Entrance"
            "d4-boss-ready" -> "Arrghus"
            "d5-entrance" -> "D5 Entrance"
            "d5-boss-ready" -> "Twinrova"
            "d6-entrance" -> "D6 Entrance"
            "d6-boss-ready" -> "King Dodongo"
            "d7-entrance" -> "D7 Entrance"
            "d7-boss-ready" -> "Kydrog"
            "d8-entrance" -> "D8 Entrance"
            "maku-seed-ready" -> "Maku Seed"
            "fortress-open" -> "Fortress Open"
            "d8-sky-boss-room" -> "D8 Sky Boss"
            "kydreeok-fight" -> "Kydreeok"
            "jump-kalyxo-05" -> "East Peak"
            "jump-kalyxo-1d" -> "Crossroads"
            "jump-kalyxo-2f" -> "Tail Approach"
            "jump-shrine-power" -> "Shrine Power"
            "jump-shrine-wisdom" -> "Shrine Wisdom"
            "jump-shrine-courage" -> "Shrine Courage"
            "jump-d8-sky" -> "D8 Sky Tower"
            "jump-wayward-village" -> "Wayward Village"
            "jump-wayward-tavern" -> "Village Tavern"
            "jump-wayward-bar-back" -> "Tavern Back"
            "jump-bean-spot0" -> "Bean Ranch"
            "jump-bean-spot1" -> "Bean Tail"
            "jump-bean-spot2" -> "Bean Mountain"
            "jump-bean-spot3" -> "Bean Beach"
            "jump-horse-archery" -> "Ranch Archery"
            else -> crispTitle(cp.label)
        }

        private fun checkpointCode(cp: Progression.Checkpoint): String {
            val match = Regex("^d([1-8])-").find(cp.id)
            if (match != null) return "D${match.groupValues[1]}"
            if (cp.expectRoom >= 0) return String.format(Locale.US, "$%02X", cp.expectRoom and 0xFF)
            return String.format(Locale.US, "$%02X", cp.target and 0xFF)
        }

        private fun checkpointTag(cp: Progression.Checkpoint): String {
            val dMatch = Regex("^d([1-8])-boss").find(cp.id)
            if (dMatch != null) {
                val rm = if (cp.expectRoom >= 0) String.format(Locale.US, "$%02X", cp.expectRoom and 0xFF) else "Boss"
                return "D${dMatch.groupValues[1]} · $rm"
            }
            if (cp.id == "d8-sky-boss-room") return "D8 · $96"
            if (cp.id == "kydreeok-fight") return "Final · $57"
            val beatBit = if (cp.beat > 0) "Beat ${cp.beat} · " else ""
            val locBit = if (cp.kind == Progression.Kind.AREA) {
                String.format(Locale.US, "OW $%02X", cp.target and 0xFF)
            } else if (cp.room >= 0) {
                String.format(Locale.US, "Rm $%03X", cp.room)
            } else {
                String.format(Locale.US, "Ent $%02X", cp.target and 0xFF)
            }
            return "$beatBit$locBit"
        }
    }
}
