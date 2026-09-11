import { test, describe } from "node:test";
import assert from "node:assert/strict";
import algosdk from "algosdk";
import * as stub from "../src/stub.mjs";
import { buildPayment } from "../src/txns.mjs";

const GENESIS_HASH_B64 = "wGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit8=";
const suggestedParams = {
  fee: 1000,
  firstValid: 1000,
  lastValid: 2000,
  genesisHash: algosdk.base64ToBytes(GENESIS_HASH_B64),
  genesisID: "mainnet-v1.0",
  flatFee: true
};

describe("stub wallet", () => {
  test("connect returns a single valid Algorand address, stable across calls", async () => {
    const accounts1 = await stub.connect();
    assert.equal(accounts1.length, 1);
    assert.ok(algosdk.isValidAddress(accounts1[0]));

    const accounts2 = await stub.connect();
    assert.equal(accounts2[0], accounts1[0], "same page-session account on repeated connect()");
  });

  test("reconnect returns the same account after connect", async () => {
    const [addr] = await stub.connect();
    const reconnected = await stub.reconnect();
    assert.deepEqual(reconnected, [addr]);
  });

  test("signGroups signs sign:true entries and nulls out sign:false entries", async () => {
    const [addr] = await stub.connect();
    const txnB64 = buildPayment({
      sender: addr,
      receiver: addr,
      amountMicro: 1000,
      suggestedParams
    });

    const [[signed, skipped]] = await stub.signGroups([
      [
        { txnB64, sign: true },
        { txnB64, sign: false }
      ]
    ]);

    assert.equal(skipped, null);
    assert.equal(typeof signed, "string");

    const decoded = algosdk.decodeSignedTransaction(algosdk.base64ToBytes(signed));
    assert.equal(decoded.txn.sender.toString(), addr);
    assert.ok(decoded.sig instanceof Uint8Array);
  });

  test("submit derives txIds from the signed bytes without any network call", async () => {
    const [addr] = await stub.connect();
    const txnB64 = buildPayment({
      sender: addr,
      receiver: addr,
      amountMicro: 5,
      suggestedParams
    });
    const [[signedB64]] = await stub.signGroups([[{ txnB64, sign: true }]]);

    const [txId] = await stub.submit([signedB64]);
    const expected = algosdk
      .decodeSignedTransaction(algosdk.base64ToBytes(signedB64))
      .txn.txID();
    assert.equal(txId, expected);
  });

  test("disconnect clears the session; reconnect then returns empty", async () => {
    await stub.connect();
    await stub.disconnect();
    const reconnected = await stub.reconnect();
    assert.deepEqual(reconnected, []);
  });
});
