/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tunnel

import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.audit.AuditLog
import com.gestalt.launcher.launch.ClientLog
import com.gestalt.launcher.launch.LaunchAbortedException
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.net.LocalhostResolver
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The connection's tunnel command could not bring the address's port up. The launch stopped before any fetch. */
class TunnelFailedException(message: String, cause: Throwable? = null) : LaunchAbortedException(message, cause)

/**
 * Opens a connection's **port-forward** before the launch and closes it after the Administrator exits.
 *
 * A connection may name a *tunnel command* — typically a batch file that starts an AWS SSM
 * port-forwarding session (`aws ssm start-session … AWS-StartPortForwardingSession …`), or an `ssh -L`,
 * or anything else that makes the address's port listen locally. The launcher runs it with the user's
 * own tools and environment (nothing is bundled), waits for the address's host:port to accept a
 * connection, then fetches and launches as usual. The tunnel is held while Administrators launched
 * through it are running and torn down — the command and every process it spawned — when the last
 * one exits, or when the launcher closes.
 *
 * Rules:
 *  - The address must carry an explicit port: it is the local end of the forward, and it is what the
 *    Administrator shows on its login screen.
 *  - If the port is already listening when a launch starts (a tunnel opened by hand, or by a previous
 *    launch whose Administrator is still running), the command is not run again and nothing is torn
 *    down afterwards — the launcher only closes what it opened.
 *  - The command's output goes to `logs/client/<connection>-<stamp>-tunnel.log`; a failure to open the
 *    port reports the command's exit code and the last lines of that log.
 *  - The command is the user's own, trusted local config — like the Java home or heap size — and is
 *    never imported from a connection file (the importer drops it).
 */
class TunnelManager(
    private val logDir: Path = AppPaths.logDir.resolve("client"),
    private val starter: ProcessStarter = ProcessStarter.real(),
    private val probe: (host: String, port: Int) -> Boolean = ::portAccepting,
    private val sleeper: (Long) -> Unit = { if (it > 0) Thread.sleep(it) },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Spawns the tunnel command (injected so the manager is tested without a real shell). */
    fun interface ProcessStarter {
        fun start(command: List<String>, workingDir: Path?, log: Path?): Process

        companion object {
            /** The real thing: the command through the platform shell, output appended to [log] (or discarded). */
            fun real(): ProcessStarter = ProcessStarter { command, workingDir, log ->
                val builder = ProcessBuilder(command).redirectErrorStream(true)
                if (workingDir != null) builder.directory(workingDir.toFile())
                if (log != null) builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                else builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                builder.start()
            }
        }
    }

    /**
     * One launch's hold on a connection's tunnel. [managed] is true when the launcher opened the tunnel
     * (and will close it once every lease is released); false when the port was already being served
     * by something the launcher doesn't own. Closing twice is harmless.
     */
    class Lease internal constructor(
        private val manager: TunnelManager,
        val connectionId: String,
        val managed: Boolean,
        /** The tunnel this lease was counted on — a lease never releases a replacement opened later. */
        private val entry: Live?,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true) && managed && entry != null) manager.release(connectionId, entry)
        }
    }

    internal class Live(val process: Process, val log: Path?) {
        var users = 0
    }

    private val live = ConcurrentHashMap<String, Live>()
    private val locks = ConcurrentHashMap<String, Any>()

    private fun lockFor(id: String): Any = locks.computeIfAbsent(id) { Any() }

    /**
     * Make sure [connection]'s port-forward is up. Null when the connection has no tunnel command.
     * [onWaiting] is called once if the command has to be run (the UI shows "Opening tunnel…").
     * Throws [TunnelFailedException] when the command fails or the port never comes up, and
     * [InterruptedException] when the launch is cancelled meanwhile (the command is then torn down).
     */
    @Throws(TunnelFailedException::class, InterruptedException::class)
    fun acquire(connection: Connection, onWaiting: () -> Unit = {}): Lease? {
        val command = connection.tunnelCommand?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { connection.baseUri() }.getOrNull()
            ?: throw TunnelFailedException("the tunnel needs a usable address: ${connection.serverUrl}")
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw TunnelFailedException("the tunnel needs a host in the address: ${connection.serverUrl}")
        val port = uri.port.takeIf { it > 0 }
            ?: throw TunnelFailedException("the tunnel needs an explicit port in the address — it is the local end of the forward (for example https://localhost:31065)")

        synchronized(lockFor(connection.id)) {
            live[connection.id]?.let { current ->
                if (current.process.isAlive) {
                    current.users++
                    return Lease(this, connection.id, managed = true, entry = current)
                }
                live.remove(connection.id) // the command has exited; whatever it left behind is not ours
            }
            if (probe(host, port)) {
                AuditLog.event("tunnel.reuse", "connection" to connection.id, "port" to port)
                return Lease(this, connection.id, managed = false, entry = null)
            }
            onWaiting()
            val log = ClientLog.openTunnel(logDir, connection, command)
            val process = try {
                starter.start(commandLine(command), workingDirFor(command), log)
            } catch (e: IOException) {
                throw TunnelFailedException("could not start the tunnel command: ${e.message}", e)
            }
            val entry = Live(process, log).also { it.users = 1 }
            try {
                awaitPort(entry, host, port)
            } catch (t: Throwable) {
                destroy(process)
                throw t
            }
            live[connection.id] = entry
            AuditLog.event(
                "tunnel.open", "connection" to connection.id, "port" to port,
                "pid" to runCatching { process.pid() }.getOrNull(), "log" to (log?.toString() ?: "-"),
            )
            return Lease(this, connection.id, managed = true, entry = entry)
        }
    }

    /** The number of launches currently holding [connectionId]'s tunnel (0 when the launcher runs none). */
    fun holders(connectionId: String): Int = live[connectionId]?.users ?: 0

    /** Tear down every tunnel the launcher opened — on exit. */
    fun closeAll() {
        for (id in live.keys.toList()) {
            synchronized(lockFor(id)) {
                live.remove(id)?.let { destroy(it.process); AuditLog.event("tunnel.close", "connection" to id, "reason" to "shutdown") }
            }
        }
    }

    private fun release(id: String, entry: Live) {
        synchronized(lockFor(id)) {
            // A lease on a tunnel that died and was replaced must not count against the replacement.
            if (live[id] !== entry) return
            entry.users--
            if (entry.users <= 0) {
                live.remove(id)
                destroy(entry.process)
                AuditLog.event("tunnel.close", "connection" to id, "reason" to "last Administrator exited")
            }
        }
    }

    /**
     * Poll until [host]:[port] accepts a connection. The port is checked before the command's liveness
     * so a command that detaches the forward and exits (a batch file using `start`) still counts as
     * success; a command that exits without the port, or a timeout, fails with the log's tail.
     */
    private fun awaitPort(entry: Live, host: String, port: Int) {
        val deadline = clock() + timeoutMs
        while (true) {
            if (Thread.interrupted()) throw InterruptedException("launch cancelled while the tunnel was opening")
            if (probe(host, port)) return
            if (!entry.process.isAlive) {
                throw TunnelFailedException(
                    "the tunnel command exited (code ${entry.process.exitValue()}) before $host:$port was listening${logHint(entry.log)}",
                )
            }
            if (clock() >= deadline) {
                throw TunnelFailedException("the tunnel command did not open $host:$port within ${timeoutMs / 1000} s${logHint(entry.log)}")
            }
            sleeper(POLL_MS)
        }
    }

    /** The command's last few output lines, for the failure message; empty when there is no log. */
    private fun logHint(log: Path?): String {
        if (log == null) return ""
        // cmd.exe and the tools it runs write in the console code page, not UTF-8.
        val tail = runCatching {
            Files.readAllLines(log, Charset.defaultCharset()).filter { it.isNotBlank() && !it.startsWith("#") }.takeLast(5)
        }.recoverCatching {
            Files.readAllLines(log, StandardCharsets.ISO_8859_1).filter { it.isNotBlank() && !it.startsWith("#") }.takeLast(5)
        }.getOrDefault(emptyList())
        val lines = if (tail.isEmpty()) "" else " — " + tail.joinToString(" | ").take(400)
        return "$lines (log: $log)"
    }

    private fun destroy(process: Process) {
        // The command is usually a shell running a batch file that runs the real tunnel binary: take
        // the whole tree down, politely first, forcibly if it lingers.
        runCatching { process.toHandle().descendants().forEach { d -> runCatching { d.destroy() } } }
        runCatching { process.destroy() }
        val exited = try {
            process.waitFor(3, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt() // a cancelled launch stays cancelled; still finish the teardown
            false
        } catch (e: RuntimeException) {
            false
        }
        if (!exited) {
            runCatching { process.toHandle().descendants().forEach { d -> runCatching { d.destroyForcibly() } } }
            runCatching { process.destroyForcibly() }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val POLL_MS = 250L
        private const val CONNECT_PROBE_MS = 400

        /** The process-wide manager: launches share tunnels per connection, and exit tears them all down. */
        val shared: TunnelManager by lazy {
            TunnelManager().also { m -> Runtime.getRuntime().addShutdownHook(Thread(m::closeAll, "tunnel-shutdown")) }
        }

        /**
         * The command as the platform shell runs it: `cmd.exe /c <command>` on Windows (so a `.bat` /
         * `.cmd` path or a plain command line both work), `/bin/sh -c <command>` elsewhere.
         */
        internal fun commandLine(command: String, windows: Boolean = isWindows()): List<String> =
            if (windows) listOf("cmd.exe", "/c", command) else listOf("/bin/sh", "-c", command)

        /**
         * A command that is just an absolute path to an existing file runs in that file's directory (a
         * batch file's relative paths work). A relative path keeps the launcher's directory — moving the
         * working directory would make the command itself resolve somewhere else.
         */
        internal fun workingDirFor(command: String): Path? {
            val bare = command.trim().trim('"')
            if (bare.isEmpty()) return null
            val path = runCatching { Path.of(bare) }.getOrNull() ?: return null
            if (!path.isAbsolute) return null
            return if (runCatching { Files.isRegularFile(path) }.getOrDefault(false)) path.parent else null
        }

        /** True when something accepts a TCP connection on [host]:[port] — every address the host resolves to is tried. */
        fun portAccepting(host: String, port: Int): Boolean {
            val addresses = runCatching { LocalhostResolver.resolveOrdered(host) }.getOrNull() ?: return false
            for (address in addresses) {
                try {
                    Socket().use { s -> s.connect(InetSocketAddress(address, port), CONNECT_PROBE_MS) }
                    return true
                } catch (e: IOException) {
                    // refused / timed out on this address — try the next
                }
            }
            return false
        }

        private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    }
}
