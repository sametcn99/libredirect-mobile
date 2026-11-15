package dev.libredirect.mobile.core.url

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * `URLDecoder`/`URLEncoder` implement `application/x-www-form-urlencoded`
 * (space <-> '+'), which is the conventional encoding for query-string
 * values — not raw percent-encoding. That's intentional here: a value
 * extracted from a source query string and re-embedded into a new one
 * should round-trip through the same convention.
 *
 * The String-name overloads are deliberate: the `Charset` overloads of
 * `URLDecoder.decode`/`URLEncoder.encode` are Java 10 APIs that Android
 * only ships from API 33, while this module supports API 26+. Android
 * lint cannot catch this in a pure-JVM module (see issue #12).
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

    fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decode(value: String): String? =
        try {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            null
        }
}
