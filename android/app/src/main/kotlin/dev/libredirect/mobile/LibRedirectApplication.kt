package dev.libredirect.mobile

import android.app.Application
import dev.libredirect.mobile.manifest.ManifestRepository

class LibRedirectApplication : Application() {
    val manifestRepository: ManifestRepository by lazy { ManifestRepository(this) }
}
