package dev.libredirect.mobile.settings

import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.core.routing.InstanceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decision helpers on [AppSettings] feed the routing core
 * (paused/disabled/ask-mode, per-site overrides, custom tracking rules) —
 * none of them had direct coverage before.
 */
class AppSettingsTest {
    @Test
    fun `tab auto close parses known ids and falls back to never`() {
        assertEquals(TabAutoClose.ONE_DAY, TabAutoClose.fromId("1d"))
        assertEquals(TabAutoClose.ONE_WEEK, TabAutoClose.fromId("1w"))
        assertEquals(TabAutoClose.ONE_MONTH, TabAutoClose.fromId("1m"))
        assertEquals(TabAutoClose.NEVER, TabAutoClose.fromId("never"))
        assertEquals(TabAutoClose.NEVER, TabAutoClose.fromId("1y"))
        assertEquals(TabAutoClose.NEVER, TabAutoClose.fromId(null))
        assertEquals(TabAutoClose.NEVER, TabAutoClose.fromId(""))
    }

    @Test
    fun `route toggles and ask mode read the sets`() {
        val settings = AppSettings(disabledRoutes = setOf("youtube"), askModeRoutes = setOf("instagram"))
        assertFalse(settings.isRouteEnabled("youtube"))
        assertTrue(settings.isRouteEnabled("instagram"))
        assertTrue(settings.isRouteAskMode("instagram"))
        assertFalse(settings.isRouteAskMode("youtube"))
    }

    @Test
    fun `routing is paused strictly until the deadline`() {
        val settings = AppSettings(pausedUntilEpochMillis = 1_000L)
        assertTrue(settings.isRoutingPaused(nowMillis = 999L))
        // The pause lapses the moment now reaches the deadline.
        assertFalse(settings.isRoutingPaused(nowMillis = 1_000L))
        assertFalse(settings.isRoutingPaused(nowMillis = 1_001L))
        assertFalse(AppSettings().isRoutingPaused(nowMillis = Long.MAX_VALUE))
    }

    @Test
    fun `site settings match exact hosts and subdomains case-insensitively`() {
        val override = SiteSetting(javaScriptEnabled = false)
        val settings = AppSettings(siteSettings = mapOf("example.org" to override))

        assertEquals(override, settings.siteSettingFor("example.org"))
        assertEquals(override, settings.siteSettingFor("EXAMPLE.ORG"))
        assertEquals(override, settings.siteSettingFor("www.example.org"))
        assertEquals(override, settings.siteSettingFor("a.b.example.org"))
        assertNull(settings.siteSettingFor("notexample.org"))
        assertNull(settings.siteSettingFor("example.org.evil.com"))
    }

    @Test
    fun `empty site settings are recognized`() {
        assertTrue(SiteSetting().isEmpty)
        assertFalse(SiteSetting(textZoom = 100).isEmpty)
        assertFalse(SiteSetting(contentBlocking = false).isEmpty)
    }

    @Test
    fun `custom tracking rules normalize names and hosts`() {
        val settings =
            AppSettings(
                customTrackingParams =
                    listOf(
                        CustomTrackingParam(host = null, name = " SessionID "),
                        CustomTrackingParam(host = "Shop.Example.", name = "AffID"),
                        CustomTrackingParam(host = "  ", name = "blank-host-goes-global"),
                        CustomTrackingParam(host = "example.org", name = "   "),
                    ),
            )
        val rules = settings.customTrackingRules()
        assertEquals(setOf("sessionid", "blank-host-goes-global"), rules.global)
        assertEquals(mapOf("shop.example" to setOf("affid")), rules.byHost)
    }

    @Test
    fun `routing context carries every user preference`() {
        val exception = ExceptionRule.Domain(host = "example.org")
        val settings =
            AppSettings(
                exceptions = listOf(exception),
                disabledRoutes = setOf("youtube"),
                selectedFrontends = mapOf("instagram" to "proxigram"),
                instanceSelections = mapOf("instagram/proxigram" to InstanceSelection.Fastest),
                customTrackingParams = listOf(CustomTrackingParam(name = "utm_extra")),
            )
        val context = settings.routingContext()
        assertEquals(listOf(exception), context.exceptions)
        assertEquals(setOf("youtube"), context.disabledRoutes)
        assertEquals(mapOf("instagram" to "proxigram"), context.selectedFrontends)
        assertEquals(mapOf("instagram/proxigram" to InstanceSelection.Fastest), context.instanceSelections)
        assertEquals(setOf("utm_extra"), context.customTrackingRules.global)
    }
}
