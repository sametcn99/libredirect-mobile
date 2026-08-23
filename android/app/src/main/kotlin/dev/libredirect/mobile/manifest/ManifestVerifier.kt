package dev.libredirect.mobile.manifest

/**
 * Verifies a manifest's detached signature against the app's embedded
 * public key (project plan §13). [ManifestRepository] refuses to activate
 * any remote manifest that fails this check — see [Ed25519ManifestVerifier]
 * for the concrete implementation.
 */
fun interface ManifestVerifier {
    fun verify(
        manifestBytes: ByteArray,
        signatureBytes: ByteArray,
    ): Boolean
}
