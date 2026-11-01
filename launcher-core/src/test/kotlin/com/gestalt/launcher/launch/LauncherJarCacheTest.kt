/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import com.gestalt.launcher.digest.DigestVerifier
import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.net.ArtifactFetcher
import com.gestalt.launcher.testutil.TestArtifacts
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * A jar already in the cache whose bytes match the digest the server publishes this launch is reused
 * rather than re-downloaded — the key speed win for launching over a slow port-forward / SSM tunnel,
 * where re-pulling all ~88 jars every launch took over a minute. The jar is still gate-verified, so a
 * stale/changed jar re-downloads.
 */
class LauncherJarCacheTest {

    private val base = "https://host:8443/"

    private class CountingFetcher(private val responses: Map<String, ByteArray>) : ArtifactFetcher {
        val fetched: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        override fun fetchBytes(uri: URI): ByteArray {
            fetched += uri.toString()
            return responses[uri.toString()] ?: throw IOException("refused/absent: $uri")
        }
    }

    private fun jnlp(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jnlp spec="1.0+" version="1.0.0">
          <resources><jar href="core.jar" main="true"/></resources>
          <application-desc main-class="com.mirth.connect.client.ui.Mirth"/>
        </jnlp>
    """.trimIndent()

    private fun signedCoreJar(dir: Path, ca: TestArtifacts.Ca): ByteArray {
        val leaf = TestArtifacts.codeSigningLeaf("CN=OIE,O=BrightCodeCompany OÜ,C=EE", ca)
        val jar = dir.resolve("build-core.jar")
        TestArtifacts.writeSignedJar(jar, mapOf("mirth/Main.class" to "main".toByteArray()), leaf.keyPair.private, leaf.chain)
        return Files.readAllBytes(jar)
    }

    private fun responses(jarBytes: ByteArray): Map<String, ByteArray> = mapOf(
        "${base}webstart" to jnlp().toByteArray(),
        "${base}digest/1.0.0" to "${DigestVerifier.sha256Hex(jarBytes)}  core.jar".toByteArray(),
        "${base}core.jar" to jarBytes,
    )

    private fun launcher(cacheDir: Path, ca: TestArtifacts.Ca, fetcher: ArtifactFetcher): LauncherCore =
        LauncherCore(
            trustStore = TrustStore.of(ca.cert),
            signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS,
            gateMode = TrustMode.PIN_KNOWN_SIGNERS,
            cacheDir = cacheDir,
            fetcherFactory = { fetcher },
            serverLeafProbe = { error("probe not used (unpinned)") },
        )

    @Test
    @DisplayName("a cached jar matching the published digest is reused, not re-downloaded")
    fun cachedJarReused(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val cacheDir = dir.resolve("cache")
        val conn = Connection(id = "c", name = "C", serverUrl = base) // unpinned, Standard

        // First launch: downloads + caches the jar.
        val first = CountingFetcher(responses(jarBytes))
        launcher(cacheDir, ca, first).prepareLaunch(conn)
        assertTrue(first.fetched.any { it == "${base}core.jar" }) { "the jar must be downloaded the first time" }

        // Second launch with the SAME cache: a fetcher that has NO jar response (throws if asked). The jar
        // must come from cache, so prepareLaunch still succeeds and the jar URL is never fetched.
        val second = CountingFetcher(responses(jarBytes) - "${base}core.jar")
        val plan = launcher(cacheDir, ca, second).prepareLaunch(conn)

        assertEquals(1, plan.admitted.size) { "the cached jar must still be admitted" }
        assertTrue(second.fetched.none { it == "${base}core.jar" }) { "the cached jar must NOT be re-downloaded" }
    }

    @Test
    @DisplayName("a stale cached jar (bytes no longer match the digest) is re-downloaded")
    fun staleCacheReDownloads(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val cacheDir = dir.resolve("cache")
        val conn = Connection(id = "c", name = "C", serverUrl = base)

        // Pre-seed the cache slot with WRONG bytes (won't match the published digest).
        val cachedPath = cacheDir.resolve("c").resolve("1.0.0").resolve("core.jar")
        Files.createDirectories(cachedPath.parent)
        Files.write(cachedPath, "stale".toByteArray())

        val fetcher = CountingFetcher(responses(jarBytes))
        val plan = launcher(cacheDir, ca, fetcher).prepareLaunch(conn)

        assertEquals(1, plan.admitted.size)
        assertTrue(fetcher.fetched.any { it == "${base}core.jar" }) { "a stale cache must trigger a fresh download" }
    }

    @Test
    @DisplayName("a cached jar swapped for a DIFFERENT but validly-signed jar is re-downloaded (digest mismatch)")
    fun differentValidlySignedCacheReDownloads(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val realJar = signedCoreJar(dir, ca) // what the server serves this launch
        val cacheDir = dir.resolve("cache")
        val conn = Connection(id = "c", name = "C", serverUrl = base)

        // Seed the cache slot with a SECOND, legitimately-signed jar whose bytes differ from realJar.
        val otherLeaf = TestArtifacts.codeSigningLeaf("CN=OIE,O=BrightCodeCompany OÜ,C=EE", ca)
        val otherPath = dir.resolve("other.jar")
        TestArtifacts.writeSignedJar(otherPath, mapOf("mirth/Other.class" to "other".toByteArray()), otherLeaf.keyPair.private, otherLeaf.chain)
        val cachedPath = cacheDir.resolve("c").resolve("1.0.0").resolve("core.jar")
        Files.createDirectories(cachedPath.parent)
        Files.write(cachedPath, Files.readAllBytes(otherPath))

        val fetcher = CountingFetcher(responses(realJar))
        val plan = launcher(cacheDir, ca, fetcher).prepareLaunch(conn)

        assertEquals(1, plan.admitted.size)
        assertTrue(fetcher.fetched.any { it == "${base}core.jar" }) {
            "a cached jar whose SHA-256 differs from the published digest must be re-downloaded even if it is itself validly signed"
        }
    }

    @Test
    @DisplayName("jars are shared across connections by digest: a second connection on the same version gets them from the pool, not its tunnel — still digest-checked against ITS server")
    fun sharedAcrossConnections(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val cacheDir = dir.resolve("cache")

        // Connection A downloads and admits the jar; the admitted bytes enter the pool.
        val a = CountingFetcher(responses(jarBytes))
        launcher(cacheDir, ca, a).prepareLaunch(Connection(id = "a", name = "A", serverUrl = base))
        assertTrue(a.fetched.any { it == "${base}core.jar" })
        val pool = JarPool(cacheDir.resolve(JarPool.DIR_NAME))
        assertEquals(1, pool.size()) { "the admitted jar is pooled" }
        assertTrue(Files.isRegularFile(pool.find(DigestVerifier.sha256Hex(jarBytes))!!))

        // Connection B, same server content, a fetcher with NO jar: the jar comes from the pool.
        val b = CountingFetcher(responses(jarBytes) - "${base}core.jar")
        val plan = launcher(cacheDir, ca, b).prepareLaunch(Connection(id = "b", name = "B", serverUrl = base))
        assertEquals(1, plan.admitted.size)
        assertTrue(b.fetched.none { it == "${base}core.jar" }) { "pooled bytes, no download" }
        assertTrue(Files.isRegularFile(cacheDir.resolve("b").resolve("1.0.0").resolve("core.jar"))) { "B has its own slot now" }

        // A server publishing a DIFFERENT digest for the same name is not served pooled bytes: it downloads.
        val other = TestArtifacts.codeSigningLeaf("CN=OIE,O=BrightCodeCompany OÜ,C=EE", ca)
        val otherPath = dir.resolve("other.jar")
        TestArtifacts.writeSignedJar(otherPath, mapOf("mirth/Other.class" to "other".toByteArray()), other.keyPair.private, other.chain)
        val otherBytes = Files.readAllBytes(otherPath)
        val c = CountingFetcher(responses(otherBytes))
        launcher(cacheDir, ca, c).prepareLaunch(Connection(id = "c", name = "C", serverUrl = base))
        assertTrue(c.fetched.any { it == "${base}core.jar" }) { "other bytes: the pool has nothing under that digest" }
        assertEquals(2, pool.size())

        // A corrupt pool entry is dropped and the jar downloaded.
        val entry = pool.find(DigestVerifier.sha256Hex(jarBytes))!!
        Files.write(entry, "corrupt".toByteArray())
        val d = CountingFetcher(responses(jarBytes))
        launcher(cacheDir, ca, d).prepareLaunch(Connection(id = "d", name = "D", serverUrl = base))
        assertTrue(d.fetched.any { it == "${base}core.jar" }) { "the corrupt entry must not be used" }
        assertEquals(jarBytes.toList(), Files.readAllBytes(pool.find(DigestVerifier.sha256Hex(jarBytes))!!).toList()) { "re-pooled from the fresh download" }
    }

    @Test
    @DisplayName("the pool: restore verifies the copy, offer is idempotent, prune drops what has not been used")
    fun poolMechanics(@TempDir dir: Path) {
        val pool = JarPool(dir.resolve("pool"))
        val src = dir.resolve("x.jar")
        Files.write(src, "jar-bytes".toByteArray())
        val sha = DigestVerifier.sha256Hex(src)
        assertEquals(null, pool.find(sha))
        assertEquals(false, pool.restore(sha, dir.resolve("out").resolve("x.jar")))
        pool.offer(sha, src)
        pool.offer(sha, src)
        assertEquals(1, pool.size())
        assertTrue(pool.restore(sha, dir.resolve("out").resolve("x.jar")))
        assertEquals("jar-bytes", Files.readString(dir.resolve("out").resolve("x.jar")))
        assertEquals(null, pool.find("not-a-digest"))
        assertEquals(0, pool.prune(maxAgeDays = 1))
        assertEquals(1, pool.prune(maxAgeDays = 0, now = java.time.Instant.now().plusSeconds(60))) { "unused for longer than the age: dropped" }
        assertEquals(0, pool.size())
    }
}
