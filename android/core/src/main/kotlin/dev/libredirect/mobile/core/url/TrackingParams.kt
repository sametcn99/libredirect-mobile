package dev.libredirect.mobile.core.url

import java.net.URLDecoder

/**
 * Removes well-known tracking parameters (`utm_*`, `fbclid`, `gclid`, …)
 * from a URL's query string before it is passed through or redirected.
 *
 * The denylist is a static, app-distributed list — no manifest changes, no
 * new routing strategy, consistent with the fixed primitive allowlist. Two
 * scopes exist:
 *
 * - [GLOBAL]: click identifiers and campaign tags that have no legitimate
 *   non-tracking use anywhere, plus every `utm_`-prefixed name.
 * - [HOST_SCOPED]: names that are only tracking on specific hosts and could
 *   collide with real parameters elsewhere (e.g. YouTube's `si` share tag).
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

    private val HOST_SCOPED: Map<String, Set<String>> =
        mapOf(
            "youtube.com" to setOf("si"),
            "youtu.be" to setOf("si"),
        )

    /**
     * Cleans a raw query string. Returns null when nothing should be
     * removed, an empty string when every pair was removed, or the cleaned
     * query (kept pairs joined with `&`) otherwise.
     */
    fun cleanQuery(
        rawQuery: String?,
        host: String,
    ): String? {
        if (rawQuery.isNullOrEmpty()) return null
        val scoped = scopedParamsFor(host)
        var removed = false
        val kept =
            rawQuery.split("&").filter { pair ->
                if (pair.isEmpty()) {
                    true
                } else {
                    val name = decodedName(pair)?.lowercase()
                    val isTracking = name != null && (name.startsWith("utm_") || name in GLOBAL || name in scoped)
                    if (isTracking) removed = true
                    !isTracking
                }
            }
        return if (removed) kept.joinToString("&") else null
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
    ): String {
        val cleaned = cleanQuery(url.rawQuery, url.host) ?: return input
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
    fun stripped(url: ParsedUrl): ParsedUrl {
        val cleaned = cleanQuery(url.rawQuery, url.host) ?: return url
        return url.copy(rawQuery = cleaned.ifEmpty { null })
    }

    private fun scopedParamsFor(host: String): Set<String> =
        HOST_SCOPED.entries
            .filter { (domain, _) -> host == domain || host.endsWith(".$domain") }
            .flatMapTo(mutableSetOf()) { it.value }

    private fun decodedName(pair: String): String? {
        val separator = pair.indexOf('=')
        val rawName = if (separator >= 0) pair.substring(0, separator) else pair
        return try {
            URLDecoder.decode(rawName, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
