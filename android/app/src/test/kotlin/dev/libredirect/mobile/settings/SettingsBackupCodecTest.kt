package dev.libredirect.mobile.settings

import dev.libredirect.mobile.core.routing.ExceptionRule
import dev.libredirect.mobile.core.routing.InstanceSelection
import dev.libredirect.mobile.ui.browser.BrowserBookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupCodecTest {
    /** Every settings field populated with a distinct, non-default value. */
    private val fullSettings =
        AppSettings(
            routingEnabled = false,
            selectedBrowserPackage = "org.mozilla.firefox",
            disabledRoutes = setOf("youtube"),
            askModeRoutes = setOf("reddit"),
            selectedFrontends = mapOf("youtube" to "invidious"),
            instanceSelections =
                mapOf(
                    "youtube/invidious" to InstanceSelection.Pinned("https://inv.example"),
                    "x/nitter" to InstanceSelection.Fastest,
                ),
            exceptions = listOf(ExceptionRule.Domain("example.org"), ExceptionRule.UrlPrefix("x.com/home")),
            searchEngine = "brave",
            customSearchEngineName = "Mine",
            customSearchEngineUrl = "https://search.example/?q=%s",
            browserPageTheme = "dark",
            browserAutoDarken = false,
            clipboardOfferEnabled = true,
            autoInstanceRefreshEnabled = true,
            browserTextZoom = 150,
            appLockEnabled = true,
            autoFailoverEnabled = false,
            backgroundLatencyMeasureEnabled = true,
            unwrapRedirectWrappersEnabled = false,
            shortLinkResolveEnabled = true,
            queryParamStripEnabled = false,
            customTrackingParams =
                listOf(
                    CustomTrackingParam(host = null, name = "mytag"),
                    CustomTrackingParam(host = "shop.example", name = "aff*"),
                ),
            contentBlockerEnabled = false,
            closeIncognitoOnExit = true,
            autoCloseOldTabs = TabAutoClose.ONE_WEEK.id,
            historyLimit = 0,
            autoHideToolbar = true,
            backgroundAudioEnabled = true,
            siteSettings =
                mapOf(
                    "example.org" to SiteSetting(javaScriptEnabled = false, forceDark = true, textZoom = 130),
                ),
            pausedUntilEpochMillis = 1234567890L,
        )

    private val backup =
        SettingsBackupCodec.Backup(
            exportedAtEpochMillis = 1_700_000_000_000,
            appVersionName = "1.4.0",
            settings = fullSettings,
            customRoutes = emptyList(),
            bookmarks = listOf(BrowserBookmark(title = "Docs", url = "https://example.org/docs")),
        )

    @Test
    fun `fully populated settings survive a round trip`() {
        val decoded = SettingsBackupCodec.decode(SettingsBackupCodec.encode(backup))
        val success = decoded as SettingsBackupCodec.ImportResult.Success
        assertEquals(fullSettings, success.backup.settings)
        assertEquals(1, success.backup.bookmarks.size)
    }

    @Test
    fun `codec covers every AppSettings constructor property`() {
        // AppSettings is serialized directly, so every @Serializable
        // constructor property is carried by construction. This asserts a
        // sample of them shows up in the JSON so an accidental move to a
        // hand-written payload type cannot silently drop fields.
        val encoded = SettingsBackupCodec.encode(backup)
        listOf(
            "askModeRoutes",
            "queryParamStripEnabled",
            "customTrackingParams",
            "siteSettings",
            "backgroundAudioEnabled",
            "pausedUntilEpochMillis",
        ).forEach { name ->
            assertTrue("AppSettings.$name missing from backup JSON", encoded.contains("\"$name\""))
        }
    }

    @Test
    fun `malformed content is rejected`() {
        val result = SettingsBackupCodec.decode("not json at all")
        assertEquals(SettingsBackupCodec.Reason.MALFORMED, (result as SettingsBackupCodec.ImportResult.Failure).reason)
    }

    @Test
    fun `unsupported schema version is rejected`() {
        val bumped = backup.copy(backupSchemaVersion = SettingsBackupCodec.BACKUP_SCHEMA_VERSION + 1)
        val result = SettingsBackupCodec.decode(SettingsBackupCodec.encode(bumped))
        assertEquals(
            SettingsBackupCodec.Reason.UNSUPPORTED_VERSION,
            (result as SettingsBackupCodec.ImportResult.Failure).reason,
        )
    }

    @Test
    fun `non-https custom instance is rejected on import`() {
        val hostile =
            fullSettings.copy(
                instanceSelections =
                    mapOf(
                        "youtube/invidious" to InstanceSelection.Custom("http://192.168.1.5"),
                    ),
            )
        val result =
            SettingsBackupCodec.decode(
                SettingsBackupCodec.encode(backup.copy(settings = hostile)),
            )
        assertEquals(
            SettingsBackupCodec.Reason.INVALID_CUSTOM_INSTANCE,
            (result as SettingsBackupCodec.ImportResult.Failure).reason,
        )
    }

    @Test
    fun `oversized payload is rejected`() {
        val huge =
            SettingsBackupCodec.encode(
                backup.copy(
                    bookmarks =
                        List(
                            80_000,
                        ) { index -> BrowserBookmark(title = "b$index", url = "https://e$index.org") },
                ),
            )
        assertTrue(huge.toByteArray(Charsets.UTF_8).size > SettingsBackupCodec.MAX_BACKUP_BYTES)
        val result = SettingsBackupCodec.decode(huge)
        assertEquals(SettingsBackupCodec.Reason.TOO_LARGE, (result as SettingsBackupCodec.ImportResult.Failure).reason)
    }
}
