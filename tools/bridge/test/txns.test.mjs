import { test, describe } from "node:test";
import assert from "node:assert/strict";
import algosdk from "algosdk";
import {
  buildPayment,
  buildAssetTransfer,
  buildOptIn,
  decodeTxnSummary,
  b64decode,
  b64encode
} from "../src/txns.mjs";

// Fixed suggested params, no network involved.
const GENESIS_HASH_B64 = "wGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit8=";
const suggestedParams = {
  fee: 1000,
  firstValid: 1000,
  lastValid: 2000,
  genesisHash: algosdk.base64ToBytes(GENESIS_HASH_B64),
  genesisID: "mainnet-v1.0",
  flatFee: true
};

const sender = algosdk.generateAccount().addr.toString();
const receiver = algosdk.generateAccount().addr.toString();
const ASSET_ID = 2681521901; // tFRY

describe("txns", () => {
  test("buildPayment round-trips sender/receiver/amount/note and has a stable txId", () => {
    const b64 = buildPayment({
      sender,
      receiver,
      amountMicro: 1234500,
      noteUtf8: "fry-reward",
      suggestedParams
    });
    const summary = decodeTxnSummary(b64);
    assert.equal(summary.type, "pay");
    assert.equal(summary.sender, sender);
    assert.equal(summary.receiver, receiver);
    assert.equal(summary.amount, 1234500);
    assert.equal(summary.note, "fry-reward");
    assert.equal(summary.assetId, undefined);
    assert.equal(typeof summary.txId, "string");
    assert.ok(summary.txId.length > 0);

    // Building the exact same transaction twice yields the same txId (deterministic).
    const b64Again = buildPayment({
      sender,
      receiver,
      amountMicro: 1234500,
      noteUtf8: "fry-reward",
      suggestedParams
    });
    assert.equal(decodeTxnSummary(b64Again).txId, summary.txId);
  });

  test("buildPayment omits note when noteUtf8 is not given", () => {
    const b64 = buildPayment({ sender, receiver, amountMicro: 1, suggestedParams });
    const summary = decodeTxnSummary(b64);
    assert.equal(summary.note, undefined);
  });

  test("buildAssetTransfer round-trips assetId/amount/note", () => {
    const b64 = buildAssetTransfer({
      sender,
      receiver,
      assetId: ASSET_ID,
      amountMicro: 42,
      noteUtf8: "asset-note",
      suggestedParams
    });
    const summary = decodeTxnSummary(b64);
    assert.equal(summary.type, "axfer");
    assert.equal(summary.sender, sender);
    assert.equal(summary.receiver, receiver);
    assert.equal(summary.amount, 42);
    assert.equal(summary.assetId, ASSET_ID);
    assert.equal(summary.note, "asset-note");
  });

  test("buildOptIn is a 0-amount self asset transfer", () => {
    const b64 = buildOptIn({ sender, assetId: ASSET_ID, suggestedParams });
    const summary = decodeTxnSummary(b64);
    assert.equal(summary.type, "axfer");
    assert.equal(summary.sender, sender);
    assert.equal(summary.receiver, sender);
    assert.equal(summary.amount, 0);
    assert.equal(summary.assetId, ASSET_ID);
  });

  test("note bytes round-trip through non-ASCII UTF-8", () => {
    const b64 = buildPayment({
      sender,
      receiver,
      amountMicro: 1,
      noteUtf8: "fry \u{1F35F} reward",
      suggestedParams
    });
    assert.equal(decodeTxnSummary(b64).note, "fry \u{1F35F} reward");
  });

  test("b64encode/b64decode round-trip arbitrary bytes", () => {
    const bytes = new Uint8Array([0, 1, 2, 254, 255, 16, 32]);
    const back = b64decode(b64encode(bytes));
    assert.deepEqual(Array.from(back), Array.from(bytes));
  });
});
