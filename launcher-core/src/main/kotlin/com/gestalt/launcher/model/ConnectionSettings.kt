/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.model

import kotlinx.serialization.Serializable

/**
 * How a connection's [Connection.serverUrl] is composed/edited. This is a UI hint that is remembered
 * across edits; all three modes ultimately write a full `serverUrl` (the base URL).
 *
 *  - [Standard]: host/IP + port → `https://<host>:<port>`.
 *  - [LocalhostPortForward]: host fixed to `localhost`, only the port varies (the SSM-tunnel case).
 *  - [Custom]: an arbitrary full URL (http, a non-standard path, or no explicit port).
 */
@Serializable
enum class ConnectionMode { Standard, LocalhostPortForward, Custom }

/** Which JVM the child Administrator process runs on. */
@Serializable
enum class JavaHomeMode { Default, Bundled, Custom }

/**
 * Per-connection override of the global "close launcher after a connection launches" setting (legacy MCAL
 * "Close window after launching").
 *
 *  - [Inherit]: follow the global setting (the default).
 *  - [On] / [Off]: force the behavior for this connection regardless of the global setting.
 *
 * Either way the close happens only after a SUCCESSFUL launch — never on a launch failure.
 */
@Serializable
enum class CloseAfterLaunch { Inherit, On, Off }

/**
 * Java runtime selection for the child JVM.
 *
 *  - [JavaHomeMode.Default]: *Automatic* — the bundled runtime the server's version calls for (Java 8
 *    for Mirth / OIE up to 4.6, Java 17 from 4.7), falling back to the launcher's own JVM when no
 *    bundled runtime is present.
 *  - [JavaHomeMode.Bundled]: a runtime shipped with the app, identified by [bundledVersion] ("8"|"17");
 *    resolves to the launcher's own JVM if that runtime isn't found.
 *  - [JavaHomeMode.Custom]: the JDK/JRE at [customPath].
 */
@Serializable
data class JavaHomeConfig(
    val mode: JavaHomeMode = JavaHomeMode.Default,
    val bundledVersion: String = "17",
    val customPath: String? = null,
)

/**
 * A "default or custom" SSL setting (protocols or cipher suites). When [custom] is false the child JVM
 * keeps the secure default; when true, [value] (a comma-separated list) overrides it.
 */
@Serializable
data class SslOption(
    val custom: Boolean = false,
    val value: String = "",
)
