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
 * Trusting a server certificate is a deliberate, audited action. These helpers record the decision in
 * the shared [TrustedCertStore] and write the corresponding `servercert.*` audit record. Nothing here
 * happens automatically — the caller (UI) invokes it only after a human has reviewed the fingerprint
 * obtained from [ServerCertProbe] (auto-approve is the launch pipeline's own, separately audited path).
 *
 * `pin`/`revoke` keep their names and the [ConnectionStore] parameter for source compatibility with the
 * old per-connection pin model; they no longer write anything onto the connection.
 */
object ServerCertPinning {

    fun fingerprint(cert: X509Certificate): String = CertFingerprint.sha256Hex(cert)

    /** Trust [cert] for [connection] (recorded in the shared store). Returns the connection unchanged. */
    fun pin(
        store: ConnectionStore,
        connection: Connection,
        cert: X509Certificate,
        persist: Boolean = true,
        trusted: TrustedCertStore = TrustedCertStore.shared,
    ): Connection {
        trusted.trust(cert, connection.hostPort, connection.id)
        AuditLog.serverCertPinned(hostOf(connection), fingerprint(cert))
        return connection
    }

    fun trust(connection: Connection, cert: X509Certificate) {
        AuditLog.serverCertTrusted(hostOf(connection), cert.subjectX500Principal.name)
    }

    /**
     * Withdraw trust for the certificates this connection introduced: entries no other connection uses
     * are removed (they will prompt again); shared ones just lose this connection's tag. Also clears a
     * legacy pin if one is still on the connection.
     */
    fun revoke(
        store: ConnectionStore,
        connection: Connection,
        persist: Boolean = true,
        trusted: TrustedCertStore = TrustedCertStore.shared,
    ): Connection {
        trusted.list().filter { connection.id in it.connectionIds && it.connectionIds.size == 1 }.forEach { trusted.remove(it.sha256) }
        trusted.forgetConnection(connection.id)
        val updated = if (connection.isPinned) connection.copy(pinnedCertSha256 = null).also { store.update(it, persist) } else connection
        AuditLog.serverCertRevoked(hostOf(connection))
        return updated
    }

    private fun hostOf(connection: Connection): String =
        runCatching { connection.baseUri().host }.getOrNull() ?: connection.serverUrl
}
