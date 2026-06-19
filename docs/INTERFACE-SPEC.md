# Open Integration Engine Launcher — Server Interface Spec

> **Status (after Phase 2): launcher side implemented; server side still to be provided.**
> The **client/launcher** half of this contract now exists in `:launcher-core`: a fail-closed
> pipeline that fetches `/webstart` (JNLP) and `/digest/{version}` over pinned/verified https, parses
> the JNLP as untrusted (jars / main-class / args / version only), downloads each jar, and runs it
> through the single `CodebaseVerifier` gate (digest → signature → publisher pin) before it can join
> the classpath. See `docs/RUNNING.md`. What remains is the **server** that serves these endpoints,
> plus the open questions at the bottom (descriptor signing, manifest signing, TSA-chain trust,
> revocation strategy). The endpoint shapes below are the agreed contract the launcher already speaks.

## Context

Gestalt launches the Open Integration Engine (OIE) / Mirth Connect Administrator client. The client
is delivered as a set of jars (a `core` plus zero or more per-extension jars). The launcher must
download those jars, **verify every one** with `:core-trust` before it is placed on a classpath or
executed, cache the verified artifacts, and re-verify on subsequent launches.

```
launcher ──GET /webstart──▶ server   (JNLP descriptor: where the jars are, versions)
launcher ──GET /digest/───▶ server   (SHA-256 manifest of all artifacts)
launcher ──GET <jar urls>─▶ server   (core + per-extension jars)
launcher ──verify(jar)────▶ :core-trust   (PKIX + EKU + timestamp + pin)  ◀── the part that exists
launcher ──cache + reverify on next launch
```

## Endpoints (TODO)

### `GET /webstart`
JNLP-style launch descriptor. Returns the set of artifacts to load, their versions, and their
download URLs.

- **Response:** `application/x-java-jnlp-file` (or a JSON equivalent — decision TODO).
- **Must include:** core jar coordinates, ordered list of extension jars, main class, JVM args.
- **TODO:** decide JNLP-compat vs. a clean JSON descriptor; signature over the descriptor itself.

### `GET /digest/{version}`
A signed SHA-256 manifest binding artifact names → digests for a given release, so the launcher can
detect substitution/rollback before downloading and after caching.

- **Response:** `text/plain` manifest: `<sha-256-hex>  <artifact-name>` per line.
- **TODO:** the manifest itself must be signed (detached signature or served from a signed jar) and
  verified with `:core-trust` before any digest in it is trusted.

### `GET` jar URLs (core + per-extension)
Plain artifact download (the URLs come from `/webstart`).

- **TODO:** range/resume support, content-length sanity caps, TLS pinning decision.

## Download → verify → cache pipeline (TODO)

1. Fetch `/webstart`; verify the descriptor's own signature.
2. Fetch `/digest/{version}`; verify its signature; record expected digests.
3. For each jar: download → check SHA-256 against the digest manifest → `JarSignatureVerifier.verify`
   in `PIN_KNOWN_SIGNERS` mode (the OIE publisher + Gestalt are the pinned signers) → only then admit.
4. Store verified jars in a local cache keyed by `{name, version, sha-256}`.
5. **On every launch**, re-verify cached jars (signatures *and* digests) before use — cache is not
   trusted on its own.

## Trust integration (already implemented in `:core-trust`)

- `TrustStore` anchors: Sectigo Public Code Signing Root R46 + USERTrust RSA (OIE chain) and the
  Gestalt self-signed cert.
- `JarSignatureVerifier` in `PIN_KNOWN_SIGNERS` mode is the gate; `VerificationResult` carries the
  signer DN, validated chain, timestamp usage, and failure reason for audit/logging.

## Open questions (TODO)

- Revocation: this phase disables OCSP/CRL checking. Decide online OCSP vs. stapled vs. short-lived
  digest manifests for the download path.
- TSA-chain trust: the verifier validates the timestamp token's own signature but does not yet PKIX
  the TSA chain to a trusted timestamping root — harden this for the download path.
- Transport: Apache HttpClient is **not** a dependency yet; add it (or `java.net.http`) here.
- Descriptor format: JNLP compatibility vs. bespoke JSON.
