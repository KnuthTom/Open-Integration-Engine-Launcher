/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tls

import com.gestalt.launcher.audit.AuditLog
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionStore
import java.security.cert.X509Certificate

/**
 * Pinning a server certificate is a deliberate, audited action. These helpers persist the pin onto a
 * [Connection] and write the corresponding `servercert.*` audit record. Nothing here happens
 * automatically — the caller (UI) invokes it only after a human has reviewed the fingerprint
 * obtained from [ServerCertProbe].
 */
object ServerCertPinning {

    fun fingerprint(cert: X509Certificate): String = CertFingerprint.sha256Hex(cert)

    /**
     * [persist] mirrors [ConnectionStore.update]: `true` (default) writes `connections.json` synchronously;
     * the UI passes `false` and commits through its single debounced save path instead of writing on
     * the UI thread. The audit record is written either way.
     */
    fun pin(store: ConnectionStore, connection: Connection, cert: X509Certificate, persist: Boolean = true): Connection {
        val fp = fingerprint(cert)
        val updated = connection.copy(pinnedCertSha256 = fp)
        store.update(updated, persist)
        AuditLog.serverCertPinned(hostOf(connection), fp)
        return updated
    }

    fun trust(connection: Connection, cert: X509Certificate) {
        AuditLog.serverCertTrusted(hostOf(connection), cert.subjectX500Principal.name)
    }

    fun revoke(store: ConnectionStore, connection: Connection, persist: Boolean = true): Connection {
        val updated = connection.copy(pinnedCertSha256 = null)
        store.update(updated, persist)
        AuditLog.serverCertRevoked(hostOf(connection))
        return updated
    }

    private fun hostOf(connection: Connection): String =
        runCatching { connection.baseUri().host }.getOrNull() ?: connection.serverUrl
}
