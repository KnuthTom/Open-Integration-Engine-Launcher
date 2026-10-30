# Architecture

Contributor onboarding for the Open Integration Engine Launcher — how it's structured and, above all,
how the **fail-closed trust path** works. (Sanitized for the public repo: no real connection data,
hostnames, or environment specifics appear here; use synthetic `example.com` / `localhost` values when
extending docs or tests.)

## Big picture

The launcher does one security-critical job: given a connection, **fetch the Administrator client your
server publishes, prove it is genuine and intact, and only then run it** — on a bundled JVM with
default-secure flags. There is a single verification chokepoint and no click-through bypass: any
failure aborts the launch.

It is a **clean-room** implementation. It contains no Mirth Connect / NextGen source and bundles no
proprietary client jar; the Administrator is downloaded from the user's own server at launch time and
verified, never redistributed.

## Modules

| module           | responsibility                                                                    |
|------------------|-----------------------------------------------------------------------------------|
| `:core-trust`    | Pure jar-signature verification (`JarSignatureVerifier`): CMS/PKCS#7 parsing, manifest integrity, PKIX path validation, codeSigning EKU, RFC-3161 timestamp awareness. No UI, no networking. Returns a rich `VerificationResult`. |
| `:launcher-core` | The launch path and everything around it: the verification **gate**, JNLP parsing, artifact fetch, TLS + pinning, the connection model + store + importer, credentials, audit log, and self-update. No Compose UI. |
| `:design-system` | A token-driven Compose theming engine (skins/modes/density), themed base components, an icon registry, and the branding shell (`Branding`). |
| `:app`           | The Compose Desktop product UI (connection list / form / import-export / launch) plus the `--gallery` and `--cli` entry points, built entirely on `:design-system` + `:launcher-core`. Being replaced by `:app-fx`; retires at parity. |
| `:app-fx`        | The product UI rebuilt on JavaFX 21 (JDK 17 runtime) to the 2026 redesign, on `:launcher-core` only. `LauncherModel` (JavaFX properties over the store; headless-testable), `LaunchController` (one launch in flight per connection, cancel by interrupt, prompts routed as events), `TreeRows` (the sidebar's sort / filter / folder walk), and stock controls styled by `base.css` + a light / dark colour sheet swapped on the Scene. Icons are Lucide path data drawn as `SVGPath`s. |

## The launch pipeline (fail-closed)

Implemented in `launcher-core` (`LauncherCore.prepareLaunch` builds a verified plan; `launch` then
spawns it):

1. **Pin check (pinned https only).** Capture the server leaf certificate and fail closed if it
   doesn't match the stored pin. The verified leaf is also handed to the child JVM (below).
2. **Fetch + parse JNLP (untrusted).** Only the jar list, main-class, version, and application
   arguments are read. The parser is XXE-hardened; any "allow-self-signed" / "skip-digest"-style
   directive is detected, audit-logged as ignored, and inert.
3. **Resolve digests.** Prefer the per-jar SHA-256 the JNLP carries inline; fall back to an external
   `/digest/<version>` manifest only if absent.
4. **Download + gate each jar.** `CodebaseVerifier.verify` runs three checks in order — **digest**
   (vs. the manifest) → **signature** (`:core-trust`) → **publisher pin** (in `PIN_KNOWN_SIGNERS`
   mode). Only a jar that passes all three becomes an `AdmittedJar` — the *only* type the classpath
   builder accepts, so nothing unverified can reach the classpath. Any rejection throws
   `LaunchAbortedException`.
5. **Build the child-JVM command.** `ChildJvmCommandBuilder` assembles a default-secure command:
   modern TLS only, the classpath from `AdmittedJar`s, the bundled runtime (Automatic: Java 8 for
   servers up to 4.6, Java 17 from 4.7 — or the connection's explicit choice),
   and — only on Java 9+ — a *curated allow-list* of module flags from the JNLP (`--add-opens` etc.).
   The client's server-URL argument is rewritten to the typed host, keeping the typed scheme and port
   except that a plain-http address is sent to the server's https port, the only one that serves the API.
   Server-provided `-D`/`-javaagent`/`-X` are dropped. For a pinned server, the leaf is written to a
   per-connection PKCS12 truststore and passed to the child so its own TLS connection succeeds.
6. **Spawn.** `LauncherCore.launch` is the only place a process starts (`ProcessBuilder(...).start()`),
   strictly after a fully verified plan.

`LauncherCore` is constructor-injectable (fetcher, server-leaf probe, sleeper), so the whole pipeline
runs headlessly in tests without real networking or spawning a process.

## Trust model

- **Signature enforcement** defaults to `PIN_KNOWN_SIGNERS`: the signer leaf must match a pinned
  publisher (by DN attributes, so cert *renewals* survive) **and** chain to a trusted anchor. `CA_ROOT`
  (any codeSigning leaf chaining to an anchor) is a config fallback. The bundled anchors are public
  root CAs.
- **TLS** (`HttpClientFactory`, HttpClient 5, hostname verification always on): non-pinned connections
  get full chain + hostname verification; pinned connections match the exact server-leaf SHA-256 and
  tolerate a hostname mismatch only for that pinned leaf (the field reality for self-signed engines and
  SSH/SSM tunnels). A rotated server cert fails closed with a re-pin prompt.
- **Credentials** live only in the OS keychain (Windows DPAPI / macOS Keychain / Linux libsecret). The
  connection store is secret-scanned on save, so a password can never land in `connections.json`; the
  importer likewise never imports credentials. At launch, a saved password is read from the keychain and
  handed to the Administrator as its 3rd and 4th program arguments (`[serverUrl, version, username,
  password]`) — the auto-login the client itself defines and MCAL used. The launch plan stays
  secret-free; the credentials join the command only at the spawn, only for the stock two-argument
  descriptor, the array is zeroed afterwards, and the audit log records the event, never the value.
  The inherited caveat: a program argument is visible to other processes of the same user.
- **Audit** — security-relevant events are appended to `logs/audit.log`, never including a secret.
- **Client output** — the spawned Administrator's stdout/stderr go to `logs/client/<connection>-<stamp>.log`
  (newest 20 kept; header without the command line), so a login that fails inside the client is
  diagnosable from a windowless launcher.
- **Tunnels** — `TunnelManager` opens a connection's port-forward before the launch (its *tunnel
  command*, run through the platform shell with the user's own environment), waits for the address's
  port, and holds the process per connection with a lease count: shared by concurrent launches of the
  same connection, torn down — the whole process tree — when the last Administrator exits or the
  launcher closes. A port that is already listening is used as-is and never torn down. The command is
  trusted local config and is dropped on import.
- **Loopback address family** — `localhost` is two addresses and a port-forward listens on one of them.
  The launcher's own fetches fall back across both and record which answered
  (`ArtifactFetcher.lastRemoteAddress`); the child JVM is then pinned to that family
  (`-Djava.net.preferIPv4Stack=true` for 127.0.0.1, `-Djava.net.preferIPv6Addresses=true` for ::1) so
  its own connection can't be stranded on the one the tunnel doesn't serve. Remote hosts keep the
  default dual stack.

## Connections, storage, import

- `Connection` is the model; `ConnectionStore` persists the connections and their `Folder` tree to
  `connections.json` (schema 2) with an **atomic, crash-safe write** (temp → rename, `.bak` fallback,
  corrupt-file recovery). Folders nest (`parentId`), keep a manual order and their expanded state;
  a connection carries `folderId`, `addedAt` and `lastLaunchedAt` (the sort modes and the header's
  "Launched … ago"). A schema-1 file's flat `group` becomes a top-level folder on load; deleting a
  folder lifts its contents to the parent, never drops them.
- `ConnectionImport` detects format by root node: a top-level JSON **array** is a legacy MCAL export; an
  object with a `connections` array is the launcher's own format. Import regenerates duplicate ids,
  derives connection mode, strips a trailing `/webstart`, maps legacy icon/runtime fields, and **drops
  credentials**. An export carries the folders of the exported connections (with their ancestors);
  an import brings them back as a fresh subtree at the top level.
- The shipped app starts with **no connections** — users add their own. (Test fixtures use synthetic
  data only.)

## Self-update (optional)

`UpdateService` fetches a signed manifest (`latest.json`) over hardened HTTPS, verifies an **Ed25519**
signature against one or more pinned public keys, checks the version is strictly newer, and verifies
the downloaded artifact's SHA-256 — all fail-closed. The pinned-key list supports rotation (ship the
new public key in an update signed by the current key, then switch signers). GitHub Releases can host
the channel. See [UPDATES.md](UPDATES.md).

## Packaging & runtimes

`:app-fx` uses the Compose Gradle plugin as a jpackage wrapper to produce per-OS installers and a
portable app-image (state under `./data` beside the executable, via a sentinel file); JavaFX runs from
the classpath in the packaged app, started by a plain `main`. Each package bundles **two client runtimes**
(Zulu FX JRE 8 + 17 — JavaFX included, which the Administrator needs — fetched from Azul at build time,
not committed) for launching the Administrator; the launcher itself runs on its own jlinked JDK-17
runtime. See [PACKAGING.md](PACKAGING.md)
and [BUILDING.md](BUILDING.md).

## Theming & branding

Every visual value comes from theme tokens; nothing is hardcoded. `Branding` carries the app name,
logo, and palette. The default build uses the vendor-neutral `Branding.Default` (wordmark, neutral
palette); `Branding.Gestalt` is an **optional** branding pack (the original identity + SVG assets).

## Where state lives

All persisted state (connection list, credential blobs, truststores, cached verified jars, prefs,
audit log) lives under a single per-user data dir (or `./data` in portable mode). Tests redirect it via
`-Dgestalt.home=<build dir>` so the suite never touches a real profile, and nothing real is ever
committed.
