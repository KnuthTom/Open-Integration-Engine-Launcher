/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.trust

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * End-to-end tests for [JarSignatureVerifier].
 *
 * Jar fixtures are dropped into `src/test/resources/fixtures/` by the developer (they are large
 * and/or proprietary, so they are gitignored). Each test that needs a fixture SKIPs with a clear
 * message when it is absent, so the suite is green on a clean checkout / in CI before fixtures land.
 *
 * See `src/test/resources/fixtures/README.md` for how to produce each fixture.
 */
class JarSignatureVerifierTest {

    // ---- The genuine OIE jar: must validate in PIN_KNOWN_SIGNERS, with a usable timestamp -------

    @Test
    @DisplayName("genuine OIE jar validates in PIN_KNOWN_SIGNERS mode with a timestamp")
    fun oieJarValidatesPinned() {
        val jar = fixture("oie-mirth-client-core.jar")
        assumeTrue(jar != null) { "SKIP: drop the genuine OIE jar at $FIXTURES/oie-mirth-client-core.jar" }

        val verifier = JarSignatureVerifier(
            sectigoAnchors(),
            VerifierConfig.pinning(BRIGHTCODE_DN),
        )
        val result = verifier.verify(jar!!)

        assertTrue(result.valid) { "expected valid, got failure: ${result.failureReason}" }
        assertNotNull(result.signerDN)
        assertTrue(result.signerDN!!.contains("BrightCodeCompany")) {
            "signerDN should name BrightCodeCompany, was: ${result.signerDN}"
        }
        assertTrue(result.usedTimestamp) { "expected the RFC-3161 timestamp to be used" }
        assertNotNull(result.timestampInstant)
        assertEquals(TrustMode.PIN_KNOWN_SIGNERS, result.trustMode)
        assertTrue(result.chain.size >= 2) { "expected a multi-cert chain, got ${result.chain.size}" }
    }

    @Test
    @DisplayName("genuine OIE jar is rejected when the publisher is not on the pin list")
    fun oieJarRejectedWhenNotPinned() {
        val jar = fixture("oie-mirth-client-core.jar")
        assumeTrue(jar != null) { "SKIP: drop the genuine OIE jar at $FIXTURES/oie-mirth-client-core.jar" }

        val verifier = JarSignatureVerifier(
            sectigoAnchors(),
            VerifierConfig.pinning("CN=Someone Else,O=Not Us,C=US"),
        )
        val result = verifier.verify(jar!!)

        assertFalse(result.valid)
        assertTrue(result.failureReason!!.contains("pinned", ignoreCase = true)) {
            "reason: ${result.failureReason}"
        }
    }

    @Test
    @DisplayName("genuine OIE jar validates in CA_ROOT mode (any leaf chaining to the root)")
    fun oieJarValidatesCaRoot() {
        val jar = fixture("oie-mirth-client-core.jar")
        assumeTrue(jar != null) { "SKIP: drop the genuine OIE jar at $FIXTURES/oie-mirth-client-core.jar" }

        val result = JarSignatureVerifier(sectigoAnchors(), VerifierConfig.caRoot()).verify(jar!!)

        assertTrue(result.valid) { "expected valid, got: ${result.failureReason}" }
        assertTrue(result.usedTimestamp)
    }

    // ---- A jar signed with the Gestalt self-signed identity -------------------------------------

    @Test
    @DisplayName("gestalt-signed jar validates against the Gestalt anchor")
    fun gestaltSignedJarValidates() {
        val jar = fixture("gestalt-signed.jar")
        val anchor = gestaltAnchor()
        assumeTrue(jar != null) { "SKIP: sign a small jar with tools/gen-signing-cert + jarsigner -> $FIXTURES/gestalt-signed.jar" }
        assumeTrue(anchor != null) { "SKIP: copy tools/out/gestalt-signing.pem -> $FIXTURES/anchors/gestalt-signing.pem" }

        val result = JarSignatureVerifier(anchor!!, VerifierConfig.caRoot()).verify(jar!!)

        assertTrue(result.valid) { "expected valid, got: ${result.failureReason}" }
        assertNotNull(result.signerDN)
        assertTrue(result.signerDN!!.contains("Gestalt", ignoreCase = true)) {
            "signerDN: ${result.signerDN}"
        }
    }

    // ---- Negative cases --------------------------------------------------------------------------

    @Test
    @DisplayName("tampered OIE jar (one flipped byte) fails with a clear reason")
    fun tamperedJarFails() {
        val jar = fixture("tampered.jar")
        assumeTrue(jar != null) { "SKIP: flip one class byte in the OIE jar -> $FIXTURES/tampered.jar (see README)" }

        val result = JarSignatureVerifier(sectigoAnchors(), VerifierConfig.pinning(BRIGHTCODE_DN)).verify(jar!!)

        assertFalse(result.valid) { "tampered jar must not validate" }
        assertNotNull(result.failureReason)
        assertTrue(
            result.failureReason!!.contains("tamper", ignoreCase = true) ||
                result.failureReason!!.contains("digest", ignoreCase = true) ||
                result.failureReason!!.contains("invalid", ignoreCase = true),
        ) { "reason should point at integrity failure, was: ${result.failureReason}" }
    }

    @Test
    @DisplayName("unsigned jar fails with a clear reason")
    fun unsignedJarFails() {
        val jar = fixture("unsigned.jar")
        assumeTrue(jar != null) { "SKIP: jar up any classes without signing -> $FIXTURES/unsigned.jar" }

        val result = JarSignatureVerifier(sectigoAnchors(), VerifierConfig.caRoot()).verify(jar!!)

        assertFalse(result.valid)
        assertTrue(result.failureReason!!.contains("not signed", ignoreCase = true)) {
            "reason: ${result.failureReason}"
        }
    }

    // ---- Always-on sanity check (no fixtures needed) --------------------------------------------

    @Test
    @DisplayName("committed Sectigo + USERTrust anchors load and parse")
    fun anchorsLoad() {
        val store = sectigoAnchors()
        assertEquals(2, store.anchors.size)
        assertTrue(store.anchors.any { it.subjectX500Principal.name.contains("Sectigo Public Code Signing Root R46") })
        assertTrue(store.anchors.any { it.subjectX500Principal.name.contains("USERTrust RSA Certification Authority") })
    }

    // ---------------------------------------------------------------------------------------------

    private fun sectigoAnchors(): TrustStore =
        pemStore(
            "/anchors/sectigo-code-signing-root-r46.pem",
            "/anchors/usertrust-rsa-root.pem",
        )

    private fun gestaltAnchor(): TrustStore? =
        javaClass.getResourceAsStream("/fixtures/anchors/gestalt-signing.pem")
            ?.use { TrustStore.fromPem(it) }

    private fun pemStore(vararg classpath: String): TrustStore {
        var store: TrustStore? = null
        for (p in classpath) {
            val s = javaClass.getResourceAsStream(p)?.use { TrustStore.fromPem(it) }
                ?: error("missing committed test resource: $p")
            store = store?.plus(s) ?: s
        }
        return store!!
    }

    private fun fixture(name: String): File? =
        javaClass.getResource("/fixtures/$name")?.toURI()?.let(::File)?.takeIf { it.isFile }

    companion object {
        // The actual publisher DN baked into the genuine OIE jar's signer leaf.
        private const val BRIGHTCODE_DN =
            "CN=BrightCodeCompany OÜ,O=BrightCodeCompany OÜ,ST=Harjumaa,C=EE"
        private const val FIXTURES = "core-trust/src/test/resources/fixtures"
    }
}
