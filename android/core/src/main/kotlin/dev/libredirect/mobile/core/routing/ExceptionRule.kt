package dev.libredirect.mobile.core.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * User-defined routing exclusion (project plan §24). Local app settings
 * only — exceptions never appear in the remote manifest and are evaluated
 * before the router runs at all.
 */
@Serializable
sealed interface ExceptionRule {
    /**
     * Matches this host and any of its subdomains, e.g. "example.com" also
     * covers "www.example.com". A `*` anywhere turns the rule into a wildcard
     * pattern matched against the host (`*.example.com`).
     */
    @Serializable
    @SerialName("domain")
    data class Domain(val host: String) : ExceptionRule

    /**
     * Matches when "{host}{path}" of the incoming URL starts with this value,
     * e.g. "youtube.com/channel/example". A `*` anywhere turns the rule into
     * a wildcard pattern matched against "{host}{path}", so a rule with a
     * wildcard host and a wildcard path segment matches any subdomain and
     * any segment (a "live" example: `*.example.com`, star, `live`).
     */
    @Serializable
    @SerialName("url-prefix")
    data class UrlPrefix(val value: String) : ExceptionRule

    /**
     * Skips routing for links that arrive from the given app (2.5), matched
     * against the sender reported by `EXTRA_REFERRER`. Convenience only — a
     * sending app can be spoofed, so this is never presented as a security
     * guarantee.
     */
    @Serializable
    @SerialName("source-app")
    data class SourceApp(val packageName: String) : ExceptionRule
}
