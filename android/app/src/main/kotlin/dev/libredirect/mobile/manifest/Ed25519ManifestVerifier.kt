package dev.libredirect.mobile.manifest

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64

/**
 * Raw Ed25519 (RFC 8032), not a Tink keyset: a 32-byte public key and a
 * 64-byte signature, both base64. This pairs directly with Node's built-in
 * `crypto.sign(null, message, privateKey)` on the signing side
 * (tools/src/sign-routes.ts) without needing a shared keyset format across
 * two different language ecosystems.
 *
 * [PUBLIC_KEY_BASE64] must be kept in sync with whatever private key CI
 * signs with — see SECURITY.md for the rotation procedure. Losing the
 * private key means generating a new pair and shipping the new public key
 * in an app update; it does not compromise anything already signed.
 */
object Ed25519ManifestVerifier : ManifestVerifier {
    private const val PUBLIC_KEY_BASE64 = "4nNJAh0u3fDzCHJxTXYi/OnrYgv+EGd7uEdTJ35tiNo="

    private val publicKey: Ed25519PublicKeyParameters by lazy {
        Ed25519PublicKeyParameters(Base64.getDecoder().decode(PUBLIC_KEY_BASE64), 0)
    }

    override fun verify(
        manifestBytes: ByteArray,
        signatureBytes: ByteArray,
    ): Boolean {
        val signature =
            try {
                // The fetched .sig file is base64 text and commonly ends with a
                // trailing newline; the strict Basic decoder rejects that as-is.
                Base64.getDecoder().decode(String(signatureBytes, Charsets.UTF_8).trim())
            } catch (_: IllegalArgumentException) {
                return false
            }
        if (signature.size != SIGNATURE_LENGTH_BYTES) return false

        val verifier = Ed25519Signer()
        verifier.init(false, publicKey)
        verifier.update(manifestBytes, 0, manifestBytes.size)
        return verifier.verifySignature(signature)
    }

    private const val SIGNATURE_LENGTH_BYTES = 64
}
