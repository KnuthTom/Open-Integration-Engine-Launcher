/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.trust

import java.security.cert.X509Certificate
import java.time.Instant

/**
 * Rich outcome of verifying a signed jar. Deliberately not a bare boolean: callers
 * (UI, audit log, policy gate) need the signer identity, the validated chain, whether
 * an RFC-3161 timestamp was relied upon, and — on failure — a single clear reason.
 *
 * @property valid             whether the jar passed every check for the active [trustMode].
 * @property signerDN          RFC-2253 subject DN of the signer leaf, if one was found.
 * @property chain             the validated chain, leaf-first, up to and including the trust anchor.
 * @property usedTimestamp     true when a valid RFC-3161 timestamp was found and used as the
 *                             PKIX validity instant (so an expired-now leaf can still validate).
 * @property timestampInstant  the timestamp's genTime, when [usedTimestamp] is true.
 * @property trustMode         the policy under which this result was produced.
 * @property failureReason     human-readable reason the jar was rejected; null on success.
 * @property integrityVerified true once the CMS signature over the .SF verified AND every entry's digest
 *                             matched the signed manifest — i.e. the content is exactly what the signer
 *                             signed — even if a later TRUST step (chain, EKU, pin) then failed. A caller
 *                             may admit such a jar on the strength of an explicit decision about that
 *                             exact signer; a jar with `integrityVerified = false` must never be admitted.
 */
data class VerificationResult(
    val valid: Boolean,
    val signerDN: String?,
    val chain: List<X509Certificate>,
    val usedTimestamp: Boolean,
    val timestampInstant: Instant?,
    val trustMode: TrustMode,
    val failureReason: String?,
    val integrityVerified: Boolean = valid,
) {
    /** Leaf certificate of the validated chain, if any. */
    val leaf: X509Certificate? get() = chain.firstOrNull()

    companion object {
        internal fun success(
            signerDN: String,
            chain: List<X509Certificate>,
            usedTimestamp: Boolean,
            timestampInstant: Instant?,
            trustMode: TrustMode,
        ) = VerificationResult(
            valid = true,
            signerDN = signerDN,
            chain = chain,
            usedTimestamp = usedTimestamp,
            timestampInstant = timestampInstant,
            trustMode = trustMode,
            failureReason = null,
        )

        internal fun failure(
            reason: String,
            trustMode: TrustMode,
            signerDN: String? = null,
            chain: List<X509Certificate> = emptyList(),
            usedTimestamp: Boolean = false,
            timestampInstant: Instant? = null,
            integrityVerified: Boolean = false,
        ) = VerificationResult(
            valid = false,
            signerDN = signerDN,
            chain = chain,
            usedTimestamp = usedTimestamp,
            timestampInstant = timestampInstant,
            trustMode = trustMode,
            failureReason = reason,
            integrityVerified = integrityVerified,
        )
    }
}
