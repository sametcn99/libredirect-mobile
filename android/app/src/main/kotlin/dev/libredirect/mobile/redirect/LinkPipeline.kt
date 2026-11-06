package dev.libredirect.mobile.redirect

import dev.libredirect.mobile.browser.ShortLinkResolver
import dev.libredirect.mobile.core.routing.ExceptionMatcher
import dev.libredirect.mobile.core.routing.InstanceHealth
import dev.libredirect.mobile.core.routing.RoutingContext
import dev.libredirect.mobile.core.url.ShortLinkPolicy
import dev.libredirect.mobile.core.url.UrlParser
import dev.libredirect.mobile.core.url.UrlUnwrapper
import dev.libredirect.mobile.settings.AppSettings
import dev.libredirect.mobile.ui.browser.BrowserDataRepository

/**
 * The pre-routing pipeline shared by [RedirectActivity] and the in-app
 * browser (3.1/3.3): unwrap redirect wrappers/AMP locally, optionally
 * resolve shortener links, and assemble the [RoutingContext] with health
 * data and referrer app. User exceptions checked against the ORIGINAL
 * URL suppress all preprocessing — an excepted link must arrive byte-for-
 * byte intact.
 */
class LinkPipeline(
    private val settings: AppSettings,
    private val sourceApp: String? = null,
    private val shortLinkResolver: ShortLinkResolver = ShortLinkResolver(),
) {
    data class Prepared(
        val url: String,
        val unwrappedLayers: Int = 0,
        val shortLinkResolved: Boolean = false,
    )

    /**
     * Runs the local preprocessing steps. Only ever touches the network
     * when the opt-in short-link resolver is enabled AND the host is on the
     * fixed allowlist.
     */
    suspend fun prepare(input: String): Prepared {
        var url = input
        var unwrappedLayers = 0
        var shortLinkResolved = false

        if (matchesUserException(input)) return Prepared(url)

        if (settings.unwrapRedirectWrappersEnabled) {
            val result = UrlUnwrapper.unwrapAll(url)
            if (result.url != url) {
                url = result.url
                unwrappedLayers = result.count
            }
        }

        if (settings.shortLinkResolveEnabled) {
            val host = UrlParser.parse(url)?.host
            if (host != null && ShortLinkPolicy.isAllowed(host)) {
                val resolved = shortLinkResolver.resolve(url)
                if (resolved.changed) {
                    url = resolved.url
                    shortLinkResolved = true
                }
            }
        }

        return Prepared(url = url, unwrappedLayers = unwrappedLayers, shortLinkResolved = shortLinkResolved)
    }

    private fun matchesUserException(url: String): Boolean {
        val parsed = UrlParser.parse(url) ?: return false
        return ExceptionMatcher.matches(parsed, settings.exceptions, sourceApp)
    }

    /** Health snapshot for the routing core: probe latencies + load-failure cooldowns. */
    suspend fun healthSnapshot(dataRepository: BrowserDataRepository): Map<String, InstanceHealth> {
        val latencies = dataRepository.cachedLatencies()
        val failures = dataRepository.instanceFailures()
        val keys = latencies.keys + failures.keys
        if (keys.isEmpty()) return emptyMap()
        return keys.associateWith { host ->
            InstanceHealth(latencyMillis = latencies[host], lastFailedAtMillis = failures[host])
        }
    }

    fun routingContext(
        health: Map<String, InstanceHealth>,
        nowMillis: Long,
    ): RoutingContext {
        val base = settings.routingContext()
        return base.copy(
            instanceHealth = health,
            nowMillis = nowMillis,
            sourceApp = sourceApp,
        )
    }

    companion object {
        fun sourcePackageFromReferrer(referrer: String?): String? {
            if (referrer.isNullOrBlank()) return null
            val uri = android.net.Uri.parse(referrer)
            return uri.host?.takeIf { it.isNotBlank() }
        }
    }
}
