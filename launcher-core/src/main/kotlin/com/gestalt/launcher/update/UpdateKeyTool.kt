/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.update

import java.io.File
import java.util.Base64

/**
 * Generate a Gestalt **update** Ed25519 keypair (dev identity, or a production key to be injected via
 * secrets). Writes:
 *  - `<out-dir>/update-private.b64` — PKCS#8 private key. **SECRET** — keep out of the repo (the repo's
 *    `tools/out/` is gitignored). Feed this to [UpdateManifestTool] `--key`.
 *  - `<out-dir>/update-public.b64` — X.509 public key. Pin it in [UpdateKeys].
 */
object UpdateKeyTool {
    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(args.getOrNull(0) ?: "tools/out").apply { mkdirs() }
        val (privateKey, publicKey) = UpdateCrypto.generateKeyPair()
        val publicB64 = Base64.getEncoder().encodeToString(publicKey)
        File(outDir, "update-private.b64").writeText(Base64.getEncoder().encodeToString(privateKey))
        File(outDir, "update-public.b64").writeText(publicB64)
        println("Private key (SECRET): ${File(outDir, "update-private.b64").absolutePath}")
        println("Public key  (pin in UpdateKeys): $publicB64")
    }
}
