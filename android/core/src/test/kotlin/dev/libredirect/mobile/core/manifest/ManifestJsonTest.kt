package dev.libredirect.mobile.core.manifest

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

private val ALL_STRATEGIES_MANIFEST =
    """
    {
      "schemaVersion": 1,
      "revision": 7,
      "generatedAt": "2026-08-23T14:00:00Z",
      "routes": [
        {
          "id": "youtube",
          "name": "YouTube",
          "hosts": ["youtube.com", "www.youtube.com"],
          "frontends": [
            {
              "id": "invidious",
              "name": "Invidious",
              "strategy": { "type": "replace-origin" },
              "instances": ["https://yewtu.be"]
            },
            {
              "id": "official",
              "name": "Official Site",
              "strategy": { "type": "passthrough" }
            }
          ]
        },
        {
          "id": "reddit",
          "name": "Reddit",
          "hosts": ["reddit.com"],
          "frontends": [
            {
              "id": "redlib",
              "name": "Redlib",
              "strategy": { "type": "template", "output": "{instance}/search?q={query:q}" },
              "instances": ["https://redlib.example.org"]
            }
          ]
        },
        {
          "id": "some-service",
          "name": "Some Service",
          "hosts": ["some-service.example"],
          "frontends": [
            {
              "id": "some-app",
              "name": "Some App",
              "strategy": { "type": "custom-scheme", "scheme": "someapp" }
            }
          ]
        }
      ]
    }
    """.trimIndent()

class ManifestJsonTest {
    @Test
    fun `decodes a manifest covering all four strategies`() {
        val manifest = ManifestJson.decode(ALL_STRATEGIES_MANIFEST)

        assertEquals(1, manifest.schemaVersion)
        assertEquals(7, manifest.revision)
        assertEquals(3, manifest.routes.size)
        assertEquals(Strategy.ReplaceOrigin, manifest.routes[0].frontends[0].strategy)
        assertEquals(Strategy.Passthrough, manifest.routes[0].frontends[1].strategy)
        assertEquals(
            Strategy.Template("{instance}/search?q={query:q}"),
            manifest.routes[1].frontends[0].strategy,
        )
        assertEquals(Strategy.CustomScheme("someapp"), manifest.routes[2].frontends[0].strategy)
    }

    @Test
    fun `rejects an unrecognized top-level field`() {
        val raw =
            """
            {
              "schemaVersion": 1,
              "revision": 1,
              "generatedAt": "2026-08-23T14:00:00Z",
              "routes": [],
              "telemetryEndpoint": "https://evil.example.org"
            }
            """.trimIndent()

        assertThrows(SerializationException::class.java) { ManifestJson.decode(raw) }
    }

    @Test
    fun `rejects an unsupported strategy type`() {
        val raw =
            """
            {
              "schemaVersion": 1,
              "revision": 1,
              "generatedAt": "2026-08-23T14:00:00Z",
              "routes": [
                {
                  "id": "youtube",
                  "name": "YouTube",
                  "hosts": ["youtube.com"],
                  "frontends": [
                    { "id": "js", "name": "JS", "strategy": { "type": "eval" }, "instances": ["https://example.org"] }
                  ]
                }
              ]
            }
            """.trimIndent()

        assertThrows(SerializationException::class.java) { ManifestJson.decode(raw) }
    }

    @Test
    fun `rejects instances on a passthrough frontend`() {
        val raw =
            """
            {
              "schemaVersion": 1,
              "revision": 1,
              "generatedAt": "2026-08-23T14:00:00Z",
              "routes": [
                {
                  "id": "youtube",
                  "name": "YouTube",
                  "hosts": ["youtube.com"],
                  "frontends": [
                    {
                      "id": "official",
                      "name": "Official Site",
                      "strategy": { "type": "passthrough" },
                      "instances": ["https://youtube.com"]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) { ManifestJson.decode(raw) }
    }

    @Test
    fun `rejects revision below 1`() {
        val raw =
            """
            {
              "schemaVersion": 1,
              "revision": 0,
              "generatedAt": "2026-08-23T14:00:00Z",
              "routes": []
            }
            """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) { ManifestJson.decode(raw) }
    }
}
