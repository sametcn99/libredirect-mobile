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

## Routing data updates (manifest refresh)

Routing data ships with the app as a bundled manifest (routes, hosts,
frontend structure). The "Routing data → Update now" action refreshes only
the per-frontend instance lists:

1. The app fetches the upstream instance list directly from
   `https://raw.githubusercontent.com/libredirect/instances/main/data.json`
   over HTTPS (`ManifestEndpoints.INSTANCES_DATA_URL`). There is no
   intermediate hosting and no transport verification beyond TLS.
2. The response is parsed strictly — HTTPS-only, origin-only instance
   URLs, deduplicated, capped per frontend — and merged into the active
   manifest. A refresh can never change routes, hosts, frontend structure,
   or routing strategies.
3. The merged manifest must pass the full structural/semantic validator
   and a routing self-test before it is written atomically to `active.json`.
4. Any failure keeps the last known good manifest in place — routing never
   blocks on a failed update.

## Threat model summary

| Threat | Mitigation |
| --- | --- |
| Malicious/incorrect upstream data | Converter validation, allowlisted routing strategies, fixture tests |
| Tampered instance list in transit | HTTPS-only fetch; merged manifest is strictly parsed and re-validated before activation |
| Malicious redirect target | URL scheme allowlist, private/local host rejection |
| Regex denial-of-service | The shipped manifest has no free-form route regex; the upstream converter only accepts a conservative finite-host subset |
| Broken/corrupted update | Last Known Good fallback; routing never blocks on a failed update |

The full threat model is documented as the corresponding phases (upstream
conversion, converter validation, security hardening) land. Routing itself
never performs a network request, is fail-open (a routing failure opens the
original URL rather than dropping it), and the app sends no telemetry.

## Disclosure policy

We will credit reporters (unless anonymity is requested) in the release
notes once a fix ships. We ask that you give us a reasonable window to
ship a fix before any public disclosure.
