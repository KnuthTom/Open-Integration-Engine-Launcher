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
        override fun waitFor(): Int = exit ?: 0
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
    ) = TunnelManager(logDir = dir, starter = starter, probe = probe, sleeper = {}, timeoutMs = timeoutMs, clock = clock)

    @Test
    @DisplayName("no tunnel command: nothing to acquire, nothing started")
    fun noTunnel(@TempDir dir: Path) {
        val starter = Scripted()
        assertNull(manager(dir, starter, { _, _ -> false }).acquire(connection(command = null)))
        assertNull(manager(dir, starter, { _, _ -> false }).acquire(connection(command = "   ")))
        assertTrue(starter.started.isEmpty())
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
    @DisplayName("a tunnel that died and was replaced: the old launch's lease never closes the replacement")
    fun staleLeaseDoesNotCloseReplacement(@TempDir dir: Path) {
        val starter = Scripted()
        // The port listens exactly while one of the started commands is alive.
        val m = manager(dir, starter, { _, _ -> starter.processes.any { it.isAlive } })
        val first = m.acquire(connection())!!
        starter.processes[0].die(1) // the forward idles out while the first Administrator is still open
        val second = m.acquire(connection())!! // the next launch opens a fresh one
        assertEquals(2, starter.processes.size)
        assertTrue(second.managed)

        first.close() // the first Administrator exits
        assertFalse(starter.processes[1].destroyed.get()) { "the replacement is the second launch's, not the first's" }
        assertEquals(1, m.holders("c1"))
        second.close()
        assertTrue(starter.processes[1].destroyed.get())
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
