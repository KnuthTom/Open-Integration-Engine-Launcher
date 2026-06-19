/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.digest

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Computes and compares SHA-256 digests. Comparison is constant-time via [MessageDigest.isEqual]. */
object DigestVerifier {

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256Hex(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    /** True iff [file]'s SHA-256 equals [expectedHex] (case-insensitive hex), compared constant-time. */
    fun matches(file: Path, expectedHex: String): Boolean =
        constantTimeEquals(sha256Hex(file), expectedHex.lowercase().trim())

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
