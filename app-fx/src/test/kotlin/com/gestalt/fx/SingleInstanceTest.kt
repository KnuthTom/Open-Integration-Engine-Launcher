/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One launcher per profile: the second asks the first to show itself and does not start. The second
 * "process" here is the same JVM claiming the same directory again — the lock is already held (by this
 * JVM, which the lock API reports the same way as another process), so the hand-over path runs for real
 * over the loopback socket.
 */
class SingleInstanceTest {

    @AfterEach
    fun release() = SingleInstance.release()

    @Test
    @DisplayName("the first launcher claims the profile; a second one hands over to it (its window is shown) and does not start")
    fun handOver(@TempDir dir: Path) {
        val shown = CountDownLatch(1)
        assertTrue(SingleInstance.claimOrHandOver(dir)) { "nobody else: start" }
        SingleInstance.onShow = { shown.countDown() }
        val lines = Files.readAllLines(dir.resolve("instance.lock"))
        assertEquals(2, lines.size)
        assertTrue(lines[0].toInt() in 1..65535) { "the port" }
        assertEquals(32, lines[1].length) { "the secret" }

        assertFalse(SingleInstance.claimOrHandOver(dir)) { "the holder answered: this one must not start" }
        assertTrue(shown.await(5, TimeUnit.SECONDS)) { "the holder was asked to show its window" }

        SingleInstance.release()
        assertTrue(SingleInstance.claimOrHandOver(dir)) { "the claim is free again once released" }
    }

    @Test
    @DisplayName("a held lock whose file is unreadable, or a wrong secret, lets the newcomer start rather than leaving the user with nothing")
    fun doubtfulMeansStart(@TempDir dir: Path) {
        assertTrue(SingleInstance.claimOrHandOver(dir))
        val file = dir.resolve("instance.lock")
        val good = Files.readAllLines(file)

        Files.write(file, listOf(good[0], "0".repeat(32))) // the right port, a wrong secret: the holder says NO
        assertTrue(SingleInstance.claimOrHandOver(dir))

        Files.writeString(file, "garbage")
        assertTrue(SingleInstance.claimOrHandOver(dir))
    }
}
