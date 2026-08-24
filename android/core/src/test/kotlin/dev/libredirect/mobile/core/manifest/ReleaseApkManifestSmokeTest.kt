package dev.libredirect.mobile.core.manifest

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class ReleaseApkManifestSmokeTest {
    @Test
    fun `release apk contains a decodable valid bundled manifest`() {
        val apkPath = System.getProperty(RELEASE_APK_PROPERTY) ?: return
        val apk = File(apkPath)
        require(apk.isFile) { "Release APK does not exist: $apkPath" }

        ZipFile(apk).use { archive ->
            val entry =
                requireNotNull(archive.getEntry(BUNDLED_MANIFEST_PATH)) {
                    "Release APK does not contain $BUNDLED_MANIFEST_PATH"
                }
            val raw = archive.getInputStream(entry).bufferedReader().use { it.readText() }
            val manifest = ManifestJson.decode(raw)

            assertTrue(ManifestValidator.validate(manifest).toString(), ManifestValidator.validate(manifest).isEmpty())
            assertTrue("Bundled manifest is unexpectedly small", manifest.routes.size >= MIN_ROUTE_COUNT)
        }
    }

    private companion object {
        const val RELEASE_APK_PROPERTY = "releaseApk"
        const val BUNDLED_MANIFEST_PATH = "assets/routes.json"
        const val MIN_ROUTE_COUNT = 40
    }
}
