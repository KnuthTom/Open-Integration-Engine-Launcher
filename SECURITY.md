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
- **The admission cache** (`cache/admissions.json`) — a record of jar bytes (by SHA-256) the gate
  admitted under a trust-policy fingerprint, so a relaunch skips the CMS / PKIX / timestamp work for
  bytes it verified before. The digest the server publishes is still checked on every launch, so a
  changed jar is never admitted from the cache; a change of posture, pins, anchors or publisher
  decisions invalidates every entry. The cache lives in the user's own profile: someone who can write
  there can already replace the launcher, so it is not a trust boundary — but a way to admit a jar
  through it without that access would be in scope.
- **The jar pool** (`cache/pool/`) — admitted jars filed by SHA-256 and shared across connections.
  It is a byte source only: a pooled jar is used for a connection only when its server publishes that
  exact digest, and it still passes the gate (or a cached admission under the policy in force). A way
  to get bytes a server did not publish onto a classpath through the pool would be in scope.
- **Local state the launcher runs from** — the tunnel command (a file can carry one, but an import
  shows every command it carries and brings them in switched off unless the user chooses otherwise;
  a way to get one to run without that review would be in scope), the trust decisions (an imported
  file's certificate pin and auto-approve switch are dropped — a way to plant trust through an import
  would be in scope; a connection made in the window starts with auto-approve on, which is the
  user's own trust-on-first-use of a server they just typed in, pinned from then on), the
  single-instance lock and its loopback hand-over (`instance.lock`: a per-run secret, loopback only,
  the only action is "show your window").

## Supported versions

Only the **latest release** is supported. Fixes ship in a new release.

## Unsigned releases

Release binaries are **not code-signed**. Always verify the published **`SHA256SUMS`** for your
download, and review the attached **CycloneDX SBOM**. If you need a chain you control, build from
source — it produces the same artifact.

The launcher's design and threat model are described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
