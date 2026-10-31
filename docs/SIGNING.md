# Code-signing Open Integration Engine Launcher (Phase 4b, Layer 1)

Layer-1 signing is the **OS-facing** trust on the distributables themselves (the `.exe`/`.msi`/`.dmg`/
`.deb`…), separate from the Layer-2 jar-signature trust the launcher enforces at runtime. Signing runs
**after** the Phase-4a package build, as a Gradle/CI step.

**Dev uses self-signed identities; production swaps in real certificates purely via build secrets — no
code change.** Certificates and passwords are read from the environment and are **never** committed
(the dev artifacts land in the gitignored `tools/out/`).

---

## Windows — Authenticode (.exe + .msi)

### Prerequisites
- **WiX Toolset v3** on `PATH` (jpackage shells out to it to build the `.msi`/`.exe`). No admin: download
  `wix314-binaries.zip` from the [WiX 3 releases](https://github.com/wixtoolset/wix3/releases), unzip, and
  add the folder to `PATH`.
- **signtool.exe** (Windows SDK). If the SDK isn't installed, get it without admin from the NuGet package
  `Microsoft.Windows.SDK.BuildTools` (the `.nupkg` is a zip; `signtool.exe` is under `bin/<ver>/x64/`).

### Dev certificate (self-signed)
```powershell
./tools/gen-win-codesign-cert.ps1 -Password 'dev-pfx-pw'
# -> tools/out/gestalt-dev-codesign.pfx  (SECRET, gitignored)
```
A self-signed cert is **not** chained to a public root, so SmartScreen / "Unknown publisher" warnings are
expected on other machines. That is acceptable for internal testing only.

### Sign (post-package)
```powershell
# 1) build the installer(s) with WiX on PATH
$env:PATH = "C:\path\to\wix314;$env:PATH"
gradle :app-fx:packageMsi       # and/or :app-fx:packageExe / :app-fx:packageDistributionForCurrentOS

# 2) Authenticode-sign + RFC-3161 timestamp every produced .exe/.msi
gradle :app:signWindows `
  -Pgestalt.win.pfx="tools/out/gestalt-dev-codesign.pfx" `
  -Pgestalt.win.pfxPassword="dev-pfx-pw" `
  -Pgestalt.signtool="C:\path\to\signtool.exe"     # optional; auto-discovered in the Windows SDK
```
`signWindows` signs the app-image launcher `.exe` and every `.msi`/`.exe` installer with
`signtool sign /fd SHA256 /f <pfx> /tr <tsa> /td SHA256`. Configuration (property **or** env var):

| Property | Env var | Meaning | Default |
|----------|---------|---------|---------|
| `gestalt.win.pfx` | `GESTALT_WIN_PFX` | path to the code-signing `.pfx` | *(required)* |
| `gestalt.win.pfxPassword` | `GESTALT_WIN_PFX_PASSWORD` | `.pfx` password | empty |
| `gestalt.signtool` | `GESTALT_SIGNTOOL` | path to `signtool.exe` | auto-discover in the SDK |
| `gestalt.tsa` | `GESTALT_TSA_URL` | RFC-3161 timestamp authority | `http://timestamp.digicert.com` |

> The dev flow passes the password via `/p` for convenience. For CI/production prefer a cert in a
> store/HSM (`signtool /sha1 <thumbprint>` or a KSP/CSP) so no password is on the command line.

### Verify
```powershell
signtool verify /pa /v build\compose\binaries\main\msi\GestaltCloudConnect-1.0.0.msi
```
`/pa` uses the **Default Authentication Verification Policy** (the code-signing policy). For a dev
self-signed cert, expect "A certificate chain processed, but terminated in a root certificate which is
not trusted" — that is the SmartScreen warning, not a signature defect; the signature + timestamp are
present. With a production cert the chain validates cleanly.

### Production (OV/EV)
Obtain an OV or EV code-signing certificate from a CA. Supply it as the `.pfx` (OV) via
`GESTALT_WIN_PFX`/`GESTALT_WIN_PFX_PASSWORD` secrets, or for EV/HSM-backed certs point `signtool` at the
token's CSP/KSP (the `signWindows` invocation is the only thing that changes — no code change).

---

## macOS — Developer ID + notarization (.app / .dmg)

Run on a macOS build host (jpackage is per-host). Identities/credentials come from the build env.

```bash
# 1) codesign the app bundle with a Developer ID Application identity + hardened runtime + entitlements
codesign --force --deep --options runtime \
  --entitlements packaging/macos/entitlements.plist \
  --sign "Developer ID Application: Gestalt Diagnostics (TEAMID)" \
  "build/compose/binaries/main/app/Open Integration Engine Launcher.app"

# 2) notarize the .dmg (or a zipped .app) and WAIT for the result
xcrun notarytool submit "build/compose/binaries/main/dmg/Open Integration Engine Launcher-1.0.0.dmg" \
  --apple-id "$AC_APPLE_ID" --team-id "$AC_TEAM_ID" --password "$AC_APP_PASSWORD" --wait
#   (or: --keychain-profile "<profile>", or an App Store Connect API key)

# 3) staple the ticket so it verifies offline
xcrun stapler staple "build/compose/binaries/main/dmg/Open Integration Engine Launcher-1.0.0.dmg"
spctl --assess --type install -v "build/compose/binaries/main/dmg/Open Integration Engine Launcher-1.0.0.dmg"
```

Minimal `packaging/macos/entitlements.plist` for a hardened-runtime JVM app (add only what the app needs):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>com.apple.security.cs.allow-jit</key><true/>
  <key>com.apple.security.cs.allow-unsigned-executable-memory</key><true/>
  <key>com.apple.security.cs.disable-library-validation</key><true/>
</dict></plist>
```
Credentials (`AC_APPLE_ID`, `AC_TEAM_ID`, `AC_APP_PASSWORD`, or an API key) are secrets — never committed.

---

## Linux — GPG (.deb / .rpm)

Sign the packages, or publish a detached signature + checksums. Document the repo signing **public** key
so users can verify.

```bash
# .deb
dpkg-sig --sign builder build/compose/binaries/main/deb/*.deb        # uses your default GPG key

# .rpm  (needs ~/.rpmmacros: %_gpg_name <KEYID>)
rpm --addsign build/compose/binaries/main/rpm/*.rpm

# OR: detached signature + checksums (works for any artifact)
( cd build/compose/binaries/main && sha256sum */*.{deb,rpm} > SHA256SUMS \
  && gpg --armor --detach-sign SHA256SUMS )
# users verify:  gpg --verify SHA256SUMS.asc SHA256SUMS && sha256sum -c SHA256SUMS
```
Publish the signing **public** key (e.g. `gestalt-release-pubkey.asc`) alongside the downloads.

---

## Secrets summary (never committed)

| Platform | Secret(s) |
|----------|-----------|
| Windows  | code-signing `.pfx` + password (`GESTALT_WIN_PFX`, `GESTALT_WIN_PFX_PASSWORD`), or an HSM/token CSP |
| macOS    | Developer ID identity in the keychain; notarization Apple ID + app-specific password / API key |
| Linux    | GPG private key (in the build agent's keyring) |

These plug into the **same** build/sign steps used for the dev identities — switching from dev to
production is a secrets change, not a code change.

See also: [PACKAGING.md](PACKAGING.md) (building the artifacts) and [UPDATES.md](UPDATES.md) (the signed
self-update that consumes these signed artifacts).
