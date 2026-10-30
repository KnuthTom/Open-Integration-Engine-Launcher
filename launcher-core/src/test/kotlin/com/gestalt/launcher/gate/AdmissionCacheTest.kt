/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.gate

import com.gestalt.launcher.testutil.TestArtifacts
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AdmissionCacheTest {

    private fun entry(sha: String, policy: String = "p1") =
        AdmissionCache.Entry(sha, policy, "CN=Signer,O=Example Lab", usedTimestamp = true, admittedAt = AdmissionCache.now(), artifactName = "webstart/client-lib/x.jar")

    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)

    @Test
    @DisplayName("an admission is found by the bytes' digest under the same policy only, and survives a reload")
    fun recordLookupPersist(@TempDir dir: Path) {
        val file = dir.resolve("admissions.json")
        val cache = AdmissionCache(file)
        assertNull(cache.lookup(shaA, "p1"))
        cache.record(entry(shaA))
        assertNotNull(cache.lookup(shaA, "p1"))
        assertNotNull(cache.lookup(shaA.uppercase(), "p1")) { "digest case does not matter" }
        assertNull(cache.lookup(shaA, "p2")) { "another policy: verify in full again" }
        assertNull(cache.lookup(shaB, "p1"))

        cache.flush()
        assertTrue(Files.isRegularFile(file))
        val reloaded = AdmissionCache(file)
        assertEquals(1, reloaded.size())
        assertEquals("CN=Signer,O=Example Lab", reloaded.lookup(shaA, "p1")!!.signerDN)
    }

    @Test
    @DisplayName("bounded: the oldest admissions fall out; a corrupt file is ignored, never fatal")
    fun boundedAndTolerant(@TempDir dir: Path) {
        val file = dir.resolve("admissions.json")
        val cache = AdmissionCache(file, maxEntries = 3)
        for (c in listOf('1', '2', '3', '4')) cache.record(entry(c.toString().repeat(64)))
        assertEquals(3, cache.size())
        assertNull(cache.lookup("1".repeat(64), "p1")) { "the oldest went" }
        assertNotNull(cache.lookup("4".repeat(64), "p1"))

        Files.writeString(file, "{ not json")
        val fromCorrupt = AdmissionCache(file)
        assertEquals(0, fromCorrupt.size())
        fromCorrupt.record(entry(shaA))
        fromCorrupt.flush()
        assertEquals(1, AdmissionCache(file).size())
    }

    @Test
    @DisplayName("the policy fingerprint changes with the mode, the pins, the anchors and every publisher decision")
    fun policyFingerprint(@TempDir dir: Path) {
        val ca1 = TestArtifacts.selfSignedCa("CN=Root One,O=Pretend CA,C=GB")
        val ca2 = TestArtifacts.selfSignedCa("CN=Root Two,O=Pretend CA,C=GB")
        val pins = GestaltTrust.OFFICIAL_SIGNER_PINS
        val signers = TrustedSignerStore(dir.resolve("signers.json"))

        val base = AdmissionCache.policyFingerprint(TrustMode.PIN_KNOWN_SIGNERS, pins, TrustStore.of(ca1.cert), signers)
        assertEquals(base, AdmissionCache.policyFingerprint(TrustMode.PIN_KNOWN_SIGNERS, pins, TrustStore.of(ca1.cert), signers)) { "deterministic" }
        assertNotEquals(base, AdmissionCache.policyFingerprint(TrustMode.CA_ROOT, pins, TrustStore.of(ca1.cert), signers)) { "mode" }
        assertNotEquals(base, AdmissionCache.policyFingerprint(TrustMode.PIN_KNOWN_SIGNERS, pins.drop(1), TrustStore.of(ca1.cert), signers)) { "pins" }
        assertNotEquals(base, AdmissionCache.policyFingerprint(TrustMode.PIN_KNOWN_SIGNERS, pins, TrustStore.of(ca1.cert, ca2.cert), signers)) { "anchors" }

        val leaf = TestArtifacts.codeSigningLeaf("CN=In-house,O=Example Lab,C=US", ca2)
        signers.trust(leaf.chain.first(), "x.jar", "c1")
        val afterTrust = AdmissionCache.policyFingerprint(TrustMode.PIN_KNOWN_SIGNERS, pins, TrustStore.of(ca1.cert), signers)
        assertNotEquals(base, afterTrust) { "a publisher decision" }
        signers.reject(leaf.chain.first(), "x.jar", "c1")
        assertNotEquals(afterTrust, AdmissionCache.policyFingerprint(TrustMode.PIN_KNOWN_SIGNERS, pins, TrustStore.of(ca1.cert), signers)) { "and its reversal" }
    }
}
