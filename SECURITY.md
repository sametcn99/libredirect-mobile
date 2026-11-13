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

The app's outbound network requests are direct HTTPS to fixed, documented
sources; there is no backend of our own and no telemetry:

1. **Live instance refresh** — fetches `data.json` from
   `https://raw.githubusercontent.com/libredirect/instances/main/data.json`
   (`ManifestEndpoints.INSTANCES_DATA_URL`), on explicit user action.
2. **Update check** — fetches the GitHub Releases API "latest release"
   endpoint at app start. Purely informational and fail-safe: any failure
   returns no result and the app shows nothing.
3. **Blocklist refresh (content blocking)** — fetches a hosts-format
   blocklist from one fixed source (`BlocklistEndpoints.REMOTE_URL`,
   StevenBlack hosts) daily via WorkManager, with a byte cap, strict
   host-line parsing, and atomic activation; the APK's bundled list is the
   offline fallback.
4. **Short-link resolution (opt-in, off by default)** — when the user
   enables it, links from a fixed allowlist of shortener hosts are followed
   with HEAD requests (max 5 hops, HTTPS-only hops, no cookies, public
   hosts only, per-hop validation; any failure returns the original link).
   This is the one deliberate exception to "routing never touches the
   network": requests go to the shortener service, which is why the feature
   is opt-in and prominently documented.
5. **In-app browser & download traffic** — whatever the user navigates to
   or explicitly downloads in the built-in WebView browser (plus short
   ranged metadata probes while the pre-download confirmation sheet is open
   for an HTTP download whose size, type, or extension is unknown).

## Permissions

`INTERNET` is the only permission required for routing. Foreground
playback and downloads additionally declare `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PLAYBACK` (for the opt-in background audio
service), `FOREGROUND_SERVICE_DATA_SYNC` (for the in-app download manager
foreground service on Android 14+), and `POST_NOTIFICATIONS` (requested at
runtime on Android 13+ when enabling background audio or starting a
download) to display media controls and transfer notifications. No storage,
location, contact, or identifier permissions exist, and
`QUERY_ALL_PACKAGES` is not used — the app sees only launchable activities
(for the per-app exception picker and onboarding) and VIEW-capable browsers.

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
- **Content blocking** is purely host-based: requests are denied by
  hostname lookup (exact or parent-domain suffix) — no JavaScript, no
  element hiding, no regex, and no code can ever come from a blocklist.
- **Reader mode** runs only the app's own packaged extractor script
  (`assets`-inlined JavaScript evaluated into the page) — no third-party
  script is ever fetched or executed, keeping the "no JS from remote data"
  invariant intact.
- **Per-site settings** (JavaScript off, forced dark, …) apply only inside
  this app's WebView and are stored locally.
- **Downloads** run through the app's built-in OkHttp download manager in a
  foreground service (`DownloadService`) rather than delegating to the
  system Download Manager. Outbound HTTP download URLs pass through the
  same tracking-parameter stripping, allowlisted wrapper unwrapping, and
  domain-exception rules before connecting; active cookies stay in memory
  only during transfer and are never persisted to the local downloads
  index. A pre-download prompt warns on executable or installer files
  even if confirmation prompts are otherwise turned off, and completed
  files are published via `MediaStore.Downloads` on Android 10+ (or the
  app's private downloads directory exposed through `FileProvider` on
  older versions) without requesting storage permissions.
- **Browsing data controls** (clear cookies/cache, clear history) act only
  on this app's own WebView profile and local storage.
- **Settings backup/restore** goes through the system file picker (SAF) —
  no storage permission. Imports are size-capped and re-validated (schema
  version, HTTPS-only pinned instances) before anything is applied, and a
  summary dialog confirms the replacement first.
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
