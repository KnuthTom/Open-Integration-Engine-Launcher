/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.trust

import javax.security.auth.x500.X500Principal

/**
 * Configuration for [JarSignatureVerifier].
 *
 * @property trustMode        which acceptance policy to apply (default [TrustMode.PIN_KNOWN_SIGNERS]).
 * @property pinnedSubjectDNs allow-list of signer-leaf subject DNs (RFC-2253). Required and only
 *                            consulted when [trustMode] is [TrustMode.PIN_KNOWN_SIGNERS]. Matching
 *                            is by canonical X.500 equality, so attribute ordering / spacing /
 *                            case in the configured string do not matter.
 */
data class VerifierConfig(
    val trustMode: TrustMode = TrustMode.PIN_KNOWN_SIGNERS,
    val pinnedSubjectDNs: Set<String> = emptySet(),
) {
    /** Parsed form of [pinnedSubjectDNs], for canonical comparison against a leaf's principal. */
    val pinnedPrincipals: Set<X500Principal> by lazy {
        pinnedSubjectDNs.map { X500Principal(it) }.toSet()
    }

    init {
        require(trustMode != TrustMode.PIN_KNOWN_SIGNERS || pinnedSubjectDNs.isNotEmpty()) {
            "PIN_KNOWN_SIGNERS requires at least one entry in pinnedSubjectDNs"
        }
    }

    companion object {
        /** Convenience for the common case: pin one publisher. */
        fun pinning(vararg subjectDNs: String) =
            VerifierConfig(TrustMode.PIN_KNOWN_SIGNERS, subjectDNs.toSet())

        /** Convenience: accept anything chaining to a trusted anchor with the codeSigning EKU. */
        fun caRoot() = VerifierConfig(TrustMode.CA_ROOT, emptySet())
    }
}
