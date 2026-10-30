/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.credential

import com.gestalt.launcher.AppPaths
import com.sun.jna.platform.win32.Crypt32Util
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.file.Files

/**
 * Windows credential store backed by DPAPI (Crypt32 CryptProtectData/CryptUnprotectData) at
 * current-user scope. The password is encrypted with the user's DPAPI master key; only the encrypted
 * blob is written to disk (under the per-user credentials dir). The blob is not a plaintext secret —
 * it is decryptable only by the same Windows user on the same machine.
 */
class WindowsDpapiCredentialStore : CredentialStore {
    override val name: String = "windows-dpapi"

    private fun blobFile(connectionId: String, username: String) =
        AppPaths.credentialsDir.resolve(CredentialStores.accountKey(connectionId, username) + ".dpapi")

    override fun store(connectionId: String, username: String, password: CharArray) {
        val plain = toBytes(password)
        try {
            val protectedBlob = Crypt32Util.cryptProtectData(plain) // flags=0 => CurrentUser scope
            Files.createDirectories(AppPaths.credentialsDir)
            Files.write(blobFile(connectionId, username), protectedBlob)
        } finally {
            plain.fill(0)
        }
    }

    override fun retrieve(connectionId: String, username: String): CharArray? {
        val f = blobFile(connectionId, username)
        if (!Files.isRegularFile(f)) return null
        val blob = Files.readAllBytes(f)
        val plain = Crypt32Util.cryptUnprotectData(blob)
        try {
            return toChars(plain)
        } finally {
            plain.fill(0)
        }
    }

    override fun delete(connectionId: String, username: String) {
        Files.deleteIfExists(blobFile(connectionId, username))
    }

    override fun exists(connectionId: String, username: String): Boolean = Files.isRegularFile(blobFile(connectionId, username))

    private fun toBytes(chars: CharArray): ByteArray {
        val bb = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val out = ByteArray(bb.remaining())
        bb.get(out)
        if (bb.hasArray()) bb.array().fill(0)
        return out
    }

    private fun toChars(bytes: ByteArray): CharArray {
        val cb = Charsets.UTF_8.decode(ByteBuffer.wrap(bytes))
        val out = CharArray(cb.remaining())
        cb.get(out)
        if (cb.hasArray()) cb.array().fill('\u0000')
        return out
    }
}
