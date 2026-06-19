/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.audit

import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.credential.CredentialStores
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * The audit trail is written to logs/audit.log and records events (including credential access) but
 * NEVER a secret value. (The test JVM routes the per-user dir under build/ via -Dgestalt.home.)
 */
class AuditLogTest {

    @Test
    @DisplayName("audit.log is populated with structured events and contains no secret values")
    fun auditLogPopulatedNoSecrets() {
        val secret = "audit-secret-xyz"
        val creds = CredentialStores.forCurrentOs() // audited wrapper around the OS store
        creds.store("audit-conn", "dave", secret.toCharArray())
        creds.retrieve("audit-conn", "dave")
        creds.delete("audit-conn", "dave")
        AuditLog.event("test.marker", "k" to "v")

        val logFile = AppPaths.logDir.resolve("audit.log")
        assertTrue(Files.isRegularFile(logFile)) { "audit.log should exist at $logFile" }
        val text = Files.readString(logFile)

        assertTrue(text.contains("event=credential.access")) { "credential access must be audited" }
        assertTrue(text.contains("event=test.marker"))
        assertFalse(text.contains(secret)) { "the audit log must never contain the secret value" }
    }
}
