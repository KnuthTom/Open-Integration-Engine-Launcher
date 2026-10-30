# Running & Verifying Open Integration Engine Launcher (Phase 2 security core)

This covers how to build, how the fail-closed launch path behaves, and the **manual acceptance** for
re-launching the OIE/Mirth client over the SSM tunnel with the Phase-2 hardening on.

## Build & test

```bash
./gradlew build                 # all modules, all tests
./gradlew :launcher-core:test   # just the security-core tests
./gradlew cyclonedxBom          # writes build/reports/bom.{json,xml} (the SBOM)
```

> Gradle is not on PATH on the build machine; the wrapper (`./gradlew`) bootstraps Gradle 8.10.2. A
> standalone distribution at `C:\dev\_gradle_dist\gradle-8.10.2\bin\gradle.bat` is used for fast
> local builds.

Optional CVE gate (needs an NVD API key):

```bash
NVD_API_KEY=… ./gradlew dependencyCheckAggregate   # fails on CVSS >= 7.0
```

## What the launch path guarantees

The launcher is a **single verification chokepoint that fails closed**. Per launch:

1. `GET /webstart` (JNLP) over https — parsed for **jar list / main-class / arguments / version only**.
   Any `allow-self-signed` / `allow-incorrect-digest` / "disable-verification" directive in the JNLP
   is detected, audited as **ignored**, and has no effect.
2. `GET /digest/{version}` — the SHA-256 manifest.
3. Each jar is downloaded and run through `CodebaseVerifier`:
   - **(a) digest** vs the manifest, then **(b) signature** via the Phase-0 `JarSignatureVerifier`
     (integrity + chain-to-trusted-root + codeSigning EKU + RFC-3161 timestamp), then the
     **publisher pin** (PIN_KNOWN_SIGNERS, default).
   - Only a fully-passing jar becomes an `AdmittedJar` — the only type the classpath builder accepts.
4. Any miss raises `LaunchAbortedException`. No partial/degraded launch, no click-through bypass.

Trust posture:

- **PIN_KNOWN_SIGNERS** (default) pins three publishers by DN attributes (so cert renewals survive):
  `O="NEXTGEN HEALTHCARE, INC."` (Mirth), `O="BrightCodeCompany OÜ"` (OIE),
  `CN="Gestalt Cloud Connect Code Signing", O="Gestalt Diagnostics"` (Gestalt) — each must chain to a
  trusted root (Sectigo R46 / USERTrust, or the Gestalt self-signed anchor).
- **CA_ROOT** (any leaf chaining to a trusted root) is available via config as a fallback, not the default.
- There is **no `--allow-unverified-signatures` escape** anywhere in this codebase.

TLS:

- Every https call (JNLP, digest, jars, any OCSP/CRL) uses Apache HttpClient 5 with hostname
  verification **on**. `NoopHostnameVerifier` is never used.
- A **pinned** connection accepts iff the server leaf SHA-256 == the stored pin; a hostname mismatch
  is tolerated only for that exact pin (this is what lets the localhost/SSM-tunnel case work).
- The launcher's **own** fetch honors the connection's `sslProtocols` / `sslCipherSuites` overrides
  (modern 1.3/1.2 by default); the child JVM gets the same, and `-Djdk.tls.ephemeralDHKeySize=legacy`
  and weak protocols/ciphers stay **off** unless a connection sets `legacyTlsCompatibility = true`.
- For a **pinned** connection, the launched Administrator (which opens its own TLS connection to the
  server) is handed the pinned leaf as a per-connection PKCS12 truststore under
  `<dataDir>/truststores/<id>.p12` via `-Djavax.net.ssl.trustStore` — a store of public certs only,
  separate from the code-signing anchors. A presented leaf that doesn't match the pin **fails closed**.

Credentials:

- Passwords go only through `CredentialStore` (Windows DPAPI / macOS Keychain / Linux libsecret).
  They never touch `connections.json` (enforced by a secret scan at save time) or any log.

Audit:

- `logs/audit.log` (under the per-user dir, RollingFile, bounded retention) records connection
  add/edit/delete, server-cert pin/trust/revoke, codebase download + per-jar verification results,
  signature-enforcement decisions, child launch, and credential access — **events only, no secrets**.
  `event=launch.timing … descriptorsMs=… downloadVerifyMs=…` is where a slow launch shows which half
  was slow; `event=child.launch … reachedAt=…` is the address the launcher reached the server on.

Client output:

- `logs/client/<connection>-<yyyyMMdd-HHmmss>.log` — one file per launch, the Administrator's own
  stdout/stderr (the installed launcher is windowless, so this is the only place its "error connecting
  to the server", TLS or class-loading complaints land). Headed with the server, version, Java and main
  class — never the command line, which carries the auto-login credentials. The newest 20 are kept.

## Port-forwards (tunnels)

A connection's **Tunnel** field names a command the launcher runs *before* launching, with the
user's own tools and environment — typically a batch file that starts an AWS SSM port-forwarding
session (`aws ssm start-session … --document-name AWS-StartPortForwardingSession …`), or an
`ssh -N -L …`. The address's **port is the local end of the forward** (`https://localhost:31065`), so
it must be explicit; it is what the Administrator shows on its login screen.

On Launch: if the port is already listening (a tunnel opened by hand, or by a previous launch whose
Administrator is still running) the command is not run and nothing is closed afterwards. Otherwise the
command runs (`cmd.exe /c …` on Windows, `/bin/sh -c …` elsewhere; a bare path to a file runs in that
file's folder), the launcher waits up to 60 s for the port — the Launch button reads *Opening tunnel…*
— then fetches and launches as usual. The tunnel is held while Administrators launched through it run
and torn down (the command and everything it spawned) when the last one exits, or when the launcher
closes. The command's output goes to `logs/client/<connection>-<stamp>-tunnel.log`; a tunnel that
fails reports the command's exit code and the log's last lines in the launch failure.

Through a port-forward (a loopback address, or any connection with a tunnel command) the launcher
fetches over **one** TCP connection at a time: an SSM forward without TCP multiplexing (the plugin's
basic mode, used when the instance's SSM agent is older) services a single connection and parks the
rest in the listener's backlog until it closes, so "parallel" fetches there only time out and retry.
Right before the Administrator is started the launcher **re-checks** the port — an outdated
session-manager-plugin has been seen to drop the session when the launcher's connections close — and
reopens a dead forward it started (`audit.log`: `tunnel.reopen`). Upgrade the plugin when it prints
"An outdated SessionManagerPlugin version detected": the basic mode also gives the Administrator
itself a single connection at a time.

The tunnel command is local config like the Java home: it is exported with the connection but **never
imported** from a file (a file from elsewhere must not be able to plant a command the launcher would
run; `audit.log` records `import.tunnelCommand … NOT_IMPORTED`). Set it by hand on each machine.

## Testing a connection (diagnostic)

Before launching (or to triage a failure), run the **Test connection** diagnostic — the `Connect` button
on a connection row and in the form, or headless:

```bash
./gradlew :app:run --args="--test <connection-name|url> [--pin <sha256>]"
# e.g. --test https://localhost:31065        (first-connect: surfaces the untrusted leaf to pin)
#      --test https://localhost:31065 --pin fc457c…   (verify the pinned path)
```

It runs four staged checks and reports `PASS`/`FAIL` with the exact detail (full text appended to
`logs/diagnostics.txt`):

1. **DNS + TCP** — resolve the host and open the socket, distinguishing *host not found* vs *connection
   refused* vs *timed out*.
2. **TLS handshake** — capture the server **leaf** (CN, SANs, issuer, validity, SHA-256), validate it
   against the system CAs **and** the pin, and on failure **classify** it: `PKIX_PATH` (untrusted chain),
   `HOSTNAME_MISMATCH`, `PROTOCOL_OR_CIPHER`, or `PIN_MISMATCH`. An untrusted/self-signed leaf routes to
   the managed trust-on-first-connect dialog (review the SHA-256 → pin) — never a "trust anyway" bypass.
3. **JNLP** — `GET <baseUrl>/webstart`: HTTP status + byte count.
4. **Digest** — `GET <baseUrl>/digest/<version>`: HTTP status (a 404 is fine when the JNLP carries inline
   per-jar SHA-256, as a real OIE/Mirth server does).

The diagnostic uses the **same** transport the real launch uses, so a `TLS PASS` here means a launch will
connect. The four everyday cases: plain-http localhost (works, no TLS); an SSM-tunnel / VPN host whose cert
names the appliance not the address (untrusted + hostname-mismatch → pin → passes, hostname bypassed by the
pin); and a public-CA FQDN (validates by the normal chain + hostname, **no pin**); an unreachable host
reports a clean DNS/refused/timeout, never a TLS error or stack trace.

## Manual acceptance — relaunch over the SSM tunnel

> Requires the live OIE server reachable via the SSM port-forward (e.g. `https://localhost:8443/`).
> This step is manual because it needs the tunnel and the real client.

1. **Add the connection** (non-secret), pointing at the tunnel URL. Save it; confirm
   `connections.json` contains the host/username but **no password**.
2. **Pin the server cert**: probe the leaf (`ServerCertProbe.fetchLeaf`), review the SHA-256, and pin
   it (`ServerCertPinning.pin`). Confirm an `event=servercert.pin …` line in `audit.log`.
3. **Store the credential** via the CredentialStore (Windows: DPAPI). Confirm it is in the OS
   keychain and **not** in any file under the data dir except the encrypted blob.
4. **Launch.** Expect:
   - signature enforcement **ON** — the genuine OIE jar admits (BrightCodeCompany pinned), and the
     client starts; a tampered or unknown-signer jar would abort with a clear reason.
   - hostname verification **ON** — the pinned localhost cert is accepted by pin, not by a disabled check.
   - `-Djdk.tls.ephemeralDHKeySize` is **NOT** on the child command line (check the spawned process
     args) unless the connection opted into legacy TLS.
   - the saved credential is read from the keychain (an `event=credential.access action=retrieve` line
     appears; the value never does) and the Administrator signs in on its own
     (`event=child.autologin result=SUPPLIED`); without a saved password it shows its login screen.
   - `logs/audit.log` shows the full sequence: `codebase.download`, one `jar.verify … result=ADMITTED`
     per jar, `signature.enforce … result=ACCEPT`, and `child.launch`.

## Design system & gallery (Phase 3a)

The `:design-system` module is a fully token-driven Compose theming engine: `GestaltThemeData`
(colors, typography, shapes, spacing, elevation, component variants) is produced by
`gestaltTheme(skin, mode, branding)` and provided via `GestaltTheme { … }`; components read everything
they draw from `GestaltTheme.colors/.shapes/.spacing/.variants` — no hardcoded colors, radii, or
spacing. **Skin** (Sharp/Soft) and **Mode** (Light/Dark) are independent axes; **Branding** seeds the
palette and supplies the app name + logo. Icons are bundled SVGs — Lucide (ISC) for chrome, Health
Icons (CC0) for the pathology connection set — tinted from the color tokens (see
[`THIRD_PARTY_LICENSES.md`](../THIRD_PARTY_LICENSES.md)).

Run the **component gallery** (the Phase 3a acceptance artifact). As of Phase 3b the product UI is the
default window, so the gallery is behind a flag:

```bash
./gradlew :app:run --args="--gallery"
```

The minimal non-UI path is also reachable behind a flag:

```bash
./gradlew :app:run --args="--cli"   # prints identity + trust posture, then exits
```

### Manual acceptance — the whole UI re-skins

1. **Launch the gallery** (`:app:run --args="--gallery"`). The header shows the **Gestalt** mark + app name (from
   `Branding`); the default look is **Sharp + Light** with the Indigo base and sparing Magenta/Gold.
2. **Flip Skin → Soft.** Confirm the ENTIRE gallery changes at once — corner radius (square → rounded),
   density (compact → comfortable), elevation (flat/bordered → shadowed cards), and the component
   variants: nav rail → top tabs, flat rows → cards, outlined fields → underlined, the default button
   filled → outlined. Colors are unchanged (skin is structural).
3. **Flip Mode → Dark.** Confirm only the colors change (background/surface/text invert; primary/accent
   lighten for contrast); structure is identical to the current skin.
4. **Open the Connection icons tab** and confirm the pathology glyphs (microscope, specimens, blood
   work, cells, DNA, lab, …). Pick one; confirm the sample "connection row" on the Components tab
   updates to the chosen icon, and that every icon recolors with the theme.

## Product UI (Phase 3b)

The product app (the default `:app:run` window) is built entirely from `:design-system` components and
re-skins live with the Skin/Mode switcher in the top bar. It replaces the old CLI default.

```bash
./gradlew :app:run                      # the product UI (connection list / form / launch)
```

What it does:
- **Connection list** — each connection renders as a themed list row: pathology icon, name, group +
  base URL (muted), a reachability status dot, and row actions (Launch / Edit / Duplicate / Delete).
  Search (name/URL/group); sort Manual/Name/Group/Status (group headers when grouped); **drag the grip
  handle to reorder** in Manual mode (persisted via `sortOrder`). Status is an on-demand, async,
  cancellable TCP reachability probe (click a dot, or "Check all") — never run automatically on load.
  Names/groups implying prod/stage/test/dev get a small environment chip.
- **Connection form** — mode radio (Standard host+port; Localhost/port-forward = port only; Custom = full
  URL), each with an **https/http scheme choice** (https default; plain http is allowed for servers like an
  OIE appliance on `http://localhost:8080` and is flagged "NO TLS" on the row/form/diagnosis), icon picker, Java runtime
  (Automatic/Bundled[8|17]/Custom path), a tunnel command (see *Port-forwards*), JavaFX home, max heap, show-console, SSL protocols/ciphers
  (Default/Custom), legacy-DH toggle, optional pinned-cert SHA-256, and optional username/password that
  are written **only to the OS keychain** (never to `connections.json`). New / Save / Save As / Delete.
- **Import / Export** — Import auto-detects the format by JSON root node (a top-level **array** is a legacy
  MCAL export; an **object** with `connections` is a Gestalt export) and dedupes by name+base URL
  (Merge/Replace/Skip, chosen in the toolbar); legacy `/webstart` is stripped, modes/icons/javaHome are
  mapped, ids are regenerated, and **secrets are never imported**. Export writes a schema-versioned
  Gestalt `connections.json` with no secrets. On first run, if `-Dgestalt.legacy.connections=<path>` (or
  `GESTALT_LEGACY_CONNECTIONS`) points at a legacy file and the store is empty, it is imported
  automatically.
- **Launch** — runs the existing Phase 1/2 pipeline unchanged (`LauncherCore.launch`): fetch/parse JNLP
  → download + `CodebaseVerifier` (digest + signature, fail-closed) → spawn the Administrator child JVM.
  The connection's settings are applied to the child JVM (selected Java home, `-Xmx`, console window via
  `java`/`javaw`, custom SSL protocols/ciphers, legacy DH only when opted in); pinned connections
  validate against the pin. Row status shows Verifying → Launching → Running / Failed; every step is in
  `logs/audit.log`. "Close after launching" is a persisted toggle.

### Manual acceptance — import, reorder, launch

1. **Launch the app** (`:app:run`). Header shows the Gestalt wordmark + name; defaults Sharp + Light.
2. **Import** your legacy `connections.json` (toolbar → Import). All ~36 connections appear with mapped
   icons and modes; open one in each radio mode and confirm host/port/URL are derived correctly.
3. **Drag** a row by its grip handle to reorder; relaunch the app and confirm the order persisted.
4. **Launch** the OIE appliance (e.g. a `https://localhost:9912` port-forward over the tunnel) and
   confirm it still downloads, verifies (signature ON), and opens the Administrator — honoring the
   connection's heap / console / Java-home settings. No admin rights required.

## Management & settings (Phase 3c)

Three new areas, all built from `:design-system` components and re-skinning with the theme. They are a
**UI over** `:core-trust` / `:launcher-core` — no verification or launch logic was changed or forked.

**Trust** (top-bar nav → *Trust*):
- **Server certificates** — every connection with a pinned cert is listed. *Details* probes the live
  leaf (`ServerCertProbe`) and shows subject / issuer / SHA-256 / validity, flagging **near-expiry** and
  a **pin mismatch** (the server is presenting a different cert than the one pinned). *Re-pin* re-runs
  the managed review; *Unpin* removes the pin. Both are audited.
- **Trust on first connect** — when a launch reaches an untrusted/self-signed server, the launch stops
  and the in-UI **diagnosis** offers *Review & pin certificate*. That captures the presented cert and
  opens a managed dialog (subject / issuer / fingerprint / validity) with an explicit **Trust & pin**
  action. This is the deliberate replacement for the old click-through TOFU; every pin is written to
  `audit.log` (`event=servercert.pin`).
- **Code-signing trust** — the posture toggle (**PIN_KNOWN_SIGNERS** default / **CA_ROOT**) takes effect
  on the next launch (it is fed into `LauncherCore`'s gate mode). The three built-in publisher pins
  (NextGen / BrightCodeCompany / Gestalt) and the bundled CA anchors (Sectigo R46 / USERTrust + the
  Gestalt self-signed anchor, with expiry warnings) are shown read-only; an **advanced** section adds /
  removes a custom signer DN or a custom CA-anchor PEM (audited, fed into the gate).

**Settings** (top-bar nav → *Settings*): default theme + "close after launching"; **launch defaults**
(default Java runtime + heap, applied to new connections); **trust-posture default**; **log level**;
data / logs / cache paths with *Open folder* and *Clear cache*; update-channel URL. **Build & Signing**
(developer/reseller) captures the keystore **path**, key **alias**, and **TSA URL** only — the PKCS12
private key and passphrase are **never** stored (taken from the OS keychain or prompted at build time);
the active branding (name / logo / palette) is shown read-only. The signed build is Phase 4.

**In-UI diagnosis**: on any launch failure the row shows *Failed (details)* and a dialog explains the
likely cause + next step in plain language — **never a stack trace, never a verify-weakening action**.
Classes handled: server-cert untrusted, TLS handshake/cipher mismatch, **digest mismatch (hard fail, no
override)**, **untrusted signer (points to the trust posture, no override)**, host unreachable,
invalid JNLP descriptor, and missing Java runtime (names the configured runtime). Each links to the
relevant fix (trust dialog / connection form / trust settings) and to *View logs* (opens `logs/`).

### Manual acceptance — trust, settings, diagnosis

> Requires the OIE appliance reachable via the SSM port-forward (e.g. `https://localhost:9912`).

1. **Trust on first connect.** Add/point a connection at the tunnel and **Launch**. The launch stops and
   a diagnosis (“Server certificate isn't trusted”) appears; click **Review & pin certificate**, confirm
   the captured cert's fingerprint, and **Trust & pin**. Confirm `connections.json` now has the connection's
   `pinnedCertSha256` and `audit.log` has `event=servercert.pin`. **Relaunch** — it now passes the TLS pin.
2. **Settings / Build & Signing.** Open *Settings*, set a keystore path, alias and TSA URL. Confirm they
   persist across a restart, and that `ui-prefs.properties` contains the path/alias/TSA but **no**
   `password` / `passphrase` / private-key material.
3. **Diagnosis without a stack trace.** Stop the tunnel and **Launch** again. Confirm a clear
   “Couldn't reach &lt;host:port&gt;” diagnosis with *Open connection* / *View logs* — not a raw exception.

## Known residual / next-phase

- **The `/digest/` manifest is not yet signed.** It is fetched over verified/pinned https, but a
  malicious or compromised server could publish a digest matching a tampered jar. This does **not**
  defeat the gate: the **signature** leg is the real authority — `JarSignatureVerifier` requires every
  jar entry to be covered by the publisher's signature (the completeness check added after the Phase-2
  security review), so an added/swapped class is rejected regardless of the digest. Signing the digest
  manifest (and PKIX-validating the TSA chain) is tracked in
  [`docs/INTERFACE-SPEC.md`](INTERFACE-SPEC.md) for the server phase.
- The macOS/Linux credential stores are implemented (native Security framework via JNA; libsecret)
  but are not exercised by CI on Windows; the Windows DPAPI path is.
