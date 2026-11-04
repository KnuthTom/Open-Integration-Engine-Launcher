/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tunnel

import com.gestalt.launcher.model.Connection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The port-forward lifecycle without a real shell: a fake process stands in for the tunnel command,
 * the port probe is scripted, and the clock is stepped by hand.
 */
class TunnelManagerTest {

    /** A tunnel command that stays alive until destroyed (or is scripted to exit with [exitWith]). */
    private class FakeProcess(private var exit: Int? = null) : Process() {
        val destroyed = AtomicBoolean(false)
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int {
            while (true) {
                exit?.let { return it }
                Thread.sleep(5)
            }
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !isAlive
        override fun exitValue(): Int = exit ?: throw IllegalThreadStateException("running")
        override fun isAlive(): Boolean = exit == null
        override fun destroy() {
            destroyed.set(true)
            if (exit == null) exit = 143
        }
        /** The command dies on its own (an SSM session idling out) — not destroyed by us. */
        fun die(code: Int) { if (exit == null) exit = code }
    }

    private class Scripted(private val process: () -> FakeProcess = { FakeProcess() }) : TunnelManager.ProcessStarter {
        val started = ArrayList<List<String>>()
        val dirs = ArrayList<Path?>()
        val logs = ArrayList<Path?>()
        val processes = ArrayList<FakeProcess>()
        override fun start(command: List<String>, workingDir: Path?, log: Path?): Process {
            started += command; dirs += workingDir; logs += log
            return process().also { processes += it }
        }
    }

    private fun connection(url: String = "https://localhost:31065", command: String? = "forward.bat", id: String = "c1") =
        Connection(id = id, name = "Example Lab", serverUrl = url, tunnelCommand = command)

    /** A probe that answers false [refusals] times, then true. */
    private fun probeAfter(refusals: Int): (String, Int) -> Boolean {
        val n = AtomicInteger()
        return { _, _ -> n.incrementAndGet() > refusals }
    }

    /** A probe that refuses each port once, then finds it listening — every connection's forward "comes up". */
    private fun probePerPort(): (String, Int) -> Boolean {
        val seen = HashMap<Int, Int>()
        return { _, port -> synchronized(seen) { val n = (seen[port] ?: 0) + 1; seen[port] = n; n > 1 } }
    }

    private fun manager(
        dir: Path,
        starter: Scripted,
        probe: (String, Int) -> Boolean,
        clock: () -> Long = { 0L },
        timeoutMs: Long = 60_000,
        sessionPollMs: Long = 50,
    ) = TunnelManager(logDir = dir, starter = starter, probe = probe, sleeper = {}, timeoutMs = timeoutMs, clock = clock, cacheDir = dir.resolve("tunnel-cache"), sessionPollMs = sessionPollMs)

    @Test
    @DisplayName("no tunnel command, or the port-forward switched off: nothing to acquire, nothing started")
    fun noTunnel(@TempDir dir: Path) {
        val starter = Scripted()
        assertNull(manager(dir, starter, { _, _ -> false }).acquire(connection(command = null)))
        assertNull(manager(dir, starter, { _, _ -> false }).acquire(connection(command = "   ")))
        val off = connection(command = "forward.bat").copy(tunnelEnabled = false)
        assertFalse(off.hasTunnel)
        assertNull(off.activeTunnelCommand) { "the command is kept but not run" }
        assertNull(manager(dir, starter, { _, _ -> false }).acquire(off))
        assertTrue(starter.started.isEmpty())
        val legacy = connection(command = "forward.bat")
        assertNull(legacy.tunnelEnabled)
        assertTrue(legacy.hasTunnel) { "a file from before the switch existed: on when there is a command" }
        assertEquals("forward.bat", legacy.activeTunnelCommand)
    }

    @Test
    @DisplayName("the port is already listening: the command is not run, and releasing closes nothing")
    fun portAlreadyOpen(@TempDir dir: Path) {
        val starter = Scripted()
        val m = manager(dir, starter, { _, _ -> true })
        val lease = m.acquire(connection())
        assertNotNull(lease)
        assertFalse(lease!!.managed) { "something else serves the port — not ours to close" }
        assertTrue(starter.started.isEmpty())
        lease.close()
        assertEquals(0, m.holders("c1"))
    }

    @Test
    @DisplayName("runs the command through the shell, waits for the port, logs beside the client logs, and tears it down on release")
    fun startsWaitsAndTearsDown(@TempDir dir: Path) {
        val starter = Scripted()
        var waiting = 0
        val m = manager(dir, starter, probeAfter(refusals = 3))
        val lease = m.acquire(connection()) { waiting++ }

        assertNotNull(lease); assertTrue(lease!!.managed)
        assertEquals(1, waiting) { "the UI is told once that the tunnel is being opened" }
        assertEquals(1, starter.started.size)
        val cmd = starter.started.single()
        assertTrue(cmd.last() == "forward.bat" && (cmd.first() == "cmd.exe" || cmd.first() == "/bin/sh")) { "$cmd" }
        assertNotNull(starter.logs.single()); assertTrue(starter.logs.single()!!.fileName.toString().endsWith("-tunnel.log"))
        assertTrue(Files.readString(starter.logs.single()!!).contains("# command: forward.bat"))
        assertEquals(1, m.holders("c1"))
        assertFalse(starter.processes.single().destroyed.get())

        lease.close()
        assertTrue(starter.processes.single().destroyed.get()) { "the last lease takes the tunnel down" }
        assertEquals(0, m.holders("c1"))
        lease.close() // idempotent
        assertEquals(0, m.holders("c1"))
    }

    @Test
    @DisplayName("two launches of one connection share the tunnel; it closes with the last of them")
    fun sharedBetweenLaunches(@TempDir dir: Path) {
        val starter = Scripted()
        val m = manager(dir, starter, probeAfter(refusals = 1))
        val first = m.acquire(connection())!!
        val second = m.acquire(connection())!!
        assertEquals(1, starter.started.size) { "one command for both" }
        assertTrue(second.managed)
        assertEquals(2, m.holders("c1"))

        first.close()
        assertFalse(starter.processes.single().destroyed.get()) { "still held by the second launch" }
        assertEquals(1, m.holders("c1"))
        second.close()
        assertTrue(starter.processes.single().destroyed.get())
    }

    @Test
    @DisplayName("a forward that dies while an Administrator uses it is reopened on its own; existing and new leases keep working across the reopen")
    fun reopensAfterCrashWhileInUse(@TempDir dir: Path) {
        val starter = Scripted()
        // The port listens exactly while one of the started commands is alive.
        val m = manager(dir, starter, { _, _ -> starter.processes.any { it.isAlive } })
        val first = m.acquire(connection())!!
        starter.processes[0].die(1) // the SSM session idles out while the first Administrator is still open
        awaitUntil("the forward is reopened") { starter.processes.size == 2 && starter.processes[1].isAlive }
        assertEquals(1, m.holders("c1")) { "same tunnel, same holder" }

        val second = m.acquire(connection())!! // another launch joins the reopened forward
        assertEquals(2, starter.processes.size) { "nothing new started: the reopened forward serves it" }
        assertEquals(2, m.holders("c1"))
        first.close()
        assertFalse(starter.processes[1].destroyed.get()) { "still held by the second launch" }
        second.close()
        assertTrue(starter.processes[1].destroyed.get())
        assertEquals(0, m.holders("c1"))
    }

    @Test
    @DisplayName("a forward the launcher took down itself is not reopened; one that keeps crashing is given up")
    fun crashReopenIsBounded(@TempDir dir: Path) {
        val starter = Scripted()
        var now = 0L
        val m = manager(dir, starter, { _, _ -> starter.processes.any { it.isAlive } }, clock = { now })
        val lease = m.acquire(connection())!!
        repeat(TunnelManager.REOPEN_LIMIT) { i ->
            starter.processes.last().die(1)
            awaitUntil("reopen #${i + 1}") { starter.processes.size == i + 2 && starter.processes[i + 1].isAlive }
        }
        starter.processes.last().die(1) // one too many inside the window
        awaitUntil("given up") { m.holders("c1") == 0 }
        assertEquals(TunnelManager.REOPEN_LIMIT + 1, starter.processes.size) { "no further reopen" }
        lease.close() // stale lease: nothing to release
        assertEquals(0, m.holders("c1"))

        // Taken down by the launcher: stays down.
        val m2 = manager(dir, Scripted(), probeAfter(refusals = 1))
        val l2 = m2.acquire(connection())!!
        l2.close()
        Thread.sleep(150)
        assertEquals(0, m2.holders("c1"))
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    @DisplayName("recheck before the spawn: a port that still answers is left alone; a forward that stopped listening is reopened in place")
    fun recheckReopensDeadForward(@TempDir dir: Path) {
        val starter = Scripted()
        var listening = true
        // The plugin process lives on but its listener is gone (the session was dropped).
        val m = manager(dir, starter, { _, _ -> listening && starter.processes.any { it.isAlive } })
        val lease = m.acquire(connection())!!
        assertEquals(1, starter.started.size)

        m.recheck(connection(), lease)
        assertEquals(1, starter.started.size) { "still listening: nothing to do" }

        listening = false
        var waiting = 0
        m.recheck(connection(), lease) { waiting++; listening = true }
        assertEquals(2, starter.started.size) { "reopened" }
        assertEquals(1, waiting)
        assertTrue(starter.processes[0].destroyed.get()) { "the old process is taken down" }
        assertTrue(starter.processes[1].isAlive)
        assertEquals(1, m.holders("c1")) { "same tunnel entry, same holder" }

        lease.close() // the Administrator exits: the lease taken on the FIRST process closes the reopened one
        assertTrue(starter.processes[1].destroyed.get())
        assertEquals(0, m.holders("c1"))
    }

    @Test
    @DisplayName("basic-mode detection reads the plugin's own output: the bracket-less accept line means one connection at a time")
    fun basicModeDetection(@TempDir dir: Path) {
        val starter = Scripted()
        val m = manager(dir, starter, probeAfter(refusals = 1))
        assertFalse(m.isBasicForward("c1")) { "no tunnel yet" }
        m.acquire(connection())
        val log = starter.logs.single()!!
        Files.writeString(log, Files.readString(log) + "Starting session with SessionId: user-abc123\nPort 31065 opened for sessionId user-abc123.\nWaiting for connections...\n\nConnection accepted for session [user-abc123]\n")
        assertFalse(m.isBasicForward("c1")) { "multiplexing: brackets" }
        Files.writeString(log, Files.readString(log) + "Connection accepted for session user-abc123.\n")
        assertTrue(m.isBasicForward("c1")) { "basic: bare id with a period" }
        assertFalse(m.isBasicForward("other"))
    }

    @Test
    @DisplayName("the server side refusing the forwarded port is read from the plugin's own output, while the forward is held")
    fun destinationRefusedDetection(@TempDir dir: Path) {
        val starter = Scripted()
        val m = manager(dir, starter, probeAfter(refusals = 1))
        assertFalse(m.destinationRefused("c1")) { "no tunnel yet" }
        val lease = m.acquire(connection())!!
        val log = starter.logs.single()!!
        Files.writeString(log, Files.readString(log) + "Waiting for connections...\n\nConnection accepted for session [user-abc123]\n")
        assertFalse(m.destinationRefused("c1")) { "accepted, nothing refused" }
        Files.writeString(log, Files.readString(log) + "\nConnection to destination port failed, check SSM Agent logs.\n")
        assertTrue(m.destinationRefused("c1"))
        assertFalse(m.destinationRefused("other"))
        lease.close()
        assertFalse(m.destinationRefused("c1")) { "released: the forward and its log are no longer held" }
    }

    @Test
    @DisplayName("a held forward whose session ends while its process lives on (a batch file parked on `pause`) is reopened for its holders; the lease knows its forward was replaced")
    fun sessionEndingUnderAHeldForwardReopensIt(@TempDir dir: Path) {
        val starter = Scripted()
        val m = manager(dir, starter, { _, _ -> starter.processes.any { it.isAlive } })
        val lease = m.acquire(connection())!!
        assertFalse(lease.replacedUnder)
        // The agent closes the session; the plugin exits; cmd.exe sits on `pause` — the process stays alive.
        starter.logs[0]!!.let { Files.writeString(it, Files.readString(it) + "\nExiting session with sessionId: user-abc123.\n\nPress any key to continue . . .\n") }
        awaitUntil("the dead session's forward is replaced") { starter.processes.size == 2 && starter.processes[1].isAlive }
        assertTrue(starter.processes[0].destroyed.get()) { "the parked process was taken down with its session" }
        assertEquals(1, m.holders("c1")) { "same holder, same lease" }
        assertTrue(lease.replacedUnder) { "a fetch that failed meanwhile failed against the dead forward" }
        assertFalse(m.sessionEnded("c1")) { "the fresh session has not ended" }
        lease.close()
        assertTrue(starter.processes[1].destroyed.get())
        assertFalse(m.hasLiveTunnels())
    }

    @Test
    @DisplayName("acquire on a held forward whose session has ended reopens it in place before joining")
    fun acquireJoinsAReopenedForward(@TempDir dir: Path) {
        val starter = Scripted()
        // The watcher is slow here, so acquire finds the dead session itself.
        // The first forward's port answers while it comes up; a reopened one answers while it lives.
        val m = manager(dir, starter, { _, _ -> starter.processes.size == 1 || (starter.processes.lastOrNull()?.isAlive == true && starter.processes.size > 1) }, sessionPollMs = 60_000)
        val first = m.acquire(connection())!!
        starter.logs[0]!!.let { Files.writeString(it, Files.readString(it) + "\nExiting session with sessionId: user-abc123.\n") }
        val second = m.acquire(connection())!!
        assertEquals(2, starter.processes.size) { "reopened in place, then joined" }
        assertTrue(starter.processes[0].destroyed.get())
        assertEquals(2, m.holders("c1"))
        assertTrue(first.replacedUnder)
        assertFalse(second.replacedUnder) { "taken after the reopen" }
        first.close(); second.close()
        assertFalse(m.hasLiveTunnels())
    }

    @Test
    @DisplayName("the refusal line is waited for (bounded) — the plugin writes it around the moment it drops the connection — and is still found after the forward was reopened with a fresh log")
    fun destinationRefusedIsPatientAndRemembersEarlierLogs(@TempDir dir: Path) {
        val starter = Scripted()
        var now = 0L
        var polls = 0
        val sleeper: (Long) -> Unit = { ms ->
            now += ms; polls++
            if (polls == 3) starter.logs.first()!!.let { Files.writeString(it, Files.readString(it) + "\nConnection to destination port failed, check SSM Agent logs.\n") }
        }
        val m = TunnelManager(logDir = dir, starter = starter, probe = { _, _ -> starter.processes.any { it.isAlive } }, sleeper = sleeper, timeoutMs = 60_000, clock = { now }, cacheDir = dir.resolve("tunnel-cache"))
        val lease = m.acquire(connection())!!
        assertFalse(m.destinationRefused("c1")) { "nothing written yet, and no wait asked for" }
        polls = 0
        assertTrue(m.destinationRefused("c1", 1_500)) { "the line turned up on the third poll" }
        assertEquals(3, polls)

        // Basic (non-mux) mode ends the session on the refusal; the manager reopens the forward with a new log.
        starter.processes[0].die(1)
        awaitUntil("the forward is reopened") { starter.logs.size == 2 && starter.processes[1].isAlive }
        assertTrue(m.destinationRefused("c1")) { "what the earlier process reported is still readable" }
        lease.close()

        val again = m.acquire(connection())!!
        polls = 0; now = 0
        assertFalse(m.destinationRefused("c1", 1_500)) { "a forward that never reports it: the wait is bounded" }
        assertEquals(15, polls)
        again.close()
    }

    @Test
    @DisplayName("a forward opened at another remote port runs the command re-pointed at it — a batch file as a copy, run in its own folder — and the lease and log say so")
    fun remotePortRepointing(@TempDir dir: Path) {
        val starter = Scripted()
        val m = manager(dir, starter, probeAfter(refusals = 1))
        val inline = "aws ssm start-session --target mi-0123456789abcdef0 --document-name AWS-StartPortForwardingSession --parameters \"localPortNumber=31065,portNumber=8000\""
        assertEquals(8000, m.commandRemotePort(connection(command = inline)))
        assertNull(m.commandRemotePort(connection(command = "forward.bat")))
        assertEquals(8443, m.nextRemotePort(connection(command = inline), setOf(8000)))
        assertEquals(8000, m.nextRemotePort(connection(command = inline), emptySet()))
        assertNull(m.nextRemotePort(connection(command = inline), setOf(8000, 8443)))
        assertNull(m.nextRemotePort(connection(command = "forward.bat"), emptySet())) { "no port the launcher could change" }

        val lease = m.acquire(connection(command = inline), remotePort = 8443)!!
        assertEquals(8443, lease.remotePort)
        assertTrue(starter.started.single().last().contains("portNumber=8443")) { starter.started.toString() }
        assertTrue(Files.readString(starter.logs.single()!!).contains("remote port 8443 instead of 8000"))
        lease.close()

        val scripts = Files.createDirectories(dir.resolve("scripts"))
        val bat = scripts.resolve("forward.bat")
        Files.writeString(bat, "@echo off\r\n$inline\r\n")
        val starter2 = Scripted()
        val m2 = manager(dir, starter2, probeAfter(refusals = 1))
        val lease2 = m2.acquire(connection(command = bat.toString()), remotePort = 8443)!!
        val ran = starter2.started.single().last()
        assertEquals(dir.resolve("tunnel-cache").resolve("c1-8443.bat").toString(), ran)
        assertTrue(Files.readString(Path.of(ran)).contains("portNumber=8443"))
        assertEquals(scripts, starter2.dirs.single()) { "the copy runs in the original's folder" }
        assertEquals(8443, lease2.remotePort)
        lease2.close()

        val starter3 = Scripted()
        val plain = manager(dir, starter3, probeAfter(refusals = 1)).acquire(connection(command = bat.toString()))!!
        assertEquals(8000, plain.remotePort) { "no override: the command's own port, run as it is" }
        assertEquals(bat.toString(), starter3.started.single().last())
        plain.close()
    }

    @Test
    @DisplayName("the remote port that answered is remembered per connection in the cache dir, survives a new manager, and is forgotten on request")
    fun remotePortMemory(@TempDir dir: Path) {
        val m = manager(dir, Scripted(), { _, _ -> false })
        assertNull(m.rememberedRemotePort("c1"))
        m.rememberRemotePort("c1", 8443)
        assertEquals(8443, m.rememberedRemotePort("c1"))
        assertEquals(8443, manager(dir, Scripted(), { _, _ -> false }).rememberedRemotePort("c1")) { "persisted" }
        m.rememberRemotePort("c1", null)
        assertNull(m.rememberedRemotePort("c1"))
        assertNull(manager(dir, Scripted(), { _, _ -> false }).rememberedRemotePort("c1"))
        Files.writeString(dir.resolve("tunnel-cache").resolve(TunnelManager.REMOTE_PORTS_FILE), "{ not json")
        assertNull(manager(dir, Scripted(), { _, _ -> false }).rememberedRemotePort("c1")) { "a corrupt file is ignored" }
    }

    @Test
    @DisplayName("recheck: a forward the launcher did not open, now dead, is reported — it can't be reopened here")
    fun recheckUnmanagedDeadThrows(@TempDir dir: Path) {
        val starter = Scripted()
        var open = true
        val m = manager(dir, starter, { _, _ -> open })
        val lease = m.acquire(connection())!!
        assertFalse(lease.managed)
        open = false
        val ex = assertThrows(TunnelFailedException::class.java) { m.recheck(connection(), lease) }
        assertTrue(ex.message!!.contains("stopped listening")) { ex.message }
        assertTrue(starter.started.isEmpty())
        m.recheck(connection(command = null), null) // no tunnel: nothing to check
    }

    @Test
    @DisplayName("onIdle fires when the last tunnel is released (at once when none is running) — what keeps the hidden launcher alive")
    fun idleNotification(@TempDir dir: Path) {
        val starter = Scripted()
        val m = manager(dir, starter, probeAfter(refusals = 1))
        var idle = 0
        m.onIdle { idle++ }
        assertEquals(1, idle) { "nothing running: idle at once" }

        val lease = m.acquire(connection())!!
        assertTrue(m.hasLiveTunnels())
        m.onIdle { idle++ }
        assertEquals(1, idle) { "a tunnel is held: not yet" }
        lease.close()
        assertFalse(m.hasLiveTunnels())
        assertEquals(2, idle) { "the last release fires it" }
    }

    @Test
    @DisplayName("different connections get their own tunnels")
    fun perConnection(@TempDir dir: Path) {
        val starter = Scripted()
        val m = manager(dir, starter, probePerPort())
        val a = m.acquire(connection(id = "a", url = "https://localhost:31001", command = "a.bat"))!!
        val b = m.acquire(connection(id = "b", url = "https://localhost:31002", command = "b.bat"))!!
        assertTrue(a.managed && b.managed)
        assertEquals(listOf("a.bat", "b.bat"), starter.started.map { it.last() })
        a.close()
        assertTrue(starter.processes[0].destroyed.get()); assertFalse(starter.processes[1].destroyed.get())
        b.close()
        assertTrue(starter.processes[1].destroyed.get())
    }

    @Test
    @DisplayName("the command exits before the port is up: the failure names the exit code and the log's last lines")
    fun commandExitsEarly(@TempDir dir: Path) {
        val starter = Scripted { FakeProcess(exit = 3) }
        val m = manager(dir, starter, { _, _ -> false })
        val logs = starter.logs
        val ex = assertThrows(TunnelFailedException::class.java) {
            m.acquire(connection())
        }
        assertTrue(ex.message!!.contains("exited (code 3)")) { ex.message }
        assertTrue(ex.message!!.contains("localhost:31065")) { ex.message }
        assertTrue(ex.message!!.contains("-tunnel.log")) { ex.message }
        assertEquals(0, m.holders("c1"))
        assertEquals(1, logs.size)
    }

    @Test
    @DisplayName("the port never comes up: times out, tears the command down, and says so")
    fun timesOut(@TempDir dir: Path) {
        val starter = Scripted()
        var now = 0L
        val m = manager(dir, starter, { _, _ -> false }, clock = { now.also { now += 400 } }, timeoutMs = 1_000)
        val ex = assertThrows(TunnelFailedException::class.java) { m.acquire(connection()) }
        assertTrue(ex.message!!.contains("did not open localhost:31065 within 1 s")) { ex.message }
        assertTrue(starter.processes.single().destroyed.get())
        assertEquals(0, m.holders("c1"))
    }

    @Test
    @DisplayName("a cancelled launch while the tunnel opens tears the command down and surfaces as InterruptedException")
    fun cancelledWhileWaiting(@TempDir dir: Path) {
        val starter = Scripted()
        val m = manager(dir, starter, { _, _ -> false })
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { m.acquire(connection()) }
        } finally {
            Thread.interrupted() // clear, whatever happened
        }
        assertTrue(starter.processes.single().destroyed.get())
    }

    @Test
    @DisplayName("the address must carry an explicit port — it is the local end of the forward")
    fun needsExplicitPort(@TempDir dir: Path) {
        val starter = Scripted()
        val ex = assertThrows(TunnelFailedException::class.java) { manager(dir, starter, { _, _ -> true }).acquire(connection(url = "https://localhost")) }
        assertTrue(ex.message!!.contains("explicit port")) { ex.message }
        assertTrue(starter.started.isEmpty())
    }

    @Test
    @DisplayName("closeAll tears down every tunnel the launcher opened")
    fun closeAll(@TempDir dir: Path) {
        val starter = Scripted()
        val m = manager(dir, starter, probePerPort())
        m.acquire(connection(id = "a", url = "https://localhost:31001"))
        m.acquire(connection(id = "b", url = "https://localhost:31002"))
        assertEquals(2, starter.processes.size)
        m.closeAll()
        assertTrue(starter.processes.all { it.destroyed.get() })
        assertEquals(0, m.holders("a")); assertEquals(0, m.holders("b"))
    }

    @Test
    @DisplayName("the shell wrapping and the working directory rule")
    fun commandShapes(@TempDir dir: Path) {
        assertEquals(listOf("cmd.exe", "/c", "C:\\tools\\forward.bat"), TunnelManager.commandLine("C:\\tools\\forward.bat", windows = true))
        assertEquals(listOf("/bin/sh", "-c", "ssh -N -L 31065:mirth:8443 bastion"), TunnelManager.commandLine("ssh -N -L 31065:mirth:8443 bastion", windows = false))
        val script = Files.writeString(dir.resolve("forward.bat"), "@echo off")
        assertEquals(script.toAbsolutePath().parent, TunnelManager.workingDirFor(script.toAbsolutePath().toString()))
        assertEquals(script.toAbsolutePath().parent, TunnelManager.workingDirFor("\"${script.toAbsolutePath()}\"")) { "a quoted path is still a path" }
        assertNull(TunnelManager.workingDirFor("tools/forward.bat")) { "a relative path keeps the launcher's directory, or the command itself would stop resolving" }
        assertNull(TunnelManager.workingDirFor("aws ssm start-session --target mi-0123456789abcdef0"))
        assertNull(TunnelManager.workingDirFor(""))
    }

    @Test
    @DisplayName("the real probe: true while something listens on the port, false once it is gone")
    fun realProbe() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val port = server.localPort
        try {
            assertTrue(TunnelManager.portAccepting("localhost", port) || TunnelManager.portAccepting("127.0.0.1", port))
        } finally {
            server.close()
        }
        assertFalse(TunnelManager.portAccepting("127.0.0.1", port))
    }
}
