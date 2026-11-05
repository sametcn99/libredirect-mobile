package dev.libredirect.mobile.ui.browser

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import dev.libredirect.mobile.R

/**
 * WebView derives the `prefers-color-scheme` value it exposes to pages from
 * the `isLightTheme` attribute of the theme of the Context it was created
 * with — a DayNight theme always resolves that against the system setting,
 * which is why the fixed ForceLight/ForceDark variants exist. The night-mode
 * configuration override is applied as well, for resource resolution inside
 * the WebView and for older WebView builds that key off uiMode. The value is
 * read when the WebView is created, so switching the page theme requires a
 * new WebView (see [BrowserViewModel.getOrCreateWebView]).
 */
internal object WebViewTheming {
    private const val LIGHT_BACKGROUND = 0xFFFFFFFF.toInt()
    private const val DARK_BACKGROUND = 0xFF1C1B1F.toInt()

    fun themedContext(
        base: Context,
        dark: Boolean,
    ): Context {
        val nightMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val override =
            Configuration(base.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
            }
        val themeRes =
            if (dark) {
                R.style.Theme_LibRedirectMobile_ForceDark
            } else {
                R.style.Theme_LibRedirectMobile_ForceLight
            }
        return ContextThemeWrapper(base, themeRes).apply {
            applyOverrideConfiguration(override)
        }
    }

    /**
     * Matches the WebView's own background to the page theme so a blank or
     * still-loading page doesn't flash white in dark mode.
     */
    fun applyBackground(
        webView: WebView,
        dark: Boolean,
    ) {
        webView.setBackgroundColor(if (dark) DARK_BACKGROUND else LIGHT_BACKGROUND)
    }

    /**
     * Algorithmic darkening only ever applies while the WebView's theme is
     * dark, and only to pages that don't declare dark support themselves, so
     * sites with a native dark theme keep using it.
     */
    fun applyAutoDarken(
        webView: WebView,
        dark: Boolean,
        autoDarken: Boolean,
    ) {
        val settings = webView.settings
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, autoDarken)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            // Older WebView builds that predate algorithmic darkening.
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(
                settings,
                if (dark && autoDarken) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF,
            )
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                @Suppress("DEPRECATION")
                WebSettingsCompat.setForceDarkStrategy(
                    settings,
                    WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING,
                )
            }
        }
    }
}
