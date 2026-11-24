package dev.libredirect.mobile.ui.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.UUID

/** Owns the private profile of one browser session. Profiles are never reattached across sessions. */
internal class WebViewProfileManager {
    private var profileName: String? = null

    fun clearAbandonedProfiles() {
        if (performedStartupCleanup || !isSupported()) return
        performedStartupCleanup = true
        val store = ProfileStore.getInstance()
        store.allProfileNames
            .filter { it == LEGACY_PROFILE || it.startsWith(PROFILE_PREFIX) }
            .forEach(::deleteProfile)
    }

    fun attach(webView: WebView) {
        val name = profileName ?: "$PROFILE_PREFIX${UUID.randomUUID()}"
        WebViewCompat.setProfile(webView, name)
        profileName = name
    }

    fun cookies(): CookieManager? =
        profileName?.takeIf { isSupported() }?.let { ProfileStore.getInstance().getProfile(it)?.cookieManager }

    fun storage(): WebStorage? =
        profileName?.takeIf { isSupported() }?.let { ProfileStore.getInstance().getProfile(it)?.webStorage }

    fun endSession() {
        val name = profileName ?: return
        profileName = null
        if (isSupported()) deleteProfile(name)
    }

    private fun deleteProfile(name: String) {
        // A live renderer can defer deletion. The next process tries again before creating a new profile.
        runCatching { ProfileStore.getInstance().deleteProfile(name) }
    }

    companion object {
        private const val PROFILE_PREFIX = "libredirect-private-"
        private const val LEGACY_PROFILE = "incognito"
        private var performedStartupCleanup = false

        fun isSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }
}
