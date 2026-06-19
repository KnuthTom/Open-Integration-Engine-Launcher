/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.update

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec

/** Guards the pinned update key(s): a botched base64 paste would silently disable update verification. */
class UpdateKeysTest {

    @Test
    @DisplayName("at least one update public key is pinned and each is a usable Ed25519 key")
    fun pinnedKeysAreValid() {
        assertTrue(UpdateKeys.pinned.isNotEmpty(), "no pinned update key — update verification would always fail")
        UpdateKeys.pinned.forEach { spki ->
            assertDoesNotThrow {
                KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
            }
        }
    }
}
