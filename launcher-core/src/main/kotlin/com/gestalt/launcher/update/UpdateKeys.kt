/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.update

import java.util.Base64

/**
 * The pinned Gestalt **update** public key(s). A manifest is trusted only if its signature verifies
 * against one of these (see [UpdateCrypto.verify]). This is independent of the Layer-2 jar-signing
 * trust anchors — updates have their own key.
 *
 * The list supports **rotation**: ship an app update that ADDS the next public key here BEFORE
 * switching the release signer to the new private key, so clients on the old build still accept
 * manifests signed by either key during the transition. The matching private key is a Gestalt release
 * secret and never lives in this repo (see docs/UPDATES.md).
 */
object UpdateKeys {
    // Dev/self-signed update identity. Production keys are added/swapped here via a normal app update.
    private val PINNED_BASE64: List<String> = listOf(
        "MCowBQYDK2VwAyEAj7FDGUabwIdyi1xroMxpgrKqTIgISY2y8vrmNfhsMJ4=",
    )

    val pinned: List<ByteArray> by lazy {
        PINNED_BASE64.mapNotNull { runCatching { Base64.getDecoder().decode(it.trim()) }.getOrNull() }
    }
}
