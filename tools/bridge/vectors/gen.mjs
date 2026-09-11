// Generates canonical request-signing fixture vectors consumed by the Android test suite
// (app/src/test/resources/fixtures/canonical/vectors.json) so Kotlin's signature/clientToken
// implementation can be checked against a known-good reference computed here in Node.

import { createHash, createHmac } from "node:crypto";
import { writeFileSync, mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(here, "..", "..", "..", "app", "src", "test", "resources", "fixtures", "canonical");
const outFile = join(outDir, "vectors.json");

const METHOD = "POST";
const PATH = "/api/rewards/claim";
const TIMESTAMP = 1700000000;
const USER_AGENT = "FryApp/0.3.0 (Android; wallet-bridge)";
const CLIENT_TOKEN_PREFIX = "fry-rewards-client-";
const SIGNATURE_KEY = "fry-rewards-signature-v1-";

const bodies = [
  { miner_key: "FEM-ABC" },
  { miner_key: "FEM-ABC", no: 3 },
  { miner_key: "FEM-ABC", preview: true },
  { miner_keys: ["A", "B"] },
  { groupId: "g1", signedUserLegB64: "AAAA" },
  { txId: "T", address: "ADDR", miner_key: "K", amount: 12, asset_id: "2681521901" },
  {},
  { a: { b: [1, 2, { c: null }] }, d: false },
  { list: [] }
];

function sha256hex(input) {
  return createHash("sha256").update(input).digest("hex");
}

function hmacSha256hex(key, msg) {
  return createHmac("sha256", key).update(msg).digest("hex");
}

const clientToken = sha256hex(CLIENT_TOKEN_PREFIX + USER_AGENT);

const vectors = bodies.map((body) => {
  const canonical = JSON.stringify(body);
  const signature = hmacSha256hex(
    SIGNATURE_KEY,
    `${METHOD}|${PATH}|${canonical}|${TIMESTAMP}`
  );
  return {
    body,
    canonical,
    method: METHOD,
    path: PATH,
    timestamp: TIMESTAMP,
    userAgent: USER_AGENT,
    clientToken,
    signature
  };
});

mkdirSync(outDir, { recursive: true });
writeFileSync(outFile, JSON.stringify(vectors, null, 2) + "\n");

console.log(`wrote ${vectors.length} vectors to ${outFile}`);
console.log(JSON.stringify(vectors[0], null, 2));
