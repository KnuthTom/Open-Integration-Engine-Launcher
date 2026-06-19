/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.jnlp

/**
 * A single jar referenced by the JNLP.
 *
 * @property sha256 the optional inline integrity digest the server publishes for this jar. OIE/Mirth set
 *   it as a base64 SHA-256 attribute (`<jar … sha256="…"/>`); null when absent. It is the integrity
 *   source the launcher prefers — see `LauncherCore` — because a real OIE/Mirth server carries the
 *   per-jar hash here rather than at a separate `/digest` endpoint.
 */
data class JnlpJar(val href: String, val main: Boolean, val sha256: String? = null)

/**
 * The **safe** projection of a server JNLP. The JNLP is untrusted: only these fields are ever read
 * from it, and none of them can carry a security decision. In particular there is deliberately no
 * field for "allow self-signed", "allow incorrect digest", or any trust toggle — such directives, if
 * present in the document, are detected and surfaced in [ignoredDirectives] for the audit trail but
 * have no effect whatsoever.
 *
 * @property untrustedJvmArgs JVM args the server *requested*. They are recorded for transparency but
 *   the launcher does NOT pass them to the child JVM (a server must not be able to inject
 *   `-javaagent`, TLS-weakening system properties, etc.). See `ChildJvmCommandBuilder`.
 */
data class JnlpDescriptor(
    val codebase: String?,
    val mainClass: String?,
    val version: String?,
    val jars: List<JnlpJar>,
    val arguments: List<String>,
    val untrustedJvmArgs: List<String>,
    val ignoredDirectives: List<String>,
) {
    val mainJar: JnlpJar? get() = jars.firstOrNull { it.main }
}
