/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.gate

import com.gestalt.launcher.testutil.TestArtifacts
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * DN-attribute pin matching, with the regression for a publisher whose org name contains a comma.
 *
 * The official NextGen pin is `O="NEXTGEN HEALTHCARE, INC."`; the real Mirth 4.0.0 signer leaf carries
 * `O=Nextgen Healthcare, Inc.`. These must match (case-insensitively) — but the value was previously
 * extracted via IETFUtils.valueToString, which RFC-2253-escapes the comma to `Nextgen Healthcare\, Inc.`,
 * so the pin silently failed and the launch was rejected as "signer not on the pinned allow-list".
 */
class SignerPinTest {

    private val ca = TestArtifacts.selfSignedCa("CN=Test Issuing CA,O=Test")

    private fun leaf(dn: String) = TestArtifacts.codeSigningLeaf(dn, ca).cert

    @Test
    @DisplayName("a publisher O containing a comma matches a pin on that O (case-insensitive)")
    fun commaInOrgMatches() {
        // Backslash-escaped comma inside the RFC-2253 value -> a single O attribute "Nextgen Healthcare, Inc.".
        val cert = leaf("CN=Nextgen Healthcare\\, Inc.,OU=Information Services,O=Nextgen Healthcare\\, Inc.,L=Irvine,ST=California,C=US")

        assertTrue(SignerPin.of("NextGen", "O" to "NEXTGEN HEALTHCARE, INC.").matches(cert)) {
            "the pinned org (with a comma) must match the leaf's O regardless of case/escaping"
        }
        // And the exact-cased form matches too.
        assertTrue(SignerPin.of("NextGen", "O" to "Nextgen Healthcare, Inc.").matches(cert))
    }

    @Test
    @DisplayName("a non-comma org still matches (no regression for the BrightCodeCompany case)")
    fun simpleOrgStillMatches() {
        val cert = leaf("CN=OIE,O=BrightCodeCompany OÜ,C=EE")
        assertTrue(SignerPin.of("OIE", "O" to "BrightCodeCompany OÜ").matches(cert))
    }

    @Test
    @DisplayName("a different org does not match (matching is not loosened)")
    fun differentOrgDoesNotMatch() {
        val cert = leaf("CN=Nextgen Healthcare\\, Inc.,O=Nextgen Healthcare\\, Inc.,C=US")
        assertFalse(SignerPin.of("Imposter", "O" to "Someone Else, LLC").matches(cert))
        // All required attributes must be present: a CN requirement the leaf doesn't satisfy fails.
        assertFalse(SignerPin.of("NextGen+CN", "O" to "Nextgen Healthcare, Inc.", "CN" to "Different CN").matches(cert))
    }
}
