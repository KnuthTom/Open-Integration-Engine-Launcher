/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.gate

import com.gestalt.launcher.digest.DigestManifest
import com.gestalt.launcher.testutil.TestArtifacts
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Signature-enforcement acceptance: the single gate admits genuine signers, rejects unknown ones in
 * PIN mode, rejects tampered/unsigned jars, and — crucially — produces an [GateOutcome.Admitted]
 * (the only thing that can join the classpath) ONLY on full success. Hermetic: jars/certs are minted
 * with BouncyCastle.
 */
class GateSignatureEnforcementTest {

    private fun manifest(vararg named: Pair<String, Path>) =
        DigestManifest.parse(TestArtifacts.digestManifestText(*named))

    @Test
    @DisplayName("unknown-but-valid-CA signer: REJECTED in PIN_KNOWN_SIGNERS, ACCEPTED in CA_ROOT")
    fun unknownCaSignerRejectedInPinAcceptedInCaRoot(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Test Root CA,O=Test CA Org,C=US")
        val leaf = TestArtifacts.codeSigningLeaf("CN=Rogue Signer,O=Totally Unknown Vendor,C=US", ca)
        val jar = dir.resolve("rogue.jar")
        TestArtifacts.writeSignedJar(jar, mapOf("com/x/A.class" to "rogue".toByteArray()), leaf.keyPair.private, leaf.chain)
        val m = manifest("rogue.jar" to jar)
        val trust = TrustStore.of(ca.cert)

        val pinGate = CodebaseVerifier(trust, GestaltTrust.OFFICIAL_SIGNER_PINS, TrustMode.PIN_KNOWN_SIGNERS)
        val pinned = pinGate.verify("rogue.jar", jar, m)
        assertTrue(pinned is GateOutcome.Rejected) { "unknown signer must be rejected in PIN mode" }
        assertTrue((pinned as GateOutcome.Rejected).reason.contains("pinned", ignoreCase = true))

        val caGate = CodebaseVerifier(trust, GestaltTrust.OFFICIAL_SIGNER_PINS, TrustMode.CA_ROOT)
        val caOutcome = caGate.verify("rogue.jar", jar, m)
        assertTrue(caOutcome is GateOutcome.Admitted) {
            "valid chain-to-root must be accepted in CA_ROOT: ${(caOutcome as? GateOutcome.Rejected)?.reason}"
        }
    }

    @Test
    @DisplayName("publisher pin matches by DN attributes (renewal-surviving)")
    fun brightCodePublisherPinMatchesByDnAttributes(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Sectigo,O=Pretend CA,C=GB")
        val leaf = TestArtifacts.codeSigningLeaf("CN=OIE Build 2031,O=BrightCodeCompany OÜ,ST=Harjumaa,C=EE", ca)
        val jar = dir.resolve("oie-like.jar")
        TestArtifacts.writeSignedJar(jar, mapOf("oie/Main.class" to "oie".toByteArray()), leaf.keyPair.private, leaf.chain)
        val outcome = CodebaseVerifier(TrustStore.of(ca.cert), GestaltTrust.OFFICIAL_SIGNER_PINS)
            .verify("oie-like.jar", jar, manifest("oie-like.jar" to jar))
        assertTrue(outcome is GateOutcome.Admitted) { (outcome as? GateOutcome.Rejected)?.reason ?: "" }
        assertTrue((outcome as GateOutcome.Admitted).jar.signerDN.contains("BrightCodeCompany"))
    }

    @Test
    @DisplayName("tampered jar fails the digest check (gate admits nothing)")
    fun tamperedJarFailsDigest(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Test Root,O=Test,C=US")
        val leaf = TestArtifacts.codeSigningLeaf("CN=Sig,O=BrightCodeCompany OÜ,C=EE", ca)
        val jar = dir.resolve("good.jar")
        TestArtifacts.writeSignedJar(jar, mapOf("a/A.class" to "hello".toByteArray()), leaf.keyPair.private, leaf.chain)
        val m = manifest("good.jar" to jar) // digest of the original bytes
        val bytes = Files.readAllBytes(jar)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0xFF).toByte()
        Files.write(jar, bytes)

        val outcome = CodebaseVerifier(TrustStore.of(ca.cert), GestaltTrust.OFFICIAL_SIGNER_PINS).verify("good.jar", jar, m)
        assertTrue(outcome is GateOutcome.Rejected)
        outcome as GateOutcome.Rejected
        assertFalse(outcome.digestPass)
        assertTrue(outcome.reason.contains("digest", ignoreCase = true))
    }

    @Test
    @DisplayName("unsigned jar fails the signature check (digest passes, signature does not)")
    fun unsignedJarFailsSignature(@TempDir dir: Path) {
        val jar = dir.resolve("unsigned.jar")
        TestArtifacts.writeUnsignedJar(jar, mapOf("a/A.class" to "hello".toByteArray()))
        val m = manifest("unsigned.jar" to jar) // correct digest so we reach the signature step
        val ca = TestArtifacts.selfSignedCa("CN=Test,O=Test,C=US")
        val outcome = CodebaseVerifier(TrustStore.of(ca.cert), GestaltTrust.OFFICIAL_SIGNER_PINS).verify("unsigned.jar", jar, m)
        assertTrue(outcome is GateOutcome.Rejected)
        outcome as GateOutcome.Rejected
        assertTrue(outcome.digestPass)
        assertFalse(outcome.signaturePass)
    }

    @Test
    @DisplayName("stowaway: an unsigned class added to a validly-signed, pinned jar is rejected")
    fun stowawayEntryRejected(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val leaf = TestArtifacts.codeSigningLeaf("CN=OIE,O=BrightCodeCompany OÜ,C=EE", ca)
        val jar = dir.resolve("stow.jar")
        // A genuine signed jar PLUS an extra class that is not under the signature.
        TestArtifacts.writeSignedJar(
            jar,
            entries = mapOf("oie/Main.class" to "ok".toByteArray()),
            signerKey = leaf.keyPair.private,
            chain = leaf.chain,
            unsignedExtras = mapOf("com/evil/Pwn.class" to "evil".toByteArray()),
        )
        // The attacker republishes the digest of the tampered file, so the DIGEST leg passes...
        val outcome = CodebaseVerifier(TrustStore.of(ca.cert), GestaltTrust.OFFICIAL_SIGNER_PINS)
            .verify("stow.jar", jar, manifest("stow.jar" to jar))
        // ...but the SIGNATURE leg must still reject the uncovered class.
        assertTrue(outcome is GateOutcome.Rejected) { "stowaway class must not be admitted" }
        outcome as GateOutcome.Rejected
        assertTrue(outcome.digestPass) { "digest of the whole file matches the (attacker-published) manifest" }
        assertFalse(outcome.signaturePass)
        assertTrue(outcome.reason.contains("not covered", ignoreCase = true) || outcome.reason.contains("manifest", ignoreCase = true)) {
            outcome.reason
        }
    }

    @Test
    @DisplayName("artifact absent from the digest manifest is rejected closed")
    fun missingDigestRejectedClosed(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Test,O=Test,C=US")
        val leaf = TestArtifacts.codeSigningLeaf("CN=Sig,O=BrightCodeCompany OÜ,C=EE", ca)
        val jar = dir.resolve("nodigest.jar")
        TestArtifacts.writeSignedJar(jar, mapOf("a/A.class" to "x".toByteArray()), leaf.keyPair.private, leaf.chain)
        val unrelated = DigestManifest.parse("${"0".repeat(64)}  someother.jar")
        val outcome = CodebaseVerifier(TrustStore.of(ca.cert), GestaltTrust.OFFICIAL_SIGNER_PINS).verify("nodigest.jar", jar, unrelated)
        assertTrue(outcome is GateOutcome.Rejected)
        assertFalse((outcome as GateOutcome.Rejected).digestPass)
    }
}
