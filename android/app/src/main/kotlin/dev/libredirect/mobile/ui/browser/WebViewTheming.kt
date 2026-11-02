package dev.libredirect.mobile.ui.browser

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import dev.libredirect.mobile.R

/**
 * WebView (for apps targeting API 33+) derives the `prefers-color-scheme`
 * value it exposes to pages — and whether algorithmic darkening may kick in —
 * from the `android:isLightTheme` attribute of the theme of the Context it was
 * created with. Wrapping the Activity in a ContextThemeWrapper whose
 * configuration forces night mode on or off lets each WebView render pages
 * light or dark regardless of the system setting. The value is read when the
 * WebView is created, so switching the page theme requires a new WebView.
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
        return ContextThemeWrapper(base, R.style.Theme_LibRedirectMobile).apply {
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
