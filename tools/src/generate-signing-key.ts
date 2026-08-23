#!/usr/bin/env bun
/**
 * Generates a new Ed25519 manifest-signing keypair. Run manually — not part
 * of any CI workflow — because rotating this key means shipping a new
 * public key in an app release before CI can sign with the new private key.
 *
 * Usage: bun run tools/src/generate-signing-key.ts
 */
import { generateKeyPairSync } from "node:crypto";

const { publicKey, privateKey } = generateKeyPairSync("ed25519");

const spkiDer = publicKey.export({ type: "spki", format: "der" }) as Buffer;
// RFC 8410: SPKI DER for Ed25519 is a fixed 12-byte prefix + the 32-byte raw key.
const rawPublicKeyBase64 = spkiDer.subarray(spkiDer.length - 32).toString("base64");

const privateKeyPem = privateKey.export({ type: "pkcs8", format: "pem" }).toString();

console.log("Public key (base64) — paste into");
console.log(
  "android/app/src/main/kotlin/dev/libredirect/mobile/manifest/Ed25519ManifestVerifier.kt",
);
console.log("as PUBLIC_KEY_BASE64:\n");
console.log(rawPublicKeyBase64);

console.log("\nPrivate key (PKCS8 PEM) — store as the MANIFEST_SIGNING_KEY GitHub Actions secret.");
console.log("Do NOT commit this to the repository in any form:\n");
console.log(privateKeyPem);
