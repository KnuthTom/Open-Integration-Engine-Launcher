/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher

import com.gestalt.launcher.gate.GestaltTrust
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Pins the Gestalt Cloud Connect rename so a regression to the old "Gestalt Command" name fails CI. */
class RenameTest {

    @Test
    @DisplayName("per-user data dir is named GestaltCloudConnect")
    fun dataDirRenamed() {
        assertEquals("GestaltCloudConnect", AppPaths.APP_DIR_NAME)
    }

    @Test
    @DisplayName("the Gestalt publisher pin uses the new code-signing DN")
    fun signerPinRenamed() {
        val pin = GestaltTrust.OFFICIAL_SIGNER_PINS.firstOrNull { it.label == "Gestalt Cloud Connect" }
        assertTrue(pin != null) { "expected a 'Gestalt Cloud Connect' publisher pin" }
        assertEquals("Gestalt Cloud Connect Code Signing", pin!!.requiredRdns["CN"])
        assertEquals("Gestalt Diagnostics", pin.requiredRdns["O"])

        // The old branding must be gone from the allow-list.
        assertTrue(GestaltTrust.OFFICIAL_SIGNER_PINS.none { it.label == "Gestalt Command" })
    }
}
