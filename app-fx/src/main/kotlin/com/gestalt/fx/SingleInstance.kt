/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx

import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.audit.AuditLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * One launcher per user profile.
 *
 * A launcher that closed while an Administrator still depended on a port-forward it opened keeps running
 * hidden (see `Shell.requestClose`). Starting the launcher again must bring **that** window back rather
 * than open a second one: two launchers would race over `connections.json` and the preferences, and the
 * tunnel's state lives in the first. So the first instance takes a lock on `instance.lock` in the data
 * dir and listens on a loopback port; a later one finds the lock held, sends `SHOW <token>` to the port
 * written in the file, and exits once the first answers `OK`. Anything doubtful (the file unreadable, the
 * port not answering, a stale token) means the newcomer starts normally — a second window is a nuisance,
 * a launcher that will not open is a failure.
 *
 * The lock is a region far past the end of the file, so the file's contents stay readable under Windows'
 * mandatory locking. Locks die with their process, so there is no stale lock to clean up.
 */
object SingleInstance {

    private const val LOCK_FILE = "instance.lock"
    private const val LOCK_POSITION = 1L shl 40
    private const val CONNECT_MS = 1_000
    private const val REPLY_MS = 3_000

    /** What a SHOW request runs on the first instance; set by the application once its window exists. */
    @Volatile
    var onShow: (() -> Unit)? = null

    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /**
     * Claim the profile for this process (true: carry on and start the UI), or hand over to the launcher
     * already running (false: it has been told to show its window; this process should exit).
     */
    fun claimOrHandOver(dir: Path = AppPaths.dataDir): Boolean {
        val file = dir.resolve(LOCK_FILE)
        val ch = runCatching {
            Files.createDirectories(dir)
            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
        }.getOrNull() ?: return true // no lock file possible: run, as before this existed
        val held = runCatching { ch.tryLock(LOCK_POSITION, 1L, false) }.getOrNull() // null or exception: another process (or this JVM) holds it
        if (held == null) {
            runCatching { ch.close() }
            return !handOver(file)
        }
        channel = ch
        lock = held
        // Empty the file at once: a newcomer in the instant before the server is up sees nothing to connect
        // to (and retries), not the previous run's port.
        runCatching { ch.truncate(0); ch.force(false) }
        runCatching { listen(ch) }.onFailure {
            // No loopback server: run anyway, just without the hand-over (the next launcher starts its own window).
            runCatching { held.release(); ch.close() }
            channel = null; lock = null
            AuditLog.event("launcher.singleInstance", "status" to "UNAVAILABLE", "error" to (it.message ?: it.javaClass.simpleName))
        }
        return true
    }

    /** The first instance: a loopback server whose port and secret the lock file carries. */
    private fun listen(ch: FileChannel) {
        // A loopback TCP port rather than a Unix-domain socket: on Windows (the main target) nothing prompts
        // for it; macOS's application firewall may ask once per unsigned build.
        val sock = ServerSocket()
        try {
            sock.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 2)
            val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val body = "${sock.localPort}\n$token\n".toByteArray(Charsets.US_ASCII)
            ch.truncate(0)
            ch.write(ByteBuffer.wrap(body), 0)
            ch.force(false)
            server = sock
            thread(name = "single-instance", isDaemon = true) {
                while (!sock.isClosed) {
                    val client = runCatching { sock.accept() }.getOrNull()
                    if (client == null) { // closed, or a transient accept failure: never spin
                        if (!sock.isClosed) runCatching { Thread.sleep(100) }
                        continue
                    }
                    thread(name = "single-instance-request", isDaemon = true) { serve(client, token) }
                }
            }
        } catch (t: Throwable) {
            runCatching { sock.close() }
            throw t
        }
    }

    private fun serve(client: Socket, token: String) {
        client.use { s ->
            s.soTimeout = REPLY_MS
            val line = runCatching { BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII)).readLine() }.getOrNull() ?: return
            val ok = line == "SHOW $token"
            if (ok) {
                AuditLog.event("launcher.showRequested", "from" to "another launcher")
                onShow?.let { runCatching { it() } }
            }
            runCatching {
                val out = OutputStreamWriter(s.getOutputStream(), Charsets.US_ASCII)
                out.write(if (ok) "OK\n" else "NO\n")
                out.flush()
            }
        }
    }

    /** The newcomer: ask the holder to show itself. True when it answered OK. */
    private fun handOver(file: Path): Boolean {
        // The holder writes the file right after locking it; a newcomer in that same instant retries briefly.
        var port = 0
        var token = ""
        for (attempt in 0 until 10) {
            val lines = runCatching { Files.readAllLines(file, Charsets.US_ASCII) }.getOrNull().orEmpty()
            port = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
            token = lines.getOrNull(1)?.trim().orEmpty()
            if (port in 1..65535 && token.length == 32) break
            Thread.sleep(100)
        }
        if (port !in 1..65535 || token.length != 32) return false
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_MS)
                s.soTimeout = REPLY_MS
                val out = OutputStreamWriter(s.getOutputStream(), Charsets.US_ASCII)
                out.write("SHOW $token\n")
                out.flush()
                val reply = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII)).readLine()
                reply == "OK"
            }
        }.getOrDefault(false).also { if (it) AuditLog.event("launcher.handover", "to" to "the running launcher") }
    }

    /**
     * Release the claim — on the way out, so a launcher started while this one exits is not told to wait
     * for a window that will never come back (it finds no server and no lock, and starts normally). The OS
     * would release the lock at process death anyway; tests rely on this too.
     */
    fun release() {
        runCatching { server?.close() }
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        server = null; lock = null; channel = null
        onShow = null
    }
}
