package dev.libredirect.mobile.browser

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Redirect-loop prevention (project plan §19) lives here: the launch intent
 * always either carries an explicit target package, or — when no preferred
 * or system-default browser is known — an app chooser that has this app's
 * own component excluded via [Intent.EXTRA_EXCLUDE_COMPONENTS], so this
 * app can never end up back in its own hands regardless of what the user
 * or system picks.
 */
class BrowserLauncher(private val packageManager: PackageManager, private val ownPackageName: String) {
    fun installedBrowsers(): List<BrowserInfo> =
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

    fun isInstalled(packageName: String): Boolean = installedBrowsers().any { it.packageName == packageName }

    /**
     * @param preferredPackage a user-chosen browser (Phase 6 settings). Falls back to the
     * system default browser, then to an exclusion-chooser, if unset or no longer installed.
     */
    fun launch(
        context: Context,
        url: String,
        preferredPackage: String?,
    ): Boolean {
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

        val target = preferredPackage?.takeIf { isInstalled(it) } ?: systemDefaultBrowserPackage()
        if (target != null) {
            viewIntent.setPackage(target)
            return tryStart(context, viewIntent)
        }

        val chooser =
            Intent.createChooser(viewIntent, null).apply {
                putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    arrayOf(ComponentName(context.packageName, REDIRECT_ACTIVITY_CLASS_NAME)),
                )
            }
        return tryStart(context, chooser)
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

    private companion object {
        const val REDIRECT_ACTIVITY_CLASS_NAME = "dev.libredirect.mobile.redirect.RedirectActivity"
    }
}
