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
    /** Matches this host and any of its subdomains, e.g. "example.com" also covers "www.example.com". */
    @Serializable
    @SerialName("domain")
    data class Domain(val host: String) : ExceptionRule

    /** Matches when "{host}{path}" of the incoming URL starts with this value, e.g. "youtube.com/channel/example". */
    @Serializable
    @SerialName("url-prefix")
    data class UrlPrefix(val value: String) : ExceptionRule
}
