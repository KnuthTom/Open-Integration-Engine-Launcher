# Open Integration Engine Launcher

[![CI](https://github.com/KnuthTom/Open-Integration-Engine-Launcher/actions/workflows/ci.yml/badge.svg)](https://github.com/KnuthTom/Open-Integration-Engine-Launcher/actions/workflows/ci.yml)
[![License: MPL-2.0](https://img.shields.io/badge/License-MPL--2.0-brightgreen.svg)](LICENSE)

An **independent, open-source desktop launcher** for the [Open Integration Engine (OIE)](https://www.openintegrationengine.org/) /
Mirth Connect **Administrator** client. It is a community replacement for the discontinued
NextGen *Mirth Connect Administrator Launcher (MCAL)*: you point it at your engine, it
**verifies and launches** the Administrator your server publishes — over a hardened, fail-closed
trust path — without you hand-managing Java Web Start, keystores, or JVM flags.

> **Independent project.** Not affiliated with, endorsed by, or sponsored by the Open Integration
> Engine project, NextGen Healthcare, or Mirth Connect. "Mirth" and "NextGen" are trademarks of
> NextGen Healthcare, Inc.; "Open Integration Engine" is a separate open-source project. Those names
> are used here only to describe what this launcher interoperates with. This is a **clean-room**
> implementation — it contains no Mirth/NextGen source and bundles no proprietary client.

> ⚠️ **Community-maintained, unsigned, no warranty.** Release binaries are **not code-signed**, so
> Windows SmartScreen and macOS Gatekeeper will warn on first run (see [Install](#install)). The
> software is provided "as is"; see the [LICENSE](LICENSE) and [NOTICE](NOTICE).

> 🙋 **Maintainers wanted.** The originator (Gestalt Diagnostics) is stepping back and donating this
> to the community. If you run OIE/Mirth and want to help steer the project, please open an issue —
> co-maintainers are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## What it does

- **Connection manager** — a list/form UI for your engines, with legacy (MCAL) import/export.
- **Fail-closed launch** — every downloaded Administrator jar must pass one verification gate
  (SHA-256 digest → code-signature → publisher pin) before it can reach the classpath. Any failure
  aborts the launch; there is no click-through bypass.
- **TLS that fits the field** — full chain + hostname verification for public-CA servers; exact
  server-leaf certificate **pinning** (trust-on-first-use) for self-signed engines and SSH/SSM
  tunnels, with a re-pin prompt when a server's certificate rotates.
- **Bundled client runtimes** — launches the Administrator on a bundled Zulu FX **JRE 8 or 17**
  (JavaFX included, which the Administrator needs), chosen by the server's version or per
  connection, so you don't need a system Java.
- **Credentials stay in the OS keychain** (Windows DPAPI / macOS Keychain / Linux libsecret) —
  never written to `connections.json` or any log. A saved password signs you in to the Administrator
  automatically, the way MCAL did.

Architecture and the trust model are documented in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and
[SECURITY.md](SECURITY.md).

## Install

Download the artifact for your OS from the [latest Release](https://github.com/KnuthTom/Open-Integration-Engine-Launcher/releases/latest).
Each release ships per-OS installers **and** a portable zip, with a `SHA256SUMS` file and a CycloneDX
SBOM. Verify the checksum before running:

```bash
sha256sum -c SHA256SUMS        # Linux/macOS
# Windows (PowerShell): Get-FileHash .\<file> -Algorithm SHA256
```

Because the builds are **unsigned**, the OS will warn on first launch:

- **Windows** — SmartScreen shows "Windows protected your PC". Click **More info → Run anyway**.
  (Or right-click the file → Properties → **Unblock**.)
- **macOS** — Gatekeeper says the app "cannot be opened". **Right-click the app → Open → Open**, or
  clear the quarantine attribute:
  ```bash
  xattr -dr com.apple.quarantine "Open Integration Engine Launcher.app"
  ```
- **Linux** — use the portable zip (runs unzipped, state under `./data`) or the distro package.

If you'd rather not trust a prebuilt binary, [build from source](#build-from-source) — it's the same
artifact.

## Build from source

Requires **JDK 17** (the Gradle toolchain pins it; no system Java needed beyond a JDK 17 to bootstrap).

```bash
./gradlew build            # compile + run all tests
./gradlew :app:run         # launch the product UI (Compose, being replaced)
./gradlew :app-fx:run      # launch the redesigned product UI (JavaFX)
./gradlew :app:run --args="--gallery"   # the design-system component gallery
```

Packaging an installer / portable image for the current OS is documented in
[docs/PACKAGING.md](docs/PACKAGING.md); the toolchain notes are in [docs/BUILDING.md](docs/BUILDING.md).
The build fetches the bundled Zulu FX runtimes from Azul at package time (not committed).

## Updates (optional)

The launcher can check a **signed** update manifest (Ed25519, fail-closed) and offer an update only if
it verifies. Point **Settings → Updates → Update channel URL** at wherever you publish
`latest.json` + artifacts — **GitHub Releases works as the channel**. The format, signing, and key
rotation are in [docs/UPDATES.md](docs/UPDATES.md). Leave the channel blank to disable update checks.

## Branding

The default build is **vendor-neutral**. An optional **Gestalt** branding pack (name, wordmark,
palette) ships in the source as `Branding.Gestalt` and the `design-system/.../branding/*.svg` assets;
opt in by passing it in `Main` if you want the original Gestalt identity. The product name is yours to
choose — see the `TODO(maintainer)` in `Branding.Default`.

## Contributing

Issues and PRs are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) and the
[Code of Conduct](CODE_OF_CONDUCT.md). Two house rules matter most: **keep it clean-room** (no
Mirth/NextGen source, ever) and **no real connection data** in code, tests, or docs (use synthetic
`example.com` / `localhost` values).

## License & credits

Licensed under the **Mozilla Public License 2.0** ([LICENSE](LICENSE)) — the same family as the
OIE / Mirth lineage. Third-party assets and runtimes are recorded in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md); code dependencies in the CycloneDX SBOM.

Originally built by **Gestalt Diagnostics** and released to the community. See [NOTICE](NOTICE).
