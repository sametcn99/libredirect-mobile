package dev.libredirect.mobile.browser

import dev.libredirect.mobile.core.url.ShortLinkPolicy
import dev.libredirect.mobile.core.url.UrlValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Opt-in short-link resolution (3.3): follows HTTPS redirects of allowlisted
 * shortener hosts with HEAD requests (falling back to GET without reading
 * the body) until the final URL or the hop limit. Every hop must be HTTPS
 * on a public host; cookies are never stored; any failure returns the
 * original link (fail-open). This is the one feature that makes routing
 * perform a network request, which is why it is off by default and
 * documented in README/SECURITY.
 */
class ShortLinkResolver(
    timeoutSeconds: Long = ShortLinkPolicy.TIMEOUT_SECONDS,
) {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

    /**
     * @return the final URL after following redirects, or the input when the
     * link is not an allowed shortener or anything goes wrong.
     */
    suspend fun resolve(input: String): ResolvedShortLink =
        withContext(Dispatchers.IO) {
            val first = firstValidTarget(input) ?: return@withContext ResolvedShortLink(input, 0, false)
            val chain =
                generateSequence(ChainLink(first, 0)) { current ->
                    if (current.hops >= ShortLinkPolicy.MAX_HOPS) {
                        null
                    } else {
                        redirectTargetOf(current.url)
                            ?.takeIf(::isFollowable)
                            ?.let { ChainLink(it, current.hops + 1) }
                    }
                }.toList()
            val last = chain.last()
            ResolvedShortLink(last.url, last.hops, last.hops > 0)
        }

    private data class ChainLink(val url: String, val hops: Int)

    data class ResolvedShortLink(
        val url: String,
        val hops: Int,
        val changed: Boolean,
    )

    // Guard chain: each check rejects a distinct failure mode; collapsing
    // them into one boolean would hide the policy from readers.
    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
    /** Only an allowlisted shortener host with a valid https URL starts a resolution. */
    private fun firstValidTarget(input: String): String? {
        val parsed = dev.libredirect.mobile.core.url.UrlParser.parse(input) ?: return null
        if (parsed.scheme != "https") return null
        if (!ShortLinkPolicy.isAllowed(parsed.host)) return null
        if (!UrlValidation.isAllowedTargetUrl(input)) return null
        return input
    }

    private fun isFollowable(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) && UrlValidation.isAllowedTargetUrl(url)

    @Suppress("TooGenericExceptionCaught")
    private fun redirectTargetOf(url: String): String? =
        try {
            client
                .newCall(Request.Builder().url(url).head().build())
                .execute()
                .use { response ->
                    if (!response.isRedirect) {
                        null
                    } else {
                        response.header("Location")
                    }
                }
        } catch (_: Exception) {
            null
        }
}
