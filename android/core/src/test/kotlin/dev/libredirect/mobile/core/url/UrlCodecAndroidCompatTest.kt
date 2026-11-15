package dev.libredirect.mobile.core.url

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #12 regression net. `URLDecoder.decode(String, Charset)` /
 * `URLEncoder.encode(String, Charset)` are Java 10 overloads that Android
 * ships only from API 33, while the app's minSdk is 26. Calling them threw
 * `NoSuchMethodError` at routing time on every API 26-32 device, and the
 * usual guards cannot catch a reintroduction here:
 *
 * - Android lint never scans this pure-JVM module;
 * - JVM unit tests run against a desktop JDK where the overloads exist, so
 *   they pass no matter which overload the code calls.
 *
 * This test therefore inspects the module's compiled bytecode directly and
 * fails when any of its own classes references the API 33+ descriptor.
 */
class UrlCodecAndroidCompatTest {
    @Test
    fun `module bytecode never references the API 33 URL codec overloads`() {
        val location = QueryString::class.java.protectionDomain.codeSource?.location
        assertNotNull("No code source location for the main classes", location)
        val mainClasses = File(location!!.toURI())
        assertTrue("Main classes directory not found at $mainClasses", mainClasses.isDirectory)

        val offenders =
            mainClasses
                .walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .filter { classFile ->
                    String(classFile.readBytes(), Charsets.ISO_8859_1).contains(FORBIDDEN_DESCRIPTOR)
                }.map { it.relativeTo(mainClasses).path }
                .sorted()
                .toList()

        assertTrue(
            "These classes call URLDecoder/URLEncoder with the Java 10 Charset overload " +
                "(Android API 33+ only — issue #12); use the String-name overload instead: $offenders",
            offenders.isEmpty(),
        )
    }

    private companion object {
        /** Shared descriptor of `decode(String, Charset)` and `encode(String, Charset)`. */
        const val FORBIDDEN_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;"
    }
}
