/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.gate

import com.gestalt.launcher.audit.AuditLog
import com.gestalt.launcher.digest.DigestManifest
import com.gestalt.launcher.digest.DigestVerifier
import com.gestalt.trust.JarSignatureVerifier
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import com.gestalt.trust.VerifierConfig
import java.nio.file.Path

/**
 * A jar that has passed the [CodebaseVerifier] gate and may therefore join the launch classpath.
 *
 * This is a **sealed** type whose only implementation is private to [CodebaseVerifier]. Nothing else
 * in the program — and nothing in another module — can construct one. Because the child-JVM
 * classpath is assembled only from `AdmittedJar`s, there is, by construction, no code path that adds
 * a jar to the classpath without passing the gate.
 */
sealed interface AdmittedJar {
    val artifactName: String
    val file: Path
    val signerDN: String
    val sha256: String
    val usedTimestamp: Boolean
}

/** Outcome of running a single jar through the gate. */
sealed interface GateOutcome {
    data class Admitted(val jar: AdmittedJar) : GateOutcome
    data class Rejected(
        val artifactName: String,
        val reason: String,
        val digestPass: Boolean,
        val signaturePass: Boolean,
        val signerDN: String?,
    ) : GateOutcome
}

/**
 * THE single verification chokepoint. Every downloaded jar must pass [verify] before it can become an
 * [AdmittedJar]. The checks run in order and **fail closed** — any miss aborts admission:
 *
 *   (a) digest check    — SHA-256 of the file vs the server `/digest/` manifest
 *   (b) signature check — the Phase-0 [JarSignatureVerifier] (integrity + chain-to-trusted-root +
 *                         codeSigning EKU + RFC-3161 timestamp awareness)
 *   (b2) publisher pin  — in [TrustMode.PIN_KNOWN_SIGNERS] (default) the signer leaf must match one of
 *                         the [signerPins] (DN-attribute match). [TrustMode.CA_ROOT] skips this and
 *                         accepts any leaf chaining to a trusted root.
 *
 * The signature engine is run in CA_ROOT mode and the DN-attribute pin is applied here, so renewals
 * (new serial/CN, same organization) still match — see [SignerPin].
 */
class CodebaseVerifier(
    private val trustStore: TrustStore,
    private val signerPins: List<SignerPin>,
    private val mode: TrustMode = TrustMode.PIN_KNOWN_SIGNERS,
    private val connectionId: String = "-",
) {
    private val signatureVerifier = JarSignatureVerifier(trustStore, VerifierConfig.caRoot())

    init {
        require(mode == TrustMode.CA_ROOT || signerPins.isNotEmpty()) {
            "PIN_KNOWN_SIGNERS requires a non-empty signer-pin allow-list"
        }
    }

    fun verify(artifactName: String, file: Path, manifest: DigestManifest): GateOutcome {
        // (a) DIGEST — fail closed.
        val expected = manifest.expectedFor(artifactName)
            ?: return reject(artifactName, "no digest in manifest for '$artifactName'", false, false, null)
        val actual = DigestVerifier.sha256Hex(file)
        if (!DigestVerifier.constantTimeEquals(actual, expected)) {
            return reject(artifactName, "digest mismatch (expected $expected, got $actual)", false, false, null)
        }

        // (b) SIGNATURE — fail closed.
        val result = signatureVerifier.verify(file.toFile())
        if (!result.valid) {
            return reject(artifactName, result.failureReason ?: "signature verification failed", true, false, result.signerDN)
        }

        // (b2) PUBLISHER PIN — DN-attribute allow-list unless CA_ROOT.
        if (mode == TrustMode.PIN_KNOWN_SIGNERS) {
            val leaf = result.leaf
            val matched = leaf != null && signerPins.any { it.matches(leaf) }
            if (!matched) {
                return reject(artifactName, "signer is not on the pinned allow-list: ${result.signerDN}", true, false, result.signerDN)
            }
        }

        // ADMIT.
        AuditLog.jarVerified(connectionId, artifactName, result.signerDN, true, true, admitted = true)
        AuditLog.signatureEnforcement(mode.name, result.signerDN, "ACCEPT")
        return GateOutcome.Admitted(
            AdmittedJarImpl(artifactName, file, result.signerDN ?: "-", actual, result.usedTimestamp),
        )
    }

    private fun reject(
        artifactName: String,
        reason: String,
        digestPass: Boolean,
        signaturePass: Boolean,
        signerDN: String?,
    ): GateOutcome.Rejected {
        AuditLog.jarVerified(connectionId, artifactName, signerDN, digestPass, signaturePass, admitted = false, reason = reason)
        if (digestPass) AuditLog.signatureEnforcement(mode.name, signerDN, "REJECT")
        return GateOutcome.Rejected(artifactName, reason, digestPass, signaturePass, signerDN)
    }

    /** The ONLY implementation of [AdmittedJar]; private, so only this gate can mint one. */
    private class AdmittedJarImpl(
        override val artifactName: String,
        override val file: Path,
        override val signerDN: String,
        override val sha256: String,
        override val usedTimestamp: Boolean,
    ) : AdmittedJar
}
