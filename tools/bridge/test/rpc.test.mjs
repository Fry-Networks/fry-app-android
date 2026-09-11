import { test, describe } from "node:test";
import assert from "node:assert/strict";
import {
  parseRequest,
  encodeReply,
  encodeErrorReply,
  encodeEvent,
  toBridgeError,
  BridgeError,
  BridgeErrorCode
} from "../src/rpc.mjs";

describe("rpc codecs", () => {
  test("parseRequest parses a well-formed request", () => {
    const req = parseRequest(JSON.stringify({ id: 7, method: "ping", params: { a: 1 } }));
    assert.deepEqual(req, { id: 7, method: "ping", params: { a: 1 } });
  });

  test("parseRequest defaults params to {}", () => {
    const req = parseRequest(JSON.stringify({ id: 1, method: "ping" }));
    assert.deepEqual(req.params, {});
  });

  test("parseRequest throws INVALID_ARGS on malformed JSON", () => {
    assert.throws(() => parseRequest("{not json"), (e) => {
      assert.ok(e instanceof BridgeError);
      assert.equal(e.code, BridgeErrorCode.INVALID_ARGS);
      return true;
    });
  });

  test("parseRequest throws INVALID_ARGS when method missing", () => {
    assert.throws(() => parseRequest(JSON.stringify({ id: 1 })), (e) => {
      assert.equal(e.code, BridgeErrorCode.INVALID_ARGS);
      return true;
    });
  });

  test("encodeReply produces {id, ok:true, result}", () => {
    const out = JSON.parse(encodeReply(3, { foo: "bar" }));
    assert.deepEqual(out, { id: 3, ok: true, result: { foo: "bar" } });
  });

  test("encodeErrorReply produces {id, ok:false, error}", () => {
    const be = new BridgeError(BridgeErrorCode.TIMEOUT, "took too long", "detail-here");
    const out = JSON.parse(encodeErrorReply(9, be));
    assert.deepEqual(out, {
      id: 9,
      ok: false,
      error: { code: "TIMEOUT", message: "took too long", detail: "detail-here" }
    });
  });

  test("encodeEvent passes the object through as JSON", () => {
    const out = JSON.parse(encodeEvent({ event: "ready", version: "1" }));
    assert.deepEqual(out, { event: "ready", version: "1" });
  });
});

describe("toBridgeError mapping table", () => {
  const cases = [
    {
      name: "Pera SESSION_CONNECT rejected",
      input: { message: "Session connect rejected by user", data: { type: "SESSION_CONNECT" } },
      code: BridgeErrorCode.USER_REJECTED
    },
    {
      name: "Pera SESSION_CONNECT cancel",
      input: { message: "User cancelled the request", data: { type: "SESSION_CONNECT" } },
      code: BridgeErrorCode.USER_REJECTED
    },
    {
      name: "Pera SIGN_TRANSACTIONS pending 4100",
      input: {
        message: "Confirmation Failed(4100): request pending on the wallet",
        data: { type: "SIGN_TRANSACTIONS" }
      },
      code: BridgeErrorCode.PENDING_REQUEST
    },
    {
      name: "Pera SIGN_TRANSACTIONS rejected 4100",
      input: {
        message: "Confirmation Failed(4100): rejected by the user",
        data: { type: "SIGN_TRANSACTIONS" }
      },
      code: BridgeErrorCode.USER_REJECTED
    },
    {
      name: "session disconnected",
      input: { message: "Session currently disconnected" },
      code: BridgeErrorCode.NOT_CONNECTED
    },
    {
      name: "not initialized",
      input: { message: "WalletConnect client not initialized" },
      code: BridgeErrorCode.NOT_CONNECTED
    },
    {
      name: "MESSAGE_NOT_RECEIVED",
      input: { message: "no response", data: { type: "MESSAGE_NOT_RECEIVED" } },
      code: BridgeErrorCode.USER_REJECTED
    },
    {
      name: "OPERATION_CANCELLED",
      input: { message: "cancelled", data: { type: "OPERATION_CANCELLED" } },
      code: BridgeErrorCode.USER_REJECTED
    },
    {
      name: "WalletConnect 4001",
      input: { message: "User rejected", code: 4001 },
      code: BridgeErrorCode.USER_REJECTED
    },
    {
      name: "timeout",
      input: { message: "Request timeout after 120s" },
      code: BridgeErrorCode.TIMEOUT
    },
    {
      name: "fetch failure",
      input: (() => {
        const e = new TypeError("Failed to fetch");
        return e;
      })(),
      code: BridgeErrorCode.NETWORK
    },
    {
      name: "NetworkError",
      input: { message: "NetworkError when attempting to fetch resource" },
      code: BridgeErrorCode.NETWORK
    },
    {
      name: "unknown",
      input: { message: "something totally unrecognized happened" },
      code: BridgeErrorCode.UNKNOWN
    }
  ];

  for (const c of cases) {
    test(c.name, () => {
      const be = toBridgeError(c.input);
      assert.equal(be.code, c.code);
    });
  }

  test("passes through an existing BridgeError unchanged", () => {
    const original = new BridgeError(BridgeErrorCode.WALLET_NOT_INSTALLED, "no wallet");
    assert.equal(toBridgeError(original), original);
  });

  test("UNKNOWN carries the original message as detail", () => {
    const be = toBridgeError({ message: "mystery failure" });
    assert.equal(be.detail, "mystery failure");
  });
});
