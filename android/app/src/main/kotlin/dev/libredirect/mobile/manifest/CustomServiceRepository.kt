package dev.libredirect.mobile.manifest

import android.content.Context
import dev.libredirect.mobile.core.manifest.Route
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stores user-created services separately from the signed upstream manifest. */
class CustomServiceRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = false
            encodeDefaults = false
        }

    @Synchronized
    fun routes(): List<Route> =
        try {
            json.decodeFromString(ListSerializer(Route.serializer()), preferences.getString(KEY_ROUTES, "[]") ?: "[]")
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }

    @Synchronized
    fun save(route: Route) {
        val updated = routes().filterNot { it.id == route.id } + route
        preferences.edit().putString(KEY_ROUTES, json.encodeToString(updated)).apply()
    }

    @Synchronized
    fun delete(routeId: String) {
        val updated = routes().filterNot { it.id == routeId }
        preferences.edit().putString(KEY_ROUTES, json.encodeToString(updated)).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "custom_services"
        const val KEY_ROUTES = "routes_json"
    }
}
