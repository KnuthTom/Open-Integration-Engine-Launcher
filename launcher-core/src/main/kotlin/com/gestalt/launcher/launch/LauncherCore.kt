/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.audit.AuditLog
import com.gestalt.launcher.digest.DigestManifest
import com.gestalt.launcher.digest.DigestVerifier
import com.gestalt.launcher.gate.AdmittedJar
import com.gestalt.launcher.gate.CodebaseVerifier
import com.gestalt.launcher.gate.GateOutcome
import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.gate.SignerPin
import com.gestalt.launcher.jnlp.JnlpDescriptor
import com.gestalt.launcher.jnlp.JnlpParser
import com.gestalt.launcher.net.ArtifactFetcher
import com.gestalt.launcher.net.HttpArtifactFetcher
import com.gestalt.launcher.net.LocalhostResolver
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.tls.CertFingerprint
import com.gestalt.launcher.tls.HttpClientFactory
import com.gestalt.launcher.tls.ServerCertProbe
import com.gestalt.launcher.tls.ServerTrustStore
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate

/** Raised whenever any verification step fails. The launch is aborted; nothing is executed. */
class LaunchAbortedException(message: String) : Exception(message)

/**
 * Coarse, observational launch phases for UI progress. Purely informational — emitting them does not
 * change any verification logic or control flow (the pipeline stays fail-closed regardless).
 */
enum class LaunchPhase { Downloading, Verifying, Launching, Running, Failed }

/** The result of preparing a launch: the verified jars and the exact child-JVM command to run. */
data class LaunchPlan(
    val connection: Connection,
    val version: String,
    val mainClass: String,
    val admitted: List<AdmittedJar>,
    val appArgs: List<String>,
    val command: List<String>,
)

/**
 * One jar the plan will download and gate. [artifactName] is the jar's path relative to the server root
 * (`webstart/extensions/libs/foo/common.jar`), NOT its basename: two Mirth extensions routinely ship a
 * jar with the same basename, and keying the cache slot and the digest lookup by basename would make
 * them overwrite each other and put one path on the classpath twice.
 */
internal data class PlannedJar(val artifactName: String, val uri: URI, val sha256: String?)

/**
 * Orchestrates the fail-closed launch pipeline:
 *
 *   fetch /webstart (JNLP) → parse (untrusted) → follow its `<extension>` component descriptors (same
 *   host, same parser, bounded) → take per-jar digests from the inline `sha256` attributes (falling
 *   back to an external /digest/{version} manifest only if absent) → download each jar →
 *   [CodebaseVerifier] gate (digest then signature) → assemble classpath from [AdmittedJar]s only →
 *   build the default-secure child-JVM command.
 *
 * Every https fetch goes through [HttpClientFactory] (pinning + hostname verification). Every URL must
 * be https (no http downgrade). Any gate rejection raises [LaunchAbortedException] — there is no
 * partial or degraded launch, and no click-through bypass.
 */
class LauncherCore(
    private val trustStore: TrustStore = GestaltTrust.productionTrustStore(),
    private val signerPins: List<SignerPin> = GestaltTrust.OFFICIAL_SIGNER_PINS,
    private val gateMode: TrustMode = TrustMode.PIN_KNOWN_SIGNERS,
    private val cacheDir: Path = AppPaths.cacheDir,
    private val commandBuilder: ChildJvmCommandBuilder = ChildJvmCommandBuilder(),
    private val fetcherFactory: (Connection) -> ArtifactFetcher = { conn ->
        HttpArtifactFetcher(HttpClientFactory.forConnection(conn))
    },
    /**
     * Captures the server leaf for a pinned https connection (the cert handed to the child JVM). Injected
     * so the pin check + truststore propagation can be tested offline; the default does a real handshake.
     */
    private val serverLeafProbe: (Connection) -> X509Certificate = { conn ->
        val uri = conn.baseUri()
        val host = uri.host ?: throw LaunchAbortedException("connection has no host: ${conn.serverUrl}")
        val port = if (uri.port != -1) uri.port else 443
        ServerCertProbe.handshake(
            host, port,
            HttpClientFactory.resolveProtocols(conn),
            HttpClientFactory.resolveCiphers(conn),
        ).leaf ?: throw LaunchAbortedException("server $host:$port presented no certificate")
    },
    /** Sleep between connect retries (injected so the port-forward retry path is testable without waits). */
    private val sleeper: (Long) -> Unit = { if (it > 0) Thread.sleep(it) },
) {
    /** Verify everything and produce the launch plan. Does NOT spawn the process. */
    fun prepareLaunch(connection: Connection, onPhase: (LaunchPhase) -> Unit = {}): LaunchPlan {
        val fetcher = fetcherFactory(connection)
        try {
            onPhase(LaunchPhase.Downloading)
            // 0. For a pinned https connection, capture the server leaf up front and fail closed if it
            //    does not match the pin. This both enforces the pin early and gives us the exact leaf to
            //    hand to the launched Administrator (which opens its OWN TLS connection to the server).
            val pinnedLeaf = capturePinnedLeaf(connection)

            // 1. JNLP (untrusted): only jars / extensions / main-class / args / version are read from it.
            val jnlpUri = requireSupportedScheme(connection.webstartUri())
            val jnlp = JnlpParser.parse(withConnectRetry(connection) { fetcher.fetchText(jnlpUri) })
            // The server version is the Administrator's second application argument (Mirth and OIE both
            // pass `<server-url> <version>`); the `<jnlp version>` attribute is a fallback no stock server
            // sets. It keys the on-disk cache, so a server upgrade lands in a fresh directory.
            val version = serverVersionOf(jnlp)

            val mainClass = jnlp.mainClass
                ?: throw LaunchAbortedException("JNLP did not specify a main-class")

            // 1b. Follow the `<extension>` component descriptors (one per installed Mirth extension) and
            //     flatten everything into one jar list keyed by server-relative path.
            val jars = collectJars(connection, fetcher, jnlp)
            if (jars.isEmpty()) throw LaunchAbortedException("JNLP listed no jars")
            AuditLog.codebaseDownloaded(connection.id, version, jars.size)

            // 2. Integrity digests. A real OIE/Mirth server publishes a per-jar SHA-256 inline in the JNLP,
            //    so prefer those; fall back to the optional external /digest/{version} manifest only when the
            //    JNLP carried none. Either way every jar is digest-checked at the gate below — fail closed if
            //    neither source covers it.
            val manifest = inlineDigestManifest(jars)
                ?: DigestManifest.parse(withConnectRetry(connection) { fetcher.fetchText(requireSupportedScheme(connection.digestUri(version))) })

            onPhase(LaunchPhase.Verifying)
            // 3-4. Download each jar and run it through the single gate. Fail closed on any miss.
            val gate = CodebaseVerifier(trustStore, signerPins, gateMode, connection.id)
            val admitted = ArrayList<AdmittedJar>(jars.size)
            for (jar in jars) {
                val name = jar.artifactName
                val file = cacheFile(connection.id, version, name)

                // Reuse an already-downloaded jar whose bytes still match the digest the server published
                // THIS launch (the manifest is re-fetched every time). This skips the slow re-download of
                // unchanged jars — the dominant cost over a low-bandwidth port-forward / SSM tunnel. The jar
                // is still digest + signature verified at the gate below either way, so a stale or tampered
                // cache file simply misses the digest match here (or is rejected at the gate) and re-downloads.
                if (!cachedJarMatches(file, manifest, name)) {
                    val bytes = withConnectRetry(connection) { fetcher.fetchBytes(jar.uri) }
                    Files.write(file, bytes)
                }

                when (val outcome = gate.verify(name, file, manifest)) {
                    is GateOutcome.Admitted -> admitted += outcome.jar
                    is GateOutcome.Rejected -> {
                        runCatching { Files.deleteIfExists(file) }
                        throw LaunchAbortedException("verification failed for '$name': ${outcome.reason}")
                    }
                }
            }

            // Bound the on-disk cache: now that this version is verified, drop any other-version jars for
            // this connection (keeps the cache-reuse speed-up for the current version, no unbounded growth).
            runCatching { pruneOldVersionCaches(connection.id, version) }

            // Propagate the pinned server leaf to the child JVM as a per-connection PKCS12 truststore so
            // the launched Administrator's own TLS connection to the server also succeeds. Only for a
            // pinned https connection — a non-pinned (public-CA) server validates normally via the child's
            // default trust store, so no override is written.
            val tlsProps = pinnedLeaf?.let { leaf ->
                val store = ServerTrustStore.write(connection.id, leaf)
                AuditLog.serverTrustStoreWritten(connection.id, store.toAbsolutePath().toString())
                mapOf(
                    "javax.net.ssl.trustStore" to store.toAbsolutePath().toString(),
                    "javax.net.ssl.trustStorePassword" to ServerTrustStore.PASSWORD,
                    "javax.net.ssl.trustStoreType" to ServerTrustStore.TYPE,
                )
            } ?: emptyMap()

            // The Mirth client connects back to — and shows on its login screen — the server URL passed as
            // its first application argument. The JNLP carries the server's OWN idea of that URL, which is
            // wrong whenever the user reaches the server by any other address: a port-forward / SSM tunnel
            // (https://localhost:31065 vs the server's https://localhost:8443), a reverse proxy, a VPN name,
            // or just a different port. The address the user typed is, by definition, the one the launcher
            // just fetched everything from, so it is ALWAYS what the client gets — scheme, host and port.
            // A no-op when the JNLP URL already matches.
            val appArgs = rewriteServerUrlArg(connection.serverUrl, jnlp.arguments)
            val command = commandBuilder.build(
                connection, mainClass, admitted, appArgs,
                extraSystemProperties = tlsProps,
                serverJvmArgs = jnlp.untrustedJvmArgs,
            )
            AuditLog.childLaunched(connection.id, mainClass, admitted.size)
            return LaunchPlan(connection, version, mainClass, admitted, appArgs, command)
        } finally {
            (fetcher as? AutoCloseable)?.let { runCatching { it.close() } }
        }
    }

    /** Verify and then actually spawn the child JVM. */
    fun launch(connection: Connection, onPhase: (LaunchPhase) -> Unit = {}): Process {
        val plan = prepareLaunch(connection, onPhase)
        onPhase(LaunchPhase.Launching)
        val process = ProcessBuilder(plan.command).inheritIO().start()
        onPhase(LaunchPhase.Running)
        return process
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** True when [connection] targets the loopback interface (localhost / 127.x / ::1) — the forward case. */
    private fun isLoopbackConnection(connection: Connection): Boolean =
        LocalhostResolver.isLoopbackHost(runCatching { connection.baseUri().host }.getOrNull())

    /**
     * Run [block], retrying ONLY on a transient connect-level failure (connection refused / connect or
     * socket timeout) and ONLY for a LOOPBACK connection (a localhost port-forward). An SSM / port-forward
     * tunnel often refuses the launcher's rapid burst of connections (cert probe + JNLP + each jar) even
     * though a single connection a moment later succeeds; a few backoff retries absorb that. Remote
     * connections, and every non-connect error (TLS/PKIX, HTTP status, digest/signature), are NOT retried —
     * so a genuine "server down" or an untrusted cert still fails fast, and normal connections are unchanged.
     */
    private fun <T> withConnectRetry(connection: Connection, block: () -> T): T {
        val maxAttempts = if (isLoopbackConnection(connection)) PORT_FORWARD_CONNECT_ATTEMPTS else 1
        var attempt = 0
        while (true) {
            attempt++
            try {
                return block()
            } catch (e: Exception) {
                if (attempt >= maxAttempts || !isTransientConnect(e)) throw e
                sleeper((250L * attempt).coerceAtMost(2_000L))
            }
        }
    }

    /**
     * For a pinned https connection, capture the server's leaf certificate and fail closed if it does not
     * match the stored pin. Returns the verified leaf (to be written into the child-JVM truststore), or
     * null when the connection is plain http or unpinned (nothing to propagate — the child uses normal
     * validation). The launcher's own fetches are pinned independently by [HttpClientFactory]; this is the
     * authoritative early pin check plus the source of the leaf bytes the child needs.
     */
    private fun capturePinnedLeaf(connection: Connection): X509Certificate? {
        if (!connection.isHttps || !connection.isPinned) return null
        // isPinned is true here, so a stored pin that does not normalize to a 64-hex value is malformed —
        // fail closed with a clear message rather than skipping the check (defense-in-depth).
        val pin = connection.normalizedPin
            ?: throw LaunchAbortedException("connection is marked pinned but its stored certificate pin is not a valid SHA-256")
        val leaf = withConnectRetry(connection) { serverLeafProbe(connection) }
        val actual = CertFingerprint.sha256Hex(leaf)
        if (actual != pin) {
            throw LaunchAbortedException(
                "server certificate does not match the pinned certificate (expected $pin, got $actual)",
            )
        }
        return leaf
    }

    /**
     * Honor the connection's own transport: both `https` and `http` are allowed (an OIE/Mirth server may
     * be reached over plain http, e.g. `http://localhost:8080`). Only a non-web scheme (file/ftp/…) is
     * refused here; the no-**downgrade** rule (an https connection must not fetch over http) is enforced
     * per-jar in [resolveJarUri]. Digest + code-signature verification still applies on every transport.
     */
    private fun requireSupportedScheme(uri: URI): URI {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            throw LaunchAbortedException("refusing unsupported URL scheme '${uri.scheme}': $uri")
        }
        return uri
    }

    /**
     * Flatten the root JNLP plus every `<extension>` component descriptor it (transitively) references
     * into one ordered jar list: root jars first (so the `main="true"` client jar leads the classpath),
     * then each extension's jars in descriptor order. Bounded in depth, descriptor count and jar count so
     * a hostile or looping server can't make the launcher walk forever; a descriptor seen twice is
     * skipped; the same artifact listed twice with DIFFERENT digests aborts (the server contradicts
     * itself — nothing to trust).
     *
     * Every href — descriptor or jar — resolves against the CONNECTION's base, never a JNLP `codebase`:
     * Mirth sets the codebase of every descriptor it emits to the server root, which is exactly what the
     * connection points at, and ignoring the server-supplied value keeps the same-host / no-downgrade
     * rules authoritative.
     */
    private fun collectJars(connection: Connection, fetcher: ArtifactFetcher, root: JnlpDescriptor): List<PlannedJar> {
        val planned = LinkedHashMap<String, PlannedJar>()
        fun addJars(desc: JnlpDescriptor) {
            for (jar in desc.jars) {
                val uri = requireSupportedScheme(resolveArtifactUri(connection, jar.href))
                val name = artifactNameFor(connection, uri)
                val existing = planned[name]
                if (existing == null) {
                    planned[name] = PlannedJar(name, uri, jar.sha256)
                } else if (!existing.sha256.isNullOrBlank() && !jar.sha256.isNullOrBlank() && existing.sha256 != jar.sha256) {
                    throw LaunchAbortedException("descriptors list '$name' twice with different digests")
                }
                if (planned.size > MAX_JARS) throw LaunchAbortedException("descriptors list more than $MAX_JARS jars")
            }
        }
        addJars(root)

        val visited = HashSet<URI>()
        val queue = ArrayDeque<Pair<String, Int>>()
        root.extensions.forEach { queue.addLast(it to 1) }
        while (queue.isNotEmpty()) {
            val (href, depth) = queue.removeFirst()
            val uri = requireSupportedScheme(resolveArtifactUri(connection, href))
            if (!visited.add(uri)) continue // already walked (a cycle or a shared dependency) — never re-fetched
            if (depth > MAX_EXTENSION_DEPTH) {
                throw LaunchAbortedException("extension descriptors nest deeper than $MAX_EXTENSION_DEPTH: $href")
            }
            if (visited.size > MAX_EXTENSION_DESCRIPTORS) {
                throw LaunchAbortedException("more than $MAX_EXTENSION_DESCRIPTORS extension descriptors")
            }
            val ext = JnlpParser.parse(withConnectRetry(connection) { fetcher.fetchText(uri) })
            addJars(ext)
            ext.extensions.forEach { queue.addLast(it to depth + 1) }
        }
        return planned.values.toList()
    }

    /**
     * Resolve a jar / descriptor href against the **connection's** base URL — deliberately NOT the JNLP's
     * own `codebase`, which is server-controlled and untrusted. A href that resolves to a different host
     * than the configured connection is rejected (SSRF / artifact substitution), and an https connection
     * is never downgraded to an http artifact by a crafted absolute href. The gate would still reject a
     * foreign or tampered jar, but we fail even earlier and closed.
     */
    private fun resolveArtifactUri(connection: Connection, href: String): URI {
        val base = baseForRelativeResolution(connection.baseUri())
        val resolved = base.resolve(href)
        val baseHost = base.host?.lowercase()
        val resolvedHost = resolved.host?.lowercase()
        if (baseHost != null && resolvedHost != null && baseHost != resolvedHost) {
            throw LaunchAbortedException("artifact URL host '$resolvedHost' differs from the connection host '$baseHost'")
        }
        if (base.scheme.equals("https", ignoreCase = true) && resolved.scheme.equals("http", ignoreCase = true)) {
            throw LaunchAbortedException("refusing to downgrade artifact transport from https to http: $resolved")
        }
        return resolved
    }

    /**
     * The artifact's name for the cache slot, the digest lookup and the audit trail: its path relative to
     * the connection base (`webstart/client-lib/mirth-client.jar`), never just the basename. Any `.`/`..`
     * segment is refused so a hostile href can't escape the cache directory.
     */
    private fun artifactNameFor(connection: Connection, uri: URI): String {
        val basePath = baseForRelativeResolution(connection.baseUri()).path.orEmpty()
        val path = uri.path.orEmpty()
        val relative = if (basePath.isNotEmpty() && path.startsWith(basePath)) path.substring(basePath.length) else path
        val segments = relative.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.any { it == "." || it == ".." }) {
            throw LaunchAbortedException("refusing artifact path '$path'")
        }
        return segments.joinToString("/")
    }

    /**
     * The connection base as a *directory*, for resolving relative hrefs. [URI.resolve] treats the last
     * path segment of the base as a file: against `http://host:8080` (empty path) it yields the malformed
     * `http://host:8080webstart/...`, and against `https://host/mirth` it drops `/mirth`. A trailing "/"
     * fixes both — `webstart/x.jar` becomes `http://host:8080/webstart/x.jar` and
     * `https://host/mirth/webstart/x.jar`. An absolute href ignores the base entirely (so the host and
     * downgrade checks still apply to it).
     */
    private fun baseForRelativeResolution(base: URI): URI =
        if (base.rawPath.isNullOrEmpty() || !base.rawPath.endsWith("/")) {
            URI.create(base.toString().substringBefore('?').substringBefore('#').trimEnd('/') + "/")
        } else {
            base
        }

    /**
     * True when [file] already holds bytes whose SHA-256 matches the digest [manifest] expects for [name] —
     * i.e. a cached jar that is byte-identical to what the server would serve this launch. Uses the SAME
     * hex comparison the gate uses, so "cache hit here" and "passes the digest gate" never disagree.
     */
    private fun cachedJarMatches(file: Path, manifest: DigestManifest, name: String): Boolean {
        if (!Files.isRegularFile(file)) return false
        val expected = manifest.expectedFor(name) ?: return false
        return runCatching { DigestVerifier.constantTimeEquals(DigestVerifier.sha256Hex(file), expected) }
            .getOrDefault(false)
    }

    /**
     * The cache slot for [artifactName] under `<cache>/<connection>/<version>/`, mirroring the artifact's
     * server-relative directories (each segment sanitized) so same-basename jars from different extensions
     * get distinct slots. [artifactNameFor] has already refused `.`/`..` segments.
     */
    private fun cacheFile(connectionId: String, version: String, artifactName: String): Path {
        var dir = cacheDir.resolve(sanitize(connectionId)).resolve(sanitize(version))
        val segments = artifactName.split('/')
        segments.dropLast(1).forEach { dir = dir.resolve(sanitize(it)) }
        Files.createDirectories(dir)
        return dir.resolve(sanitize(segments.last()))
    }

    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * The digest manifest the descriptors carry inline — one SHA-256 per `<jar>`, keyed by the same
     * server-relative artifact name the gate verifies under. Null unless EVERY jar declares a digest, so a
     * server that omits them (or declares only some) falls back to the external `/digest` manifest rather
     * than silently leaving jars uncovered. (An uncovered jar would still be rejected at the gate; this
     * just keeps the fallback clean.)
     */
    private fun inlineDigestManifest(jars: List<PlannedJar>): DigestManifest? {
        if (jars.isEmpty() || jars.any { it.sha256.isNullOrBlank() }) return null
        return DigestManifest.fromInlineDigests(jars.associate { it.artifactName to it.sha256!! })
    }

    companion object {
        /** Connect attempts for a port-forward connection (1 = no retry for every other mode). */
        private const val PORT_FORWARD_CONNECT_ATTEMPTS = 5

        /** Bounds on the extension-descriptor walk (a stock Mirth server has ~40 extensions, 1 level deep). */
        private const val MAX_EXTENSION_DEPTH = 3
        private const val MAX_EXTENSION_DESCRIPTORS = 256
        private const val MAX_JARS = 4096

        /** Shape of the Administrator's version argument (`4.4.2`, `4.5.2`, `3.12.0-SNAPSHOT`). */
        private val VERSION_ARG = Regex("^\\d+(\\.\\d+){1,3}([.-][A-Za-z0-9]+)*$")

        /**
         * The server version a launch runs against: the Administrator's second application argument when it
         * looks like a version (what Mirth and OIE both pass), else the `<jnlp version>` attribute / a
         * version property if the server set one, else `current`. Keys the on-disk jar cache.
         */
        internal fun serverVersionOf(jnlp: JnlpDescriptor): String =
            jnlp.arguments.getOrNull(1)?.trim()?.takeIf { VERSION_ARG.matches(it) }
                ?: jnlp.version?.trim()?.takeIf { it.isNotEmpty() }
                ?: "current"

        /**
         * Delete a connection's entire cached-jar directory (every version) — called when the connection
         * is removed so downloaded jars don't linger on disk. Best-effort; safe if the directory is absent.
         * Uses the same name sanitization as [cacheFile] so it targets exactly that connection's subtree.
         */
        fun purgeConnectionCache(connectionId: String, cacheDir: Path = AppPaths.cacheDir) {
            val dir = cacheDir.resolve(connectionId.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            runCatching { if (Files.isDirectory(dir)) dir.toFile().deleteRecursively() }
        }

        /**
         * Bound the on-disk cache: after a successful launch, drop cached jars for OTHER versions of
         * [connectionId], keeping only [keepVersion]. A connection points at one server with one current
         * version, so stale-version jars are dead weight; this keeps the cache-reuse speed-up for the
         * current version while preventing unbounded growth as a server is upgraded over time. Best-effort.
         */
        fun pruneOldVersionCaches(connectionId: String, keepVersion: String, cacheDir: Path = AppPaths.cacheDir) {
            val sanitize = { s: String -> s.replace(Regex("[^A-Za-z0-9._-]"), "_") }
            val connDir = cacheDir.resolve(sanitize(connectionId))
            val keep = sanitize(keepVersion)
            runCatching {
                if (Files.isDirectory(connDir)) {
                    Files.newDirectoryStream(connDir).use { stream ->
                        stream.forEach { entry ->
                            if (Files.isDirectory(entry) && entry.fileName.toString() != keep) {
                                runCatching { entry.toFile().deleteRecursively() }
                            }
                        }
                    }
                }
            }
        }

        /**
         * True when [t] (or a cause) is a transient connectivity failure — connection refused
         * ([java.net.ConnectException], which Apache's HttpHostConnectException extends) or a socket timeout
         * ([java.net.SocketTimeoutException], covering both a connect timeout and a stalled read; either is
         * safe to retry here since each retry re-issues a fresh, idempotent request and the gate re-verifies).
         * Deliberately NOT true for TLS/PKIX ([javax.net.ssl.SSLException]), HTTP-status, or integrity errors,
         * so an untrusted cert or a tampered jar fails fast and is never masked by a retry.
         */
        internal fun isTransientConnect(t: Throwable): Boolean {
            var cur: Throwable? = t
            val seen = java.util.IdentityHashMap<Throwable, Boolean>()
            while (cur != null && seen.put(cur, true) == null) {
                if (cur is java.net.ConnectException || cur is java.net.SocketTimeoutException) return true
                if (cur.javaClass.simpleName == "ConnectTimeoutException") return true // Apache connect timeout
                cur = cur.cause
            }
            return false
        }

        /**
         * Rewrite the **first** http(s)-URL application argument so its scheme + host + port become those
         * of [serverUrl] (the connection's own address), preserving any path/query/fragment. If [serverUrl]
         * carries no explicit port, the argument's own port is kept (never dropped to the default). This makes
         * the launched client connect back through — and show on its login screen — the same address the
         * user typed, rather than the server's self-reported URL in the JNLP (which differs behind a
         * port-forward, a reverse proxy, a VPN name, or simply another port). Applied to every connection.
         * Non-URL arguments (e.g. the version) are passed through untouched, and only the first URL argument
         * is rewritten so a later URL argument (should one ever appear) is left alone. A no-op when
         * [serverUrl] is unparseable or the argument already matches.
         */
        internal fun rewriteServerUrlArg(serverUrl: String, args: List<String>): List<String> {
            val base = runCatching { URI(serverUrl) }.getOrNull()?.takeIf { it.host != null } ?: return args
            var rewrote = false
            return args.map { arg ->
                if (rewrote) return@map arg
                val u = runCatching { URI(arg) }.getOrNull()
                val scheme = u?.scheme?.lowercase()
                if (u != null && (scheme == "https" || scheme == "http") && u.host != null) {
                    rewrote = true
                    // Use the connection's port; if it has none (a port-less serverUrl), keep the arg's own
                    // port rather than dropping it (which would send the client to the default 443/80).
                    val port = if (base.port != -1) base.port else u.port
                    runCatching {
                        URI(base.scheme, base.userInfo, base.host, port, u.path?.ifBlank { null }, u.query, u.fragment).toString()
                    }.getOrElse { arg }
                } else {
                    arg
                }
            }
        }
    }
}
