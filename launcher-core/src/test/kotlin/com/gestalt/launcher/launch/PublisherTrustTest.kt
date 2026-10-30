/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.gate.TrustedSignerStore
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.net.ArtifactFetcher
import com.gestalt.launcher.testutil.TestArtifacts
import com.gestalt.launcher.tls.CertFingerprint
import com.gestalt.launcher.tls.CertTrustStatus
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Trust on first use for jar publishers. Real Mirth shops run in-house and third-party extensions whose
 * client jars are signed with keys the launcher has never heard of (often a self-made key with no
 * codeSigning EKU). Integrity is never negotiable; trust in the signer is the user's call, per exact
 * signing certificate, audited.
 */
class PublisherTrustTest {

    private val base = "https://mirth.example.internal:8443/"

    private class FakeFetcher(private val responses: Map<String, ByteArray>) : ArtifactFetcher {
        override fun fetchBytes(uri: URI): ByteArray =
            responses[uri.toString()] ?: throw java.io.IOException("no canned response for $uri")
    }

    private fun jnlp(sha: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jnlp spec="1.0+">
          <resources><jar href="webstart/extensions/libs/custom/custom-client.jar" main="true" sha256="$sha"/></resources>
          <application-desc main-class="com.mirth.connect.client.ui.Mirth">
            <argument>https://localhost:8443</argument><argument>4.4.2</argument>
          </application-desc>
        </jnlp>
    """.trimIndent()

    private fun b64Sha(bytes: ByteArray) =
        java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun jarSignedBy(dir: Path, key: PrivateKey, chain: List<X509Certificate>, extras: Map<String, ByteArray> = emptyMap()): ByteArray {
        val jar = dir.resolve("custom-client-${System.nanoTime()}.jar")
        TestArtifacts.writeSignedJar(jar, mapOf("custom/Plugin.class" to "plugin".toByteArray()), key, chain, extras)
        return Files.readAllBytes(jar)
    }

    private fun responses(jar: ByteArray) = mapOf(
        "${base}webstart" to jnlp(b64Sha(jar)).toByteArray(),
        "${base}webstart/extensions/libs/custom/custom-client.jar" to jar,
    )

    private fun launcher(dir: Path, anchors: TrustStore, signers: TrustedSignerStore, jar: ByteArray, mode: TrustMode = TrustMode.PIN_KNOWN_SIGNERS) =
        LauncherCore(
            trustStore = anchors,
            signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS,
            gateMode = mode,
            cacheDir = dir.resolve("cache"),
            trustedSigners = signers,
            fetcherFactory = { FakeFetcher(responses(jar)) },
            sleeper = {},
        )

    private fun conn() = Connection(id = "c", name = "C", serverUrl = base)

    @Test
    @DisplayName("an in-house signer (self-made key, no codeSigning EKU, unknown chain) stops the launch for review, recorded PENDING")
    fun unknownPublisherStopsForReview(@TempDir dir: Path) {
        val anchors = TrustStore.of(TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB").cert)
        val inHouse = TestArtifacts.selfSignedCa("CN=Example Lab Integration Team,O=Example Lab,C=US") // no EKU, chains nowhere
        val jar = jarSignedBy(dir, inHouse.keyPair.private, listOf(inHouse.cert))
        val signers = TrustedSignerStore(dir.resolve("trusted-signers.json"))

        val ex = assertThrows(PublisherReviewRequiredException::class.java) {
            launcher(dir, anchors, signers, jar).prepareLaunch(conn())
        }

        assertEquals(inHouse.cert, ex.signer)
        assertEquals("webstart/extensions/libs/custom/custom-client.jar", ex.artifactName)
        val entry = signers.get(CertFingerprint.sha256Hex(inHouse.cert))!!
        assertEquals(CertTrustStatus.PENDING, entry.status)
        assertEquals("Example Lab Integration Team", entry.displayName)
        assertEquals(listOf("webstart/extensions/libs/custom/custom-client.jar"), entry.artifacts)
        assertEquals(listOf("c"), entry.connectionIds)
        // The jar is kept (it is intact); only trust is missing.
        assertTrue(Files.isRegularFile(dir.resolve("cache/c/4.4.2/webstart/extensions/libs/custom/custom-client.jar")))
    }

    @Test
    @DisplayName("once the publisher is trusted the jar is admitted — exact signer, integrity still enforced, EKU and chain not required")
    fun trustedPublisherAdmits(@TempDir dir: Path) {
        val anchors = TrustStore.of(TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB").cert)
        val inHouse = TestArtifacts.selfSignedCa("CN=Example Lab Integration Team,O=Example Lab,C=US")
        val jar = jarSignedBy(dir, inHouse.keyPair.private, listOf(inHouse.cert))
        val signers = TrustedSignerStore(dir.resolve("trusted-signers.json"))
        signers.trust(inHouse.cert, connectionId = "c")

        val plan = launcher(dir, anchors, signers, jar).prepareLaunch(conn())

        assertEquals(1, plan.admitted.size)
        assertTrue(plan.admitted.single().signerDN.contains("Example Lab Integration Team"))

        // A DIFFERENT key with the same DN is a different publisher: review again, never admitted on the name.
        val impostor = TestArtifacts.selfSignedCa("CN=Example Lab Integration Team,O=Example Lab,C=US")
        val impostorJar = jarSignedBy(dir, impostor.keyPair.private, listOf(impostor.cert))
        assertThrows(PublisherReviewRequiredException::class.java) {
            launcher(dir, anchors, signers, impostorJar).prepareLaunch(conn())
        }
    }

    @Test
    @DisplayName("a rejected publisher fails closed — no review prompt, no retry")
    fun rejectedPublisherFailsClosed(@TempDir dir: Path) {
        val anchors = TrustStore.of(TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB").cert)
        val inHouse = TestArtifacts.selfSignedCa("CN=Example Lab Integration Team,O=Example Lab,C=US")
        val jar = jarSignedBy(dir, inHouse.keyPair.private, listOf(inHouse.cert))
        val signers = TrustedSignerStore(dir.resolve("trusted-signers.json"))
        signers.reject(inHouse.cert, connectionId = "c")

        val ex = assertThrows(LaunchAbortedException::class.java) { launcher(dir, anchors, signers, jar).prepareLaunch(conn()) }

        assertFalse(ex is PublisherReviewRequiredException) { "a rejection is final, not a review" }
        assertTrue(ex.message.orEmpty().contains("rejected by the user")) { ex.message }
        assertEquals(CertTrustStatus.REJECTED, signers.statusOf(inHouse.cert))
    }

    @Test
    @DisplayName("a CA-valid publisher that isn't on the pinned allow-list is reviewable too (PIN mode), and admitted once trusted")
    fun pinModeUnknownButValidPublisher(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val vendor = TestArtifacts.codeSigningLeaf("CN=Acme Interfaces,O=Acme Interfaces LLC,C=US", ca) // valid chain, proper EKU, not pinned
        val jar = jarSignedBy(dir, vendor.keyPair.private, vendor.chain)
        val signers = TrustedSignerStore(dir.resolve("trusted-signers.json"))

        assertThrows(PublisherReviewRequiredException::class.java) { launcher(dir, TrustStore.of(ca.cert), signers, jar).prepareLaunch(conn()) }
        assertEquals(CertTrustStatus.PENDING, signers.statusOf(vendor.chain.first()))

        signers.trust(signers.pending().single().sha256)
        assertEquals(1, launcher(dir, TrustStore.of(ca.cert), signers, jar).prepareLaunch(conn()).admitted.size)
    }

    @Test
    @DisplayName("a tampered jar is a hard stop: never reviewable, nothing recorded, cache slot removed")
    fun tamperedJarIsNeverReviewable(@TempDir dir: Path) {
        val anchors = TrustStore.of(TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB").cert)
        val inHouse = TestArtifacts.selfSignedCa("CN=Example Lab Integration Team,O=Example Lab,C=US")
        // A stowaway entry not covered by the signed manifest: integrity fails before trust is even considered.
        val jar = jarSignedBy(dir, inHouse.keyPair.private, listOf(inHouse.cert), extras = mapOf("evil/Backdoor.class" to "x".toByteArray()))
        val signers = TrustedSignerStore(dir.resolve("trusted-signers.json"))
        signers.trust(inHouse.cert) // even a TRUSTED publisher can't carry tampered content

        val ex = assertThrows(LaunchAbortedException::class.java) { launcher(dir, anchors, signers, jar).prepareLaunch(conn()) }

        assertFalse(ex is PublisherReviewRequiredException)
        assertNull(signers.pending().firstOrNull())
        assertFalse(Files.exists(dir.resolve("cache/c/4.4.2/webstart/extensions/libs/custom/custom-client.jar"))) { "a rejected jar is deleted" }
    }

    @Test
    @DisplayName("the production anchors include the platform's root CAs, not only the two bundled roots")
    fun systemRootsAreAnchors() {
        val store = GestaltTrust.productionTrustStore()
        val bundled = GestaltTrust.publicRoots().anchors.size
        assertTrue(store.anchors.size > bundled + 10) { "expected the JDK cacerts roots among ${store.anchors.size} anchors (bundled: $bundled)" }
        // (Not every cacerts root carries a basicConstraints extension — some are old V1 certificates — so the
        // check is on count and self-issuance, not on the extension.)
        assertTrue(GestaltTrust.systemRoots().all { it.subjectX500Principal == it.issuerX500Principal }) { "system anchors are self-issued roots" }
    }
}
