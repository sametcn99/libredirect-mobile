package dev.libredirect.mobile.core.url

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * `URLDecoder`/`URLEncoder` implement `application/x-www-form-urlencoded`
 * (space <-> '+'), which is the conventional encoding for query-string
 * values — not raw percent-encoding. That's intentional here: a value
 * extracted from a source query string and re-embedded into a new one
 * should round-trip through the same convention.
 */
object QueryString {
    fun find(
        rawQuery: String?,
        name: String,
    ): String? =
        rawQuery
            ?.takeIf(String::isNotEmpty)
            ?.split("&")
            ?.asSequence()
            ?.mapNotNull { pair ->
                if (pair.isEmpty()) {
                    null
                } else {
                    val separator = pair.indexOf('=')
                    val rawName = if (separator >= 0) pair.substring(0, separator) else pair
                    val rawValue = if (separator >= 0) pair.substring(separator + 1) else ""
                    (decode(rawName) == name) to decode(rawValue)
                }
            }
            ?.firstOrNull { it.first }
            ?.second

    fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private fun decode(value: String): String? =
        try {
            URLDecoder.decode(value, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
}
