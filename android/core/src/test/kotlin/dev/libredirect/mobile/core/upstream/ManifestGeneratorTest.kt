package dev.libredirect.mobile.core.upstream

import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.manifest.Strategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ManifestGeneratorTest {
    private val fixedTime: Instant = Instant.parse("2026-11-05T12:00:00Z")

    @Test
    fun `groups hosts by applicable frontends with union over excludeTargets`() {
        val config =
            LibRedirectConfigParser.parse(
                """
                {
                  "services": {
                    "youtube": {
                      "name": "YouTube",
                      "targets": [
                        "^https?:\\/{2}(www\\.)?youtube\\.com\\/",
                        "^https?:\\/{2}youtu\\.be\\/"
                      ],
                      "frontends": {
                        "invidious": { "name": "Invidious", "instanceList": true },
                        "piped": { "name": "Piped", "instanceList": true, "excludeTargets": [1] }
                      }
                    }
                  }
                }
                """.toByteArray(),
            )!!
        val instances =
            mapOf(
                "invidious" to listOf("https://inv.example"),
                "piped" to listOf("https://piped.example"),
            )

        val generation = ManifestGenerator.generate(config, instances, previousRevision = 41, generatedAt = fixedTime)

        val manifest = generation.manifest
        assertEquals(42, manifest.revision)
        assertEquals("2026-11-05T12:00:00Z", manifest.generatedAt)
        assertEquals(1, manifest.schemaVersion)
        assertEquals(2, manifest.routes.size)

        val primary = manifest.routes[0]
        assertEquals("youtube", primary.id)
        assertEquals("YouTube", primary.name)
        assertEquals(setOf("youtube.com", "www.youtube.com"), primary.hosts.toSet())
        assertEquals(
            listOf(
                Frontend("invidious", "Invidious", Strategy.ReplaceOrigin, listOf("https://inv.example")),
                Frontend("piped", "Piped", Strategy.ReplaceOrigin, listOf("https://piped.example")),
            ),
            primary.frontends,
        )

        // youtu.be is only matched by target[1], where piped is excluded: its
        // own additional-hosts route carries invidious alone.
        val additional = manifest.routes[1]
        assertEquals("youtube-1", additional.id)
        assertEquals("YouTube (additional hosts)", additional.name)
        assertEquals(listOf("youtu.be"), additional.hosts)
        assertEquals(
            listOf(Frontend("invidious", "Invidious", Strategy.ReplaceOrigin, listOf("https://inv.example"))),
            additional.frontends,
        )
        assertTrue(generation.notes.isEmpty())
    }

    @Test
    fun `frontend lookup uses kebab-cased keys matching the runtime instance parser`() {
        val config =
            LibRedirectConfigParser.parse(
                """
                {
                  "services": {
                    "teddit": {
                      "name": "Teddit",
                      "targets": ["^https?:\\/{2}teddit\\.example\\/"],
                      "frontends": {
                        "pipedMaterial": { "name": "Piped Material", "instanceList": true }
                      }
                    }
                  }
                }
                """.toByteArray(),
            )!!

        val generation =
            ManifestGenerator.generate(
                config,
                instancesByFrontend = mapOf("piped-material" to listOf("https://pm.example")),
                previousRevision = 1,
                generatedAt = fixedTime,
            )

        assertEquals(
            listOf(Frontend("piped-material", "Piped Material", Strategy.ReplaceOrigin, listOf("https://pm.example"))),
            generation.manifest.routes.single().frontends,
        )
    }

    @Test
    fun `service without usable frontends falls back to the original passthrough frontend`() {
        val config =
            LibRedirectConfigParser.parse(
                """
                {
                  "services": {
                    "wikiless": {
                      "name": "Wikipedia",
                      "targets": ["^https?:\\/{2}(www\\.)?wikipedia\\.org\\/"],
                      "frontends": {
                        "wikiless": { "name": "Wikiless", "instanceList": true }
                      }
                    }
                  }
                }
                """.toByteArray(),
            )!!

        val generation =
            ManifestGenerator.generate(
                config,
                instancesByFrontend = emptyMap(),
                previousRevision = 7,
                generatedAt = fixedTime,
            )

        val route = generation.manifest.routes.single()
        // The route id comes from the service key, the display name from the service.
        assertEquals("wikiless", route.id)
        assertEquals("Wikipedia", route.name)
        assertEquals(setOf("wikipedia.org", "www.wikipedia.org"), route.hosts.toSet())
        assertEquals(
            listOf(Frontend("original", "Original site", Strategy.Passthrough)),
            route.frontends,
        )
        assertTrue(generation.notes.any { "no usable HTTPS clearnet instances" in it })
    }

    @Test
    fun `unsupported targets are reported never guessed`() {
        val config =
            LibRedirectConfigParser.parse(
                """
                {
                  "services": {
                    "quora": {
                      "name": "Quora",
                      "targets": [
                        "^https?:\\/{2}([a-zA-Z0-9-]+\\.)*quora\\.com\\/",
                        "^ftp:\\/{2}example\\.com\\/"
                      ],
                      "frontends": {
                        "quorar": { "name": "Quorar", "instanceList": true }
                      }
                    }
                  }
                }
                """.toByteArray(),
            )!!

        val generation =
            ManifestGenerator.generate(
                config,
                instancesByFrontend = mapOf("quorar" to listOf("https://quorar.example")),
                previousRevision = 3,
                generatedAt = fixedTime,
            )

        // The wildcard-subdomain target converts into an anchored hostname
        // pattern (never a guessed finite list); the wrong-scheme target is
        // dropped and reported.
        val route = generation.manifest.routes.single()
        assertEquals("quora", route.id)
        assertTrue(route.hosts.isEmpty())
        assertEquals(listOf("^([a-zA-Z0-9-]+\\.)*quora\\.com$"), route.hostPatterns)
        assertEquals(
            listOf(Frontend("quorar", "Quorar", Strategy.ReplaceOrigin, listOf("https://quorar.example"))),
            route.frontends,
        )
        assertFalse(generation.notes.any { "target[0]" in it })
        assertTrue(generation.notes.any { "target[1] unsupported" in it })
    }

    @Test
    fun `kebab-case conversion of camelCase service and frontend keys`() {
        assertEquals("piped-material", ManifestGenerator.toKebabCase("pipedMaterial"))
        assertEquals("lib-redirect", ManifestGenerator.toKebabCase("libRedirect"))
        assertEquals("simple", ManifestGenerator.toKebabCase("simple"))
        assertEquals("free-tube", ManifestGenerator.toKebabCase("FreeTube"))
    }
}
