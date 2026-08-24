package dev.libredirect.mobile.core.version

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun `higher patch version is newer`() {
        assertTrue(AppVersion.isNewer("0.1.1", "0.1.0"))
    }

    @Test
    fun `numeric comparison beats lexical comparison`() {
        assertTrue(AppVersion.isNewer("0.10.0", "0.9.0"))
    }

    @Test
    fun `leading v prefix is ignored`() {
        assertTrue(AppVersion.isNewer("v0.2.0", "0.1.0"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(AppVersion.isNewer("0.1.0", "0.1.0"))
    }

    @Test
    fun `older version is not newer`() {
        assertFalse(AppVersion.isNewer("0.1.0", "0.2.0"))
    }

    @Test
    fun `unparseable candidate is never newer`() {
        assertFalse(AppVersion.isNewer("not-a-version", "0.1.0"))
    }

    @Test
    fun `pre-release suffix is ignored for comparison`() {
        assertFalse(AppVersion.isNewer("0.2.0-beta.1", "0.2.0"))
    }
}
