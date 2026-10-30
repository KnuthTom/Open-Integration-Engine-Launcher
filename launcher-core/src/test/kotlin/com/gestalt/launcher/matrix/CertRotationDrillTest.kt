/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.matrix

import com.gestalt.launcher.digest.DigestManifest
import com.gestalt.launcher.gate.CodebaseVerifier
import com.gestalt.launcher.gate.GateOutcome
import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.gate.SignerPin
import com.gestalt.launcher.launch.LaunchAbortedException
import com.gestalt.launcher.launch.LauncherCore
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionStore
import com.gestalt.launcher.testutil.TestArtifacts
import com.gestalt.launcher.tls.CertFingerprint
import com.gestalt.launcher.tls.PinnedTrustManager
import com.gestalt.launcher.tls.ServerCertPinning
import com.gestalt.launcher.update.ArtifactRef
import com.gestalt.launcher.update.UpdateCrypto
import com.gestalt.launcher.update.UpdateManifest
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.cert.CertificateException
import java.util.Date

/**
 * Phase-5 **cert-rotation drill** — scripted and repeatable. Proves all three rotations the product must
 * survive without weakening trust:
 *
 *  1. **Code-signing (Layer-2) rotation** — a new signing cert with the *same DN* still satisfies the
 *     publisher pin (DN-attribute match, renewal-safe), an old cert still verifies within its validity,
 *     and an *expired* signing cert fails **closed** (no timestamp to rescue it).
 *  2. **Server-cert rotation** — a rotated server leaf fails the pinned-cert check closed (the re-pin
 *     prompt), and re-pinning to the new leaf recovers the launch.
 *  3. **Update-key rotation** — following `docs/UPDATES.md`: a new-key manifest is rejected until the new
 *     public key has shipped (added to the trusted list), then accepted; the old key still works; unsigned,
 *     mis-signed, and tampered manifests are all rejected.
 *
 * Results are written to `build/cert-rotation-results.md`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CertRotationDrillTest {

    private val results = mutableListOf<Triple<String, String, String>>()

    @AfterAll
    fun report() {
        val sb = StringBuilder()
        sb.appendLine("# Gestalt Cloud Connect 1.0 — Phase 5 Cert-Rotation Drill")
        sb.appendLine()
        sb.appendLine("Generated ${java.time.LocalDate.now()} by `CertRotationDrillTest`. Scripted, repeatable;")
        sb.appendLine("no trust is weakened in any rotation.")
        sb.appendLine()
        sb.appendLine("| Rotation | Check | Status | Detail |")
        sb.appendLine("|---|---|---|---|")
        for ((check, status, detail) in results) {
            val (rot, name) = check.split("||", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            sb.appendLine("| $rot | $name | $status | $detail |")
        }
        Phase5Report.write("cert-rotation-results.md", sb.toString())
    }

    private fun record(rotation: String, check: String, ok: Boolean, detail: String) {
        results += Triple("$rotation||$check", if (ok) "PASS" else "FAIL", detail)
    }

    // --- 1. code-signing rotation --------------------------------------------------------------------

    @Test
    @DisplayName("Signing rotation: new cert (same DN) survives the pin; old verifies; expired fails closed")
    fun signingCertRotation(@TempDir dir: Path) {
        val rot = "Layer-2 signing"
        val dn = "CN=OIE,O=BrightCodeCompany OÜ,C=EE"
        val pin = SignerPin.of("OIE", "O" to "BrightCodeCompany OÜ")
        val old = TestArtifacts.codeSigningLeaf(dn, ca)
        val renewed = TestArtifacts.codeSigningLeaf(dn, ca) // same DN, fresh key + serial

        // The renewal is a genuinely different certificate that still matches the DN-attribute pin.
        assertEquals(old.cert.subjectX500Principal, renewed.cert.subjectX500Principal)
        assertNotEquals(old.cert.serialNumber, renewed.cert.serialNumber)
        assertNotEquals(old.keyPair.public, renewed.keyPair.public)
        val pinSurvives = pin.matches(old.cert) && pin.matches(renewed.cert)
        record(rot, "renewal with the same DN still satisfies PIN_KNOWN_SIGNERS", pinSurvives, "old & renewed both match O=BrightCodeCompany OÜ")
        assertTrue(pinSurvives)

        val gate = CodebaseVerifier(TrustStore.of(ca.cert), GestaltTrust.OFFICIAL_SIGNER_PINS, TrustMode.PIN_KNOWN_SIGNERS)
        val renewedAdmitted = admit(gate, dir, "renewed.jar", renewed) is GateOutcome.Admitted
        record(rot, "jar signed by the NEW (renewed) cert is admitted", renewedAdmitted, "gate admits the renewed signer")
        assertTrue(renewedAdmitted)

        val oldAdmitted = admit(gate, dir, "old.jar", old) is GateOutcome.Admitted
        record(rot, "jar signed by the OLD cert still verifies (within validity)", oldAdmitted, "old signer still trusted")
        assertTrue(oldAdmitted)

        // An expired signing cert with no RFC-3161 timestamp must be rejected (validity is enforced at now).
        val now = System.currentTimeMillis()
        val expired = TestArtifacts.codeSigningLeaf(dn, ca, Date(now - 730L * 86_400_000L), Date(now - 365L * 86_400_000L))
        val expiredOutcome = admit(gate, dir, "expired.jar", expired)
        val expiredRejected = expiredOutcome is GateOutcome.Rejected
        record(rot, "EXPIRED signing cert (no timestamp) fails closed", expiredRejected, "validity enforced; timestamped path covered by JarSignatureVerifierTest")
        assertTrue(expiredRejected) { "an expired signing cert without a timestamp must be rejected" }
    }

    private fun admit(gate: CodebaseVerifier, dir: Path, name: String, leaf: TestArtifacts.Leaf): GateOutcome {
        val jar = dir.resolve(name)
        TestArtifacts.writeSignedJar(jar, mapOf("mirth/Main.class" to name.toByteArray()), leaf.keyPair.private, leaf.chain)
        val manifest = DigestManifest.parse(TestArtifacts.digestManifestText(name to jar))
        return gate.verify(name, jar, manifest)
    }

    // --- 2. server-cert rotation ---------------------------------------------------------------------

    @Test
    @DisplayName("Server-cert rotation: rotated leaf fails the pin closed; re-pin recovers the launch")
    fun serverCertRotation(@TempDir dir: Path) {
        val rot = "Server-cert"
        val (_, oldLeaf) = TestArtifacts.serverCert("mirth-connect", "mirth-connect")
        val (_, newLeaf) = TestArtifacts.serverCert("mirth-connect", "mirth-connect")
        val oldPin = CertFingerprint.sha256Hex(oldLeaf)
        val newPin = CertFingerprint.sha256Hex(newLeaf)
        assertNotEquals(oldPin, newPin)

        // (a) The pinned trust manager rejects the rotated leaf closed — this is what raises the re-pin prompt.
        val tm = PinnedTrustManager(oldPin)
        tm.checkServerTrusted(arrayOf(oldLeaf), "RSA")
        val tmRejects = runCatching { tm.checkServerTrusted(arrayOf(newLeaf), "RSA") }.exceptionOrNull() is CertificateException
        record(rot, "pinned trust manager rejects the rotated leaf", tmRejects, "old pin → new leaf = CertificateException")
        assertTrue(tmRejects)

        // (b) The launch pipeline stops for review when the server presents a leaf the user hasn't trusted
        //     (the old leaf is trusted; the rotated one is unknown). The TLS layer's refusal is simulated by
        //     a fetcher that raises the policy's own exception until the new leaf is trusted.
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Sectigo,O=Pretend CA,C=GB")
        val codeLeaf = TestArtifacts.codeSigningLeaf("CN=OIE,O=BrightCodeCompany OÜ,C=EE", ca)
        val jarBytes = Phase5Fixtures.signedCore(dir, codeLeaf)
        val base = "https://mirth.internal:8443/"
        val responses = Phase5Fixtures.canned(base, jarBytes, "https://mirth.internal:8443")
        val trusted = com.gestalt.launcher.tls.TrustedCertStore(dir.resolve("trusted-certs.json"))
        trusted.trust(oldLeaf, "mirth.internal:8443", "rot-server")
        fun launcherPresenting(leaf: java.security.cert.X509Certificate) = LauncherCore(
            trustStore = TrustStore.of(ca.cert),
            signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS,
            gateMode = TrustMode.PIN_KNOWN_SIGNERS,
            cacheDir = dir.resolve("cache"),
            trustedCerts = trusted,
            fetcherFactory = {
                if (trusted.statusOf(leaf) == com.gestalt.launcher.tls.CertTrustStatus.TRUSTED) FakeFetcher(responses)
                else object : com.gestalt.launcher.net.ArtifactFetcher {
                    override fun fetchBytes(uri: java.net.URI): ByteArray =
                        throw javax.net.ssl.SSLHandshakeException("PKIX path building failed").apply {
                            initCause(com.gestalt.launcher.tls.UnknownServerCertificateException(listOf(leaf)))
                        }
                }
            },
            serverLeafProbe = { leaf },
        )
        val conn = Connection(id = "rot-server", name = "Rotation", serverUrl = base)
        val stopped = runCatching { launcherPresenting(newLeaf).prepareLaunch(conn) }.exceptionOrNull()
        val launchStopsForReview = stopped is com.gestalt.launcher.launch.ServerCertificateReviewRequiredException
        record(rot, "launch stops for review on the rotated leaf", launchStopsForReview, "prepareLaunch → ServerCertificateReviewRequiredException")
        assertTrue(launchStopsForReview) { "got: $stopped" }
        assertEquals(com.gestalt.launcher.tls.CertTrustStatus.PENDING, trusted.statusOf(newLeaf)) { "the rotated leaf is recorded for review" }

        // (c) Recovery: trust the new leaf (audited, persisted), launch succeeds.
        val store = ConnectionStore(dir.resolve("connections.json"))
        val saved = store.add(conn)
        ServerCertPinning.pin(store, saved, newLeaf, trusted = trusted)
        assertEquals(com.gestalt.launcher.tls.CertTrustStatus.TRUSTED, trusted.statusOf(newLeaf))
        assertEquals(newPin, trusted.get(newPin)?.sha256)
        val recovered = runCatching { launcherPresenting(newLeaf).prepareLaunch(saved) }.getOrNull()
        val recoveredOk = recovered?.admitted?.size == 1
        record(rot, "trusting the new leaf recovers the launch", recoveredOk, "after trust, prepareLaunch admits the jar")
        assertTrue(recoveredOk)

        // (d) Auto-approve: a connection with the checkbox on trusts a rotated leaf by itself and launches.
        val freshLeaf = TestArtifacts.serverCert("mirth-connect", "mirth-connect").second
        val auto = Connection(id = "rot-auto", name = "Rotation (auto)", serverUrl = base, autoApproveCertificates = true)
        val autoPlan = runCatching { launcherPresenting(freshLeaf).prepareLaunch(auto) }.getOrNull()
        val autoOk = autoPlan?.admitted?.size == 1 && trusted.get(com.gestalt.launcher.tls.CertFingerprint.sha256Hex(freshLeaf))?.autoApproved == true
        record(rot, "auto-approve trusts a new leaf without a prompt", autoOk, "prepareLaunch succeeds; entry marked autoApproved")
        assertTrue(autoOk)
    }

    // --- 3. update-key rotation ----------------------------------------------------------------------

    @Test
    @DisplayName("Update-key rotation (per UPDATES.md): accept new key only after it ships; reject unsigned/mis-signed/tampered")
    fun updateKeyRotation() {
        val rot = "Update-key"
        val (privOld, pubOld) = UpdateCrypto.generateKeyPair()
        val (privNew, pubNew) = UpdateCrypto.generateKeyPair()
        val (privRogue, _) = UpdateCrypto.generateKeyPair()

        val base = UpdateManifest(
            version = "1.1.0",
            notes = "rotation drill",
            perOS = mapOf("windows-x64" to ArtifactRef("https://updates.example.com/x.msi", "ab".repeat(32))),
        )
        val signedByNew = base.copy(signature = UpdateCrypto.sign(privNew, base))
        val signedByOld = base.copy(signature = UpdateCrypto.sign(privOld, base))
        val signedByRogue = base.copy(signature = UpdateCrypto.sign(privRogue, base))
        val unsigned = base.copy(signature = "")
        val tampered = signedByNew.copy(version = "9.9.9") // signature no longer covers the version

        // Before the new key ships, a client trusting only the OLD key rejects a new-key manifest.
        val beforeShip = !UpdateCrypto.verify(signedByNew, listOf(pubOld))
        record(rot, "new-key manifest REJECTED before the new key ships", beforeShip, "trusted=[old] → new-key sig rejected")
        assertTrue(beforeShip) { "must not switch signing keys before the new public key has shipped" }

        // After shipping the app update that adds the new public key, the client trusts both.
        val trusted = listOf(pubOld, pubNew)
        val newAccepted = UpdateCrypto.verify(signedByNew, trusted)
        record(rot, "new-key manifest ACCEPTED after the new key ships", newAccepted, "trusted=[old,new] → new-key sig accepted")
        assertTrue(newAccepted)

        val oldStillWorks = UpdateCrypto.verify(signedByOld, trusted)
        record(rot, "old-key manifest still accepted during overlap", oldStillWorks, "old key valid until dropped in a later release")
        assertTrue(oldStillWorks)

        val rejectsUnsigned = !UpdateCrypto.verify(unsigned, trusted)
        val rejectsRogue = !UpdateCrypto.verify(signedByRogue, trusted)
        val rejectsTampered = !UpdateCrypto.verify(tampered, trusted)
        record(rot, "unsigned / mis-signed / tampered manifests rejected", rejectsUnsigned && rejectsRogue && rejectsTampered, "unsigned=$rejectsUnsigned, rogue=$rejectsRogue, tampered=$rejectsTampered")
        assertFalse(UpdateCrypto.verify(unsigned, trusted))
        assertFalse(UpdateCrypto.verify(signedByRogue, trusted))
        assertFalse(UpdateCrypto.verify(tampered, trusted))
    }

    private companion object {
        // A single CA reused across the signing-rotation checks.
        val ca: TestArtifacts.Ca = TestArtifacts.selfSignedCa("CN=Pretend Sectigo,O=Pretend CA,C=GB")
    }
}
