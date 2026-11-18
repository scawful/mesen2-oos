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
    ) : OracleUi.Host {
        var ready = false
        var currentRom: String? = null
        var uiOpenedCalls = 0
        var uiClosedCalls = 0
        var romRootsHook: () -> Unit = {}
        var socketSummaryHook: () -> String = { "tcp://127.0.0.1:27015 · ready" }

        override fun nativeReady(): Boolean = ready
        override fun romRoots(): Array<File> {
            romRootsHook()
            return arrayOf(romDir)
        }
        override fun currentRomPath(): String? = currentRom
        override fun batterySaveLabel(rom: File): String = ""
        override fun romSourceLabel(rom: File): String = "app"
        override fun statesDir(): File = statesDirFile
        override fun savesDir(): File = savesDirFile
        override fun checkpointData(): CheckpointData = CheckpointData.empty(null)
        override fun presetData(): PresetData = PresetData.empty(null)
        override fun socketSummary(): String = socketSummaryHook()
        override fun uiOpened() { uiOpenedCalls++ }
        override fun uiClosed() { uiClosedCalls++ }
        override fun bootRom(path: String): Boolean = true
        override fun resetConsole() {}
        override fun requestHomeShortcut() {}
        override fun isStickyFastForward(): Boolean = false
        override fun setStickyFastForward(on: Boolean) {}
        override fun setting(key: String): Boolean = false
        override fun setSetting(key: String, on: Boolean) {}
        override fun startCheckpoint(id: String) {}
        override fun startPreset(id: String) {}
        override fun toast(message: String) {}
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

        // Move rail to ITEMS (index 3: RESUME, SAVE, LOAD, ITEMS) and enter panel.
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        ui.onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        ui.onKey(KeyEvent.KEYCODE_BUTTON_A, 0)
        // Switch from Presets -> Items -> Songs rapidly via R1.
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
}
