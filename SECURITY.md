# Security Policy

## Supported versions

Only the latest published release of LibRedirect Mobile receives security
fixes. There is no long-term support branch while the project is pre-1.0.

## Reporting a vulnerability

Please do not open a public GitHub issue for security vulnerabilities.

Instead, use GitHub's private vulnerability reporting for this repository
(Security tab → "Report a vulnerability"). Include:

- A description of the vulnerability and its impact.
- Steps to reproduce, or a proof-of-concept manifest/URL if applicable.
- The app version and Android version you tested against.

We aim to acknowledge reports within 5 business days.

## Manifest signing model

Routing data is distributed as a signed manifest, using raw Ed25519
(RFC 8032) rather than a language-specific keyset format, so signing
(Node.js `crypto`, in `tools/`) and verification (BouncyCastle, in the
Android app) interoperate without sharing a serialization format:

1. CI generates `routes.json` from upstream LibRedirect data.
2. CI signs the exact bytes of `routes.json` with the private key in the
   `MANIFEST_SIGNING_KEY` secret (`tools/src/sign-routes.ts`), producing
   `routes.sig` — a base64-encoded raw 64-byte signature. The script
   self-verifies before writing the file, so a signing bug fails CI rather
   than shipping an unverifiable manifest.
3. The app embeds the corresponding 32-byte public key
   (`Ed25519ManifestVerifier.PUBLIC_KEY_BASE64`) and refuses to activate any
   manifest whose signature does not verify (`ManifestRepository.refresh`).
4. Manifests carry a monotonically increasing `revision`; the app rejects
   any manifest with a revision lower than the one currently active
   (anti-rollback).

**Key rotation:** run `bun run tools/generate-signing-key`, store the new
private key as the `MANIFEST_SIGNING_KEY` GitHub Actions secret, and ship
the new public key in an app release before CI signs anything with the new
key — older app installs won't recognize a manifest signed with a key they
don't have embedded, so there is an unavoidable rollout window where CI
should keep signing with the old key until the new app version has had time
to reach most users. The private key is never committed to the repository
in any form.

## Threat model summary

| Threat | Mitigation |
| --- | --- |
| Malicious/incorrect upstream data | Converter validation, allowlisted routing strategies, fixture tests |
| Manifest hosting compromise | Cryptographic signature verification before activation |
| Manifest rollback | Monotonic revision check |
| Malicious redirect target | URL scheme allowlist, private/local host rejection |
| Regex denial-of-service | The shipped manifest has no free-form route regex; the upstream converter only accepts a conservative finite-host subset |
| Broken/corrupted update | Last Known Good fallback; routing never blocks on a failed update |

The full threat model is documented as the corresponding phases (manifest
signing, converter validation, security hardening) land. Routing itself
never performs a network request, is fail-open (a routing failure opens the
original URL rather than dropping it), and the app sends no telemetry.

## Disclosure policy

We will credit reporters (unless anonymity is requested) in the release
notes once a fix ships. We ask that you give us a reasonable window to
ship a fix before any public disclosure.
