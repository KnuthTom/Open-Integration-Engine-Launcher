/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.credential

import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.audit.AuditLog
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The single path through which credentials are read and written. A password NEVER touches
 * `connections.json`, any other config file, or any log — only the platform-backed implementations
 * below (Windows DPAPI, macOS Keychain, Linux libsecret) ever hold it, and only the per-user OS
 * secret store retains it at rest.
 *
 * Passwords are passed as [CharArray] so the caller can zero them after use; implementations must not
 * retain references and should clear any intermediate byte buffers.
 */
interface CredentialStore {
    /** Short identifier of the backing store, for audit records (never the secret). */
    val name: String

    fun store(connectionId: String, username: String, password: CharArray)

    /** Returns the stored password, or null if none. Caller should zero the array when done. */
    fun retrieve(connectionId: String, username: String): CharArray?

    fun delete(connectionId: String, username: String)

    /**
     * Whether a password is stored, **without reading it** where the platform allows (the UI asks this
     * when a connection is merely selected; a read on macOS can raise the Keychain's access prompt). The
     * default reads and discards.
     */
    fun exists(connectionId: String, username: String): Boolean =
        retrieve(connectionId, username)?.also { it.fill('\u0000') } != null
}

/**
 * Wraps a real [CredentialStore] and writes an audit record for every access — the **event only**,
 * never the value. All factory-built stores are wrapped in this, so credential access is always
 * accounted for in `audit.log`.
 */
class AuditingCredentialStore(private val delegate: CredentialStore) : CredentialStore {
    override val name: String get() = delegate.name

    override fun store(connectionId: String, username: String, password: CharArray) {
        delegate.store(connectionId, username, password)
        AuditLog.credentialAccessed(connectionId, "store", delegate.name)
    }

    override fun retrieve(connectionId: String, username: String): CharArray? {
        val v = delegate.retrieve(connectionId, username)
        AuditLog.credentialAccessed(connectionId, if (v != null) "retrieve" else "retrieve-miss", delegate.name)
        return v
    }

    override fun delete(connectionId: String, username: String) {
        delegate.delete(connectionId, username)
        AuditLog.credentialAccessed(connectionId, "delete", delegate.name)
    }

    /** An existence check reads no secret, so it is not a credential access and is not audited. */
    override fun exists(connectionId: String, username: String): Boolean = delegate.exists(connectionId, username)
}

/** In-memory store for tests. Never persists. */
class InMemoryCredentialStore : CredentialStore {
    override val name: String = "memory"
    private val map = ConcurrentHashMap<String, CharArray>()

    private fun key(connectionId: String, username: String) = "$connectionId\u0000$username"

    override fun store(connectionId: String, username: String, password: CharArray) {
        map[key(connectionId, username)] = password.copyOf()
    }

    override fun retrieve(connectionId: String, username: String): CharArray? =
        map[key(connectionId, username)]?.copyOf()

    override fun delete(connectionId: String, username: String) {
        map.remove(key(connectionId, username))?.fill('\u0000')
    }
}

object CredentialStores {
    /** The OS-appropriate, audit-wrapped credential store. */
    fun forCurrentOs(): CredentialStore = AuditingCredentialStore(
        when {
            AppPaths.isWindows -> WindowsDpapiCredentialStore()
            AppPaths.isMac -> MacKeychainCredentialStore()
            else -> LinuxSecretCredentialStore()
        },
    )

    /** Stable, filesystem-/service-safe account key derived from connection id + username. */
    internal fun accountKey(connectionId: String, username: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest("$connectionId\u0000$username".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
