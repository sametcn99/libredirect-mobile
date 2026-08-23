package dev.libredirect.mobile.core.manifest

import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestValidatorTest {
    @Test
    fun `accepts a valid manifest`() {
        val manifest =
            Manifest(
                schemaVersion = 1,
                revision = 1,
                generatedAt = "2026-08-23T00:00:00Z",
                routes =
                    listOf(
                        Route(
                            id = "video",
                            name = "Video",
                            hosts = listOf("video.example"),
                            frontends =
                                listOf(
                                    Frontend(
                                        id = "frontend",
                                        name = "Frontend",
                                        strategy = Strategy.ReplaceOrigin,
                                        instances = listOf("https://frontend.example"),
                                    ),
                                ),
                        ),
                    ),
            )

        assertTrue(ManifestValidator.validate(manifest).isEmpty())
    }

    @Test
    fun `rejects duplicate hosts and private instances`() {
        val route =
            Route(
                id = "video",
                name = "Video",
                hosts = listOf("video.example"),
                frontends =
                    listOf(
                        Frontend(
                            id = "frontend",
                            name = "Frontend",
                            strategy = Strategy.ReplaceOrigin,
                            instances = listOf("https://127.0.0.1"),
                        ),
                    ),
            )
        val manifest =
            Manifest(
                schemaVersion = 2,
                revision = 1,
                generatedAt = "2026-08-23T00:00:00Z",
                routes = listOf(route, route.copy(id = "video-copy")),
            )

        val errors = ManifestValidator.validate(manifest)
        assertTrue(errors.any { it.contains("schemaVersion") })
        assertTrue(errors.any { it.contains("duplicate host") })
        assertTrue(errors.any { it.contains("private instance") })
    }

    @Test
    fun `rejects unsupported template placeholders`() {
        val manifest =
            Manifest(
                schemaVersion = 1,
                revision = 1,
                generatedAt = "2026-08-23T00:00:00Z",
                routes =
                    listOf(
                        Route(
                            id = "search",
                            name = "Search",
                            hosts = listOf("search.example"),
                            frontends =
                                listOf(
                                    Frontend(
                                        id = "frontend",
                                        name = "Frontend",
                                        strategy = Strategy.Template("{instance}/?{unknown}"),
                                        instances = listOf("https://frontend.example"),
                                    ),
                                ),
                        ),
                    ),
            )

        assertTrue(ManifestValidator.validate(manifest).any { it.contains("placeholder") })
    }

    @Test
    fun `accepts bounded hostname patterns and rejects unsafe patterns`() {
        val valid =
            Manifest(
                schemaVersion = 1,
                revision = 1,
                generatedAt = "2026-08-23T00:00:00Z",
                routes =
                    listOf(
                        Route(
                            id = "pattern-service",
                            name = "Pattern service",
                            hosts = emptyList(),
                            hostPatterns = listOf("^([a-z]+\\.)?example\\.com$"),
                            frontends =
                                listOf(
                                    Frontend(
                                        id = "frontend",
                                        name = "Frontend",
                                        strategy = Strategy.Passthrough,
                                    ),
                                ),
                        ),
                    ),
            )
        assertTrue(ManifestValidator.validate(valid).isEmpty())

        val unsafe =
            valid.copy(
                routes = listOf(valid.routes.single().copy(hostPatterns = listOf("^(.+)+\\.example\\.com$"))),
            )
        assertTrue(ManifestValidator.validate(unsafe).any { it.contains("unsafe host pattern") })
    }
}
