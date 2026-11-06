package dev.libredirect.mobile.core.url

import java.net.URLDecoder

/**
 * Unwraps redirect-wrapper URLs (3.1) — links that point at a service's own
 * redirect endpoint carrying the real target in a query parameter — plus
 * AMP cache URLs, entirely offline. A static, fixed allowlist decides what
 * may be unwrapped; a URL whose host is not on the list is returned
 * untouched, and an extracted target must pass [UrlValidation.isAllowedTargetUrl]
 * or the original URL is kept (never guess, fail-open).
 */
object UrlUnwrapper {
    /**
     * `host -> query parameter(s) holding the target`. A host entry covers
     * its subdomains (`out.reddit.com/out?url=…` carries the target in the
     * query — `?` always starts the query in a URI).
     */
    private val QUERY_HOSTS: Map<String, List<String>> =
        mapOf(
            "google.com" to listOf("q", "url"),
            "l.facebook.com" to listOf("u"),
            "lm.facebook.com" to listOf("u"),
            "l.instagram.com" to listOf("u"),
            "youtube.com" to listOf("q"),
            "steamcommunity.com" to listOf("url"),
            "t.umblr.com" to listOf("z"),
            "slack-redir.net" to listOf("url"),
            "out.reddit.com" to listOf("url"),
        )

    const val MAX_UNWRAP_DEPTH = 5

    /**
     * Unwraps repeatedly (a wrapper may point at another wrapper) up to
     * [MAX_UNWRAP_DEPTH] steps. Returns the final URL: [output] equals
     * [input] when nothing matched or a step failed validation; [count]
     * reports how many layers were removed, for the local privacy stats.
     */
    fun unwrapAll(
        input: String,
        maxDepth: Int = MAX_UNWRAP_DEPTH,
    ): UnwrapResult {
        var current = input
        var count = 0
        while (count < maxDepth) {
            val next = unwrapOnce(current) ?: break
            current = next
            count++
        }
        return UnwrapResult(url = current, count = count)
    }

    data class UnwrapResult(
        val url: String,
        val count: Int,
    )

    /** One unwrap step; null when the URL is not a recognized wrapper. */
    @Suppress("ReturnCount")
    fun unwrapOnce(input: String): String? {
        val url = UrlParser.parse(input) ?: return null
        val host = url.host

        val target =
            when {
                // AMP shapes must be checked before the generic google.com query-host rule.
                host.endsWith(".cdn.ampproject.org") -> unwrapAmpCache(url.rawPath)
                hostMatches(host, "google.com") && url.rawPath.startsWith("/amp/") -> unwrapGoogleAmp(url.rawPath)

                QUERY_HOSTS.any { (domain, _) -> hostMatches(host, domain) } -> {
                    val params = QUERY_HOSTS.entries.first { (domain, _) -> hostMatches(host, domain) }.value
                    params.firstNotNullOfOrNull { param -> QueryString.find(url.rawQuery, param) }
                        ?.decodeLeniently()
                }

                else -> null
            } ?: return null
        if (!UrlValidation.isAllowedTargetUrl(target)) return null
        return target
    }

    /** `google.com/amp/s/<host>/<path>` (https; without the `s/` segment it is http). */
    private fun unwrapGoogleAmp(rawPath: String): String {
        val rest = rawPath.removePrefix("/amp/").removePrefix("s/")
        val scheme = if (rawPath.startsWith("/amp/s/")) "https" else "http"
        return "$scheme://$rest"
    }

    /** AMP cache targets: `/c/s/<host>/<path>` for https, `/c/<encoded-url>` otherwise. */
    @Suppress("ReturnCount")
    private fun unwrapAmpCache(rawPath: String): String? {
        val marker = "/c/"
        val index = rawPath.indexOf(marker)
        if (index < 0) return null
        val rest = rawPath.substring(index + marker.length)
        return if (rest.startsWith("s/")) {
            val withoutScheme = rest.removePrefix("s/")
            val slash = withoutScheme.indexOf('/')
            if (slash <= 0) return null
            "https://${withoutScheme.substring(0, slash)}${withoutScheme.substring(slash)}"
        } else {
            rest.decodeLeniently()
        }
    }

    private fun String.decodeLeniently(): String? =
        try {
            URLDecoder.decode(this, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun hostMatches(
        host: String,
        domain: String,
    ): Boolean = host == domain || host.endsWith(".$domain")
}
