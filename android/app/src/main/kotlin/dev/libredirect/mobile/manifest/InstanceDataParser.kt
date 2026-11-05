package dev.libredirect.mobile.manifest

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses libredirect/instances's `data.json` (frontend id -> per-network
 * instance URL lists) into a map of kebab-cased frontend ids to usable
 * HTTPS clearnet instance origins, mirroring the tooling-side converter
 * (`tools/src/adapters/libredirect-instances.ts`): only `clearnet`, only
 * `https://` origin-only URLs, deduplicated, capped. Only `clearnet` is
 * relevant — this app routes over plain HTTPS, not Tor/I2P/Lokinet.
 */
object InstanceDataParser {
    private const val MAX_INSTANCES_PER_FRONTEND = 64

    private val json = Json { ignoreUnknownKeys = true }

    /** Matches the manifest's `instanceOrigin` contract enforced by `ManifestValidator`
     *  (strict, lowercase, origin-only). */
    private val instanceOriginRegex =
        Regex("^https://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+(:[0-9]{1,5})?$")

    /**
     * @return frontend id (kebab-cased) -> HTTPS clearnet instances, or
     * null if [raw] is not a JSON object with the expected per-frontend shape.
     */
    fun parse(raw: ByteArray): Map<String, List<String>>? =
        try {
            parseRoot(json.parseToJsonElement(String(raw, Charsets.UTF_8)))
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun parseRoot(root: JsonElement): Map<String, List<String>> {
        require(root is JsonObject) { "instance data must be a JSON object" }
        val parsed = LinkedHashMap<String, List<String>>()
        for ((frontendKey, networksElement) in root) {
            val instances = parseFrontend(networksElement) ?: continue
            if (instances.isNotEmpty()) parsed[toKebabCase(frontendKey)] = instances
        }
        return parsed
    }

    /** null when the frontend has no `clearnet` entry (skip); throws on malformed shapes. */
    private fun parseFrontend(networksElement: JsonElement): List<String>? {
        require(networksElement is JsonObject) { "frontend entry must be an object" }
        val clearnet = networksElement["clearnet"] ?: return null
        require(clearnet is JsonArray) { "clearnet must be an array of strings" }
        val seen = LinkedHashSet<String>()
        for (element in clearnet) {
            require(element is JsonPrimitive && element.isString) { "clearnet entries must be strings" }
            val url = element.content.trim().trimEnd('/')
            if (instanceOriginRegex.matches(url)) seen.add(url)
            if (seen.size >= MAX_INSTANCES_PER_FRONTEND) break
        }
        return seen.toList()
    }

    /**
     * Kotlin port of tools/src/generate-routes.ts's toKebabCase, so upstream
     * keys like `pipedMaterial` match manifest frontend ids like `piped-material`.
     */
    private fun toKebabCase(key: String): String =
        key
            .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
            .lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trimStart('-')
            .trimEnd('-')
}
