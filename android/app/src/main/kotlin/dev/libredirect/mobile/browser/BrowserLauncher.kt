package dev.libredirect.mobile.browser

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dev.libredirect.mobile.R

/**
 * Redirect-loop prevention (project plan §19) lives here: the launch intent
 * always either carries an explicit target package, or — when no preferred
 * or system-default browser is known — an app chooser that has this app's
 * own component excluded via [Intent.EXTRA_EXCLUDE_COMPONENTS], so this
 * app can never end up back in its own hands regardless of what the user
 * or system picks.
 */
class BrowserLauncher(
    private val packageManager: PackageManager,
    private val ownPackageName: String,
    private val inAppBrowserLabel: String = "LibRedirect Browser",
) {
    constructor(context: Context) : this(
        packageManager = context.packageManager,
        ownPackageName = context.packageName,
        inAppBrowserLabel = context.getString(R.string.browser_in_app_label),
    )

    fun installedBrowsers(): List<BrowserInfo> {
        val externalBrowsers =
            packageManager
                .queryIntentActivities(probeIntent(), PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }
                .distinct()
                .filterNot { it == ownPackageName }
                .map { pkg ->
                    val label =
                        try {
                            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                        } catch (_: PackageManager.NameNotFoundException) {
                            pkg
                        }
                    BrowserInfo(packageName = pkg, label = label)
                }
        return listOf(BrowserInfo(packageName = IN_APP_BROWSER_PACKAGE, label = inAppBrowserLabel)) +
            externalBrowsers
    }

    fun isInstalled(packageName: String): Boolean =
        packageName == IN_APP_BROWSER_PACKAGE ||
            (packageName != SYSTEM_DEFAULT_PACKAGE && installedBrowsers().any { it.packageName == packageName })

    /**
     * @param preferredPackage a user-chosen browser (Phase 6 settings). Falls back to the
     * system default browser, then to an exclusion-chooser, if unset or no longer installed.
     */
    fun launch(
        context: Context,
        url: String,
        preferredPackage: String?,
    ): Boolean {
        if (preferredPackage == IN_APP_BROWSER_PACKAGE) {
            val intent =
                Intent().apply {
                    component = ComponentName(context.packageName, MAIN_ACTIVITY_CLASS_NAME)
                    action = ACTION_OPEN_IN_APP_BROWSER
                    data = Uri.parse(url)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            return tryStart(context, intent)
        }

        val target = preferredPackage?.takeIf { isInstalled(it) } ?: systemDefaultBrowserPackage()
        val targetIntent =
            if (target != null) {
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { setPackage(target) }
            } else {
                Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), null).apply {
                    putExtra(
                        Intent.EXTRA_EXCLUDE_COMPONENTS,
                        arrayOf(ComponentName(context.packageName, REDIRECT_ACTIVITY_CLASS_NAME)),
                    )
                }
            }
        return tryStart(context, targetIntent)
    }

    private fun systemDefaultBrowserPackage(): String? {
        val resolved = packageManager.resolveActivity(probeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        val pkg = resolved?.activityInfo?.packageName ?: return null
        return pkg.takeUnless { it == ownPackageName }
    }

    private fun tryStart(
        context: Context,
        intent: Intent,
    ): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun probeIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org"))

    companion object {
        const val IN_APP_BROWSER_PACKAGE = "dev.libredirect.mobile.inapp"
        const val SYSTEM_DEFAULT_PACKAGE = "system"
        const val ACTION_OPEN_IN_APP_BROWSER = "dev.libredirect.mobile.ACTION_OPEN_IN_APP_BROWSER"
        private const val REDIRECT_ACTIVITY_CLASS_NAME = "dev.libredirect.mobile.redirect.RedirectActivity"
        private const val MAIN_ACTIVITY_CLASS_NAME = "dev.libredirect.mobile.MainActivity"
    }
}
