# Security Policy

## Supported versions

Only the latest published release of LibRedirect Mobile receives security
fixes. There is no long-term support branch and no backporting to older
versions.

## Reporting a vulnerability

Please do not open a public GitHub issue for security vulnerabilities.

Instead, use GitHub's private vulnerability reporting for this repository
(Security tab → "Report a vulnerability"). Include:

- A description of the vulnerability and its impact.
- Steps to reproduce, or a proof-of-concept manifest/URL if applicable.
- The app version and Android version you tested against.

We aim to acknowledge reports within 5 business days.

## Attack surface overview

The app makes exactly three kinds of outbound network requests, all direct
HTTPS to their upstream sources; there is no backend of our own and no
telemetry:

1. **Live instance refresh** — fetches `data.json` from
   `https://raw.githubusercontent.com/libredirect/instances/main/data.json`
   (`ManifestEndpoints.INSTANCES_DATA_URL`), on explicit user action.
2. **Update check** — fetches the GitHub Releases API "latest release"
   endpoint at app start. Purely informational and fail-safe: any failure
   returns no result and the app shows nothing.
3. **In-app browser traffic** — whatever the user navigates to in the
   built-in WebView browser.

## Routing data updates (manifest refresh)

Routing data ships with the app as a bundled manifest (routes, hosts,
frontend structure). The "Routing Database → Update Data Now" action
refreshes only the per-frontend instance lists:

1. The app fetches the upstream instance list directly from
   `https://raw.githubusercontent.com/libredirect/instances/main/data.json`
   over HTTPS (`ManifestEndpoints.INSTANCES_DATA_URL`). There is no
   intermediate hosting and no transport verification beyond TLS.
2. The response is parsed strictly — HTTPS-only, origin-only instance
   URLs, deduplicated, capped per frontend — and merged into the active
   manifest. A refresh can never change routes, hosts, frontend structure,
   or routing strategies.
3. The merged manifest must pass the full structural/semantic validator
   and a routing self-test before it is written atomically to `active.json`
   (previous active state preserved as `previous.json`).
4. Any failure keeps the last known good manifest in place — routing never
   blocks on a failed update.

The manifest itself can never carry executable behavior: routing
strategies are a sealed, allowlisted set of primitives (`replace-origin`,
`template`, `custom-scheme`, `passthrough`) with no expression or eval
variant. Adding a primitive requires an app release, by design.

## Built-in browser

The browser is a standard WebView surface and inherits WebView's security
model. The app adds the following local behavior:

- **Bookmarks and history** are stored locally in app-private DataStore
  storage and never leave the device. Bookmark import accepts standard
  HTML and JSON exports and is decoded leniently (malformed entries are
  dropped, not executed).
- **Downloads** are delegated to the system Download Manager. On Android
  10+ files land in the shared Downloads directory via DownloadManager's
  own permissions; on older versions they go to the app's private
  downloads directory. The app does not request storage permissions.
- **Browsing data controls** (clear cookies/cache, clear history) act only
  on this app's own WebView profile and local storage.
- A custom search engine is only accepted if its template starts with
  `http(s)://` and contains the `%s` placeholder.

## Threat model summary

| Threat | Mitigation |
| --- | --- |
| Malicious/incorrect upstream data | Converter validation, allowlisted routing strategies, fixture tests |
| Tampered instance list in transit | HTTPS-only fetch; merged manifest is strictly parsed and re-validated before activation |
| Malicious redirect target | URL scheme allowlist, private/local host rejection |
| Regex denial-of-service | The shipped manifest has no free-form route regex; the upstream converter only accepts a conservative finite-host subset |
| Broken/corrupted update | Last Known Good fallback (`active.json` → `previous.json` → bundled asset); routing never blocks on a failed update |
| Redirect loop through this app | Launch intents carry an explicit target package, or use a chooser that excludes this app's own components |
| Update-check spoofing | Update info comes only from GitHub's Releases API for this repository over HTTPS; checks are informational and never trigger installs |
| Local data theft from backups | Backup disabled (`allowBackup="false"`); bookmarks/history/settings live in app-private storage |
| Malformed bookmark import | Lenient decode that drops invalid entries; no HTML/JS processing of imported content beyond standard link fields |

Routing itself never performs a network request and is fail-open (a
routing failure opens the original URL rather than dropping it); input
that was never an eligible URL — wrong scheme, unparseable — is dropped
instead of opened.

## Disclosure policy

We will credit reporters (unless anonymity is requested) in the release
notes once a fix ships. We ask that you give us a reasonable window to
ship a fix before any public disclosure.
