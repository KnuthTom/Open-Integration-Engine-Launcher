/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.gate

import com.gestalt.launcher.audit.AuditLog
import com.gestalt.launcher.digest.DigestManifest
import com.gestalt.launcher.digest.DigestVerifier
import com.gestalt.launcher.tls.CertTrustStatus
import com.gestalt.trust.JarSignatureVerifier
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import com.gestalt.trust.VerificationResult
import com.gestalt.trust.VerifierConfig
import java.nio.file.Path
import java.security.cert.X509Certificate

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
        /**
         * When non-null, the jar's content is exactly what this signer signed — only the TRUST in the
         * signer is missing (unknown chain / no codeSigning EKU / not on the pinned allow-list). The
         * caller may offer a review of this publisher; a rejection with `null` here is a hard stop.
         */
        val publisherToReview: X509Certificate? = null,
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
    /**
     * The user's decisions about publishers (trust on first use for code signers). A TRUSTED signer is
     * admitted by exact fingerprint once the jar's integrity is proven; a REJECTED one fails closed; an
     * unknown one is reported for review via [GateOutcome.Rejected.publisherToReview]. Null = no such
     * store (pure pin/anchor policy, as in unit tests of the pins themselves).
     */
    private val trustedSigners: TrustedSignerStore? = null,
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

        // (b) SIGNATURE — fail closed on any integrity failure. A TRUST failure (chain / EKU / pin) on a
        //     jar whose integrity IS proven is decided by the publisher store: trusted → admit,
        //     rejected → fail closed, unknown → reject with the signer attached for review.
        val result = signatureVerifier.verify(file.toFile())
        val leaf = result.leaf
        val decision = if (result.integrityVerified && leaf != null) trustedSigners?.statusOf(leaf) else null
        if (!result.valid) {
            if (decision == CertTrustStatus.TRUSTED) return admitTrustedPublisher(artifactName, file, result, actual)
            val reviewable = result.integrityVerified && leaf != null && decision != CertTrustStatus.REJECTED
            val reason = if (decision == CertTrustStatus.REJECTED) "publisher was rejected by the user: ${result.signerDN}"
                else result.failureReason ?: "signature verification failed"
            return reject(artifactName, reason, true, false, result.signerDN, publisherToReview = if (reviewable) leaf else null)
        }

        // (b2) PUBLISHER PIN — DN-attribute allow-list unless CA_ROOT; a publisher decision overrides it.
        if (mode == TrustMode.PIN_KNOWN_SIGNERS) {
            val matched = leaf != null && signerPins.any { it.matches(leaf) }
            if (!matched) {
                if (decision == CertTrustStatus.TRUSTED) return admitTrustedPublisher(artifactName, file, result, actual)
                if (decision == CertTrustStatus.REJECTED) {
                    return reject(artifactName, "publisher was rejected by the user: ${result.signerDN}", true, false, result.signerDN)
                }
                return reject(
                    artifactName, "signer is not on the pinned allow-list: ${result.signerDN}", true, false, result.signerDN,
                    publisherToReview = leaf,
                )
            }
        }

        // ADMIT.
        AuditLog.jarVerified(connectionId, artifactName, result.signerDN, true, true, admitted = true)
        AuditLog.signatureEnforcement(mode.name, result.signerDN, "ACCEPT")
        return GateOutcome.Admitted(
            AdmittedJarImpl(artifactName, file, result.signerDN ?: "-", actual, result.usedTimestamp),
        )
    }

    /** Admit on the strength of an explicit, audited decision about this exact signer (integrity already proven). */
    private fun admitTrustedPublisher(artifactName: String, file: Path, result: VerificationResult, sha256: String): GateOutcome {
        AuditLog.jarVerified(connectionId, artifactName, result.signerDN, true, true, admitted = true)
        AuditLog.signatureEnforcement("TRUSTED_PUBLISHER", result.signerDN, "ACCEPT")
        return GateOutcome.Admitted(AdmittedJarImpl(artifactName, file, result.signerDN ?: "-", sha256, result.usedTimestamp))
    }

    private fun reject(
        artifactName: String,
        reason: String,
        digestPass: Boolean,
        signaturePass: Boolean,
        signerDN: String?,
        publisherToReview: X509Certificate? = null,
    ): GateOutcome.Rejected {
        AuditLog.jarVerified(connectionId, artifactName, signerDN, digestPass, signaturePass, admitted = false, reason = reason)
        if (digestPass) AuditLog.signatureEnforcement(mode.name, signerDN, "REJECT")
        return GateOutcome.Rejected(artifactName, reason, digestPass, signaturePass, signerDN, publisherToReview)
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
