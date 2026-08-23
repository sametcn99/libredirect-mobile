package dev.libredirect.mobile.core.url

object UrlValidation {
    val ALLOWED_INPUT_SCHEMES = setOf("http", "https")
    const val MAX_INPUT_LENGTH = 32 * 1024

    private val ORIGIN_PATTERN =
        Regex(
            "^https://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+(:[0-9]{1,5})?$",
        )

    /**
     * Mirrors schema/routes.schema.json's `instanceOrigin` definition:
     * HTTPS scheme, host, optional port, no path/query/fragment. Used both
     * to validate a user-supplied custom instance at resolve time and to
     * back the settings UI's input validation.
     */
    fun isValidHttpsOrigin(value: String): Boolean = ORIGIN_PATTERN.matches(value)
}
