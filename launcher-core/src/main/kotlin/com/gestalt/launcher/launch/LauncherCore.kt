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
import com.gestalt.launcher.gate.AdmissionCache
import com.gestalt.launcher.gate.AdmittedJar
import com.gestalt.launcher.gate.CodebaseVerifier
import com.gestalt.launcher.gate.GateOutcome
import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.gate.SignerPin
import com.gestalt.launcher.gate.TrustedSignerStore
import com.gestalt.launcher.jnlp.JnlpDescriptor
import com.gestalt.launcher.jnlp.JnlpParser
import com.gestalt.launcher.net.ArtifactFetcher
import com.gestalt.launcher.net.HttpArtifactFetcher
import com.gestalt.launcher.net.LocalhostResolver
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.tls.HttpClientFactory
import com.gestalt.launcher.tls.ServerCertProbe
import com.gestalt.launcher.tls.ServerTrustFailure
import com.gestalt.launcher.tls.TrustedCertStore
import com.gestalt.launcher.tunnel.TunnelFailedException
import com.gestalt.launcher.tunnel.TunnelManager
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Raised whenever any verification step fails. The launch is aborted; nothing is executed. */
open class LaunchAbortedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The server presented an https certificate that is neither trusted by the system CAs nor reviewed by
 * the user, and the connection doesn't auto-approve. The launch stopped before any code ran; [leaf] is
 * the certificate to show for review (it has been recorded as PENDING in the trusted-certificate store).
 */
class ServerCertificateReviewRequiredException(val leaf: X509Certificate, val hostPort: String?, cause: Throwable?) :
    LaunchAbortedException("server certificate needs review before launching: ${leaf.subjectX500Principal.name}", cause)

/** The server presented a certificate the user has explicitly rejected. */
class ServerCertificateRejectedException(val leaf: X509Certificate, cause: Throwable?) :
    LaunchAbortedException("server certificate was rejected by the user: ${leaf.subjectX500Principal.name}", cause)

/**
 * A jar's content is exactly what its signer signed, but the signer is a publisher the launcher doesn't
 * trust (unknown chain, no codeSigning EKU, or not on the pinned allow-list) and the user hasn't decided
 * about it. Recorded as PENDING in the publisher store; the launch stopped before any code ran.
 */
class PublisherReviewRequiredException(val signer: X509Certificate, val artifactName: String, val reason: String) :
    LaunchAbortedException("publisher needs review before launching: ${signer.subjectX500Principal.name} (signed '$artifactName'; $reason)")

/**
 * Coarse, observational launch phases for UI progress. Purely informational — emitting them does not
 * change any verification logic or control flow (the pipeline stays fail-closed regardless).
 * [Tunneling] is reported only when the connection's port-forward command has to be run first.
 */
enum class LaunchPhase { Tunneling, Downloading, Verifying, Launching, Running, Failed }

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
 * Credentials for the Administrator's auto-login. Mirth's client takes them as its 3rd and 4th program
 * arguments after `[serverUrl, version]`; given both it signs in on its own, given neither it shows the
 * login screen. [password] is the caller's array — [LauncherCore.launch] zeroes it once the child is
 * spawned (or the launch has failed), so a caller hands it over and does not reuse it.
 */
class AutoLogin(val username: String, val password: CharArray) {
    override fun toString() = "AutoLogin(username=$username, password=***)"
}

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
open class LauncherCore(
    private val trustStore: TrustStore = GestaltTrust.productionTrustStore(),
    private val signerPins: List<SignerPin> = GestaltTrust.OFFICIAL_SIGNER_PINS,
    private val gateMode: TrustMode = TrustMode.PIN_KNOWN_SIGNERS,
    private val cacheDir: Path = AppPaths.cacheDir,
    private val commandBuilder: ChildJvmCommandBuilder = ChildJvmCommandBuilder(),
    /** The user's reviewed server certificates; every https fetch is policed against it. */
    private val trustedCerts: TrustedCertStore = TrustedCertStore.shared,
    /** The user's reviewed jar publishers; the gate consults it for signers the policy doesn't accept. */
    private val trustedSigners: TrustedSignerStore = TrustedSignerStore.shared,
    /** Opens a connection's port-forward before the launch and closes it after the Administrator exits. */
    private val tunnels: TunnelManager = TunnelManager.shared,
    private val fetcherFactory: (Connection) -> ArtifactFetcher = { conn ->
        HttpArtifactFetcher(HttpClientFactory.forConnection(conn, trustedCerts, singleConnection = tunnels.isBasicForward(conn.id)))
    },
    /**
     * Captures the server leaf after a TLS trust failure, so it can be auto-approved or handed to the
     * user for review. Injected so that path is testable offline; the default does a real handshake.
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
    /**
     * Descriptor fetches and jar download+verify run this many at a time. A stock Mirth server has ~40
     * extension descriptors and ~150 jars; one at a time over a port-forward is a long wait, and the gate
     * (SHA-256 + CMS/PKIX per jar) is CPU work that parallelises cleanly. 1 makes everything sequential.
     */
    private val parallelism: Int = DEFAULT_PARALLELISM,
    /** Where each launched client's console output is kept (see [ClientLog]). */
    private val clientLogDir: Path = AppPaths.logDir.resolve("client"),
    /**
     * Admissions remembered across launches ([AdmissionCache]): bytes the gate has already verified
     * under the policy in force skip the signature check (never the digest check). Null disables it.
     */
    private val admissions: AdmissionCache? = AdmissionCache(cacheDir.resolve(AdmissionCache.FILE_NAME)),
) {
    /**
     * Verify everything and produce the launch plan. Does NOT spawn the process.
     *
     * Server trust: an https certificate the policy doesn't accept surfaces from the first fetch. If the
     * connection auto-approves, the leaf is captured, trusted (audited as auto-approved) and the whole
     * preparation is retried once; otherwise the leaf is recorded as PENDING and the launch stops with a
     * [ServerCertificateReviewRequiredException] for the UI to offer a review. A REJECTED certificate
     * stops the launch with [ServerCertificateRejectedException].
     */
    fun prepareLaunch(connection: Connection, onPhase: (LaunchPhase) -> Unit = {}): LaunchPlan =
        try {
            prepareLaunchOnce(connection, onPhase)
        } catch (e: Exception) {
            when (ServerTrustFailure.kindOf(e)) {
                null -> throw e
                ServerTrustFailure.Kind.REJECTED -> throw ServerCertificateRejectedException(serverLeafProbe(connection), e)
                ServerTrustFailure.Kind.UNKNOWN -> {
                    val leaf = serverLeafProbe(connection)
                    if (connection.autoApproveCertificates) {
                        trustedCerts.trust(leaf, connection.hostPort, connection.id, autoApproved = true)
                        AuditLog.event("servercert.autoapprove", "connection" to connection.id, "host" to (connection.hostPort ?: "-"))
                        prepareLaunchOnce(connection, onPhase) // one retry with the certificate now trusted
                    } else {
                        trustedCerts.markPending(leaf, connection.hostPort, connection.id)
                        throw ServerCertificateReviewRequiredException(leaf, connection.hostPort, e)
                    }
                }
            }
        }

    private fun prepareLaunchOnce(connection: Connection, onPhase: (LaunchPhase) -> Unit): LaunchPlan {
        val fetcher = fetcherFactory(connection)
        val startedAt = System.currentTimeMillis()
        try {
            onPhase(LaunchPhase.Downloading)

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

            val descriptorsMs = System.currentTimeMillis() - startedAt
            onPhase(LaunchPhase.Verifying)
            // 3-4. Download each jar and run it through the single gate, [parallelism] jars at a time.
            //      Fail closed on any miss: the first rejection cancels the rest and aborts the launch.
            val gate = CodebaseVerifier(trustStore, signerPins, gateMode, connection.id, trustedSigners)
            // Everything the gate's verdict depends on besides the jar: a change invalidates cached admissions.
            val policy = AdmissionCache.policyFingerprint(gateMode, signerPins, trustStore, trustedSigners)
            val admitted = ArrayList<AdmittedJar>(jars.size)
            val cachedHits = java.util.concurrent.atomic.AtomicInteger()
            val verifyStarted = System.currentTimeMillis()
            try {
                parallel(connection) { pool ->
                    val futures = jars.map { jar ->
                        pool.submit(Callable { downloadAndGate(connection, fetcher, gate, manifest, version, jar, policy, cachedHits) })
                    }
                    for (future in futures) {
                        checkNotCancelled()
                        admitted += await(future, futures)
                    }
                }
            } finally {
                admissions?.flush()
            }
            val verifyMs = System.currentTimeMillis() - verifyStarted
            AuditLog.event(
                "launch.timing", "connection" to connection.id, "jars" to jars.size, "cachedAdmissions" to cachedHits.get(),
                "descriptorsMs" to descriptorsMs, "downloadVerifyMs" to verifyMs, "parallelism" to parallelismFor(connection),
            )

            // Bound the on-disk cache: now that this version is verified, drop any other-version jars for
            // this connection (keeps the cache-reuse speed-up for the current version, no unbounded growth).
            runCatching { pruneOldVersionCaches(connection.id, version) }

            // (No per-connection truststore is handed to the child any more: the Administrator does its own
            // server-trust handling, and overriding javax.net.ssl.trustStore only shrank what else it could
            // reach. Server trust is enforced here, on the launcher's own fetches.)

            // The Mirth client connects back to — and shows on its login screen — the server URL passed as
            // its first application argument. The JNLP carries the server's OWN idea of that URL, which is
            // wrong whenever the user reaches the server by any other address: a port-forward / SSM tunnel
            // (https://localhost:31065 vs the server's https://localhost:8443), a reverse proxy, a VPN name,
            // or just a different port. The address the user typed is, by definition, the one the launcher
            // just fetched everything from, so it is ALWAYS what the client gets — scheme, host and port.
            // A no-op when the JNLP URL already matches.
            val appArgs = rewriteServerUrlArg(connection.serverUrl, jnlp.arguments)
            // The address those fetches actually reached the server on. For a loopback connection it is
            // which loopback literal the port-forward answers on, and the client is pointed at the same one.
            val reachedAt = fetcher.lastRemoteAddress
            val command = commandBuilder.build(
                connection, mainClass, admitted, appArgs,
                serverJvmArgs = jnlp.untrustedJvmArgs,
                reachedAddress = reachedAt,
                serverVersion = version,
            )
            AuditLog.childLaunched(connection.id, mainClass, admitted.size, reachedAt?.hostAddress)
            return LaunchPlan(connection, version, mainClass, admitted, appArgs, command)
        } finally {
            (fetcher as? AutoCloseable)?.let { runCatching { it.close() } }
        }
    }

    /**
     * Verify and then actually spawn the child JVM. Returns the child [Process] so the caller can track
     * it (watch for exit, count running instances) — a launcher that drops it has no way to know the
     * Administrator closed. `open` so a UI test can substitute a fake process without a real spawn.
     *
     * Cancellation: the pipeline checks the calling thread's interrupt flag between network fetches, so
     * running it under `runInterruptible` makes a coroutine cancel take effect at the next fetch boundary
     * (a blocking socket read in progress completes first). A cancelled launch spawns nothing.
     *
     * The child's stdout/stderr go to a per-launch file under the client log directory ([ClientLog]) —
     * the Administrator reports its own failures there, and a windowless launcher has no console to show
     * them on. Only if that file can't be created does the child inherit the launcher's console.
     *
     * A connection with a tunnel command has its port-forward opened first ([TunnelManager]) and held
     * until the Administrator exits; a launch that fails after opening it releases it at once. The
     * returned [Process] is tree-aware ([ProcessTree]): it reports the Administrator as running until the
     * JVM the client relaunched itself into — not just the one spawned here — has exited.
     */
    open fun launch(connection: Connection, onPhase: (LaunchPhase) -> Unit = {}, autoLogin: AutoLogin? = null): Process {
        try {
            val lease = tunnels.acquire(connection) { onPhase(LaunchPhase.Tunneling) }
            try {
                val plan = prepareLaunch(connection, onPhase)
                checkNotCancelled()
                // The launcher's own connections just closed; an older SSM plugin can drop the session with
                // them. Make sure the port still answers before the Administrator is pointed at it.
                tunnels.recheck(connection, lease) { onPhase(LaunchPhase.Tunneling) }
                onPhase(LaunchPhase.Launching)
                val builder = ProcessBuilder(commandWithLogin(plan, autoLogin))
                val log = ClientLog.open(clientLogDir, plan)
                if (log != null) {
                    builder.redirectInput(ProcessBuilder.Redirect.INHERIT)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    AuditLog.event("child.log", "connection" to connection.id, "path" to log.toString())
                } else {
                    builder.inheritIO()
                }
                // The Administrator relaunches itself when its saved heap preference differs from -Xmx: the
                // process the launcher started exits within a second and a child JVM is the real client. Track
                // the tree, so Running, close-after-launch and the port-forward follow the client, not the stub.
                val process = ProcessTree.track(builder.start(), fingerprint = listOf(plan.mainClass) + plan.appArgs.take(1))
                if (lease != null) process.onExit().thenRun { runCatching { lease.close() } }
                onPhase(LaunchPhase.Running)
                return process
            } catch (t: Throwable) {
                // A fetch that died through a forward the launcher opened: if the forward's own log says the
                // server side refused the destination port, that — not "connection reset" — is the failure.
                // Asked before the lease goes, since releasing the last lease tears the forward down.
                val refusedBeyondTheForward = lease != null && t is IOException && tunnels.destinationRefused(connection.id)
                lease?.close()
                if (refusedBeyondTheForward) {
                    throw TunnelFailedException(
                        "the port-forward opened and accepted the connection, but nothing answered on the server side " +
                            "(the session reported \"Connection to destination port failed\"): the engine is not running there, " +
                            "or the forward's destination port is not the engine's https port",
                        t,
                    )
                }
                throw t
            }
        } finally {
            autoLogin?.password?.fill('\u0000')
        }
    }

    /**
     * The command to spawn: the plan's, plus — when [autoLogin] is supplied — the username and password
     * as the Administrator's 3rd and 4th program arguments. A username alone is passed as the 3rd
     * argument on its own: the Administrator's login screen then opens with it filled in and asks for
     * the password; with both present it signs in by itself. The credentials are appended only when the
     * descriptor had the stock two arguments (`[serverUrl, version]`); with any other shape the positions
     * would be wrong, so the launch goes ahead without them and the login screen asks. Either way is
     * audited (the event only — never the values). The plan itself stays free of secrets.
     *
     * The caveat is the Administrator's, not ours: a program argument is visible to other processes of
     * the same user, and the JVM holds it as a String until collected. This is the one auto-login the
     * client offers, and exactly what the discontinued MCAL did.
     */
    internal fun commandWithLogin(plan: LaunchPlan, autoLogin: AutoLogin?): List<String> {
        if (autoLogin == null) return plan.command
        if (autoLogin.username.isBlank()) return plan.command
        if (plan.appArgs.size != 2) {
            AuditLog.event("child.autologin", "connection" to plan.connection.id, "result" to "SKIPPED", "reason" to "descriptor has ${plan.appArgs.size} arguments, expected 2")
            return plan.command
        }
        if (autoLogin.password.isEmpty()) {
            AuditLog.event("child.autologin", "connection" to plan.connection.id, "result" to "USERNAME_ONLY")
            return plan.command + autoLogin.username
        }
        AuditLog.event("child.autologin", "connection" to plan.connection.id, "result" to "SUPPLIED")
        return plan.command + autoLogin.username + String(autoLogin.password)
    }

    /** Honour a cancel requested through thread interruption (see [launch]). */
    private fun checkNotCancelled() {
        if (Thread.interrupted()) throw InterruptedException("launch cancelled")
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
     * Each descriptor's relative hrefs resolve against THAT descriptor's base ([descriptorBase]): the
     * path of its `codebase` on the connection's own origin, else the directory the descriptor was
     * fetched from. Mirth's main `webstart.jnlp` has a root codebase and root-relative hrefs
     * (`webstart/client-lib/…`, `webstart/extensions/<name>.jnlp`); each extension descriptor has
     * codebase `…/webstart/extensions` and lists its jars as `libs/<name>/<jar>` — resolving those
     * against the root is a 404 on every extension. Scheme, host and port always come from the typed
     * address (the same-host / no-downgrade rules stay authoritative); only the path is the server's.
     */
    private fun collectJars(connection: Connection, fetcher: ArtifactFetcher, root: JnlpDescriptor): List<PlannedJar> {
        val planned = LinkedHashMap<String, PlannedJar>()
        fun addJars(desc: JnlpDescriptor, base: URI) {
            for (jar in desc.jars) {
                val uri = requireSupportedScheme(resolveArtifactUri(connection, base, jar.href))
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
        val rootBase = descriptorBase(connection, connection.webstartUri(), root)
        addJars(root, rootBase)

        // Breadth-first, one level at a time: every descriptor of a level is fetched concurrently, then
        // parsed and added in the order it was listed, so the jar order is as deterministic as before.
        val visited = HashSet<URI>()
        var level: List<Pair<String, URI>> = root.extensions.map { it to rootBase }
        var depth = 1
        parallel(connection) { pool ->
            while (level.isNotEmpty()) {
                if (depth > MAX_EXTENSION_DEPTH) {
                    throw LaunchAbortedException("extension descriptors nest deeper than $MAX_EXTENSION_DEPTH: ${level.first().first}")
                }
                val toFetch = ArrayList<URI>()
                for ((href, parentBase) in level) {
                    val uri = requireSupportedScheme(resolveArtifactUri(connection, parentBase, href))
                    if (!visited.add(uri)) continue // already walked (a cycle or a shared dependency) — never re-fetched
                    if (visited.size > MAX_EXTENSION_DESCRIPTORS) {
                        throw LaunchAbortedException("more than $MAX_EXTENSION_DESCRIPTORS extension descriptors")
                    }
                    toFetch += uri
                }
                checkNotCancelled()
                val fetched = toFetch.map { uri -> pool.submit(Callable { JnlpParser.parse(withConnectRetry(connection) { fetcher.fetchText(uri) }) }) }
                val next = ArrayList<Pair<String, URI>>()
                for ((i, future) in fetched.withIndex()) {
                    val ext = await(future, fetched)
                    val extBase = descriptorBase(connection, toFetch[i], ext)
                    addJars(ext, extBase)
                    ext.extensions.forEach { next += it to extBase }
                }
                level = next
                depth++
            }
        }
        return planned.values.toList()
    }

    /** Run [block] with a bounded pool that is always shut down afterwards (in-flight work is interrupted). */
    private fun <T> parallel(connection: Connection, block: (ExecutorService) -> T): T {
        val pool = Executors.newFixedThreadPool(parallelismFor(connection)) { r -> Thread(r, "launch-fetch").apply { isDaemon = true } }
        try {
            return block(pool)
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * How many fetches run at once for [connection]: [parallelism], except ONE through a port-forward the
     * launcher opened whose plugin runs in basic (non-multiplexing) mode — that forward services a single
     * connection and parks the rest in the listener's backlog, where they time out and retry
     * ([TunnelManager.isBasicForward]). A multiplexing forward, a hand-started tunnel and a direct host
     * all take the parallel path, which through a tunnel is several times faster.
     */
    internal fun parallelismFor(connection: Connection): Int =
        if (tunnels.isBasicForward(connection.id)) 1 else parallelism.coerceAtLeast(1)

    /**
     * Wait for one task; a failure anywhere cancels the rest and surfaces as the original exception, and
     * an interrupt of the waiting (launch) thread — a cancel — propagates as [InterruptedException].
     */
    private fun <T> await(future: Future<T>, all: List<Future<*>>): T {
        try {
            return future.get()
        } catch (e: ExecutionException) {
            all.forEach { it.cancel(true) }
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            all.forEach { it.cancel(true) }
            throw e
        }
    }

    /**
     * Where a descriptor's relative hrefs resolve. The JNLP `codebase` is honoured for its PATH only —
     * the server's own idea of its scheme/host/port is ignored in favour of the address the user typed
     * (a port-forward or proxy reaches the same server by another name) — and that path is kept under
     * the connection's base path when the connection has one (a reverse proxy adds a prefix the server
     * doesn't know about). With no codebase, the JNLP rule applies: the descriptor's own directory.
     */
    internal fun descriptorBase(connection: Connection, descriptorUri: URI, desc: JnlpDescriptor): URI {
        val connBase = connection.baseDirUri()
        val dir = descriptorUri.resolve(".")
        val codebase = desc.codebase?.trim()?.takeIf { it.isNotEmpty() } ?: return dir
        val cb = runCatching { URI(codebase) }.getOrNull() ?: return dir
        val rawCodebasePath = (if (cb.isAbsolute) cb.path else dir.resolve(cb).path).orEmpty()
        val codebasePath = if (rawCodebasePath.endsWith("/")) rawCodebasePath else "$rawCodebasePath/"
        val connPath = connBase.path.orEmpty().ifEmpty { "/" }
        val path = when {
            connPath == "/" -> codebasePath
            codebasePath.startsWith(connPath) -> codebasePath
            else -> connPath + codebasePath.trimStart('/')
        }
        return URI(connBase.scheme, connBase.authority, path, null, null)
    }

    /**
     * Resolve a jar / descriptor href against [base] (its descriptor's base, which already sits on the
     * connection's origin). A href that resolves to a different host than the configured connection is
     * rejected (SSRF / artifact substitution), and an https connection is never downgraded to an http
     * artifact by a crafted absolute href. The gate would still reject a foreign or tampered jar, but
     * we fail even earlier and closed.
     */
    private fun resolveArtifactUri(connection: Connection, base: URI, href: String): URI {
        val connBase = connection.baseDirUri()
        val resolved = base.resolve(href.trim())
        val baseHost = connBase.host?.lowercase()
        val resolvedHost = resolved.host?.lowercase()
        if (baseHost != null && resolvedHost != null && baseHost != resolvedHost) {
            throw LaunchAbortedException("artifact URL host '$resolvedHost' differs from the connection host '$baseHost'")
        }
        if (connBase.scheme.equals("https", ignoreCase = true) && resolved.scheme.equals("http", ignoreCase = true)) {
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
        val basePath = connection.baseDirUri().path.orEmpty()
        val path = uri.path.orEmpty()
        val relative = if (basePath.isNotEmpty() && path.startsWith(basePath)) path.substring(basePath.length) else path
        val segments = relative.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.any { it == "." || it == ".." }) {
            throw LaunchAbortedException("refusing artifact path '$path'")
        }
        return segments.joinToString("/")
    }

    /**
     * One jar's trip through the pipeline: reuse the cached copy when its bytes still match the digest
     * the server published THIS launch (the manifest is re-fetched every time), else download; then the
     * single gate. Runs on a pool thread; the gate is stateless per call. A publisher the gate doesn't
     * know stops the launch for review with the jar kept (it is exactly what the signer signed); any
     * other rejection deletes the cached file and aborts.
     */
    private fun downloadAndGate(
        connection: Connection, fetcher: ArtifactFetcher, gate: CodebaseVerifier, manifest: DigestManifest, version: String, jar: PlannedJar,
        policy: String, cachedHits: java.util.concurrent.atomic.AtomicInteger,
    ): AdmittedJar {
        val name = jar.artifactName
        val file = cacheFile(connection.id, version, name)
        val expected = manifest.expectedFor(name)
        // One hash of the cached file decides both reuse and (below) the gate's digest check.
        val cached: String? = if (expected != null && Files.isRegularFile(file)) runCatching { DigestVerifier.sha256Hex(file) }.getOrNull() else null
        val actual: String = if (expected == null || cached == null || !DigestVerifier.constantTimeEquals(cached, expected)) {
            val bytes = withConnectRetry(connection) { fetcher.fetchBytes(jar.uri) }
            Files.write(file, bytes)
            DigestVerifier.sha256Hex(bytes)
        } else {
            cached
        }
        // Fast path: these exact bytes (they match this launch's digest) were admitted before under the
        // policy in force. The signature was verified then; the digest is verified now.
        if (admissions != null && expected != null && DigestVerifier.constantTimeEquals(actual, expected)) {
            admissions.lookup(actual, policy)?.let { hit ->
                cachedHits.incrementAndGet()
                return gate.admitCached(name, file, actual, hit.signerDN, hit.usedTimestamp)
            }
        }
        return when (val outcome = gate.verify(name, file, manifest, sha256 = actual)) {
            is GateOutcome.Admitted -> {
                admissions?.record(AdmissionCache.Entry(outcome.jar.sha256, policy, outcome.jar.signerDN, outcome.jar.usedTimestamp, AdmissionCache.now(), name))
                outcome.jar
            }
            is GateOutcome.Rejected -> {
                val publisher = outcome.publisherToReview
                if (publisher != null) {
                    trustedSigners.markPending(publisher, name, connection.id)
                    throw PublisherReviewRequiredException(publisher, name, outcome.reason)
                }
                runCatching { Files.deleteIfExists(file) }
                throw LaunchAbortedException("verification failed for '$name': ${outcome.reason}")
            }
        }
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

        /** Concurrent descriptor fetches / jar download+verify: bounded by cores, never a flood on the tunnel. */
        val DEFAULT_PARALLELISM: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
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
         * Rewrite the **first** http(s)-URL application argument — the address the Administrator connects
         * to and shows on its login screen — so it reaches the server by the address the user typed.
         *
         * The JNLP carries the server's OWN idea of that address (`https://<host>:<https.port>`, or its
         * `server.url` property), which is wrong behind a port-forward / SSM tunnel (`https://localhost:31065`
         * for the server's `:8443`), a reverse proxy, or a VPN name. The user's address is, by definition,
         * the one the launcher just fetched everything from, so its **host** always wins, and so do its
         * scheme and port — with one exception. Mirth and OIE serve the Administrator's API over **https
         * only**: the plain-http port (`http://host:8080`) serves the launcher files and nothing else, and
         * a client sent there answers every call with a 405. So when the user typed an http address and
         * the server names an https one, the server's scheme and port stand (`http://localhost:8080` →
         * `https://localhost:8443`); typed https, or http on both sides (a server whose `server.url` is
         * http), keeps the typed scheme and port. If [serverUrl] carries no port the argument's own port is
         * kept, never dropped to the default. Path/query/fragment are preserved. Only the first URL
         * argument is rewritten; non-URL arguments (the version, `-ssl`, …) pass through. A no-op when
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
                    val typedHttp = base.scheme.equals("http", ignoreCase = true)
                    // The http port never serves the API: when the user typed http and the server says
                    // https, the client goes where the server says (its https port), on the typed host.
                    val (newScheme, newPort) = if (typedHttp && scheme == "https") {
                        u.scheme to u.port
                    } else {
                        // Typed port; if the typed address has none, the arg's own port (never dropped).
                        base.scheme to (if (base.port != -1) base.port else u.port)
                    }
                    runCatching {
                        URI(newScheme, base.userInfo, base.host, newPort, u.path?.ifBlank { null }, u.query, u.fragment).toString()
                    }.getOrElse { arg }
                } else {
                    arg
                }
            }
        }
    }
}
