package dev.libredirect.mobile.core.url

/**
 * The static rules for opt-in short-link resolution (3.3): which hosts may
 * be resolved at all and the safety bounds the network resolver must obey.
 * The policy is pure data/logic so it is JVM-testable; the app layer's
 * resolver performs the actual HTTPS HEAD requests against it.
 */
object ShortLinkPolicy {
    /**
     * Fixed allowlist of shortener hosts whose links may be followed when
     * the user opted in. Hosts that are themselves routing sources (e.g.
     * `youtu.be`) are deliberately absent — they reach the manifest first.
     */
    val ALLOWED_HOSTS: Set<String> =
        setOf(
            "t.co",
            "bit.ly",
            "tinyurl.com",
            "lnkd.in",
            "ow.ly",
            "buff.ly",
            "is.gd",
            "rb.gy",
            "cutt.ly",
            "rebrand.ly",
            "shorturl.at",
            "v.gd",
        )

    const val MAX_HOPS = 5
    const val TIMEOUT_SECONDS = 5L

    /** True when [host] (exact match only, case-insensitive) may be resolved. */
    fun isAllowed(host: String): Boolean = host.lowercase() in ALLOWED_HOSTS
}
