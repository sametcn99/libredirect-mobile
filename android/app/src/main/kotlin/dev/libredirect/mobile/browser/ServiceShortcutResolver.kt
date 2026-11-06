package dev.libredirect.mobile.browser

import dev.libredirect.mobile.core.manifest.Route
import dev.libredirect.mobile.core.routing.InstanceSelection

/**
 * Instance a home-screen service shortcut (C3) should open, extracted so the
 * selection precedence is unit-testable: the user's explicit Pinned/Custom
 * choice for the selected frontend wins; Automatic falls back to the
 * frontend's first instance. Null when the route is unknown or the chosen
 * frontend has no instances (e.g. a passthrough frontend).
 */
object ServiceShortcutResolver {
    fun resolveInstanceUrl(
        route: Route?,
        selectedFrontends: Map<String, String>,
        instanceSelections: Map<String, InstanceSelection>,
    ): String? {
        route ?: return null
        val frontendId = selectedFrontends[route.id] ?: route.frontends.first().id
        val frontend = route.frontends.find { it.id == frontendId } ?: route.frontends.first()
        return when (val selection = instanceSelections["${route.id}/${frontend.id}"]) {
            is InstanceSelection.Pinned -> selection.instance
            is InstanceSelection.Custom -> selection.instance
            InstanceSelection.Automatic, InstanceSelection.Fastest, null -> frontend.instances.firstOrNull()
        }
    }
}
