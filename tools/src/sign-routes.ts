#!/usr/bin/env bun
/**
 * Signs a routes.json manifest with the Ed25519 private key in the
 * MANIFEST_SIGNING_KEY environment variable (PKCS8 PEM — see
 * generate-signing-key.ts). Writes a base64-encoded raw 64-byte signature
 * next to the input file as <input>.sig, and immediately re-verifies it
 * with the corresponding public key before exiting successfully, so a
 * signing bug fails loudly here rather than shipping an unverifiable
 * manifest.
 *
 * Usage: bun run tools/src/sign-routes.ts <path-to-routes.json>
 */
import { createPrivateKey, createPublicKey, sign, verify } from "node:crypto";

const inputPath = process.argv[2];
if (!inputPath) {
  console.error("Usage: sign-routes.ts <path-to-routes.json>");
  process.exit(1);
}

const privateKeyPem = process.env.MANIFEST_SIGNING_KEY;
if (!privateKeyPem) {
  console.error("MANIFEST_SIGNING_KEY environment variable is not set.");
  process.exit(1);
}

const manifestBytes = await Bun.file(inputPath).bytes();
const privateKey = createPrivateKey(privateKeyPem);
const publicKey = createPublicKey(privateKey);

const signature = sign(null, manifestBytes, privateKey);

const selfCheck = verify(null, manifestBytes, publicKey, signature);
if (!selfCheck) {
  console.error(
    "Self-verification of the freshly produced signature failed. Refusing to write routes.sig.",
  );
  process.exit(1);
}

const signaturePath = `${inputPath}.sig`.replace(/\.json\.sig$/, ".sig");
await Bun.write(signaturePath, signature.toString("base64"));

console.log(
  `Signed ${inputPath} -> ${signaturePath} (${signature.length}-byte Ed25519 signature, self-verified).`,
);
