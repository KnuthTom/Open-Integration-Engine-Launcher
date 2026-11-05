# CLAUDE.md — project guide & context handoff

Context for any AI assistant (or human) working on this repo. It's self-contained: a fresh session can
read this and be productive without prior chat history. Contains **no real data or secrets** (synthetic
values only) and is safe to keep in a public repo.

## What this is
An independent, open-source **desktop launcher for the Open Integration Engine (OIE) / Mirth Connect
Administrator** client — a community replacement for NextGen's discontinued *Mirth Connect Administrator
Launcher (MCAL)*. It verifies (fail-closed) and launches the Administrator the user's own server
publishes. **Clean-room**: no Mirth/NextGen source, no bundled proprietary client. Licensed **MPL-2.0**.

> Not affiliated with / endorsed by the OIE project, NextGen Healthcare, or Mirth Connect. Those names
> are used descriptively. Originally built by **Gestalt Diagnostics**, released to the community.

## Status (2026-11-04)
- **Repo (source of truth):** `github.com/KnuthTom/Open-Integration-Engine-Launcher`, branch `main` —
  currently **PRIVATE** (flip to public when ready).
- Default branding is **vendor-neutral** (`Branding.Default`); the original Gestalt identity is an
  **optional** pack (`Branding.Gestalt`). Final product name is the maintainer's call — see the
  `TODO(maintainer)` in `Branding.Default`.
- Release binaries are **UNSIGNED** (community). A **v1.0.1** Windows MSI (the Compose UI) exists;
  **v1.1.x** are the first releases of the JavaFX UI (`:app-fx`), built with `./gradlew :app-fx:packageMsi`;
  bump `gestaltVersion` before every MSI — Windows Installer will not upgrade a product over the same version.
- **1.1.5** (JavaFX UI): port-forward resilience (refused-port fallback, sessions that end under the
  launcher, `pause`-parked scripts), the shared jar pool, full export/import round trip (trust never
  from a file), Windows scaling hard-wired, launcher colours, Quit, branded Windows process names.
- **Next (unreleased):** notices in the detail pane's bottom bar (no floating toast; the launch
  notice's X cancels), sidebar multi-select with drag / Move-to of the whole selection, icon card
  imports several images and sets the default icon for new connections, new connections start with
  auto-approve on, the Colors card as swatch rows with Cancel.
- **330+ tests pass** (`./gradlew build`).

## Stack & modules
Kotlin 2.4 · Compose Multiplatform 1.9.3 (`:app`, retiring) · JavaFX 21 (`:app-fx`) · Gradle 8.14.5 (wrapper) · JDK 17.

| module | responsibility |
|---|---|
| `:core-trust` | Pure jar-signature verification (CMS/PKIX/EKU/RFC-3161). No UI/network. |
| `:launcher-core` | The fail-closed launch path: verification gate, JNLP parse, TLS + pinning, connection model/store/import, credentials, audit, self-update. |
| `:design-system` | Token-driven Compose theming, components, icon + branding shells. |
| `:app` | Compose Desktop product UI (list / form / import-export / launch); `--gallery`, `--cli`, `--test`. **Being replaced by `:app-fx`.** |
| `:app-fx` | The product UI rebuilt on **JavaFX 21** (runs on the JDK 17 runtime) to the 2026 redesign: folder tree sidebar, detail pane with one Launch, trusted-certificate review. `./gradlew :app-fx:run`. |

Full architecture + trust pipeline: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Threat model:
[`SECURITY.md`](SECURITY.md).

## Build / run / test / package
```bash
./gradlew build                          # compile + run ALL tests (needs JDK 17)
./gradlew :app:run                       # product UI (Compose, retiring)
./gradlew :app-fx:run                    # product UI (JavaFX redesign)
./gradlew :app:run --args="--gallery"    # design-system component gallery
./gradlew :app:run --args="--test https://host:8443"   # headless connection diagnostic
./gradlew :app-fx:packageMsi             # Windows installer -> app-fx/build/compose/binaries/main/msi/
./gradlew :app-fx:packageAll             # current-OS installer(s) + portable zip (the JavaFX UI is the product)
./gradlew cyclonedxBom                   # SBOM -> build/reports/bom.{json,xml}
```
Packaging bundles Azul Zulu **FX** JRE 8 + 17 (the *client* runtimes — JavaFX included, which the
Administrator needs), fetched from Azul at build time; a connection on Automatic gets 8 for servers up
to 4.6 and 17 from 4.7.
`:app-fx` packages through the Compose Gradle plugin used purely as a jpackage wrapper (WiX download,
app-image, bundled resources); JavaFX runs from the classpath in the packaged app.

## House rules (do not break these)
1. **Clean-room** — never add decompiled/copied/derived Mirth/NextGen source or a vendored client jar.
2. **No real connection data** anywhere (code/tests/fixtures/docs/screenshots) — synthetic only:
   `localhost`, `127.0.0.1`, `*.example.com/.org`, `*.example.internal`, fake orgs ("Example Lab").
3. **No secrets** — no keystores/keys/`.pfx`/`.p12`/tokens/`.env`. `.gitignore` + a CI gitleaks scan
   (`.gitleaks.toml` allowlists the synthetic fixture passwords) guard this.
4. Every source file carries the standard **MPL-2.0 header**; new files too.

## Trust model (the important part)
Fail-closed pipeline (`LauncherCore.prepareLaunch` builds the plan; `launch` spawns it — the only spawn
point): pin check → fetch+parse JNLP (untrusted, XXE-hardened) → digests → download → **single gate**
(`CodebaseVerifier`: digest → signature → publisher pin) → only an `AdmittedJar` reaches the classpath →
default-secure child-JVM command. `PIN_KNOWN_SIGNERS` pins NextGen/Mirth + BrightCode/OIE by DN
(renewal-safe). TLS: full chain+hostname for public CAs; exact-leaf **pinning** (TOFU + re-pin) for
self-signed engines / SSH-SSM tunnels. Credentials live only in the OS keychain; the store is
secret-scanned on save. The `Gestalt Cloud Connect Code Signing` pin DN is a kept, functional anchor.

## Connections & data
- App ships with **no connections** — starts empty; users add their own. Profiles are deletable in the
  UI (row ⋮ → Delete; multi-select → Delete) with full per-connection cleanup.
- Per-user data dir is the vendor-neutral **`OpenIntegrationEngineLauncher`** (under %LOCALAPPDATA% /
  ~/Library / XDG). The older `GestaltCloudConnect` dir is **not** migrated — clean slate; prior data is
  left untouched for manual import.
- The legacy-import test fixture (`launcher-core/.../fixtures/legacy-connections.json`) is **synthetic**
  (the importer test asserts its fake credentials are never persisted).

## CI / releases
- `.github/workflows/ci.yml` — gitleaks + Gradle wrapper validation + build/test on ubuntu/windows/macos.
- `.github/workflows/release.yml` — push a `v*` tag → builds per-OS installers + portable + SBOM +
  `SHA256SUMS` → **draft** Release. Free for public repos; no signing secrets.

## Dev environment gotchas
- Gradle isn't on PATH → always use `./gradlew`.
- Tests redirect the data dir via `-Dgestalt.home=<build dir>` so they never touch a real profile.
- Packaging needs network (Azul CDN) and grows `app/build` to ~1 GB — run `./gradlew clean` after. The
  dev box runs tight on disk.
- `git http.sslVerify` should stay **true** (a prior `false` global setting was removed).

## Phase lineage (how it got here)
1–2 trust + fail-closed launch cores · 3 product UI + design system · 4 packaging + signing + self-update
· bug-fix/hardening passes · **5 validation** (matrix harness + cert-rotation drill + soak;
`launcher-core/.../matrix/`) · **OSS release**: IP/data scrub, clean history re-init, MPL relicense,
vendor-neutral rebrand, free CI/Releases.

## Open follow-ups
- Add `CODE_OF_CONDUCT.md` + issue/PR templates (via GitHub UI — intentionally not authored here).
- Publish the **v1.0.1** Release (web upload of the local MSI, or push a `v1.0.1` tag for CI).
- Decide the final product **name** (`Branding.Default`) and whether to switch the commit author email
  from `tomknuth1@gmail.com` to the GitHub no-reply before going public.
- Minor cleanups: the portable-mode sentinel is still `gestalt-portable.txt`; the three Phase 5 test
  files print "Gestalt Cloud Connect 1.0 — Phase 5 …" in their generated report headers (gitignored
  output, cosmetic) — neutralize when convenient.
- Flip the repo to **public** when ready.

## Pointers
- A copy of a similar snapshot lives at `C:\dev\OIE-Launcher-project-snapshot.md` on the originator's
  machine (outside the repo). This `CLAUDE.md` is the in-repo, durable version.
