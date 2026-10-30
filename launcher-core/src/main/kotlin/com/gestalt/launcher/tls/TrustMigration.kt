/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tls

import com.gestalt.launcher.audit.AuditLog
import com.gestalt.launcher.model.ConnectionStore

/**
 * One-time move from the old trust model (an exact-leaf pin stored ON each connection) to the shared
 * [TrustedCertStore]: every `pinnedCertSha256` becomes a TRUSTED fingerprint in the store, tagged with
 * the connection and its host, and the pin field is cleared. Idempotent — a connection without a pin is
 * untouched, so running it on every start is safe.
 */
object TrustMigration {

    /** Returns the number of pins migrated. */
    fun migrateLegacyPins(store: ConnectionStore, trusted: TrustedCertStore): Int {
        val pinned = store.list().filter { it.isPinned }
        if (pinned.isEmpty()) return 0
        pinned.forEach { conn ->
            val fp = conn.normalizedPin ?: return@forEach
            val host = runCatching { conn.baseUri() }.getOrNull()?.let { u ->
                u.host?.let { h -> "$h:${if (u.port > 0) u.port else 443}" }
            }
            trusted.trustFingerprint(fp, host, conn.id)
            store.update(conn.copy(pinnedCertSha256 = null), persist = false)
        }
        store.save()
        AuditLog.event("servercert.migrate", "count" to pinned.size, "result" to "PINS_MOVED_TO_TRUST_STORE")
        return pinned.size
    }
}
