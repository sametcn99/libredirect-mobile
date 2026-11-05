package dev.libredirect.mobile.core.upstream

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Typed view of libredirect/browser_extension's `src/config.json` — the
 * upstream source of the routing structure (services, their target regexes
 * and hosted frontends). Parsed leniently about unknown keys (the upstream
 * config carries UI-only fields this app doesn't model) but strictly about
 * the modeled ones: a shape this app doesn't understand fails the whole
 * parse, and the refresh falls back to the last good manifest rather than
 * generating a half-understood one.
 */
@Serializable
data class LibRedirectFrontend(
    val name: String,
    /** Only frontends with a hosted instance list produce instance-backed routes. */
    val instanceList: Boolean? = null,
    /** Target indexes this frontend must not apply to (see [LibRedirectService.targets]). */
    val excludeTargets: List<Int> = emptyList(),
)

@Serializable
data class LibRedirectService(
    val name: String,
    val targets: List<String> = emptyList(),
    val frontends: Map<String, LibRedirectFrontend> = emptyMap(),
)

@Serializable
data class LibRedirectConfig(
    val services: Map<String, LibRedirectService> = emptyMap(),
)

object LibRedirectConfigParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** @return the parsed config, or null when the raw bytes are not the expected shape. */
    fun parse(raw: ByteArray): LibRedirectConfig? =
        try {
            json.decodeFromString<LibRedirectConfig>(String(raw, Charsets.UTF_8))
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}
