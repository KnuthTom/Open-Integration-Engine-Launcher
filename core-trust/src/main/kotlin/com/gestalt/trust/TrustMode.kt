/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.trust

/**
 * Policy controlling which signer leaf certificates are acceptable, once a chain to a
 * trusted anchor has been established.
 */
enum class TrustMode {
    /**
     * Accept a signed jar only if the signer leaf's subject DN is on a configured
     * allow-list **and** the leaf chains to a trusted anchor. This is the default:
     * chaining to a public CA root is necessary but not sufficient — we additionally
     * pin the specific publisher so that any other certificate issued by the same CA
     * (to anyone) is rejected.
     */
    PIN_KNOWN_SIGNERS,

    /**
     * Accept any signer leaf that chains to a trusted anchor and carries the codeSigning
     * EKU. Useful when the trust anchor is itself private (e.g. the Gestalt self-signed
     * cert) and the leaf identity is therefore implicitly constrained.
     */
    CA_ROOT,
}
