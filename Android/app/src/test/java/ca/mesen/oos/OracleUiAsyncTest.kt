package ca.mesen.oos

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OracleUiAsyncTest {
    private class TestHost(
        private val romDir: File,
        private val statesDirFile: File,
        private val savesDirFile: File,
        private val capturesDirFile: File = File(romDir.parentFile, "Captures"),
        private val playtestDirFile: File = File(romDir.parentFile, "Playtest"),
    ) : OracleUi.Host {
        var ready = false
        var currentRom: String? = null
        var uiOpenedCalls = 0
        var uiClosedCalls = 0
        var bootCalls = ArrayList<String>()
        var loadedStates = ArrayList<String>()
        var recordingActive = false
        var replayActive = false
        var overlayMask = 0
        var steppedFrames = 0
        val toggleValues = hashMapOf("r2_capture" to false)
        val activeCheatAddrs = HashMap<Int, Int>()
        val warpedTargets = ArrayList<Progression.Checkpoint>()
        val fakeMemory = object : Inventory.Memory {
            private val wram = hashMapOf(
                Inventory.MODULE to 0x09,
                Inventory.SUBMODULE to 0,
                Inventory.HEALTH_MAX to 0x28,
            )
            override fun readByte(address: Int): Int = wram[address] ?: 0
            override fun writeByte(address: Int, value: Int): Boolean {
                wram[address] = value and 0xFF
                return true
            }
            override fun writeWord(address: Int, value: Int): Boolean {
                wram[address] = value and 0xFF
                wram[address + 1] = (value shr 8) and 0xFF
                return true
            }
        }
        val tools = TestingTools { addrs, vals ->
            activeCheatAddrs.clear()
            for (i in addrs.indices) activeCheatAddrs[addrs[i]] = vals[i]
        }
        var romRootsHook: () -> Unit = {}
        var socketSummaryHook: () -> String = { "tcp://127.0.0.1:27015 · ready" }
        var screenshotHook: () -> String = { "Saved screenshot: test.png" }
        var bugSnapshotHook: (String) -> String = { cat -> "Saved bug_test ($cat)" }
        var recentCapturesHook: () -> List<MediaCapture.CaptureItem> = { emptyList() }
        var launchShortcutHook: ((PlaytestShortcuts.Card) -> PlaytestShortcuts.LaunchResult)? = null
        var checkpointDataHook: () -> CheckpointData = { CheckpointData.empty(null) }
        var presetDataHook: () -> PresetData = { PresetData.empty(null) }
        var batteryLabelHook: (File) -> String = { "" }
        var previewAssetsHook: () -> Set<String> = { emptySet() }

        override fun nativeReady(): Boolean = ready
        override fun romRoots(): Array<File> {
            romRootsHook()
            return arrayOf(romDir)
        }
        override fun playtestRoots(): Array<File> = arrayOf(playtestDirFile)
        override fun launchPlaytestShortcut(card: PlaytestShortcuts.Card): PlaytestShortcuts.LaunchResult {
            launchShortcutHook?.let { return it(card) }
            return PlaytestShortcuts.launchCard(card, object : PlaytestShortcuts.Launcher {
                override fun currentRomPath(): String? = currentRom
                override fun romRoots(): Array<File> = arrayOf(romDir)
                override fun savesDir(): File = savesDirFile
                override fun bootRom(path: String): Boolean = this@TestHost.bootRom(path)
                override fun loadStateFile(path: String): Boolean {
                    loadedStates.add(path)
                    return File(path).isFile
                }
                override fun writeSram(data: ByteArray): Boolean = true
                override fun settleAfterRomLoad() {}
            })
        }
        override fun currentRomPath(): String? = currentRom
        override fun batterySaveLabel(rom: File): String = batteryLabelHook(rom)
        override fun romSourceLabel(rom: File): String = "app"
        override fun statesDir(): File = statesDirFile
        override fun savesDir(): File = savesDirFile
        override fun checkpointData(): CheckpointData = checkpointDataHook()
        override fun presetData(): PresetData = presetDataHook()
        override fun socketSummary(): String = socketSummaryHook()
        override fun uiOpened() { uiOpenedCalls++ }
        override fun uiClosed() { uiClosedCalls++ }
        override fun bootRom(path: String): Boolean {
            bootCalls.add(path)
            currentRom = path
            return true
        }
        override fun resetConsole() {}
        override fun requestHomeShortcut() {}
        override fun isStickyFastForward(): Boolean = false
        override fun setStickyFastForward(on: Boolean) {}
        override fun turboSpeedPercent(): Int = 300
        override fun turboSpeedLabel(): String = "×3"
        override fun setTurboSpeedPercent(percent: Int) {}
        override fun cycleTurboSpeed(delta: Int): Int = 300
        override fun setting(key: String): Boolean = toggleValues[key] ?: false
        override fun setSetting(key: String, on: Boolean) { toggleValues[key] = on }
        override fun startCheckpoint(id: String) {}
        override fun startPreset(id: String) {}
        override fun toast(message: String) {}
        override fun capturesDir(): File = capturesDirFile
        override fun recentCaptures(): List<MediaCapture.CaptureItem> = recentCapturesHook()
        override fun takeScreenshot(): String = screenshotHook()
        override fun isVideoRecording(): Boolean = recordingActive
        override fun videoRecordingElapsedSeconds(): Long = if (recordingActive) 5L else 0L
        override fun toggleVideoRecording(): String {
            recordingActive = !recordingActive
            return if (recordingActive) "Recording MP4: test.mp4" else "Saved video: test.mp4"
        }
        override fun isReplayBufferActive(): Boolean = replayActive
        override fun replayBufferedSeconds(): Int = if (replayActive) 12 else 0
        override fun dumpReplayClip(): String = "Saved 20s replay: replay_test.mp4"
        override fun debugOverlayMask(): Int = overlayMask
        override fun toggleDebugOverlayBit(bit: Int): String {
            overlayMask = (overlayMask xor bit) and 0x07
            return "Overlay mask: $overlayMask"
        }
        override fun captureBugSnapshot(category: String): String = bugSnapshotHook(category)
        override fun testingTools(): TestingTools = tools
        override fun activeSaveSlot(): Int = 1
        override fun saveStatePath(slot: Int): String? = File(statesDirFile, "test_$slot.mss").absolutePath
        override fun toggleCheat(cheat: TestingTools.Cheat): String = tools.toggleCheat(cheat, fakeMemory)
        override fun clearCheats(): String = tools.clearAll(fakeMemory)
        override fun runLiveAction(action: TestingTools.LiveAction): String =
            tools.runAction(action, fakeMemory) { frames -> stepFrames(frames) }
        override fun loadCaptureState(stateFile: File): Boolean = stateFile.isFile
        override fun stepFrames(frames: Int): Boolean {
            steppedFrames += frames
            return true
        }
        override fun startWarp(target: Progression.Checkpoint) {
            warpedTargets.add(target)
        }
        override fun fallToDungeonRoom(roomId: Int) {}
        override fun previewAssetNames(): Set<String> = previewAssetsHook()
        override fun readWramByte(address: Int): Int = fakeMemory.readByte(address)
        override fun writeWramByte(address: Int, value: Int): Boolean = fakeMemory.writeByte(address, value)
        override fun syncWramToSram(): Boolean = fakeMemory.syncSram()
    }

    private fun pump(queue: ConcurrentLinkedQueue<Runnable>, timeoutMs: Long = 3000, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            while (true) {
                val r = queue.poll() ?: break
                r.run()
            }
            if (until()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for async UI condition")
    }

    @Test
    fun railHasCompactSixEntries() {
        assertEquals(
            listOf("Resume", "Play & Warps", "States", "Cheats & Gear", "Capture", "ROMs & Settings"),
            RailItem.values().map { it.label },
        )
        assertEquals(
            listOf("Featured", "Overworld", "Dungeons", "Bosses", "Rooms"),
            WarpCategory.values().map { it.label },
        )
    }

    @Test
    fun openLibraryReturnsImmediatelyAndDropsStaleScans() {
        val temp = Files.createTempDirectory("oracle-ui-lib").toFile()
        val roms = File(temp, "Roms").apply { mkdirs() }
        val states = File(temp, "SaveStates").apply { mkdirs() }
        val saves = File(temp, "Saves").apply { mkdirs() }
        File(roms, "Oracle - Main.sfc").writeBytes(byteArrayOf(1, 2, 3))

        val host = TestHost(roms, states, saves)
        val firstScanStarted = CountDownLatch(1)
        val releaseFirstScan = CountDownLatch(1)
        val scanCalls = AtomicInteger(0)

        host.romRootsHook = {
            if (scanCalls.incrementAndGet() == 1) {
                firstScanStarted.countDown()
                assertTrue(releaseFirstScan.await(5, TimeUnit.SECONDS))
            }
        }

        val mainQueue = ConcurrentLinkedQueue<Runnable>()
        val ui = OracleUi(host) { r -> mainQueue.add(r) }

        val t0 = System.nanoTime()
        ui.openLibrary()
        val openElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertTrue("openLibrary must return immediately without waiting for scan", openElapsedMs < 250)
        assertEquals(Screen.LIBRARY, ui.screen)
        assertEquals(1, host.uiOpenedCalls)
        assertTrue(ui.isLoadingLibrary)
        assertTrue(firstScanStarted.await(5, TimeUnit.SECONDS))

        File(roms, "Oracle - Latest.sfc").writeBytes(byteArrayOf(4, 5, 6))
        ui.openLibrary()
        releaseFirstScan.countDown()

        pump(mainQueue) { !ui.isLoadingLibrary && ui.cardChannels.size == 2 }
        assertEquals(listOf("Latest", "Main"), ui.cardChannels)
    }

    @Test
    fun openMenuAndCheatTabSwitchLoadOffMainThread() {
        val temp = Files.createTempDirectory("oracle-ui-menu").toFile()
        val roms = File(temp, "Roms").apply { mkdirs() }
        val states = File(temp, "SaveStates").apply { mkdirs() }
        val saves = File(temp, "Saves").apply { mkdirs() }
        val rom = File(roms, "Oracle - Main.sfc").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        File(roms, "Oracle - Main.build.json").writeText("{\"build\":\"b68\"}")

        val host = TestHost(roms, states, saves)
        host.currentRom = rom.path

        val metaGate = CountDownLatch(1)
        host.socketSummaryHook = {
            assertTrue(metaGate.await(5, TimeUnit.SECONDS))
            "tcp://127.0.0.1:27015 · 2 agents"
        }

        val mainQueue = ConcurrentLinkedQueue<Runnable>()
        val ui = OracleUi(host) { r -> mainQueue.add(r) }

        val t0 = System.nanoTime()
        ui.openMenu()
        val openElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertTrue("openMenu must return immediately", openElapsedMs < 250)
        assertEquals(Screen.MENU, ui.screen)
        assertEquals(1, host.uiOpenedCalls)
        assertFalse(ui.isBusy)

        // Move rail to CHEATS and enter panel.
        repeat(RailItem.CHEATS.ordinal) { ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        assertEquals("Live", ui.currentCheatTabName)

        // Switch from Live -> Items -> Songs rapidly via R1.
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        assertEquals("Songs", ui.currentCheatTabName)

        metaGate.countDown()
        pump(mainQueue) {
            ui.currentMenuTitle == "Main b68" &&
                ui.currentSocketMeta == "tcp://127.0.0.1:27015 · 2 agents" &&
                !ui.isLoadingItems &&
                ui.currentItemRows.isNotEmpty()
        }
        assertEquals(Inventory.rows(Inventory.TAB_SONGS, emptyMap()).size, ui.currentItemRows.size)
        assertEquals(ui.currentItemRows.size, ui.currentItemValues.size)
    }

    @Test
    fun capturePanelActionsRunOffMainThreadAndRefreshRecents() {
        val temp = Files.createTempDirectory("oracle-ui-cap").toFile()
        val roms = File(temp, "Roms").apply { mkdirs() }
        val states = File(temp, "SaveStates").apply { mkdirs() }
        val saves = File(temp, "Saves").apply { mkdirs() }
        val capturesDir = File(temp, "Captures").apply { mkdirs() }
        val host = TestHost(roms, states, saves, capturesDir).apply { ready = true }

        val gate = CountDownLatch(1)
        val shotFile = File(MediaCapture.screenshotsDir(capturesDir), "oracle_test.png")
        host.screenshotHook = {
            assertTrue(gate.await(5, TimeUnit.SECONDS))
            shotFile.writeBytes(byteArrayOf(1, 2, 3, 4))
            "Saved screenshot: ${shotFile.name}"
        }
        host.recentCapturesHook = { MediaCapture.listRecent(capturesDir, 10) }

        val mainQueue = ConcurrentLinkedQueue<Runnable>()
        val ui = OracleUi(host) { r -> mainQueue.add(r) }
        ui.openMenu()
        repeat(RailItem.CAPTURE.ordinal) { ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)

        // (0,0) = Screenshot
        val t0 = System.nanoTime()
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertTrue("screenshot capture must not block UI thread", elapsedMs < 250)
        assertTrue(ui.isBusy)

        gate.countDown()
        pump(mainQueue) { !ui.isBusy && ui.currentStatus.contains("oracle_test.png") }
        assertEquals(1, ui.currentCaptures.size)
        assertEquals("oracle_test.png", ui.currentCaptures[0].name)

        // Move right to (0,1) = 20s Replay
        ui.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        pump(mainQueue) { !ui.isBusy && ui.currentStatus.contains("replay_test.mp4") }

        // Move down to row 1: Cat(1,1) -> cycle category, then left to Bug Snap(1,0) -> save.
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0) // cycles from Softlock -> Warp / Transition
        ui.onKey(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        pump(mainQueue) { !ui.isBusy && ui.currentStatus.contains("Warp / Transition") }
    }

    @Test
    fun cheatsAndGearQuickChipsSteppersAndGearWorkOffMainThread() {
        val temp = Files.createTempDirectory("oracle-ui-cheats").toFile()
        val roms = File(temp, "Roms").apply { mkdirs() }
        val states = File(temp, "SaveStates").apply { mkdirs() }
        val saves = File(temp, "Saves").apply { mkdirs() }
        val host = TestHost(roms, states, saves).apply { ready = true }

        val mainQueue = ConcurrentLinkedQueue<Runnable>()
        val ui = OracleUi(host) { r -> mainQueue.add(r) }
        ui.openMenu()
        repeat(RailItem.CHEATS.ordinal) { ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        assertEquals("Live", ui.currentCheatTabName)

        // (0,0) = Invincibility (NO_DAMAGE), (0,3) = Inf Rupees (INF_RUPEES).
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        pump(mainQueue) { !ui.isBusy && host.tools.isCheatActive(TestingTools.Cheat.NO_DAMAGE) }
        assertTrue(host.activeCheatAddrs.containsKey(TestingTools.WRAM_NOPAIN))

        repeat(3) { ui.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0) }
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        pump(mainQueue) { !ui.isBusy && host.tools.isCheatActive(TestingTools.Cheat.INF_RUPEES) }
        assertEquals(0xE7, host.activeCheatAddrs[Inventory.RUPEES])

        // Row 1 col 1 = One-Hit, Row 1 col 3 = Clear All.
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        ui.onKey(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        ui.onKey(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        pump(mainQueue) { !ui.isBusy && host.tools.isCheatActive(TestingTools.Cheat.ONE_HIT) }
        assertEquals(1, host.activeCheatAddrs[TestingTools.SPR_HP])

        ui.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        ui.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        pump(mainQueue) { !ui.isBusy && host.tools.activeCheatCount() == 0 }

        // Row 3 col 0 = Step +1f, Row 4 col 0 = Hitboxes overlay (0x01).
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        repeat(3) { ui.onKey(KeyEvent.KEYCODE_DPAD_LEFT, 0) }
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        pump(mainQueue) { !ui.isBusy && host.steppedFrames == 1 }

        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        pump(mainQueue) { !ui.isBusy && (host.overlayMask and 0x01) != 0 }

        // Row 6 col 0 = Sword cycle (0 -> 1 via hostMemory).
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        pump(mainQueue) { !ui.isBusy && host.fakeMemory.readByte(Inventory.SWORD) == 1 }
    }

    @Test
    fun playAndWarpsGridFiltersCategoriesAndLaunchesShortcuts() {
        val temp = Files.createTempDirectory("oracle-ui-playwarps").toFile()
        val roms = File(temp, "Roms").apply { mkdirs() }
        val states = File(temp, "SaveStates").apply { mkdirs() }
        val saves = File(temp, "Saves").apply { mkdirs() }
        val playtest = File(temp, "Playtest").apply { mkdirs() }
        val rom = File(roms, "Oracle - Main.sfc").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        for (i in 1..5) {
            val dir = File(playtest, "card_$i").apply { mkdirs() }
            File(dir, "card.json").writeText(
                "{\"title\":\"Card $i\",\"subtitle\":\"Area $i\",\"badge\":\"NEW\",\"order\":${i * 10}}"
            )
            File(dir, "state.mss").writeBytes(byteArrayOf(i.toByte(), 2, 3))
        }

        val cpData = CheckpointData.parse(
            "{\"checkpoints\":[{\"id\":\"d1-boss-ready\",\"beat\":9,\"label\":\"D1 Mushroom Grotto boss ready (Manhandla, room $5A)\",\"location\":{\"entrance\":\"0x26\"},\"writes\":[]}]}",
            "{\"entrances\":[{\"id\":\"0x26\",\"name\":\"D1 Mushroom Grotto\",\"area\":\"0x10\",\"room\":\"0x004A\"}],\"areas\":[{\"area\":\"0x30\",\"name\":\"Kalyxo Docks\",\"world\":\"kalyxo\",\"via_room\":\"0x0104\"}]}",
        )
        val host = TestHost(roms, states, saves, playtestDirFile = playtest).apply {
            ready = true
            currentRom = rom.path
            checkpointDataHook = { cpData }
        }
        val mainQueue = ConcurrentLinkedQueue<Runnable>()
        val ui = OracleUi(host) { r -> mainQueue.add(r) }
        ui.openMenu()
        pump(mainQueue) { !ui.isLoadingShortcuts && ui.currentShortcuts.size == 5 }

        // Move to PLAY (index 1) and enter 3-column grid.
        repeat(RailItem.PLAY.ordinal) { ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        assertEquals(WarpCategory.FEATURED, ui.currentWarpCategory)
        assertEquals(0, ui.currentWarpIndex)

        // Category pills via R1: Featured -> Overworld -> Dungeons -> Bosses -> Rooms.
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        assertEquals(WarpCategory.OVERWORLD, ui.currentWarpCategory)
        assertTrue(ui.currentWarpTags.contains("OW $30"))
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        assertEquals(WarpCategory.DUNGEONS, ui.currentWarpCategory)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        assertEquals(WarpCategory.BOSSES, ui.currentWarpCategory)
        assertTrue(ui.currentWarpTitles.contains("Manhandla"))
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        assertEquals(WarpCategory.ROOMS, ui.currentWarpCategory)
        ui.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0) // step FallRoom 0 -> 1
        assertEquals(1, ui.currentFallRoomId)

        // Cycle back to Featured (5 shortcuts + featured OW $30 Docks = 6 cards).
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        assertEquals(WarpCategory.FEATURED, ui.currentWarpCategory)
        assertEquals(6, ui.currentWarpCount)
        ui.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        ui.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        assertEquals(2, ui.currentWarpIndex)
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        assertEquals(5, ui.currentWarpIndex)
        ui.onKey(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        assertEquals(4, ui.currentWarpIndex)

        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        assertTrue(ui.isBusy)
        pump(mainQueue) { !ui.isBusy && ui.screen == Screen.CLOSED }
        assertEquals(1, host.loadedStates.size)
        assertTrue(host.loadedStates[0].endsWith("card_5/state.mss"))
        assertEquals(1, host.uiClosedCalls)
    }

    @Test
    fun statesPanelTogglesLoadAndSaveModes() {
        val temp = Files.createTempDirectory("oracle-ui-states").toFile()
        val roms = File(temp, "Roms").apply { mkdirs() }
        val states = File(temp, "SaveStates").apply { mkdirs() }
        val saves = File(temp, "Saves").apply { mkdirs() }
        val host = TestHost(roms, states, saves).apply { ready = true }

        val ui = OracleUi(host) { r -> r.run() }
        ui.openMenu()
        repeat(RailItem.STATES.ordinal) { ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        assertFalse(ui.currentStatesSaveMode)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        assertTrue(ui.currentStatesSaveMode)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_L1, 0)
        assertFalse(ui.currentStatesSaveMode)
    }

    @Test
    fun romsAndSettingsReloadsCurrentRomAndTogglesZrModeWithFastForwardDefault() {
        val temp = Files.createTempDirectory("oracle-ui-settings").toFile()
        val roms = File(temp, "Roms").apply { mkdirs() }
        val states = File(temp, "SaveStates").apply { mkdirs() }
        val saves = File(temp, "Saves").apply { mkdirs() }
        val rom = File(roms, "Oracle - Main.sfc").apply { writeBytes(byteArrayOf(9, 8, 7)) }

        val host = TestHost(roms, states, saves).apply {
            ready = true
            currentRom = rom.path
        }
        assertFalse(host.setting("r2_capture"))

        val mainQueue = ConcurrentLinkedQueue<Runnable>()
        val ui = OracleUi(host) { r -> mainQueue.add(r) }
        ui.openMenu()
        pump(mainQueue) { !ui.isLoadingLibrary && ui.cardChannels.size == 1 }

        repeat(RailItem.SETTINGS.ordinal) { ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0) // enter SETTINGS at section 0 col 0 (Reload ROM)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        assertTrue(ui.isBusy)
        pump(mainQueue) { !ui.isBusy && ui.screen == Screen.CLOSED }
        assertEquals(listOf(rom.path), host.bootCalls)

        // Re-open menu, go to SETTINGS -> down through ROM strip to Settings grid cell 2 (ZR Mode).
        ui.openMenu()
        pump(mainQueue) { !ui.isLoadingLibrary }
        repeat(RailItem.SETTINGS.ordinal) { ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) // section 1 (ROM strip)
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) // section 2 (settings grid index 0: Sticky FF)
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) // index 2: ZR Mode
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        assertTrue(host.setting("r2_capture"))
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        assertFalse(host.setting("r2_capture"))
    }

    @Test
    fun oracleHubSwitchesThreeTabsListsWarpsAndSavesAndColdBootsOnWarp() {
        val temp = Files.createTempDirectory("oracle-ui-hub").toFile()
        val roms = File(temp, "Roms").apply { mkdirs() }
        val states = File(temp, "SaveStates").apply { mkdirs() }
        val saves = File(temp, "Saves").apply { mkdirs() }
        val mainRom = File(roms, "Oracle - Main.sfc").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        File(roms, "Oracle - Showcase.sfc").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        File(states, "Oracle - Main_1.mss").apply { writeBytes(byteArrayOf(9, 8, 7)) }

        val cpData = CheckpointData.parse(
            "{\"checkpoints\":[]}",
            "{\"entrances\":[],\"areas\":[{\"area\":\"0x33\",\"name\":\"Loom Beach\",\"via_room\":\"0x0104\"}]}",
        )
        val host = TestHost(roms, states, saves).apply {
            ready = true
            checkpointDataHook = { cpData }
            batteryLabelHook = { "Heart 5 · Save ready" }
        }
        val mainQueue = ConcurrentLinkedQueue<Runnable>()
        val ui = OracleUi(host) { r -> mainQueue.add(r) }

        ui.openLibrary()
        pump(mainQueue) { !ui.isLoadingLibrary && !ui.isLoadingShortcuts && ui.cardChannels.size == 2 }
        assertEquals(0, ui.currentHubTab)

        // L1/R1 switches between Test ROMs -> Play & Warps -> States.
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        assertEquals(1, ui.currentHubTab)
        assertEquals(1, ui.hubWarpCount)

        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        assertEquals(2, ui.currentHubTab)
        assertEquals(3, ui.hubSaveCount) // 1 occupied slot state + 2 ROM battery save rows

        // Wrap forward & backward via L1/R1.
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        assertEquals(0, ui.currentHubTab)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        assertEquals(1, ui.currentHubTab)

        // Launch built-in area warp while cold: boots active Oracle ROM first, then warps.
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        assertTrue(ui.isBusy)
        pump(mainQueue) { !ui.isBusy && ui.screen == Screen.CLOSED }
        assertEquals(listOf(mainRom.path), host.bootCalls)
        assertEquals(1, host.warpedTargets.size)
        assertEquals(0x33, host.warpedTargets[0].target)
    }

    @Test
    fun bundledPreviewAssetsMatchKeyLocations() {
        val previewsDir = File("src/main/assets/oracle/previews")
        assertTrue("previews dir must exist", previewsDir.isDirectory)
        val bundled = previewsDir.listFiles { _, name -> name.endsWith(".png") }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
        assertEquals(15, bundled.size)

        val temp = Files.createTempDirectory("oracle-ui-previews").toFile()
        val host = TestHost(
            File(temp, "Roms").apply { mkdirs() },
            File(temp, "SaveStates").apply { mkdirs() },
            File(temp, "Saves").apply { mkdirs() },
        ).apply {
            previewAssetsHook = { bundled }
        }
        val ui = OracleUi(host) { r -> r.run() }

        assertEquals("d1_grotto.png", ui.resolvedPreviewAsset("d1"))
        assertEquals("d4_zora.png", ui.resolvedPreviewAsset("zora"))
        assertEquals("d6_goron.png", ui.resolvedPreviewAsset("goron"))
        assertEquals("ow_30_docks.png", ui.resolvedPreviewAsset("ow_30_docks"))
        assertEquals("ow_23_wayward.png", ui.resolvedPreviewAsset("village-impa"))
        assertEquals("ow_00_ranch.png", ui.resolvedPreviewAsset("jump-horse-archery"))
        assertEquals("abyss_40_windmill.png", ui.resolvedPreviewAsset("abyss-arrival"))
        assertEquals("showcase_main.png", ui.resolvedPreviewAsset("full"))
        assertNotNull(ui.resolvedPreviewAsset("lyra_sea_korl"))
        assertNotNull(ui.resolvedPreviewAsset("sw_81_korok_cove"))
    }
}
