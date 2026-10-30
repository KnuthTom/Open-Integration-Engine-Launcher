/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The Administrator as a **process tree**, not a process.
 *
 * `com.mirth.connect.client.ui.Mirth` checks the JVM it was started in against the heap size saved in
 * the user's Administrator preferences and, when they differ, relaunches itself in a new JVM with that
 * heap and exits — within a second of starting. To anyone holding the original [Process], the
 * Administrator "exited" while its window is right there. The launcher ties real work to that exit:
 * the row's Running state, close-after-launch, and — the one that hurts — the port-forward, which was
 * torn down under the relaunched client's feet.
 *
 * [track] wraps the spawned process so `isAlive` / `waitFor` / `onExit` answer for the whole tree:
 * while the process lives its Java children are noted (polled quickly at first, since the relaunch
 * happens early), and when it exits, any of them still alive — plus any Java process whose command
 * line carries this launch's [fingerprint] and that appeared since the spawn, in case the hand-over was
 * quicker than a poll — is adopted and awaited the same way, recursively. A process that cannot offer a
 * [ProcessHandle] (a test fake) is tracked as itself.
 */
object ProcessTree {

    /** The [process] as a tree-aware [Process]; see the class comment. [fingerprint]: strings the Administrator's command line must carry. */
    fun track(process: Process, fingerprint: List<String> = emptyList(), pollMs: Long = 100): Process {
        val handle = runCatching { process.toHandle() }.getOrNull() ?: return process
        return Tracked(process, treeExit(handle, fingerprint, pollMs))
    }

    /** Completes once [root] and every Java descendant it handed over to have exited. */
    internal fun treeExit(root: ProcessHandle, fingerprint: List<String>, pollMs: Long): CompletableFuture<Unit> {
        val done = CompletableFuture<Unit>()
        thread(name = "launch-tree-watch", isDaemon = true) {
            try {
                awaitTree(root, fingerprint, pollMs, Instant.now())
            } finally {
                done.complete(Unit)
            }
        }
        return done
    }

    private fun awaitTree(handle: ProcessHandle, fingerprint: List<String>, pollMs: Long, since: Instant) {
        val seen = LinkedHashMap<Long, ProcessHandle>()
        val started = System.nanoTime()
        while (handle.isAlive) {
            runCatching { handle.children().forEach { c -> if (isJava(c)) seen.putIfAbsent(c.pid(), c) } }
            // The relaunch, if any, happens in the first seconds; afterwards a slow poll is plenty.
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            try {
                Thread.sleep(if (elapsedMs < 30_000) pollMs else 1_000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        // Handed over? Children we saw, plus any Java process carrying this launch's fingerprint that
        // appeared since we started (a hand-over quicker than a poll).
        val heirs = LinkedHashMap<Long, ProcessHandle>()
        seen.values.filter { it.isAlive }.forEach { heirs[it.pid()] = it }
        if (fingerprint.isNotEmpty()) {
            runCatching {
                ProcessHandle.allProcesses()
                    .filter { it.isAlive && isJava(it) && startedAfter(it, since) && matches(it, fingerprint) }
                    .forEach { heirs.putIfAbsent(it.pid(), it) }
            }
        }
        for (heir in heirs.values) {
            if (heir.isAlive) awaitTree(heir, fingerprint, pollMs, since)
        }
    }

    private fun isJava(h: ProcessHandle): Boolean {
        val command = runCatching { h.info().command().orElse(null) }.getOrNull() ?: return true // unknown: keep it
        val name = command.replace('\\', '/').substringAfterLast('/').lowercase()
        return name == "java" || name == "javaw" || name == "java.exe" || name == "javaw.exe"
    }

    private fun startedAfter(h: ProcessHandle, since: Instant): Boolean =
        runCatching { h.info().startInstant().orElse(null) }.getOrNull()?.let { it >= since.minus(Duration.ofSeconds(2)) } ?: true

    /**
     * True when every [fingerprint] string occurs in what is known of the process's command line: the
     * full line where the platform gives it, else the command plus arguments, else the command alone
     * (Windows often reports only that). Nothing known: no match.
     */
    internal fun matches(h: ProcessHandle, fingerprint: List<String>): Boolean {
        val info = runCatching { h.info() }.getOrNull() ?: return false
        val line = runCatching { info.commandLine().orElse(null) }.getOrNull()
            ?: listOfNotNull(
                runCatching { info.command().orElse(null) }.getOrNull(),
                runCatching { info.arguments().orElse(null)?.joinToString(" ") }.getOrNull(),
            ).joinToString(" ").ifBlank { null }
            ?: return false
        return fingerprint.all { line.contains(it) }
    }

    /** A [Process] whose lifetime is the tree's. Streams and pid are the original's. */
    private class Tracked(private val original: Process, private val tree: CompletableFuture<Unit>) : Process() {
        override fun getOutputStream(): OutputStream = original.outputStream
        override fun getInputStream(): InputStream = original.inputStream
        override fun getErrorStream(): InputStream = original.errorStream
        override fun pid(): Long = original.pid()
        override fun toHandle(): ProcessHandle = original.toHandle()
        override fun info(): ProcessHandle.Info = original.info()
        override fun isAlive(): Boolean = !tree.isDone
        override fun waitFor(): Int {
            tree.get()
            return exitCode()
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            return try {
                tree.get(timeout, unit)
                true
            } catch (e: java.util.concurrent.TimeoutException) {
                false
            }
        }
        override fun exitValue(): Int {
            if (!tree.isDone) throw IllegalThreadStateException("process tree still running")
            return exitCode()
        }
        override fun onExit(): CompletableFuture<Process> = tree.thenApply<Process> { this }
        override fun destroy() {
            // Take the original and whatever it still parents down; an heir that has already been handed
            // over is its own process and is left alone (the launcher never kills a running Administrator).
            runCatching { original.toHandle().descendants().forEach { d -> runCatching { d.destroy() } } }
            original.destroy()
        }
        override fun destroyForcibly(): Process {
            runCatching { original.toHandle().descendants().forEach { d -> runCatching { d.destroyForcibly() } } }
            original.destroyForcibly()
            return this
        }
        private fun exitCode(): Int = runCatching { original.exitValue() }.getOrDefault(0)
    }
}
