/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.gate

import com.gestalt.launcher.digest.DigestManifest
import com.gestalt.launcher.testutil.TestArtifacts
import com.gestalt.trust.TrustMode
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reuses the Phase-0 genuine OIE jar fixture (gitignored). The full gate — digest + signature +
 * PIN — must admit it with the BrightCodeCompany publisher pinned and the RFC-3161 timestamp used.
 * Skips cleanly when the fixture is absent.
 */
class OieFixtureGateTest {

    private fun oieJar(): Path? = listOf(
        Path.of("../core-trust/src/test/resources/fixtures/oie-mirth-client-core.jar"),
        Path.of("core-trust/src/test/resources/fixtures/oie-mirth-client-core.jar"),
    ).firstOrNull { Files.isRegularFile(it) }

    @Test
    @DisplayName("genuine OIE jar PASSES the full gate in PIN mode (BrightCodeCompany pinned, timestamped)")
    fun genuineOieJarPassesPinned() {
        val jar = oieJar()
        assumeTrue(jar != null) { "SKIP: genuine OIE jar fixture not present (see core-trust fixtures README)" }

        val manifest = DigestManifest.parse(TestArtifacts.digestManifestText("oie-mirth-client-core.jar" to jar!!))
        val gate = CodebaseVerifier(GestaltTrust.publicRoots(), GestaltTrust.OFFICIAL_SIGNER_PINS, TrustMode.PIN_KNOWN_SIGNERS)
        val outcome = gate.verify("oie-mirth-client-core.jar", jar, manifest)

        assertTrue(outcome is GateOutcome.Admitted) { (outcome as? GateOutcome.Rejected)?.reason ?: "" }
        outcome as GateOutcome.Admitted
        assertTrue(outcome.jar.signerDN.contains("BrightCodeCompany")) { outcome.jar.signerDN }
        assertTrue(outcome.jar.usedTimestamp) { "expected the RFC-3161 timestamp to be used" }
    }
}
