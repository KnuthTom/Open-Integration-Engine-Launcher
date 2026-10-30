/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The Administrator hands itself over to a second JVM (its heap-preference relaunch); the launcher must
 * follow the hand-over, or everything tied to "the client exited" fires while the client is running.
 */
class ProcessTreeTest {

    @Test
    @DisplayName("a process that relaunches itself and exits is still 'alive' until its heir exits")
    fun followsTheRelaunch() {
        val stub = ProcessBuilder(javaExe(), "-cp", classpath(), RelaunchStub::class.java.name, "2000")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        val tracked = ProcessTree.track(stub, fingerprint = listOf(SleepStub::class.java.name), pollMs = 50)

        assertTrue(stub.waitFor(20, TimeUnit.SECONDS)) { "the stub exits as soon as it has spawned its heir" }
        assertFalse(stub.isAlive)
        assertTrue(tracked.isAlive) { "the heir (sleeping 2 s) is the client now" }
        assertEquals(stub.pid(), tracked.pid())

        assertTrue(tracked.waitFor(20, TimeUnit.SECONDS)) { "the tree ends when the heir exits" }
        assertFalse(tracked.isAlive)
        assertTrue(tracked.onExit().isDone)
    }

    @Test
    @DisplayName("a plain process (no relaunch) is reported exactly as before")
    fun plainProcess() {
        val p = ProcessBuilder(javaExe(), "-cp", classpath(), SleepStub::class.java.name, "200")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val tracked = ProcessTree.track(p, pollMs = 50)
        assertTrue(tracked.waitFor(20, TimeUnit.SECONDS))
        assertFalse(tracked.isAlive)
        assertEquals(0, tracked.exitValue())
    }

    @Test
    @DisplayName("a process that offers no handle (a test fake) is tracked as itself")
    fun fakeIsItself() {
        val fake = object : Process() {
            override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
            override fun getInputStream(): InputStream = InputStream.nullInputStream()
            override fun getErrorStream(): InputStream = InputStream.nullInputStream()
            override fun waitFor(): Int = 0
            override fun exitValue(): Int = 0
            override fun destroy() = Unit
        }
        assertSame(fake, ProcessTree.track(fake))
    }

    @Test
    @DisplayName("the fingerprint match reads the command line")
    fun fingerprintMatch() {
        val me = ProcessHandle.current()
        assertTrue(ProcessTree.matches(me, listOf("java")))
        assertFalse(ProcessTree.matches(me, listOf("java", "definitely-not-on-this-command-line-7f3a")))
    }

    private fun javaExe(): String {
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        return Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java").toString()
    }

    private fun classpath(): String = System.getProperty("java.class.path")
}

/** Spawns [SleepStub] with the same classpath and exits at once — the shape of the Administrator's relaunch. */
object RelaunchStub {
    @JvmStatic
    fun main(args: Array<String>) {
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val java = Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java").toString()
        ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), SleepStub::class.java.name, args[0])
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
    }
}

/** Sleeps for the given milliseconds, then exits 0. */
object SleepStub {
    @JvmStatic
    fun main(args: Array<String>) {
        Thread.sleep(args[0].toLong())
    }
}
