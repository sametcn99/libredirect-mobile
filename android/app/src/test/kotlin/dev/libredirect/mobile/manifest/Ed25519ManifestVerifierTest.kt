package dev.libredirect.mobile.manifest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The signature below is a real, known-answer vector produced by
 * tools/src/sign-routes.ts's underlying node:crypto Ed25519 signing against
 * the same key embedded in [Ed25519ManifestVerifier] — not a fabricated
 * value. It cross-checks that the raw-key/raw-signature format this class
 * expects actually interoperates with the Node.js side that produces it.
 */
class Ed25519ManifestVerifierTest {
    private val message = "hello libredirect".toByteArray(Charsets.UTF_8)
    private val validSignatureBase64 =
        "2Ee3VWmXzSHQk4vATj0/r7zoy2wk7L5vtt4pfUtVCgJf/jNLa5ZDTHAxsMm6hN0qIT2LXVXRcY5sOiQekDchDA=="

    @Test
    fun `accepts a genuine signature produced by the Node signing tool`() {
        val signatureBytes = validSignatureBase64.toByteArray(Charsets.UTF_8)
        assertTrue(Ed25519ManifestVerifier.verify(message, signatureBytes))
    }

    @Test
    fun `accepts the same signature with a trailing newline, as fetched from a text file`() {
        val signatureBytes = "$validSignatureBase64\n".toByteArray(Charsets.UTF_8)
        assertTrue(Ed25519ManifestVerifier.verify(message, signatureBytes))
    }

    @Test
    fun `rejects a tampered message`() {
        val tampered = "hello libredirecs".toByteArray(Charsets.UTF_8)
        val signatureBytes = validSignatureBase64.toByteArray(Charsets.UTF_8)
        assertFalse(Ed25519ManifestVerifier.verify(tampered, signatureBytes))
    }

    @Test
    fun `rejects a tampered signature`() {
        val raw = Base64.getDecoder().decode(validSignatureBase64)
        raw[0] = (raw[0].toInt() xor 0xFF).toByte()
        val tamperedBase64 = Base64.getEncoder().encodeToString(raw)
        assertFalse(Ed25519ManifestVerifier.verify(message, tamperedBase64.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `rejects malformed base64`() {
        assertFalse(Ed25519ManifestVerifier.verify(message, "not valid base64!!".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `rejects a signature of the wrong length`() {
        val shortSignature = Base64.getEncoder().encodeToString(ByteArray(10))
        assertFalse(Ed25519ManifestVerifier.verify(message, shortSignature.toByteArray(Charsets.UTF_8)))
    }
}
