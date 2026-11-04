/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.gate.AdmittedJar
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.JavaHomeMode
import com.gestalt.launcher.net.LocalhostResolver
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds the child-JVM command line that launches the verified client.
 *
 * Default-secure by construction:
 *  - **TLS is modern only** (1.2/1.3) for the child JVM, unless the connection explicitly overrides the
 *    protocol list (an advanced, opt-in per-connection setting).
 *  - The `-Djdk.tls.ephemeralDHKeySize=legacy` downgrade and any custom cipher list are OFF by default;
 *    they are opt-in **per connection**.
 *  - The classpath is assembled ONLY from [AdmittedJar]s — jars that passed the gate. There is no
 *    overload that accepts raw paths.
 *  - Server-provided JVM args from the JNLP are NOT honored wholesale (they could inject `-javaagent` or
 *    TLS-weakening `-D` system properties). The ONE curated exception is the module-encapsulation flags
 *    the modern Mirth client needs to run on Java 9+ — `--add-opens` / `--add-exports` / `--add-modules` /
 *    `--add-reads` — which [curateModuleArgs] whitelists and everything else is dropped. Those flags only
 *    relax module access for the already-verified client; they cannot set properties, load agents, or
 *    weaken TLS, and are applied only on a Java 9+ target (they are invalid on Java 8). Application
 *    arguments are passed through as before.
 *
 * Per-connection runtime settings (Phase 3b) are applied here from the connection's own (trusted, local)
 * config — never from the untrusted server: the selected Java runtime ([Connection.javaHome]), max heap
 * ([Connection.heapSize]), the show-console choice ([Connection.showJavaConsole] → `java` vs `javaw` on
 * Windows), and optional SSL protocol/cipher overrides. A connection left at defaults produces exactly
 * the original default-secure command.
 *
 * **Which Java runs the Administrator.** The client is a Swing application that also needs **JavaFX**
 * (its tag editor is a `JFXPanel`); the `openjfx.jar` the server ships carries the JavaFX classes but
 * no native libraries, so JavaFX must come from the JRE itself — a plain OpenJDK build fails right after
 * login with "no suitable pipeline". The bundled runtimes are therefore JavaFX-bearing JREs. With the
 * runtime left at *Automatic* ([JavaHomeMode.Default]) the version follows the server's own support
 * matrix: **Java 8** for Mirth / OIE up to 4.6 (the client is built for 8, and 8 is what the original
 * launcher ran it on), **Java 17** from 4.7 on (where 17 became the minimum). A bundled runtime that
 * isn't present (a dev run without staging) falls back to the launcher's own JVM, as before.
 */
class ChildJvmCommandBuilder(
    private val javaExe: String = defaultJavaExe(),
    /** Whether the launcher runs on Windows: the desktop scales the Administrator's window (see [build]) and the client runs through its host (see [javaExeIn]). */
    private val windowsDesktop: Boolean = AppPaths.isWindows,
) {
    fun build(
        connection: Connection,
        mainClass: String,
        classpath: List<AdmittedJar>,
        appArgs: List<String> = emptyList(),
        extraSystemProperties: Map<String, String> = emptyMap(),
        serverJvmArgs: List<String> = emptyList(),
        /** The address the launcher's own fetches reached the server on, when the transport could tell. */
        reachedAddress: InetAddress? = null,
        /** The server's version ("4.4.2"), which picks the bundled runtime when the connection says Automatic. */
        serverVersion: String? = null,
    ): List<String> {
        require(classpath.isNotEmpty()) { "refusing to launch with an empty (unverified) classpath" }
        require(mainClass.isNotBlank()) { "refusing to launch without a main class" }

        val javaHome = resolveJavaHome(connection, serverVersion)
        val cmd = mutableListOf(javaExeIn(javaHome, connection))

        // Max heap (-Xmx) — only a well-formed size is honored, as a guard against argument injection.
        connection.heapSize?.trim()?.takeIf { it.matches(HEAP_PATTERN) }?.let { cmd += "-Xmx$it" }

        // A loopback (port-forward) connection: point the client at the SAME loopback literal the launcher
        // just reached the server on. `localhost` is two addresses, and a forward listens on one of them
        // (ssh -L binds both; SSM's plugin and most other tools bind only 127.0.0.1 OR only ::1) — the
        // launcher's fetch fell back across both and found the live one, and the client's own connect must
        // not be stranded on the dead one. Reached over IPv6 → prefer IPv6 so `localhost` resolves to ::1
        // first; reached over IPv4, or unknown → IPv4-only stack (the common case, and what this always
        // did). A genuine remote host (which may be IPv6-only) is left on its default dual stack.
        if (isLoopbackConnection(connection)) {
            cmd += if (reachedAddress is Inet6Address) "-Djava.net.preferIPv6Addresses=true" else "-Djava.net.preferIPv4Stack=true"
        }

        // Modern TLS by default; an explicit per-connection protocol list overrides it.
        val protocols = connection.sslProtocols.takeIf { it.custom && it.value.isNotBlank() }?.value?.trim()
            ?: "TLSv1.2,TLSv1.3"
        cmd += "-Dhttps.protocols=$protocols"
        cmd += "-Djdk.tls.client.protocols=$protocols"

        // Cipher suites — only when the connection explicitly customizes them.
        connection.sslCipherSuites.takeIf { it.custom && it.value.isNotBlank() }?.value?.trim()?.let { ciphers ->
            cmd += "-Dhttps.cipherSuites=$ciphers"
            cmd += "-Djdk.tls.client.cipherSuites=$ciphers"
        }

        // Legacy DH key downgrade — opt-in per connection only.
        if (connection.legacyTlsCompatibility) {
            cmd += "-Djdk.tls.ephemeralDHKeySize=legacy"
        }

        // Display scale is not a setting. On a Windows desktop the Administrator is scaled by Windows: the
        // JVM draws at 100% (`sun.java2d.uiScale=1.0`, the embedded JavaFX pinned to the same — its tag
        // editor is a JFXPanel, and on its own JavaFX would follow the monitor's DPI and sit at 150% inside
        // a 100% window) and the child runs DPI-unaware (LauncherCore sets `__COMPAT_LAYER=DPIUNAWARE`), so
        // the desktop scales the whole window as a bitmap, per monitor, following whichever screen it is
        // on: uniform, correctly sized, slightly soft — the look the Administrator had under the old
        // launcher. Java's own fractional scaling was offered and withdrawn: sharp text, but one-pixel
        // borders and icons come out uneven and the factor never follows the monitor. Elsewhere no factor
        // is set and Java 9+ follows each monitor's DPI itself. A `uiScale` a connection still carries
        // from an older file is ignored.
        val targetMajor = targetMajor(connection, javaHome)
        if (windowsDesktop) {
            cmd += "-Dsun.java2d.uiScale=$WINDOWS_UI_SCALE"
            if (targetMajor >= 9) {
                cmd += "-Dglass.win.uiScale=100%"
                cmd += "-Dglass.gtk.uiScale=100%"
            }
        }
        if (targetMajor >= 9) {
            // Text: subpixel (LCD) antialiasing, pinned. Java 9+ rasterizes with FreeType, and grayscale AA
            // there comes out light and thin; LCD rendering is what the desktop's own text uses and keeps the
            // weight even. The software pipeline rather than Direct3D: D3D composites fractional-scale glyphs
            // and one-pixel lines through the GPU and softens or unevens them — the Swing UI needs no GPU.
            cmd += "-Dawt.useSystemAAFontSettings=lcd"
            cmd += "-Dswing.aatext=true"
            cmd += "-Dsun.java2d.d3d=false"
        }

        extraSystemProperties.forEach { (k, v) -> cmd += "-D$k=$v" }

        // The modern Mirth client needs JPMS module-encapsulation flags to run on Java 9+. Pass ONLY the
        // curated allowlist from the server's JNLP (never -D/-javaagent/-X), and only on a Java 9+ target
        // where the flags are valid — on Java 8 they'd abort the JVM. See [curateModuleArgs] for why safe.
        if (targetMajor >= 9) {
            cmd += curateModuleArgs(serverJvmArgs)
        }

        cmd += "-cp"
        cmd += classpath.joinToString(File.pathSeparator) { it.file.toAbsolutePath().toString() }
        cmd += mainClass
        cmd += appArgs
        return cmd
    }

    /** True when [connection] targets the loopback interface (localhost / 127.0.0.1 / ::1). */
    private fun isLoopbackConnection(connection: Connection): Boolean =
        LocalhostResolver.isLoopbackHost(runCatching { connection.baseUri().host }.getOrNull())

    /**
     * The effective Java home for [connection]: the requested runtime (Default = *Automatic*: the bundled
     * runtime [automaticBundledVersion] picks for [serverVersion], else the launcher's own JVM; Custom =
     * the configured path; Bundled = a shipped runtime if locatable), falling back to the launcher's own
     * JVM when the requested one isn't a usable directory.
     */
    private fun resolveJavaHome(connection: Connection, serverVersion: String?): Path? {
        val defaultHome = Path.of(javaExe).parent?.parent // <home>/bin/java(.exe) -> <home>
        val requestedHome: Path? = when (connection.javaHome.mode) {
            JavaHomeMode.Default -> resolveBundledHome(automaticBundledVersion(serverVersion)) ?: defaultHome
            JavaHomeMode.Custom -> connection.javaHome.customPath?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            JavaHomeMode.Bundled -> resolveBundledHome(connection.javaHome.bundledVersion)
        }
        return requestedHome?.takeIf { runCatching { Files.isDirectory(it) }.getOrDefault(false) } ?: defaultHome
    }

    /**
     * The java binary inside [home] — on Windows the Administrator's own process host when the runtime
     * carries one (`bin/OpenIntegrationEngineAdministrator.exe`, or the `-console` variant when the
     * console is requested: the package builds it into each bundled runtime, so Task Manager names the
     * process "Open Integration Engine Administrator" rather than by the JVM vendor's javaw.exe), else
     * `java.exe` / `javaw.exe`; on other platforms `java`. Falls back to the launcher's own [javaExe].
     */
    private fun javaExeIn(home: Path?, connection: Connection): String {
        val windows = windowsDesktop
        val bin = home?.resolve("bin") ?: return javaExe
        if (windows) {
            val host = bin.resolve(if (connection.showJavaConsole) "$ADMINISTRATOR_HOST-console.exe" else "$ADMINISTRATOR_HOST.exe")
            if (Files.isRegularFile(host) && Files.isRegularFile(bin.resolve("jli.dll"))) return host.toString()
        }
        val binary = when {
            windows && connection.showJavaConsole -> "java.exe"
            windows -> "javaw.exe"
            else -> "java"
        }
        return bin.resolve(binary).toString()
    }

    /**
     * Major version of the JVM that will actually run the client — read from the target home's `release`
     * file, falling back to the connection's declared runtime intent when that can't be read. Used only to
     * gate the Java 9+ module flags (which abort a Java 8 launch).
     */
    private fun targetMajor(connection: Connection, home: Path?): Int {
        home?.let { majorVersionOf(it) }?.let { return it }
        return when (connection.javaHome.mode) {
            JavaHomeMode.Bundled -> connection.javaHome.bundledVersion.filter { it.isDigit() }.toIntOrNull() ?: 17
            JavaHomeMode.Custom -> 17 // unknown custom path; assume a modern JVM
            JavaHomeMode.Default -> parseMajor(System.getProperty("java.specification.version")) ?: 17
        }
    }

    private fun majorVersionOf(home: Path): Int? {
        val release = home.resolve("release")
        val line = runCatching { Files.readAllLines(release) }.getOrDefault(emptyList())
            .firstOrNull { it.startsWith("JAVA_VERSION=") } ?: return null
        return parseMajor(line.substringAfter('='))
    }

    /**
     * Locate a bundled runtime for [version] ("8"/"17").
     *
     *  1. An explicit override — `-Dgestalt.java.<version>.home` / `GESTALT_JAVA_<version>_HOME` —
     *     wins (used for dev/testing without a packaged build).
     *  2. Otherwise the Phase-4 packaged location: `runtimes/jre<version>` under the app resources
     *     directory. Compose/jpackage sets `compose.application.resources.dir` to that directory at
     *     runtime, tracking the launcher's own location — so this resolves correctly both for an
     *     installed app and for the portable app-image that has been moved/unzipped anywhere.
     *
     * Returns null when neither is present, so the caller falls back to the default JVM.
     */
    private fun resolveBundledHome(version: String): Path? =
        bundledRuntimeHome(
            version = version,
            override = System.getProperty("gestalt.java.$version.home")
                ?: System.getenv("GESTALT_JAVA_${version}_HOME"),
            resourcesDir = System.getProperty("compose.application.resources.dir"),
        )

    companion object {
        private val HEAP_PATTERN = Regex("^\\d+[kKmMgG]?$")
        /** The Administrator's process host on Windows (`<name>.exe` / `<name>-console.exe` in a bundled runtime's bin); see [javaExeIn]. */
        const val ADMINISTRATOR_HOST = "OpenIntegrationEngineAdministrator"
        /** The factor the JVM draws at on a Windows desktop, where the desktop scales the window (see [build]). */
        const val WINDOWS_UI_SCALE = "1.0"
        private const val MAX_MODULE_ARGS = 256

        /** The only JVM-arg flags the launcher will accept from a (untrusted) server JNLP. */
        private val ALLOWED_MODULE_FLAGS = setOf("--add-opens", "--add-exports", "--add-modules", "--add-reads")

        /**
         * Filter server-provided JVM args down to the SAFE module-encapsulation flags the modern client
         * needs — [ALLOWED_MODULE_FLAGS], in either `--flag=value` or `--flag value` form. Everything else
         * (`-D…`, `-javaagent…`, `-X…`, `-cp`, bare values, …) is dropped. These flags only relax module
         * access for the already-verified client we chose to run; they cannot set system properties, load
         * agents, or alter TLS — so a malicious server gains nothing through them. Bounded to a sane count
         * as a guard against a pathological JNLP.
         */
        internal fun curateModuleArgs(tokens: List<String>): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            while (i < tokens.size && out.size < MAX_MODULE_ARGS) {
                val t = tokens[i].trim()
                when {
                    t.contains('=') && t.substringBefore('=') in ALLOWED_MODULE_FLAGS -> { out += t; i++ }
                    t in ALLOWED_MODULE_FLAGS -> {
                        val value = tokens.getOrNull(i + 1)
                        if (value != null && !value.startsWith("-")) { out += t; out += value; i += 2 } else i++
                    }
                    else -> i++
                }
            }
            return out
        }

        /**
         * *Automatic* runtime choice: the server's own support matrix. Mirth / OIE up to **4.6** run their
         * client on Java 8 (the minimum they support, and what the original launcher shipped); **4.7**
         * made Java 17 the minimum. Unparseable or missing → 8 (every server in the field today).
         */
        internal fun automaticBundledVersion(serverVersion: String?): String {
            val parts = serverVersion?.trim()?.split('.', '-', '_')?.map { it.toIntOrNull() } ?: return "8"
            val major = parts.getOrNull(0) ?: return "8"
            val minor = parts.getOrNull(1) ?: 0
            return if (major > 4 || (major == 4 && minor >= 7)) "17" else "8"
        }

        /** Major Java version from a version string ("1.8.0_412" -> 8, "17.0.11" -> 17). Null if unparseable. */
        internal fun parseMajor(version: String?): Int? {
            val cleaned = version?.trim()?.trim('"')?.ifBlank { null } ?: return null
            val parts = cleaned.split('.', '_', '-', '+')
            val first = parts.getOrNull(0)?.toIntOrNull() ?: return null
            return if (first == 1) parts.getOrNull(1)?.toIntOrNull() ?: 8 else first
        }

        fun defaultJavaExe(): String {
            val home = System.getProperty("java.home")
            val exe = if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) "java.exe" else "java"
            return Path.of(home, "bin", exe).toString()
        }

        /**
         * Pure bundled-runtime resolution (extracted for testing). An explicit [override] path wins
         * unconditionally (the caller still validates it is a directory). Otherwise, if [resourcesDir]
         * is set (the Compose/jpackage app-resources dir), a `runtimes/jre<version>` directory inside
         * it is used when present. Null means "no bundled runtime — fall back to the default JVM".
         */
        internal fun bundledRuntimeHome(version: String, override: String?, resourcesDir: String?): Path? {
            override?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
            resourcesDir?.takeIf { it.isNotBlank() }?.let {
                val candidate = Path.of(it, "runtimes", "jre$version")
                if (runCatching { Files.isDirectory(candidate) }.getOrDefault(false)) return candidate
            }
            return null
        }
    }
}
