/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app

import com.gestalt.launcher.credential.InMemoryCredentialStore
import com.gestalt.launcher.launch.AutoLogin
import com.gestalt.launcher.launch.LaunchPhase
import com.gestalt.launcher.launch.LauncherCore
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionStore
import com.gestalt.launcher.net.ArtifactFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The launcher must HOLD the child process it starts: a connection's row says "Running" only while an
 * Administrator it launched is alive, clears itself on exit, allows a second instance, and a launch
 * still being prepared can be cancelled without spawning anything.
 */
class LaunchTrackingTest {

    /** A [Process] whose exit the test controls. */
    private class FakeProcess(private val fakePid: Long) : Process() {
        private val exited = CountDownLatch(1)
        fun exit() = exited.countDown()
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { exited.await(); return 0 }
        override fun exitValue(): Int { if (exited.count > 0) throw IllegalThreadStateException("running"); return 0 }
        override fun destroy() { exited.countDown() }
        override fun pid(): Long = fakePid
        override fun isAlive(): Boolean = exited.count > 0
    }

    /** A launcher that skips the pipeline and hands back a controllable process. */
    private class FakeLauncher(private val spawned: MutableList<FakeProcess>, private val pids: AtomicInteger) : LauncherCore() {
        override fun launch(connection: Connection, onPhase: (LaunchPhase) -> Unit, autoLogin: AutoLogin?): Process {
            onPhase(LaunchPhase.Verifying)
            onPhase(LaunchPhase.Launching)
            return FakeProcess(pids.incrementAndGet().toLong()).also { spawned += it }
        }
    }

    private fun newState(dir: Path, launcher: LauncherCore): AppState = AppState(
        store = ConnectionStore(dir.resolve("connections.json")),
        prefs = AppPreferences(dir.resolve("prefs.properties")),
        credentials = InMemoryCredentialStore(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        launcherFactory = { launcher },
    ).also { it.upsert(Connection(id = "c", name = "C", serverUrl = "https://h.example.com:8443")) }

    private fun await(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        var waited = 0L
        while (!cond() && waited < timeoutMs) { Thread.sleep(10); waited += 10 }
        assertTrue(cond()) { "timed out waiting for: $what" }
    }

    @Test
    @DisplayName("Running tracks the live child: counts instances, allows a relaunch, and clears itself on exit")
    fun runningFollowsTheChildProcess(@TempDir dir: Path) {
        val spawned = mutableListOf<FakeProcess>()
        val state = newState(dir, FakeLauncher(spawned, AtomicInteger()))

        state.launch("c")
        await("first instance running") { state.launches["c"]?.phase == LaunchPhase.Running }
        assertEquals(1, state.launches["c"]?.instances)
        assertEquals(1, state.runningProcesses("c").size)

        // A running connection may be launched AGAIN (same server, second Administrator window).
        state.launch("c")
        await("second instance running") { state.launches["c"]?.instances == 2 }
        assertEquals(2, spawned.size) { "a second child must have been spawned" }

        spawned[0].exit()
        await("first exit observed") { state.launches["c"]?.instances == 1 }
        assertEquals(LaunchPhase.Running, state.launches["c"]?.phase) { "still running: one instance left" }

        spawned[1].exit()
        await("row back to idle") { state.launches["c"] == null }
        assertTrue(state.runningProcesses("c").isEmpty())
    }

    @Test
    @DisplayName("a launch still downloading can be cancelled: row returns to idle, nothing spawned, no dialog")
    fun cancelWhileDownloading(@TempDir dir: Path) {
        val blockUntilCancelled = CountDownLatch(1)
        val fetchStarted = CountDownLatch(1)
        // A real LauncherCore whose fetcher blocks on the JNLP fetch until the thread is interrupted.
        val blockingLauncher = LauncherCore(
            cacheDir = dir.resolve("cache"),
            fetcherFactory = {
                object : ArtifactFetcher {
                    override fun fetchBytes(uri: URI): ByteArray {
                        fetchStarted.countDown()
                        blockUntilCancelled.await() // throws InterruptedException when cancel() interrupts us
                        error("should have been interrupted")
                    }
                }
            },
        )
        val state = newState(dir, blockingLauncher)

        state.launch("c")
        assertTrue(fetchStarted.await(5, TimeUnit.SECONDS)) { "the launch must reach the JNLP fetch" }
        assertEquals(true, state.launches["c"]?.inFlight)

        state.cancelLaunch("c")

        await("cancelled launch cleared") { state.launches["c"] == null }
        assertNull(state.dialog) { "a user cancel is not a failure — no diagnosis dialog" }
        assertTrue(state.runningProcesses("c").isEmpty()) { "nothing may have been spawned" }
    }

    @Test
    @DisplayName("an old child's exit never clobbers a newer launch being prepared for the same connection")
    fun staleExitDoesNotClobberNewLaunch(@TempDir dir: Path) {
        val spawned = mutableListOf<FakeProcess>()
        val state = newState(dir, FakeLauncher(spawned, AtomicInteger()))
        state.launch("c")
        await("running") { state.launches["c"]?.phase == LaunchPhase.Running }

        // Simulate a newer launch in preparation while the old child is still alive, then the old child exits.
        state.launches["c"] = LaunchView(LaunchPhase.Verifying)
        spawned[0].exit()
        await("exit processed") { state.runningProcesses("c").isEmpty() }
        assertEquals(LaunchPhase.Verifying, state.launches["c"]?.phase) { "the in-flight view must survive the stale exit" }
    }
}
