package dev.libredirect.mobile.core.manifest

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object ManifestJson {
    private val json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = false
            isLenient = false
        }

    /**
     * @throws SerializationException if [raw] is not valid JSON, has an
     * unrecognized field, or violates a [Manifest]/[Route]/[Frontend] `init`
     * invariant.
     */
    fun decode(raw: String): Manifest = json.decodeFromString(Manifest.serializer(), raw)
}
