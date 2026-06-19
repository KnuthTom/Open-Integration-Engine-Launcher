# Security Policy

This project is a security tool: it exists to **verify** the Administrator client before launching it.
We take reports seriously and appreciate responsible disclosure.

## Reporting a vulnerability

Please report security issues **privately**, not in public issues or pull requests:

- Preferred: open a **GitHub Security Advisory** —
  [Report a vulnerability](https://github.com/KnuthTom/Open-Integration-Engine-Launcher/security/advisories/new).
- Include enough to reproduce: affected version/commit, OS, and a minimal scenario.

You'll get an acknowledgement and, where applicable, a fix and a credited advisory. Please give
maintainers reasonable time to release a fix before any public disclosure.

> Note: this is a volunteer, community-maintained project with no paid security team or SLA. We'll do
> our best.

## What's in scope

The trust-critical surface is the most valuable to scrutinize:

- The **verification gate** — digest, code-signature (CMS/PKIX/EKU/RFC-3161 timestamp), and publisher
  pin — and any way to get an unverified jar onto the classpath.
- **TLS** — chain/hostname verification, exact-certificate pinning, and the re-pin flow.
- **JNLP parsing** — it is untrusted input (XXE / directive injection / argument injection).
- **Credential handling** — keychain storage; ensuring secrets never reach `connections.json` or logs.
- **Self-update** — Ed25519 manifest verification and artifact SHA-256 checks.

## Supported versions

Only the **latest release** is supported. Fixes ship in a new release.

## Unsigned releases

Release binaries are **not code-signed**. Always verify the published **`SHA256SUMS`** for your
download, and review the attached **CycloneDX SBOM**. If you need a chain you control, build from
source — it produces the same artifact.

The launcher's design and threat model are described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
