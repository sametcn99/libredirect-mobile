package dev.libredirect.mobile.core.url

import java.net.URLDecoder

/**
 * Optional privacy hardening on top of [TrackingParams]: instead of removing
 * only known tracking names, remove EVERY query parameter that is not on a
 * small functional allowlist. Share links append locale/interface tags
 * (`?hl=en`, `?igsh=…`, `?utm_*`) that redirect frontends never need, while
 * the path alone identifies the post — stripping them shrinks the fingerprint
 * handed to the frontend instance.
 *
 * The allowlist is deliberately minimal and global (not per route): names
 * that carry meaning on the open web — video IDs, search terms, playlist and
 * timestamp references, permalinks by id — so that stripping never changes
 * WHICH page a functional URL identifies. Everything else is dropped.
 *
 * Like [TrackingParams], this object is pure string surgery: kept pairs are
 * re-emitted verbatim, scheme/authority/path/fragment stay byte-for-byte
 * intact, and exception handling (user rules that must never modify a URL)
 * lives in the caller.
 */
object QueryParams {
    /**
     * Query parameter names with functional meaning that must survive
     * strip-all cleaning:
     * - `v`, `list`, `t`, `start` — YouTube video/playlist/timestamp links
     * - `q`, `query`, `s`, `p` — search queries across engines and on-site search
     * - `id` — permalink selectors (Hacker News `?id=`, many forums)
     * - `video`, `channel` — Twitch deep links
     * - `sort` — listing order on search/result pages that keep a query
     */
    val FUNCTIONAL_NAMES: Set<String> =
        setOf(
            "v",
            "list",
            "t",
            "start",
            "q",
            "query",
            "s",
            "p",
            "id",
            "video",
            "channel",
            "sort",
        )

    /** Outcome of one strip pass: the cleaned URL and how many pairs were removed. */
    data class StripResult(
        val url: String,
        val removedCount: Int,
    )

    /**
     * Removes every query pair whose decoded name is not in [keep]. Returns
     * [input] unchanged (and a zero count) when there is no query, nothing
     * matches, or the query segment cannot be located in [input] (e.g. the
     * parse used the space-encoded retry form).
     */
    @Suppress("ReturnCount")
    fun stripAllExcept(
        input: String,
        url: ParsedUrl,
        keep: Set<String> = FUNCTIONAL_NAMES,
    ): StripResult {
        val rawQuery = url.rawQuery ?: return StripResult(input, 0)
        var removed = 0
        val kept =
            rawQuery.split("&").filter { pair ->
                if (pair.isEmpty()) {
                    true
                } else {
                    val name = decodedName(pair)?.lowercase()
                    val isFunctional = name != null && name in keep
                    if (!isFunctional) removed++
                    isFunctional
                }
            }
        if (removed == 0) return StripResult(input, 0)

        val marker = "?$rawQuery"
        val queryStart = input.indexOf(marker)
        if (queryStart < 0) return StripResult(input, 0)
        val queryEnd = queryStart + marker.length
        val cleaned =
            if (kept.isEmpty()) {
                input.substring(0, queryStart) + input.substring(queryEnd)
            } else {
                input.substring(0, queryStart + 1) + kept.joinToString("&") + input.substring(queryEnd)
            }
        return StripResult(cleaned, removed)
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
}
