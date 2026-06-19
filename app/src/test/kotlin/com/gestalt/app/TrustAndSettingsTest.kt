/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app

import com.gestalt.app.trust.CertDetails
import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.credential.InMemoryCredentialStore
import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionStore
import com.gestalt.launcher.tls.CertFingerprint
import com.gestalt.trust.TrustMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate

/** Headless integration tests for the Phase-3c trust + settings layer (no Compose window). */
class TrustAndSettingsTest {

    private fun newState(dir: Path): AppState = AppState(
        store = ConnectionStore(dir.resolve("connections.json")),
        prefs = AppPreferences(dir.resolve("prefs.properties")),
        credentials = InMemoryCredentialStore(),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun sampleCert(): X509Certificate =
        javaClass.getResourceAsStream("/anchors/usertrust-rsa-root.pem")
            .let { requireNotNull(it) }
            .use { CertDetails.readCert(it) }

    @Test
    @DisplayName("toggling the trust posture flows into the next launch's verify config and persists")
    fun postureToggle(@TempDir dir: Path) {
        val state = newState(dir)
        assertEquals(TrustMode.PIN_KNOWN_SIGNERS, state.currentLauncherConfig().mode)

        state.updateTrustPosture(TrustMode.CA_ROOT)
        assertEquals(TrustMode.CA_ROOT, state.currentLauncherConfig().mode) { "posture must affect the next verify" }

        // Persisted per-user.
        assertEquals(TrustMode.CA_ROOT, AppPreferences(dir.resolve("prefs.properties")).trustPosture)
    }

    @Test
    @DisplayName("custom signer DN is added to the gate's pin allow-list; junk is rejected")
    fun customSignerPin(@TempDir dir: Path) {
        val state = newState(dir)
        val official = GestaltTrust.OFFICIAL_SIGNER_PINS.size
        assertEquals(official, state.currentLauncherConfig().signerPins.size)

        state.addCustomSignerDn("CN=Acme Code Signing, O=Acme Inc")
        val pins = state.currentLauncherConfig().signerPins
        assertEquals(official + 1, pins.size)
        assertTrue(pins.any { it.requiredRdns["O"].equals("Acme Inc", ignoreCase = true) })

        state.addCustomSignerDn("not a dn") // no '=' -> rejected, list unchanged
        assertEquals(official + 1, state.currentLauncherConfig().signerPins.size)
    }

    @Test
    @DisplayName("pinning a server cert sets pinnedCertSha256, writes an audit entry, and unpin clears it")
    fun serverCertPinRoundTrip(@TempDir dir: Path) {
        val state = newState(dir)
        val cert = sampleCert()
        val fp = CertFingerprint.sha256Hex(cert)
        state.upsert(Connection(id = "c1", name = "OIE prod", serverUrl = "https://oie.lab:8443", sortOrder = 0))

        state.confirmTrustPin("c1", cert)
        assertEquals(fp, state.get("c1")!!.pinnedCertSha256)

        // Audit trail records the pin (servercert.pin + the fingerprint).
        val audit = AppPaths.logDir.resolve("audit.log")
        assertTrue(Files.isRegularFile(audit)) { "audit.log not written at $audit" }
        val text = Files.readString(audit)
        assertTrue(text.contains("servercert.pin")) { "no servercert.pin event in audit log" }
        assertTrue(text.contains(fp)) { "pinned fingerprint not in audit log" }

        state.unpinServerCert("c1")
        assertNull(state.get("c1")!!.pinnedCertSha256)
    }

    @Test
    @DisplayName("settings persist per-user and Build & Signing stores no secret")
    fun settingsPersistNoSecret(@TempDir dir: Path) {
        val file = dir.resolve("prefs.properties")
        AppPreferences(file).apply {
            defaultHeap = "1024m"
            logLevel = "DEBUG"
            updateChannelUrl = "https://updates.example.com/stable"
            signingKeystorePath = "C:\\signing\\release.p12"
            signingKeyAlias = "gestalt-signing"
            signingTsaUrl = "http://timestamp.digicert.com"
        }

        val reloaded = AppPreferences(file)
        assertEquals("1024m", reloaded.defaultHeap)
        assertEquals("DEBUG", reloaded.logLevel)
        assertEquals("https://updates.example.com/stable", reloaded.updateChannelUrl)
        assertEquals("C:\\signing\\release.p12", reloaded.signingKeystorePath)
        assertEquals("gestalt-signing", reloaded.signingKeyAlias)
        assertEquals("http://timestamp.digicert.com", reloaded.signingTsaUrl)

        // GUARDRAIL: no key/passphrase ever reaches the prefs file.
        val raw = Files.readString(file).lowercase()
        listOf("passphrase", "password", "privatekey", "keypass", "storepass", "secret").forEach {
            assertFalse(raw.contains(it)) { "prefs file leaked a secret-like key: $it" }
        }
    }

    @Test
    @DisplayName("clear cache removes cache contents and keeps the directory")
    fun cacheClear(@TempDir dir: Path) {
        val state = newState(dir)
        Files.createDirectories(AppPaths.cacheDir)
        val marker = AppPaths.cacheDir.resolve("clearcache-test-marker.txt")
        Files.writeString(marker, "x")
        assertTrue(Files.exists(marker))

        state.clearCacheNow()

        assertFalse(Files.exists(marker))
        assertTrue(Files.isDirectory(AppPaths.cacheDir))
    }
}
