package dev.libredirect.mobile.core.url

import java.net.URI
import java.net.URISyntaxException

/**
 * The subset of a URL the routing core cares about. `rawPath`, `rawQuery`,
 * and `rawFragment` keep their original percent-encoding untouched — they
 * are substituted verbatim wherever a strategy passes them through, and
 * only decoded/re-encoded at the single point that needs it
 * ([dev.libredirect.mobile.core.url.QueryString]).
 */
data class ParsedUrl(
    val scheme: String,
    val host: String,
    val rawPath: String,
    val rawQuery: String?,
    val rawFragment: String?,
)

object UrlParser {
    /**
     * Scheme only, tolerant of opaque URIs (`javascript:...`, `file:...`)
     * that have no host at all. Callers must check the scheme against the
     * allowlist *before* calling [parse], which requires a host and will
     * return null for any opaque URI regardless of its scheme — conflating
     * the two would misclassify a rejected scheme as a malformed URL.
     */
    fun parseScheme(input: String): String? {
        val uri = tryParse(input) ?: tryParse(input.replace(" ", "%20")) ?: return null
        return uri.scheme?.lowercase()
    }

    /**
     * Real-world shared links are messier than strict RFC 3986 (unencoded
     * spaces, stray characters). A second attempt with spaces percent-encoded
     * covers the most common offender before giving up.
     */
    fun parse(input: String): ParsedUrl? {
        val uri = tryParse(input) ?: tryParse(input.replace(" ", "%20")) ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        return ParsedUrl(
            scheme = scheme,
            host = host,
            rawPath = uri.rawPath ?: "",
            rawQuery = uri.rawQuery,
            rawFragment = uri.rawFragment,
        )
    }

    private fun tryParse(input: String): URI? =
        try {
            URI(input)
        } catch (_: URISyntaxException) {
            null
        }
}
