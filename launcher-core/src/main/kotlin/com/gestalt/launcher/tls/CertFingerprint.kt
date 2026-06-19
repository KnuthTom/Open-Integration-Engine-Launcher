/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tls

import java.security.MessageDigest
import java.security.cert.X509Certificate

/** SHA-256 certificate fingerprints, in the canonical lowercase-hex, no-separator form used for pins. */
object CertFingerprint {

    fun sha256Hex(cert: X509Certificate): String = sha256Hex(cert.encoded)

    fun sha256Hex(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    /** Canonicalize a user-entered or stored pin so `AA:BB ...` and `aabb...` compare equal. */
    fun normalize(pin: String): String =
        pin.lowercase().replace(":", "").replace(" ", "").trim()
}
