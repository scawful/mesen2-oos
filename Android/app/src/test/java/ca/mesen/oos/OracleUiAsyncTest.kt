package ca.mesen.oos

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        var batteryLabelHook: (File) -> String = { "" }

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
        override fun presetData(): PresetData = PresetData.empty(null)
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

        // Add a second ROM and trigger a newer openLibrary scan while scan 1 is blocked.
        File(roms, "Oracle - Latest.sfc").writeBytes(byteArrayOf(4, 5, 6))
        ui.openLibrary()
        releaseFirstScan.countDown()

        pump(mainQueue) { !ui.isLoadingLibrary && ui.cardChannels.size == 2 }
        assertEquals(listOf("Latest", "Main"), ui.cardChannels)
    }

    @Test
    fun openMenuAndTabSwitchLoadOffMainThreadAndGuardStaleItems() {
        val temp = Files.createTempDirectory("oracle-ui-menu").toFile()
        val roms = File(temp, "Roms").apply { mkdirs() }
        val states = File(temp, "SaveStates").apply { mkdirs() }
        val saves = File(temp, "Saves").apply { mkdirs() }
        val rom = File(roms, "Oracle - Main.sfc").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        File(roms, "Oracle - Main.build.json").writeText("{\"build\":\"b66\"}")

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

        // Move rail to ITEMS and enter panel.
        repeat(RailItem.ITEMS.ordinal) { ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        // Switch from Presets -> Warps -> Test cheats -> Items -> Songs rapidly via R1.
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)

        metaGate.countDown()
        pump(mainQueue) {
            ui.currentMenuTitle == "Main b66" &&
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
        // Move to CAPTURE and enter panel (cursor at 0: Take screenshot).
        repeat(RailItem.CAPTURE.ordinal) { ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)

        val t0 = System.nanoTime()
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertTrue("screenshot capture must not block UI thread", elapsedMs < 250)
        assertTrue(ui.isBusy)

        gate.countDown()
        pump(mainQueue) { !ui.isBusy && ui.currentStatus.contains("oracle_test.png") }
        assertEquals(1, ui.currentCaptures.size)
        assertEquals("oracle_test.png", ui.currentCaptures[0].name)

        // Move down 2 to Save last 20s instant replay, then 2 more to Save bug snapshot (row 4).
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        pump(mainQueue) { !ui.isBusy && ui.currentStatus.contains("replay_test.mp4") }

        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        ui.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        pump(mainQueue) { !ui.isBusy && ui.currentStatus.contains("Warp / Transition") }
    }

    @Test
    fun warpsAndTestCheatsTabsWorkOffMainThread() {
        val temp = Files.createTempDirectory("oracle-ui-testtools").toFile()
        val roms = File(temp, "Roms").apply { mkdirs() }
        val states = File(temp, "SaveStates").apply { mkdirs() }
        val saves = File(temp, "Saves").apply { mkdirs() }
        val host = TestHost(roms, states, saves).apply { ready = true }

        val mainQueue = ConcurrentLinkedQueue<Runnable>()
        val ui = OracleUi(host) { r -> mainQueue.add(r) }
        ui.openMenu()
        // Enter ITEMS.
        repeat(RailItem.ITEMS.ordinal) { ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        // R1 once -> Warps, R1 twice -> Test cheats.
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        assertEquals("Warps", ui.currentItemTabName)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        assertEquals("Test cheats", ui.currentItemTabName)

        // Move to INF_HEALTH (row 0) -> toggles cheat off main thread.
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        pump(mainQueue) { !ui.isBusy && host.tools.isCheatActive(TestingTools.Cheat.INF_HEALTH) }
        assertTrue(host.activeCheatAddrs.containsKey(Inventory.HEALTH_CURRENT))

        // Move down 6 rows (past 5 cheats + Clear all) to Overlay: Hitboxes & sprites (bit 0x01).
        repeat(6) { ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        pump(mainQueue) { !ui.isBusy && (host.overlayMask and 0x01) != 0 }
    }

    @Test
    fun playtestShortcutsGridNavigatesAndLaunchesOffMainThread() {
        val temp = Files.createTempDirectory("oracle-ui-shortcuts").toFile()
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

        val host = TestHost(roms, states, saves, playtestDirFile = playtest).apply {
            ready = true
            currentRom = rom.path
        }
        val mainQueue = ConcurrentLinkedQueue<Runnable>()
        val ui = OracleUi(host) { r -> mainQueue.add(r) }
        ui.openMenu()
        pump(mainQueue) { !ui.isLoadingShortcuts && ui.currentShortcuts.size == 5 }

        // Move to SHORTCUTS (index 1) and enter 3-column grid.
        repeat(RailItem.SHORTCUTS.ordinal) { ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        assertEquals(0, ui.currentShortcutIndex)

        // Move right twice -> index 2 (row 0, col 2), then down -> clamps to index 4 (last card on partial row 1).
        ui.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        ui.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        assertEquals(2, ui.currentShortcutIndex)
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        assertEquals(4, ui.currentShortcutIndex)

        // Launch Card 5 via A button -> runs off main thread, loads state, and closes menu.
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        assertTrue(ui.isBusy)
        pump(mainQueue) { !ui.isBusy && ui.screen == Screen.CLOSED }
        assertEquals(1, host.loadedStates.size)
        assertTrue(host.loadedStates[0].endsWith("card_5/state.mss"))
        assertEquals(1, host.uiClosedCalls)
    }

    @Test
    fun reloadRomActionBootsCurrentRomOffMainThreadAndClosesMenu() {
        val temp = Files.createTempDirectory("oracle-ui-reload").toFile()
        val roms = File(temp, "Roms").apply { mkdirs() }
        val states = File(temp, "SaveStates").apply { mkdirs() }
        val saves = File(temp, "Saves").apply { mkdirs() }
        val rom = File(roms, "Oracle - Main.sfc").apply { writeBytes(byteArrayOf(9, 8, 7)) }

        val host = TestHost(roms, states, saves).apply {
            ready = true
            currentRom = rom.path
        }
        val mainQueue = ConcurrentLinkedQueue<Runnable>()
        val ui = OracleUi(host) { r -> mainQueue.add(r) }
        ui.openMenu()
        repeat(RailItem.RELOAD.ordinal) { ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        assertTrue(ui.isBusy)
        pump(mainQueue) { !ui.isBusy && ui.screen == Screen.CLOSED }
        assertEquals(listOf(rom.path), host.bootCalls)
        assertEquals(1, host.uiClosedCalls)
    }

    @Test
    fun oracleHubSwitchesThreeTabsListsSavesAndWarpsAndColdBootsOnWarp() {
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

        // L1/R1 switches between Test ROMs -> Saves & States -> Warps & Shortcuts.
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        assertEquals(1, ui.currentHubTab)
        assertEquals(3, ui.hubSaveCount) // 1 occupied slot state + 2 ROM battery save rows

        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        assertEquals(2, ui.currentHubTab)
        assertEquals(2, ui.hubWarpCount) // 1 built-in area warp + FallRoom row

        // Wrap forward & backward via L1/R1.
        ui.onKey(KeyEvent.KEYCODE_BUTTON_R1, 0)
        assertEquals(0, ui.currentHubTab)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_L1, 0)
        assertEquals(2, ui.currentHubTab)

        // Launch built-in area warp while cold (no ROM running yet): boots active Oracle ROM first, then warps.
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        assertTrue(ui.isBusy)
        pump(mainQueue) { !ui.isBusy && ui.screen == Screen.CLOSED }
        assertEquals(listOf(mainRom.path), host.bootCalls)
        assertEquals(1, host.warpedTargets.size)
        assertEquals(0x33, host.warpedTargets[0].target)
    }

    @Test
    fun r2CaptureSettingTogglesInFastPanelAndSettings() {
        val temp = Files.createTempDirectory("oracle-ui-r2cap").toFile()
        val roms = File(temp, "Roms").apply { mkdirs() }
        val states = File(temp, "SaveStates").apply { mkdirs() }
        val saves = File(temp, "Saves").apply { mkdirs() }
        val host = TestHost(roms, states, saves).apply { ready = true }
        assertFalse(host.setting("r2_capture"))

        val ui = OracleUi(host) { r -> r.run() }
        ui.openMenu()
        repeat(RailItem.FAST.ordinal) { ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0) // enter FastPanel
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) // row 1: Fast-forward speed
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) // row 2: ZR / R2 trigger mode
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        assertTrue(host.setting("r2_capture"))

        // Step back to Settings panel, row 1 (`r2_capture`), toggle back to false (default Fast Forward).
        ui.onKey(KeyEvent.KEYCODE_BUTTON_B, 0)
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0) // enter SettingsPanel
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0) // row 1: r2_capture
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        assertFalse(host.setting("r2_capture"))
    }
}
