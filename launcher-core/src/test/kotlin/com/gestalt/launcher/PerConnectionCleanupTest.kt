/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher

import com.gestalt.launcher.launch.LauncherCore
import com.gestalt.launcher.testutil.TestArtifacts
import com.gestalt.launcher.tls.ServerTrustStore
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** On connection delete the per-connection truststore and cached-jar subtree are removed, others untouched. */
class PerConnectionCleanupTest {

    @Test
    @DisplayName("ServerTrustStore.delete removes only that connection's .p12")
    fun truststoreDelete(@TempDir dir: Path) {
        val (_, leaf) = TestArtifacts.serverCert("mirth-connect", "mirth-connect")
        val a = ServerTrustStore.write("conn-1", leaf, dir)
        val b = ServerTrustStore.write("conn-2", leaf, dir)
        assertTrue(Files.exists(a) && Files.exists(b))

        ServerTrustStore.delete("conn-1", dir)

        assertFalse(Files.exists(a)) { "deleted connection's truststore must be gone" }
        assertTrue(Files.exists(b)) { "another connection's truststore must be untouched" }
        ServerTrustStore.delete("conn-1", dir) // idempotent / safe when already absent
    }

    @Test
    @DisplayName("purgeConnectionCache removes only that connection's cached-jar subtree")
    fun purgeCache(@TempDir dir: Path) {
        val cache = dir.resolve("cache")
        val keep = cache.resolve("conn-2").resolve("1.0.0")
        val drop = cache.resolve("conn-1").resolve("4.4.2")
        Files.createDirectories(keep); Files.write(keep.resolve("y.jar"), byteArrayOf(2))
        Files.createDirectories(drop); Files.write(drop.resolve("x.jar"), byteArrayOf(1))

        LauncherCore.purgeConnectionCache("conn-1", cache)

        assertFalse(Files.exists(cache.resolve("conn-1"))) { "deleted connection's cache subtree must be gone" }
        assertTrue(Files.exists(cache.resolve("conn-2"))) { "another connection's cache must be untouched" }
        LauncherCore.purgeConnectionCache("absent", cache) // safe when absent
    }

    @Test
    @DisplayName("pruneOldVersionCaches keeps the current version and drops stale versions for that connection only")
    fun pruneOldVersions(@TempDir dir: Path) {
        val cache = dir.resolve("cache")
        val old = cache.resolve("conn-1").resolve("4.4.1")
        val cur = cache.resolve("conn-1").resolve("4.4.2")
        val other = cache.resolve("conn-2").resolve("1.0.0")
        listOf(old, cur, other).forEach { Files.createDirectories(it); Files.write(it.resolve("a.jar"), byteArrayOf(1)) }

        LauncherCore.pruneOldVersionCaches("conn-1", "4.4.2", cache)

        assertFalse(Files.exists(old)) { "a stale version must be pruned" }
        assertTrue(Files.exists(cur)) { "the current version must be kept (cache reuse)" }
        assertTrue(Files.exists(other)) { "another connection's cache must be untouched" }
    }
}
