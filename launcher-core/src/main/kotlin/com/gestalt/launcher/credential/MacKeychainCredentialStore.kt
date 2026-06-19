/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.credential

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.nio.ByteBuffer
import java.nio.CharBuffer

/**
 * macOS credential store backed by the login Keychain via the native **Security framework**
 * (Keychain Services), bound with JNA — mirroring the Windows DPAPI approach.
 *
 * The secret is passed to `SecKeychainAddGenericPassword` / `…ModifyAttributesAndData` as a byte
 * buffer, never on a command line (so it can't be observed in another process's argv) and never as an
 * immutable `String` (so the plaintext lives only in zeroable buffers). Items are keyed by
 * `service="GestaltCloudConnect"`, `account=<derived account key>`.
 */
class MacKeychainCredentialStore : CredentialStore {
    override val name: String = "macos-keychain"

    private val sec: Security by lazy { Native.load("Security", Security::class.java) }
    private val serviceBytes = SERVICE.toByteArray(Charsets.UTF_8)

    override fun store(connectionId: String, username: String, password: CharArray) {
        val account = accountBytes(connectionId, username)
        val pw = toBytes(password)
        try {
            val existing = PointerByReference()
            val found = sec.SecKeychainFindGenericPassword(
                null, serviceBytes.size, serviceBytes, account.size, account, null, null, existing,
            )
            val status = if (found == ERR_SEC_SUCCESS && existing.value != null) {
                sec.SecKeychainItemModifyAttributesAndData(existing.value, null, pw.size, pw)
            } else {
                sec.SecKeychainAddGenericPassword(
                    null, serviceBytes.size, serviceBytes, account.size, account, pw.size, pw, null,
                )
            }
            check(status == ERR_SEC_SUCCESS) { "Keychain store failed (OSStatus $status)" }
        } finally {
            pw.fill(0)
        }
    }

    override fun retrieve(connectionId: String, username: String): CharArray? {
        val account = accountBytes(connectionId, username)
        val length = IntByReference()
        val data = PointerByReference()
        val status = sec.SecKeychainFindGenericPassword(
            null, serviceBytes.size, serviceBytes, account.size, account, length, data, null,
        )
        if (status != ERR_SEC_SUCCESS || data.value == null) return null
        try {
            val bytes = data.value.getByteArray(0, length.value)
            return try {
                toChars(bytes)
            } finally {
                bytes.fill(0)
            }
        } finally {
            sec.SecKeychainItemFreeContent(null, data.value)
        }
    }

    override fun delete(connectionId: String, username: String) {
        val account = accountBytes(connectionId, username)
        val itemRef = PointerByReference()
        val status = sec.SecKeychainFindGenericPassword(
            null, serviceBytes.size, serviceBytes, account.size, account, null, null, itemRef,
        )
        if (status == ERR_SEC_SUCCESS && itemRef.value != null) {
            sec.SecKeychainItemDelete(itemRef.value)
        }
    }

    private fun accountBytes(connectionId: String, username: String): ByteArray =
        CredentialStores.accountKey(connectionId, username).toByteArray(Charsets.UTF_8)

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
        if (cb.hasArray()) cb.array().fill(' ')
        return out
    }

    /** Minimal JNA binding to the macOS Keychain Services C API. */
    @Suppress("FunctionName")
    private interface Security : Library {
        fun SecKeychainAddGenericPassword(
            keychain: Pointer?, serviceNameLength: Int, serviceName: ByteArray,
            accountNameLength: Int, accountName: ByteArray,
            passwordLength: Int, passwordData: ByteArray, itemRef: PointerByReference?,
        ): Int

        fun SecKeychainFindGenericPassword(
            keychainOrArray: Pointer?, serviceNameLength: Int, serviceName: ByteArray,
            accountNameLength: Int, accountName: ByteArray,
            passwordLength: IntByReference?, passwordData: PointerByReference?, itemRef: PointerByReference?,
        ): Int

        fun SecKeychainItemModifyAttributesAndData(itemRef: Pointer, attrList: Pointer?, length: Int, data: ByteArray): Int
        fun SecKeychainItemDelete(itemRef: Pointer): Int
        fun SecKeychainItemFreeContent(attrList: Pointer?, data: Pointer?): Int
    }

    private companion object {
        const val SERVICE = "GestaltCloudConnect"
        const val ERR_SEC_SUCCESS = 0
    }
}
