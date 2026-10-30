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
        internal val entry: Live?,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true) && managed && entry != null) manager.release(connectionId, entry)
        }
    }

    /** A tunnel the launcher runs. The process is replaced in place when a dead forward is reopened, so leases stay valid. */
    internal class Live(@Volatile var process: Process, @Volatile var log: Path?) {
        var users = 0
        /** Set before the launcher itself takes the process down, so its exit is not taken for a crash. */
        @Volatile var closing = false
        /** Unexpected exits reopened recently (bounded; see [REOPEN_LIMIT]). */
        val reopens = ArrayDeque<Long>()
    }

    private class Endpoint(val command: String, val host: String, val port: Int)

    /** The tunnel command and the local end of the forward, or null when the connection has no tunnel. */
    private fun endpointOf(connection: Connection): Endpoint? {
        val command = connection.tunnelCommand?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { connection.baseUri() }.getOrNull()
            ?: throw TunnelFailedException("the tunnel needs a usable address: ${connection.serverUrl}")
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw TunnelFailedException("the tunnel needs a host in the address: ${connection.serverUrl}")
        val port = uri.port.takeIf { it > 0 }
            ?: throw TunnelFailedException("the tunnel needs an explicit port in the address — it is the local end of the forward (for example https://localhost:31065)")
        return Endpoint(command, host, port)
    }

    /** Start the command for [endpoint] with a fresh log; the caller waits for the port. */
    private fun start(connection: Connection, endpoint: Endpoint): Pair<Process, Path?> {
        val log = ClientLog.openTunnel(logDir, connection, endpoint.command)
        val process = try {
            starter.start(commandLine(endpoint.command), workingDirFor(endpoint.command), log)
        } catch (e: IOException) {
            throw TunnelFailedException("could not start the tunnel command: ${e.message}", e)
        }
        return process to log
    }

    private val live = ConcurrentHashMap<String, Live>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val idleListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** True while the launcher runs a tunnel that a launched Administrator is using. */
    fun hasLiveTunnels(): Boolean = live.isNotEmpty()

    /**
     * Run [listener] once the launcher runs no tunnels — at once if that is already so. The window uses it
     * to stay alive, hidden, after the user closes it while an Administrator still depends on a forward
     * the launcher opened, and to exit when the last one is gone.
     */
    fun onIdle(listener: () -> Unit) {
        idleListeners += listener
        notifyIfIdle()
    }

    private fun notifyIfIdle() {
        if (live.isNotEmpty()) return
        val listeners = idleListeners.toList()
        idleListeners.removeAll(listeners)
        listeners.forEach { runCatching { it() } }
    }

    private fun lockFor(id: String): Any = locks.computeIfAbsent(id) { Any() }

    /**
     * Make sure [connection]'s port-forward is up. Null when the connection has no tunnel command.
     * [onWaiting] is called once if the command has to be run (the UI shows "Opening tunnel…").
     * Throws [TunnelFailedException] when the command fails or the port never comes up, and
     * [InterruptedException] when the launch is cancelled meanwhile (the command is then torn down).
     */
    @Throws(TunnelFailedException::class, InterruptedException::class)
    fun acquire(connection: Connection, onWaiting: () -> Unit = {}): Lease? {
        val endpoint = endpointOf(connection) ?: return null
        val host = endpoint.host
        val port = endpoint.port

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
            val (process, log) = start(connection, endpoint)
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
            watch(connection, endpoint, entry, process)
            return Lease(this, connection.id, managed = true, entry = entry)
        }
    }

    /**
     * True when the forward the launcher runs for [connectionId] is an SSM session in **basic** mode — the
     * plugin's own output says so (`Connection accepted for session <id>.`; multiplexing mode prints the
     * id in brackets). Basic mode services one connection at a time, so the launcher fetches one thing at
     * a time through it. False for a multiplexing forward, a hand-started tunnel, or no tunnel.
     */
    fun isBasicForward(connectionId: String): Boolean {
        val log = live[connectionId]?.log ?: return false
        return runCatching {
            Files.readAllLines(log, Charset.defaultCharset()).any { BASIC_MODE_LINE.matches(it.trim()) }
        }.getOrDefault(false)
    }

    /**
     * When the forward dies on its own while Administrators still depend on it (an SSM session timing
     * out, a dropped network), reopen it so the client can reconnect — a bounded number of times, off the
     * exit-notification thread. An exit the launcher caused ([Live.closing]) is left alone.
     */
    private fun watch(connection: Connection, endpoint: Endpoint, entry: Live, process: Process) {
        process.onExit().thenRun {
            if (entry.closing || live[connection.id] !== entry || entry.process !== process) return@thenRun
            Thread({ reopenAfterCrash(connection, endpoint, entry, process) }, "tunnel-reopen").apply { isDaemon = true }.start()
        }
    }

    private fun reopenAfterCrash(connection: Connection, endpoint: Endpoint, entry: Live, died: Process) {
        synchronized(lockFor(connection.id)) {
            if (entry.closing || live[connection.id] !== entry || entry.process !== died || entry.users <= 0) return
            val now = clock()
            while (entry.reopens.isNotEmpty() && now - entry.reopens.first() > REOPEN_WINDOW_MS) entry.reopens.removeFirst()
            if (entry.reopens.size >= REOPEN_LIMIT) {
                AuditLog.event("tunnel.exited", "connection" to connection.id, "code" to runCatching { died.exitValue() }.getOrNull(), "result" to "GIVEN_UP")
                live.remove(connection.id)
                notifyIfIdle()
                return
            }
            entry.reopens.addLast(now)
            AuditLog.event("tunnel.reopen", "connection" to connection.id, "port" to endpoint.port, "reason" to "exited while in use (code ${runCatching { died.exitValue() }.getOrNull()})")
            try {
                val (process, log) = start(connection, endpoint)
                entry.process = process
                entry.log = log
                awaitPort(entry, endpoint.host, endpoint.port)
                AuditLog.event("tunnel.open", "connection" to connection.id, "port" to endpoint.port, "reopened" to true, "pid" to runCatching { process.pid() }.getOrNull(), "log" to (log?.toString() ?: "-"))
                watch(connection, endpoint, entry, process)
            } catch (t: Throwable) {
                if (t is InterruptedException) Thread.currentThread().interrupt()
                AuditLog.event("tunnel.exited", "connection" to connection.id, "result" to "REOPEN_FAILED", "error" to (t.message ?: t.javaClass.simpleName))
                runCatching { destroy(entry.process) }
                live.remove(connection.id)
                notifyIfIdle()
            }
        }
    }

    /**
     * Just before the Administrator is pointed at the port: make sure it still answers. The launcher's own
     * connections have just closed, and an older SSM plugin (basic, non-multiplexing forwarding) has been
     * seen to drop the whole session with them. A dead forward the launcher opened is reopened in place —
     * same lease, same holders, a fresh process and log; one it didn't open can only be reported.
     * No-op without a tunnel ([lease] null).
     */
    @Throws(TunnelFailedException::class, InterruptedException::class)
    fun recheck(connection: Connection, lease: Lease?, onWaiting: () -> Unit = {}) {
        if (lease == null) return
        val endpoint = endpointOf(connection) ?: return
        val host = endpoint.host
        val port = endpoint.port
        synchronized(lockFor(connection.id)) {
            if (probe(host, port)) return
            val entry = lease.entry?.takeIf { live[connection.id] === it }
                ?: throw TunnelFailedException(
                    "the port-forward at $host:$port stopped listening after the download, and it was not opened by " +
                        "the launcher, so it can't be reopened here — open it again and relaunch",
                )
            AuditLog.event("tunnel.reopen", "connection" to connection.id, "port" to port, "reason" to "stopped listening after the download")
            entry.closing = true
            destroy(entry.process)
            onWaiting()
            val (process, log) = start(connection, endpoint)
            entry.process = process
            entry.log = log
            entry.closing = false
            try {
                awaitPort(entry, host, port)
            } catch (t: Throwable) {
                entry.closing = true
                destroy(process)
                live.remove(connection.id) // every lease on it is now stale and releases nothing
                throw t
            }
            AuditLog.event(
                "tunnel.open", "connection" to connection.id, "port" to port, "reopened" to true,
                "pid" to runCatching { process.pid() }.getOrNull(), "log" to (log?.toString() ?: "-"),
            )
            watch(connection, endpoint, entry, process)
        }
    }

    /** The number of launches currently holding [connectionId]'s tunnel (0 when the launcher runs none). */
    fun holders(connectionId: String): Int = live[connectionId]?.users ?: 0

    /** Tear down every tunnel the launcher opened — on exit. */
    fun closeAll() {
        for (id in live.keys.toList()) {
            synchronized(lockFor(id)) {
                live.remove(id)?.let { it.closing = true; destroy(it.process); AuditLog.event("tunnel.close", "connection" to id, "reason" to "shutdown") }
            }
        }
        notifyIfIdle()
    }

    private fun release(id: String, entry: Live) {
        synchronized(lockFor(id)) {
            // A lease on a tunnel that died and was replaced must not count against the replacement.
            if (live[id] !== entry) return
            entry.users--
            if (entry.users <= 0) {
                live.remove(id)
                entry.closing = true
                destroy(entry.process)
                AuditLog.event("tunnel.close", "connection" to id, "reason" to "last Administrator exited")
            }
        }
        notifyIfIdle()
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
        /** Unexpected exits reopened per [REOPEN_WINDOW_MS] before the launcher gives the forward up. */
        internal const val REOPEN_LIMIT = 3
        internal const val REOPEN_WINDOW_MS = 10 * 60_000L
        /** The SSM plugin's basic-mode accept line (mux mode prints `[id]`, with brackets). */
        private val BASIC_MODE_LINE = Regex("^Connection accepted for session [^\\[\\]\\s]+\\.$")

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
