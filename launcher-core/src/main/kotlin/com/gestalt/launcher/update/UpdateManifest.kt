/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A single downloadable artifact for one platform: its [url] and the signed [sha256] it must match. */
@Serializable
data class ArtifactRef(val url: String, val sha256: String)

/**
 * The signed update manifest published at `<channel>/latest.json`.
 *
 * @property version the offered version (compared against the running version; only strictly-newer applies).
 * @property notes human-readable release notes (shown to the user; covered by the signature).
 * @property perOS one [ArtifactRef] per `"os-arch"` key (e.g. `"windows-x64"` — the same token the
 *   packaging build uses).
 * @property signature base64 Ed25519 signature over the canonical encoding of every other field
 *   ([UpdateCrypto.signableBytes]); verified against a pinned public key before anything is trusted.
 */
@Serializable
data class UpdateManifest(
    val version: String,
    val notes: String = "",
    val perOS: Map<String, ArtifactRef> = emptyMap(),
    val signature: String = "",
) {
    companion object {
        private val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true   // tolerate forward-compatible extra fields
            encodeDefaults = true
        }

        fun parse(text: String): UpdateManifest = JSON.decodeFromString(serializer(), text)
        fun render(manifest: UpdateManifest): String = JSON.encodeToString(serializer(), manifest)
    }
}
