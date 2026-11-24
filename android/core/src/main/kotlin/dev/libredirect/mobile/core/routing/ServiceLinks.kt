package dev.libredirect.mobile.core.routing

import dev.libredirect.mobile.core.url.ParsedUrl
import dev.libredirect.mobile.core.url.QueryString

/** Fixed, locally audited transformations for frontends whose URL shape differs from the source. */
internal object ServiceLinks {
    @Suppress("MagicNumber")
    private val LATITUDE_RANGE = -90.0..90.0

    @Suppress("MagicNumber")
    private val LONGITUDE_RANGE = -180.0..180.0

    @Suppress("MagicNumber")
    private val ZOOM_RANGE = 0.0..22.0
    private val fandomWiki = Regex("^([a-z0-9-]+)\\.(?:fandom|wikia)\\.com$")
    private val wikipediaLanguage = Regex("^([a-z][a-z0-9-]*)(\\.m)?\\.wikipedia\\.org$")
    private val videoId = Regex("^[A-Za-z0-9_-]+$")
    private val googleHost = Regex("^(www\\.)?(maps\\.)?google(\\.[a-z]{2,3}){1,2}$")
    private val center = Regex("@(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?),(\\d+(?:\\.\\d+)?)z(?:/|$)")
    private val coordinates = Regex("(-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)")

    fun matchesRoute(
        routeId: String,
        url: ParsedUrl,
    ): Boolean =
        routeId != "maps" || url.host.contains("maps.google.") ||
            url.rawPath == "/maps" || url.rawPath.startsWith("/maps/")

    fun transform(
        routeId: String,
        frontendId: String,
        url: ParsedUrl,
    ): String? =
        when {
            routeId == "fandom" && frontendId in setOf("breeze-wiki", "phantom") -> fandom(url)
            routeId in setOf("wikipedia", "wikiless") && frontendId == "wikiless" -> wikipedia(url)
            routeId == "maps" && frontendId == "osm" -> maps(url)
            routeId == "youtube" && frontendId in setOf("invidious", "cloudtube", "piped", "piped-material") ->
                youtube(url, frontendId)
            else -> suffix(url)
        }

    fun canReverseByOrigin(
        routeId: String,
        frontendId: String,
        url: ParsedUrl,
    ): Boolean =
        when (routeId) {
            "fandom", "wikipedia", "wikiless", "maps", "youtube-1" -> false
            "youtube" -> frontendId == "invidious" && url.rawPath in setOf("/watch", "/playlist", "/results")
            else -> true
        }

    private fun fandom(url: ParsedUrl): String? {
        val wiki = fandomWiki.matchEntire(url.host)?.groupValues?.get(1)?.takeUnless { it == "www" } ?: return null
        val prefix = "/$wiki"
        val search = QueryString.find(url.rawQuery, "query")
        return when {
            url.rawPath == "/wiki/Special:Search" && !search.isNullOrBlank() ->
                "$prefix/search?q=${QueryString.encode(search)}"
            url.rawPath.startsWith("/wiki/") && !url.rawPath.startsWith("/wiki/Special:") -> prefix + suffix(url)
            else -> null
        }
    }

    @Suppress("ReturnCount")
    private fun wikipedia(url: ParsedUrl): String? {
        val language = wikipediaLanguage.matchEntire(url.host)?.groupValues?.get(1) ?: return null
        if (language in setOf("www", "m") || !url.rawPath.startsWith("/wiki/")) return null
        val requestedLanguage = QueryString.find(url.rawQuery, "lang")
        if (requestedLanguage != null && requestedLanguage != language) return null
        val query =
            if (requestedLanguage == null) appendQuery(url.rawQuery, "lang=$language") else url.rawQuery
        return suffix(url.copy(rawQuery = query))
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun youtube(
        url: ParsedUrl,
        frontendId: String,
    ): String? {
        val shortHost = url.host in setOf("youtu.be", "www.youtu.be")
        val directHost = url.host in setOf("youtube.com", "www.youtube.com", "m.youtube.com")
        val shortShape = shortHost || url.rawPath.startsWith("/live/") || url.rawPath.startsWith("/shorts/")
        if (shortShape) {
            if ((!shortHost && !directHost) || frontendId != "invidious") return null
            val id = url.rawPath.trimEnd('/').substringAfterLast('/')
            if (!videoId.matches(id) || (shortHost && url.rawPath.trim('/').contains('/'))) return null
            if (QueryString.find(url.rawQuery, "v") != null) return null
            return suffix(url.copy(rawPath = "/watch", rawQuery = appendQuery("v=$id", url.rawQuery)))
        }
        val path = url.rawPath
        return when {
            directHost && (
                path in setOf("/watch", "/results", "/playlist", "/", "") ||
                    path.startsWith("/channel/")
            ) -> suffix(url)
            url.host in setOf("www.youtube-nocookie.com", "youtube-nocookie.com") &&
                frontendId == "invidious" && path.startsWith("/embed/") -> suffix(url)
            else -> null
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "ComplexCondition")
    private fun maps(url: ParsedUrl): String? {
        if (!googleHost.matches(url.host)) return null
        val path = url.rawPath
        if (path in setOf("/maps/dir", "/maps/dir/")) return directions(url)
        if (!url.host.contains("maps.google.") && path != "/maps" && !path.startsWith("/maps/")) return null
        if (path.startsWith("/maps/embed") || path.startsWith("/maps/place") || path.startsWith("/maps/dir") ||
            QueryString.find(url.rawQuery, "map_action") == "pano"
        ) {
            return null
        }
        val map = center.find(path)?.destructured?.let { (lat, lon, zoom) -> mapFragment(lat, lon, zoom) }
        val position = QueryString.find(url.rawQuery, "ll")?.let(coordinates::matchEntire)
        val query = QueryString.find(url.rawQuery, "query") ?: QueryString.find(url.rawQuery, "q")
        return when {
            !query.isNullOrBlank() -> "/search?query=${QueryString.encode(query)}${map.orEmpty()}"
            map != null -> "/$map"
            position != null -> position.destructured.let { (lat, lon) -> mapFragment(lat, lon, "15")?.let { "/$it" } }
            else -> null
        }
    }

    @Suppress("ReturnCount")
    private fun directions(url: ParsedUrl): String? {
        if (QueryString.find(url.rawQuery, "waypoints") != null ||
            QueryString.find(url.rawQuery, "avoid") != null
        ) {
            return null
        }
        val start = QueryString.find(url.rawQuery, "origin")?.takeIf(::validCoordinates) ?: return null
        val end = QueryString.find(url.rawQuery, "destination")?.takeIf(::validCoordinates) ?: return null
        val mode = QueryString.find(url.rawQuery, "travelmode") ?: "driving"
        val engine =
            when (mode) {
                "driving" -> "car"
                "walking" -> "foot"
                "bicycling" -> "bike"
                else -> return null
            }
        return "/directions?engine=fossgis_osrm_$engine&route=${QueryString.encode("$start;$end")}"
    }

    private fun validCoordinates(value: String): Boolean =
        coordinates.matchEntire(value)?.destructured?.let { (lat, lon) ->
            lat.toDouble() in LATITUDE_RANGE && lon.toDouble() in LONGITUDE_RANGE
        } ?: false

    private fun mapFragment(
        lat: String,
        lon: String,
        zoom: String,
    ): String? =
        if (validCoordinates("$lat,$lon") && zoom.toDouble() in ZOOM_RANGE) {
            "#map=${zoom.toDouble().toInt()}/$lat/$lon"
        } else {
            null
        }

    private fun appendQuery(
        query: String?,
        newPair: String?,
    ): String = listOfNotNull(query, newPair).filter(String::isNotEmpty).joinToString("&")

    private fun suffix(url: ParsedUrl): String =
        url.rawPath + url.rawQuery?.let { "?$it" }.orEmpty() + url.rawFragment?.let { "#$it" }.orEmpty()
}
