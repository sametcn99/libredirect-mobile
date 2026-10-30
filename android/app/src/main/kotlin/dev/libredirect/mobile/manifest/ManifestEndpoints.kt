package dev.libredirect.mobile.manifest

/**
 * Live instance data source. Refreshing the routing data fetches this
 * upstream file directly (no intermediate hosting of our own) and merges
 * its per-frontend HTTPS clearnet instance lists into the active manifest.
 * Routes/hosts/frontend structure come from the bundled manifest and only
 * change with an app release.
 */
object ManifestEndpoints {
    const val INSTANCES_DATA_URL = "https://raw.githubusercontent.com/libredirect/instances/main/data.json"
}
