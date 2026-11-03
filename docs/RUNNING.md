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

Speed: `event=launch.timing … cachedAdmissions=N` counts the jars whose signature check was skipped
because exactly those bytes were admitted before under the same trust policy (`cache/admissions.json`);
the digest check still runs for every jar, every launch.

Client output:

- `logs/client/<connection>-<yyyyMMdd-HHmmss>.log` — one file per launch, the Administrator's own
  stdout/stderr (the installed launcher is windowless, so this is the only place its "error connecting
  to the server", TLS or class-loading complaints land). Headed with the server, version, Java and main
  class — never the command line, which carries the auto-login credentials. The newest 20 are kept.

## Port-forwards (tunnels)

A connection's **Tunnel** row has a switch, *Open a port-forward before launching*, and a command the
launcher runs *before* launching when the switch is on, with the user's own tools and environment —
typically a batch file that starts an AWS SSM port-forwarding session (`aws ssm start-session …
--document-name AWS-StartPortForwardingSession …`), or an `ssh -N -L …`. Switching it off keeps the
command but does not run it (`tunnelEnabled` in `connections.json`; files from before the switch
existed count as on when they have a command). The address's **port is the local end of the forward**
(`https://localhost:31065`), so it must be explicit; it is what the Administrator shows on its login
screen.

On Launch: if the port is already listening (a tunnel opened by hand, or by a previous launch whose
Administrator is still running) the command is not run and nothing is closed afterwards. Otherwise the
command runs (`cmd.exe /c …` on Windows, `/bin/sh -c …` elsewhere; a bare path to a file runs in that
file's folder), the launcher waits up to 60 s for the port — the Launch button reads *Opening tunnel…*
— then fetches and launches as usual. The tunnel is held while Administrators launched through it run
and torn down (the command and everything it spawned) when the last one exits, or when the launcher
closes. The command's output goes to `logs/client/<connection>-<stamp>-tunnel.log`; a tunnel that
fails reports the command's exit code and the log's last lines in the launch failure.

The tunnel is the Administrator's connection, so closing the launcher window — by hand, or with
*Close this window after launching* — must not take it down. While an Administrator launched from here
still depends on a forward the launcher opened, the window hides but the launcher keeps running (no
window, `audit.log`: `launcher.linger`), holding the tunnel, with everything else still live; it exits on
its own once the last such Administrator has closed. There is **one launcher per profile**: starting it
again meanwhile does not open a second one — the new process asks the running (hidden) launcher to show
its window and ends (`audit.log`: `launcher.show` / `launcher.handover`; `instance.lock` in the data dir
carries the loopback port and a per-run secret, and the lock itself dies with the process). A running
launcher that cannot be reached lets the newcomer start normally.

"When the Administrator exits" means the Administrator, not the first JVM: the client checks the heap
it was started with against the heap size saved in its own preferences and, when they differ,
**relaunches itself** in a new JVM and exits within a second. The launcher follows that hand-over
(`ProcessTree`): the row stays Running, close-after-launch and the tunnel wait for the JVM the client
actually runs in. (Before this, the forward was torn down under the relaunched client — "Connection
refused" on its login screen a second after it appeared.)

Fetches run in parallel through a tunnel as everywhere else — with one exception the launcher detects
from the plugin's own output: an SSM forward in **basic** (non-multiplexing) mode, used when the
instance's SSM agent is old, services one connection at a time and parks the rest in the listener's
backlog, so the launcher fetches one thing at a time through such a forward. Right before the
Administrator is started the launcher **re-checks** the port and reopens a forward of its own that has
stopped listening. A forward that opens and accepts the launcher's connection but whose session then
reports `Connection to destination port failed` (the SSM agent on the instance could not reach the
forwarded port) is **re-pointed at the engine's usual ports** — 8443, then 8000 — when the command
names its remote port in either form the AWS CLI takes: `portNumber=<n>` or JSON `"portNumber":["<n>"]`
(an SSM command line, or inside the batch file it runs: the launcher runs a copy from its cache dir with
only that number changed — `%~dp0` / `%~f0` spelled out as the original's folder and path — started in
the original's folder; the user's file is never edited; `audit.log`: `tunnel.retryPort`). The plugin
writes that line around the moment it drops the connection — and in basic mode may end the session on
it, which the launcher answers by reopening the forward — so the launcher gives it a moment (1.5 s) and
reads every log the forward has had before deciding. A forward another Administrator is still using is
not re-pointed under it. The port that answers is remembered per connection
(`cache/tunnel/remote-ports.json`, `tunnel.remotePort`) and used first next time; the command's own
port answering again forgets it. With every port refused the launch fails as *"The tunnel is up, but
the server didn't answer"*, naming the ports tried, rather than as a reset connection; a forward that dies *while* the Administrator uses it (a session timing out, a
dropped network) is reopened on its own, a bounded number of times, so the client can reconnect
(`audit.log`: `tunnel.reopen`, `tunnel.exited`).

A session can also end **the moment the launcher connects** — the plugin prints `Exiting session with
sessionId` right after `Connection accepted`, and an outdated plugin has been seen to do this on a
multiplexed forward. The process may live on (a batch file that ends in `pause` sits at "Press any key"
with the session gone), so the launcher reads the log, not just the process: a held forward whose
session has ended is torn down (the whole tree, `pause` included) and reopened in place, the launch
prepared again once (`audit.log`: `tunnel.sessionEnded … REOPEN`), and a session that ends again fails
as *"The tunnel's session ended as soon as the launcher connected"*, passing on the plugin's own
"outdated" warning when it printed one. **Upgrade the plugin** when it prints "An outdated
SessionManagerPlugin version detected" — that warning is the first line of the tunnel log.

The tunnel command is exported with the connection like every other setting. It is the one setting the
launcher *runs*, so an import that carries any shows them first — connection by connection, the command
verbatim — and asks whether to bring them in **switched on** (you recognise them: your own export) or
**switched off** (they arrive in the Connection card with the port-forward checkbox clear, to be turned
on one at a time). A file from elsewhere can never plant a command that runs unseen. `audit.log`
records `import.tunnelCommand … policy=KEEP|DISARM|DROP`. Re-importing your own export over connections
that already exist here keeps each one's switch as it is when the command is unchanged.

## When something goes wrong in the window

An exception that escapes on the UI thread is written to `audit.log` as `launcher.error` (the class,
the message and the top three frames) and shown as a toast; the window stays up. A launcher that cannot
build its model at all (an unreadable or locked profile) shows a plain error dialog (`launcher.fatal`)
and exits rather than hanging without a window.

## The jar cache and the shared pool

Each connection keeps the jars its server published under `cache/<connection>/<version>/`; a launch
hashes what is there and downloads only what no longer matches the digest the server publishes this
time. Beyond that, jars are **shared across connections by digest** in `cache/pool/<sha256>.jar`: a
jar the gate has admitted for one connection is filed there, and another connection whose server
publishes that same digest gets the bytes from disk instead of through its tunnel — so the first launch
of a second server on the same engine version takes seconds, not the minutes the ~170-jar download
takes over SSM. The pool is only a byte source: pooled bytes go through exactly what downloaded bytes
go through (hashed and compared to what *that* server publishes, then the gate or a cached admission).
A corrupt entry hashes wrong, is dropped, and the jar is fetched. Entries unused for 90 days are pruned.
`launch.timing` reports `fromPool=` beside `cachedAdmissions=`.

## How the Administrator is scaled and drawn

There is no display-scale setting. On a Windows desktop the Administrator is scaled by **Windows**: the
JVM draws at 100% (`sun.java2d.uiScale=1.0`, the embedded JavaFX — the tag editor is a `JFXPanel` —
pinned to the same with `glass.win.uiScale` / `glass.gtk.uiScale`, since on its own it would follow the
monitor and sit at 150% inside a 100% window) and the child runs DPI-unaware
(`__COMPAT_LAYER=DPIUNAWARE` in its environment), so the desktop scales the whole window as a bitmap,
**per monitor**: drag it to a screen with a different scale setting and it takes that screen's size, the
way any older program does. The size you expect, everything in step, slightly soft — the look the
Administrator had under the old launcher. The client log header says `# scaling: Windows`.

Java's own scaling (`sun.java2d.uiScale` at 125%, 150%, or following the monitor) was offered in 1.1.x
and withdrawn: the client's Swing look-and-feel was laid out for 100%, so a fractional factor keeps
text sharp but leaves one-pixel borders and bitmap icons uneven, a fixed factor never follows the
monitor, and users said they would not use it for that reason alone. A `uiScale` a connection still
carries from an older `connections.json` is ignored. On other platforms no factor is set and Java 9+
follows each monitor's DPI itself; Java 8 is scaled by the desktop everywhere.

What else the launcher sets for the client's rendering on Java 9+: Swing text is drawn with **subpixel
(LCD) antialiasing**, pinned (`awt.useSystemAAFontSettings=lcd`, `swing.aatext=true` — Java 9+
rasterizes with FreeType, and its grayscale antialiasing comes out light and thin; LCD is what the
desktop's own text uses and keeps the weight even); and the **software pipeline** rather than Direct3D
(`sun.java2d.d3d=false` — the GPU path composites one-pixel lines and glyphs and softens or unevens
them; the Swing UI needs no GPU). What it cannot change: the client's fonts and look-and-feel (the
Administrator sets them itself). The client log header records every option that was in force.

A first launch pulls every jar through the connection (minutes over an SSM forward); the launch toast
carries a progress bar that fills jar by jar, and the Launch button counts them, so a long download
never looks like a hang.

## Sign-in (auto-login)

The detail pane's **Sign-in** card is where the Administrator's credentials are set. The **Username** is
a field of the connection (saved with it, in `connections.json`). The **Password** is typed once and
*Save password* hands it straight to the OS credential store — Windows DPAPI (encrypted for the Windows
account), the macOS Keychain, or libsecret — keyed by connection and username; it never reaches
`connections.json`, the exports, or any log, and saving it applies at once (it also saves the username).
With both in place the launcher fills them into the Administrator's login screen and it signs in
(`audit.log`: `credential.access action=retrieve`, `child.autologin result=SUPPLIED`); with a username
alone the login screen opens with the username filled in and asks for the password
(`child.autologin result=USERNAME_ONLY`). Renaming the username on Save moves the saved password with it;
*Remove* deletes it; deleting the connection deletes it; *Save as…* and Duplicate never copy it.

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
  MCAL export; an **object** with `connections` is the launcher's own export) and dedupes by name+base URL
  (Merge/Replace/Skip; the JavaFX UI merges); legacy `/webstart` is stripped, modes/icons/javaHome are
  mapped and the legacy username kept, ids are regenerated, and **a password is never imported**.
  Export (sidebar ⋯ → *Export connections…*) writes a schema-versioned `connections.json` carrying
  **every setting** — address, username, Java runtime and heap, JavaFX home, SSL options, legacy DH,
  custom webstart/digest paths, close-after-launch, certificate auto-approve, the
  port-forward command and its switch, the folder tree the connections sit in (with ancestors) and the
  **custom icons** they use (the sanitized image bytes inline, re-sanitized and content-matched on
  import) — and nothing that is not a setting: saved passwords live in the OS keychain and are entered
  once more on the other machine; the certificate decisions (`trusted-certs.json`) are that machine's
  trust state and are made again there. Importing that file on a fresh install rebuilds the connections
  as they were; importing it where they already exist merges setting by setting and keeps what is the
  machine's own (ids, placement, pins, each forward's switch when its command is unchanged). On first
  run, if `-Dgestalt.legacy.connections=<path>` (or `GESTALT_LEGACY_CONNECTIONS`) points at a legacy
  file and the store is empty, it is imported automatically.
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
