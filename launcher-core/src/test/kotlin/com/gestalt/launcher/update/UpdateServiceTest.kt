/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.update

import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.net.ArtifactFetcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fail-closed self-update acceptance: the happy path applies; a bad-signature manifest, a tampered
 * artifact (sha256 mismatch), and a downgrade are each REJECTED with nothing applied.
 */
class UpdateServiceTest {

    private val channel = "https://updates.example/gestalt/stable"
    private val manifestUrl = "$channel/latest.json"

    /** A canned [ArtifactFetcher] — no network. */
    private class Stub(val texts: Map<String, String> = emptyMap(), val bytes: Map<String, ByteArray> = emptyMap()) : ArtifactFetcher {
        override fun fetchBytes(uri: URI): ByteArray =
            bytes[uri.toString()] ?: texts[uri.toString()]?.toByteArray() ?: throw IOException("no stub for $uri")
    }

    private fun signedManifest(version: String, priv: ByteArray, artifactSha: String = "ab".repeat(32)): String {
        val m = UpdateManifest(
            version = version,
            notes = "what's new in $version",
            perOS = mapOf("windows-x64" to ArtifactRef("$channel/GestaltCloudConnect-$version.msi", artifactSha)),
        )
        return UpdateManifest.render(m.copy(signature = UpdateCrypto.sign(priv, m)))
    }

    private fun service(pub: ByteArray, fetcher: ArtifactFetcher, current: String = "1.0.0", portable: Boolean = false) =
        UpdateService(
            currentVersion = current,
            osArch = "windows-x64",
            portable = portable,
            pinnedKeys = listOf(pub),
            fetcherFactory = { fetcher },
        )

    @Test
    @DisplayName("happy path: newer signed manifest -> Available with the platform artifact")
    fun happyPath() {
        val (priv, pub) = UpdateCrypto.generateKeyPair()
        val json = signedManifest("1.2.0", priv)
        val outcome = service(pub, Stub(texts = mapOf(manifestUrl to json))).check(channel)

        val available = assertInstanceOf(UpdateOutcome.Available::class.java, outcome)
        assertEquals("1.2.0", available.manifest.version)
        assertEquals("$channel/GestaltCloudConnect-1.2.0.msi", available.artifact.url)
    }

    @Test
    @DisplayName("fail-closed: a manifest signed by an untrusted key is rejected")
    fun rejectsBadSignature() {
        val (priv, _) = UpdateCrypto.generateKeyPair()
        val (_, pinnedPub) = UpdateCrypto.generateKeyPair() // app pins a DIFFERENT key
        val json = signedManifest("1.2.0", priv)
        val outcome = service(pinnedPub, Stub(texts = mapOf(manifestUrl to json))).check(channel)
        assertInstanceOf(UpdateOutcome.Failed::class.java, outcome)
    }

    @Test
    @DisplayName("fail-closed: a tampered manifest field invalidates the signature")
    fun rejectsTamperedManifest() {
        val (priv, pub) = UpdateCrypto.generateKeyPair()
        // Sign 1.2.0 then rewrite the version to 9.9.9, keeping the old signature.
        val signed = UpdateManifest.parse(signedManifest("1.2.0", priv))
        val tampered = UpdateManifest.render(signed.copy(version = "9.9.9"))
        val outcome = service(pub, Stub(texts = mapOf(manifestUrl to tampered))).check(channel)
        assertInstanceOf(UpdateOutcome.Failed::class.java, outcome)
    }

    @Test
    @DisplayName("fail-closed: a downgrade / equal version is never offered")
    fun rejectsDowngrade() {
        val (priv, pub) = UpdateCrypto.generateKeyPair()
        val older = service(pub, Stub(texts = mapOf(manifestUrl to signedManifest("0.9.0", priv))), current = "1.0.0")
        assertInstanceOf(UpdateOutcome.UpToDate::class.java, older.check(channel))
        val equal = service(pub, Stub(texts = mapOf(manifestUrl to signedManifest("1.0.0", priv))), current = "1.0.0")
        assertInstanceOf(UpdateOutcome.UpToDate::class.java, equal.check(channel))
    }

    @Test
    @DisplayName("download verifies the signed SHA-256 before keeping the file; mismatch throws")
    fun downloadVerifiesSha(@TempDir tmp: Path) {
        val goodBytes = "the new installer".toByteArray()
        val goodSha = UpdateCrypto.sha256(goodBytes)
        val url = "$channel/GestaltCloudConnect-1.2.0.msi"

        val (priv, pub) = UpdateCrypto.generateKeyPair()
        val ok = service(pub, Stub(bytes = mapOf(url to goodBytes)))
        val dest = ok.download(ArtifactRef(url, goodSha), tmp)
        assertTrue(Files.isRegularFile(dest))
        assertEquals(goodSha, UpdateCrypto.sha256(Files.readAllBytes(dest)))

        // Server returns different bytes than the signed sha -> integrity failure, nothing kept.
        val tamperedSvc = service(pub, Stub(bytes = mapOf(url to "tampered".toByteArray())))
        assertThrows(UpdateIntegrityException::class.java) { tamperedSvc.download(ArtifactRef(url, goodSha), tmp) }
    }

    @Test
    @DisplayName("a valid manifest with no build for this platform is Unavailable (benign), not Failed")
    fun unavailableForPlatform() {
        val (priv, pub) = UpdateCrypto.generateKeyPair()
        val m = UpdateManifest("1.2.0", "notes", mapOf("linux-x64" to ArtifactRef("$channel/x.deb", "ab".repeat(32))))
        val json = UpdateManifest.render(m.copy(signature = UpdateCrypto.sign(priv, m)))
        val outcome = service(pub, Stub(texts = mapOf(manifestUrl to json))).check(channel)
        assertInstanceOf(UpdateOutcome.Unavailable::class.java, outcome)
    }

    @Test
    @DisplayName("update checks are audited (an entry is written to the audit log)")
    fun checksAreAudited() {
        val (priv, pub) = UpdateCrypto.generateKeyPair()
        service(pub, Stub(texts = mapOf(manifestUrl to signedManifest("1.2.0", priv)))).check(channel)
        val audit = AppPaths.logDir.resolve("audit.log")
        assertTrue(Files.exists(audit), "audit.log should exist")
        val text = Files.readString(audit)
        assertTrue(text.contains("event=update.check"), "audit log should record the update check")
    }
}
