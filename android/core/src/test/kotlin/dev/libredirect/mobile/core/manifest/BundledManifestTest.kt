package dev.libredirect.mobile.core.manifest

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledManifestTest {
    @Test
    fun `generated bundled manifest decodes and passes semantic validation`() {
        val candidates =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .map { File(it, "app/src/main/assets/routes.json") }
                .plus(File("android/app/src/main/assets/routes.json"))
        val file = candidates.firstOrNull(File::exists)
        requireNotNull(file) { "Could not locate bundled routes.json from ${System.getProperty("user.dir")}" }

        val manifest = ManifestJson.decode(file.readText())
        assertTrue(ManifestValidator.validate(manifest).toString(), ManifestValidator.validate(manifest).isEmpty())
        assertTrue(manifest.routes.size >= 40)
    }
}
