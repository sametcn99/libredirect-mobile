package dev.libredirect.mobile.core.url

object UrlValidation {
    val ALLOWED_INPUT_SCHEMES = setOf("http", "https")
    const val MAX_INPUT_LENGTH = 32 * 1024

    private val ORIGIN_PATTERN =
        Regex(
            "^https://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+(:[0-9]{1,5})?$",
        )

    private val HOSTNAME_PATTERN =
        Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")

    /** The manifest's `instanceOrigin` contract, enforced by `ManifestValidator`:
     * HTTPS scheme, host, optional port, no path/query/fragment. Used both
     * to validate a user-supplied custom instance at resolve time and to
     * back the settings UI's input validation. */
    fun isValidHttpsOrigin(value: String): Boolean = ORIGIN_PATTERN.matches(value)

    /**
     * Whether an unwrapped/redirected target URL is acceptable as routing
     * input: a parseable http(s) URL on a public, non-local host. Applies to
     * targets extracted from wrapper URLs (3.1) and short-link hops (3.3) —
     * anything else stays unrouted local surface.
     */
    @Suppress("ReturnCount")
    fun isAllowedTargetUrl(input: String): Boolean {
        val parsed = UrlParser.parse(input) ?: return false
        if (parsed.scheme !in ALLOWED_INPUT_SCHEMES) return false
        if (parsed.host.isEmpty() || !HOSTNAME_PATTERN.matches(parsed.host)) return false
        return !isLocalHost(parsed.host)
    }

    private const val LOCALHOST_NAME = "localhost"
    private val LOCAL_HOST_SUFFIXES = listOf(".localhost", ".local", ".invalid")
    private const val OCTET_ZERO = 0
    private const val PRIVATE_CLASS_A_FIRST_OCTET = 10
    private const val LOOPBACK_FIRST_OCTET = 127

    /** Unspecified, loopback and RFC 1918 class-A first octets are always local. */
    private val LOCAL_FIRST_OCTETS = setOf(OCTET_ZERO, PRIVATE_CLASS_A_FIRST_OCTET, LOOPBACK_FIRST_OCTET)
    private const val LINK_LOCAL_FIRST_OCTET = 169
    private const val LINK_LOCAL_SECOND_OCTET = 254
    private const val PRIVATE_CLASS_B_FIRST_OCTET = 172
    private const val PRIVATE_CLASS_B_START = 16
    private const val PRIVATE_CLASS_B_END = 31
    private const val PRIVATE_CLASS_C_FIRST_OCTET = 192
    private const val PRIVATE_CLASS_C_SECOND_OCTET = 168
    private const val IPV4_PARTS = 4
    private const val MAX_OCTET_LENGTH = 3

    /**
     * Rejects loopback, link-local, private-range and dot-suffix hosts so a
     * wrapper/short-link target can never point at the local network.
     */
    fun isLocalHost(host: String): Boolean =
        host == LOCALHOST_NAME || LOCAL_HOST_SUFFIXES.any(host::endsWith) || isLocalIpv6(host) || isLocalIpv4(host)

    private fun isLocalIpv4(host: String): Boolean {
        val parts = host.split('.')
        val looksLikeIpv4 =
            parts.size == IPV4_PARTS &&
                parts.all { part -> part.isNotEmpty() && part.length <= MAX_OCTET_LENGTH && part.all(Char::isDigit) }
        if (!looksLikeIpv4) return false
        val octets = parts.map(String::toInt)
        val first = octets[0]
        val second = octets[1]
        return first in LOCAL_FIRST_OCTETS ||
            (first == LINK_LOCAL_FIRST_OCTET && second == LINK_LOCAL_SECOND_OCTET) ||
            (first == PRIVATE_CLASS_B_FIRST_OCTET && second in PRIVATE_CLASS_B_START..PRIVATE_CLASS_B_END) ||
            (first == PRIVATE_CLASS_C_FIRST_OCTET && second == PRIVATE_CLASS_C_SECOND_OCTET)
    }

    /**
     * IPv6 literals: loopback (`::1`), unique-local fc00::/7 (`fc`/`fd`
     * prefixes) and link-local fe80::/10.
     */
    private fun isLocalIpv6(host: String): Boolean {
        if (!host.contains(':')) return false
        val normalized = host.trim('[', ']')
        val third = normalized.getOrNull(2)
        val isUniqueLocal = normalized.startsWith("fc") || normalized.startsWith("fd")
        val isLinkLocal = normalized.startsWith("fe") && third != null && third in '8'..'b'
        return normalized == "::1" || isUniqueLocal || isLinkLocal
    }
}
