package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.manifest.Frontend
import dev.libredirect.mobile.core.manifest.Manifest
import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.manifest.Strategy
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceLinkFixturesTest {
    private val origin = "https://frontend.example"
    private val manifest =
        Manifest(
            schemaVersion = 1,
            revision = 1,
            generatedAt = "2026-11-25T00:00:00Z",
            routes =
                listOf(
                    route("youtube", "invidious", "youtube.com", "youtu.be"),
                    route("fandom", "breeze-wiki", "zelda.fandom.com"),
                    route("wikipedia", "wikiless", "tr.wikipedia.org"),
                    route("maps", "osm", "maps.google.com", "www.google.com"),
                ),
        )
    private val router = UrlRouter(manifest)

    @Test
    fun `supported service fixtures preserve their meaning`() {
        val directions =
            "https://www.google.com/maps/dir/?api=1&origin=39.93,32.86" +
                "&destination=39.96,32.89&travelmode=walking"
        val fixtures =
            mapOf(
                "https://youtube.com/results?search_query=kotlin&utm_source=test" to "/results?search_query=kotlin",
                "https://youtu.be/f-o_o?t=30&si=share" to "/watch?v=f-o_o&t=30",
                "https://youtube.com/live/abc?t=9" to "/watch?v=abc&t=9",
                "https://zelda.fandom.com/wiki/Link?so=search#Overview" to "/zelda/wiki/Link?so=search#Overview",
                "https://tr.wikipedia.org/wiki/Ankara#Tarih" to "/wiki/Ankara?lang=tr#Tarih",
                "https://maps.google.com/?ll=39.93,32.86" to "/#map=15/39.93/32.86",
                "https://www.google.com/maps/@39.93,32.86,13z" to "/#map=13/39.93/32.86",
                directions to
                    "/directions?engine=fossgis_osrm_foot&route=39.93%2C32.86%3B39.96%2C32.89",
            )
        fixtures.forEach { (url, expected) ->
            assertEquals(url, origin + expected, router.explain(url, RoutingContext()).resultUrl)
        }
    }

    @Test
    fun `unverified maps links and signed URLs are preserved`() {
        val directions = "https://www.google.com/maps/dir/?origin=Ankara&destination=Istanbul"
        val signed = "https://youtube.com/watch?v=x&sig=abc&utm_source=required"
        assertEquals(TraceReason.UNSUPPORTED_LINK_SHAPE, router.explain(directions, RoutingContext()).reason)
        assertEquals(RoutingResult.Passthrough(signed), router.resolve(signed, RoutingContext()))
        assertEquals(
            "https://example.org/callback?code=abc&state=x",
            router.explain(
                "https://example.org/callback?code=abc&state=x&utm_campaign=demo",
                RoutingContext(),
            ).resultUrl,
        )
    }

    @Test
    fun `personal instance works without a public list`() {
        val frontend = Frontend("wikiless", "Wikiless", Strategy.ReplaceOrigin)
        val original = "https://tr.wikipedia.org/wiki/Ankara"
        val routes =
            manifest.routes.map {
                    route ->
                if (route.id == "wikipedia") route.copy(frontends = listOf(frontend)) else route
            }
        val context =
            RoutingContext(instanceSelections = mapOf("wikipedia/wikiless" to InstanceSelection.Custom(origin)))
        assertEquals(
            "$origin/wiki/Ankara?lang=tr",
            UrlRouter(manifest.copy(routes = routes)).explain(original, context).resultUrl,
        )
    }

    private fun route(
        id: String,
        frontendId: String,
        vararg hosts: String,
    ): Route =
        Route(id, id, hosts.toList(), listOf(Frontend(frontendId, frontendId, Strategy.ReplaceOrigin, listOf(origin))))
}
