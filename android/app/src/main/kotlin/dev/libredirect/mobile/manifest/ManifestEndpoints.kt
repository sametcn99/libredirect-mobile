package dev.libredirect.mobile.manifest

/**
 * Live upstream sources, fetched directly (no intermediate hosting of our
 * own) and combined into the routing manifest by the in-app generator:
 * config.json supplies the service/target/frontend structure, data.json the
 * per-frontend HTTPS clearnet instance lists. The bundled asset is only the
 * first-start/offline fallback.
 */
object ManifestEndpoints {
    const val CONFIG_URL = "https://raw.githubusercontent.com/libredirect/browser_extension/master/src/config.json"

    const val INSTANCES_DATA_URL = "https://raw.githubusercontent.com/libredirect/instances/main/data.json"
}
