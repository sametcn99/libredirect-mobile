package dev.libredirect.mobile.manifest

/**
 * No hosting exists yet — Phase 10 (automated upstream sync) publishes
 * `routes.json`/`routes.sig` to GitHub Pages and must replace these with
 * the real URLs before [ManifestRepository.refresh] is called anywhere.
 * Until then, calling refresh() will simply fail closed at the HTTPS-only
 * check in [RemoteManifestFetcher] (both placeholders resolve to nothing),
 * leaving routing on the bundled/Last-Known-Good manifest — safe, but not
 * yet wired to a real update source.
 */
object ManifestEndpoints {
    const val MANIFEST_URL = "https://TODO-libredirect-mobile.example/routes.json"
    const val SIGNATURE_URL = "https://TODO-libredirect-mobile.example/routes.sig"
}
