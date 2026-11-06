package dev.libredirect.mobile.core.url

import java.net.URLDecoder

/**
 * Removes well-known tracking parameters (`utm_*`, `fbclid`, `gclid`, …)
 * from a URL's query string before it is passed through or redirected.
 *
 * The denylist is a static, app-distributed list — no manifest changes, no
 * new routing strategy, consistent with the fixed primitive allowlist. Three
 * scopes exist:
 *
 * - [GLOBAL]: click identifiers and campaign tags that have no legitimate
 *   non-tracking use anywhere, plus every `utm_`-prefixed name.
 * - [HOST_SCOPED]: names that are only tracking on specific hosts and could
 *   collide with real parameters elsewhere (e.g. YouTube's `si` share tag).
 *   A name ending in `*` is a prefix match (`ref_` strips `ref_creative`).
 * - User-defined rules passed by the caller ([CustomRules]): global and
 *   host-scoped names typed in Settings, following the same prefix syntax.
 *
 * Kept pairs are re-emitted verbatim with their original percent-encoding;
 * only whole `name=value` pairs are removed. Exception handling (user rules
 * that must never modify a URL) lives in the caller, not here.
 */
object TrackingParams {
    private val GLOBAL =
        setOf(
            // Click identifiers
            "fbclid",
            "gclid",
            "gclsrc",
            "dclid",
            "gbraid",
            "wbraid",
            "msclkid",
            "twclid",
            "ttclid",
            "yclid",
            "s_kwcid",
            "li_fat_id",
            // Campaign / email attribution
            "mc_cid",
            "mc_eid",
            "_hsenc",
            "_hsmi",
            "vero_id",
            "wickedid",
            "mbid",
            // Social share attribution
            "igshid",
            "igsh",
            "mibextid",
        )

    /**
     * Host-scoped tracking parameters (3.2). A name ending in `*` matches by
     * prefix, so Amazon's `ref_` strips `ref_creative`, `ref_prd`, …
     */
    private val HOST_SCOPED: List<ScopedParams> =
        listOf(
            ScopedParams(
                "amazon.",
                prefixes = setOf("ref_", "pd_rd_", "pf_rd_"),
            ),
            ScopedParams("spotify.com", exact = setOf("si")),
            ScopedParams("tiktok.com", exact = setOf("_r", "_t", "is_from_webapp", "sender_device")),
            ScopedParams("x.com", exact = setOf("s", "t")),
            ScopedParams("twitter.com", exact = setOf("s", "t")),
            ScopedParams("aliexpress.", exact = setOf("spm", "scm", "aff_platform", "aff_trace_key")),
            ScopedParams("linkedin.com", exact = setOf("trk", "trackingid", "originalsubdomain")),
            ScopedParams("lnkd.in", exact = setOf("trk", "originalsubdomain")),
            ScopedParams("youtube.com", exact = setOf("si")),
            ScopedParams("youtu.be", exact = setOf("si")),
        )

    /**
     * Cleans a raw query string. Returns null when nothing should be
     * removed, an empty string when every pair was removed, or the cleaned
     * query (kept pairs joined with `&`) otherwise.
     */
    fun cleanQuery(
        rawQuery: String?,
        host: String,
        custom: CustomTrackingScope = CustomTrackingScope.NONE,
    ): String? {
        if (rawQuery.isNullOrEmpty()) return null
        var removed = false
        val kept =
            rawQuery.split("&").filter { pair ->
                if (pair.isEmpty()) {
                    true
                } else {
                    val name = decodedName(pair)?.lowercase()
                    val isTracking = name != null && isTrackingName(name, host, custom)
                    if (isTracking) removed = true
                    !isTracking
                }
            }
        return if (removed) kept.joinToString("&") else null
    }

    /**
     * Number of query pairs this configuration would remove — drives the
     * local privacy statistics card without exposing the denylist itself.
     */
    fun removedCount(
        rawQuery: String?,
        host: String,
        custom: CustomTrackingScope = CustomTrackingScope.NONE,
    ): Int {
        if (rawQuery.isNullOrEmpty()) return 0
        return rawQuery
            .split("&")
            .count { pair ->
                if (pair.isEmpty()) {
                    false
                } else {
                    val name = decodedName(pair)?.lowercase()
                    name != null && isTrackingName(name, host, custom)
                }
            }
    }

    private fun isTrackingName(
        name: String,
        host: String,
        custom: CustomTrackingScope,
    ): Boolean =
        name.startsWith("utm_") ||
            name in GLOBAL ||
            matchesName(name, custom.global) ||
            custom.byHost.entries.any { (domain, names) -> hostMatches(host, domain) && matchesName(name, names) } ||
            HOST_SCOPED.any {
                    scoped ->
                hostMatches(host, scoped.domain) && matchesParam(name, scoped.exact, scoped.prefixes)
            }

    private fun matchesName(
        name: String,
        names: Set<String>,
    ): Boolean =
        names.any { candidate ->
            if (candidate.contains('*')) {
                dev.libredirect.mobile.core.routing.WildcardPattern.matches(candidate, name)
            } else {
                name == candidate.lowercase()
            }
        }

    private fun matchesParam(
        name: String,
        exact: Set<String>,
        prefixes: Set<String>,
    ): Boolean = name in exact || prefixes.any(name::startsWith)

    /**
     * A trailing dot marks a brand that spans many TLDs ("amazon." covers
     * amazon.com, amazon.de, www.amazon.co.uk): the domain must appear as a
     * whole label. Otherwise the usual domain-or-subdomain rule applies.
     */
    private fun hostMatches(
        host: String,
        domain: String,
    ): Boolean =
        if (domain.endsWith('.')) {
            val brand = domain.trimEnd('.')
            host.split('.').any { it == brand }
        } else {
            host == domain || host.endsWith(".$domain")
        }

    /**
     * Removes tracking parameters from the original URL string by cutting
     * the query segment out of it, leaving scheme, authority (including any
     * port), path, and fragment byte-for-byte intact. Returns [input]
     * unchanged when there is no query, nothing matches, or the query
     * segment cannot be located (e.g. the parse used the space-encoded
     * retry form).
     */
    @Suppress("ReturnCount")
    fun stripFrom(
        input: String,
        url: ParsedUrl,
        custom: CustomTrackingScope = CustomTrackingScope.NONE,
    ): String {
        val cleaned = cleanQuery(url.rawQuery, url.host, custom) ?: return input
        val rawQuery = url.rawQuery ?: return input
        val marker = "?$rawQuery"
        val queryStart = input.indexOf(marker)
        if (queryStart < 0) return input
        val queryEnd = queryStart + marker.length
        return if (cleaned.isEmpty()) {
            input.substring(0, queryStart) + input.substring(queryEnd)
        } else {
            input.substring(0, queryStart + 1) + cleaned + input.substring(queryEnd)
        }
    }

    /** [ParsedUrl] view of the same cleaning, for building redirect targets. */
    fun stripped(
        url: ParsedUrl,
        custom: CustomTrackingScope = CustomTrackingScope.NONE,
    ): ParsedUrl {
        val cleaned = cleanQuery(url.rawQuery, url.host, custom) ?: return url
        return url.copy(rawQuery = cleaned.ifEmpty { null })
    }

    private fun decodedName(pair: String): String? {
        val separator = pair.indexOf('=')
        val rawName = if (separator >= 0) pair.substring(0, separator) else pair
        return try {
            URLDecoder.decode(rawName, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Host-scoped parameter set: exact names plus prefix rules. */
    private data class ScopedParams(
        val domain: String,
        val exact: Set<String> = emptySet(),
        val prefixes: Set<String> = emptySet(),
    )
}

/**
 * The caller-supplied part of tracking cleanup: user-defined parameter names
 * (global and per host). Keys of [byHost] are domains (subdomains included
 * by the matcher); values are names, `*` suffix = prefix rule.
 */
data class CustomTrackingScope(
    val global: Set<String> = emptySet(),
    val byHost: Map<String, Set<String>> = emptyMap(),
) {
    companion object {
        val NONE = CustomTrackingScope()
    }
}
