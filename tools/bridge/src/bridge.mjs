// Page entry point loaded by the invisible Android WebView from
// https://appassets.androidplatform.net/bridge/bridge.html
//
// Protocol: Kotlin calls window.__fryBridge.dispatch('<json string>'); this module answers
// through FryNative.onMessage('<json string>') with either a reply {id, ok, result|error} or
// an out-of-band event {event, ...}. We implement NO WalletConnect protocol ourselves -- the
// official Pera/Defly SDKs do it; this file only wires the JSON-RPC surface to them.

// MUST be first: patches window.open / clipboard / console before the wallet SDKs load.
import "./patches.mjs";

import algosdk from "algosdk";
import {
  parseRequest,
  encodeReply,
  encodeErrorReply,
  encodeEvent,
  BridgeError,
  BridgeErrorCode,
  toBridgeError
} from "./rpc.mjs";
import {
  buildPayment as buildPaymentTxn,
  buildAssetTransfer as buildAssetTransferTxn,
  buildOptIn as buildOptInTxn,
  decodeTxnSummary,
  fetchSuggestedParams,
  b64decode
} from "./txns.mjs";
import * as pera from "./wallets/pera.mjs";
import * as defly from "./wallets/defly.mjs";
import * as stub from "./stub.mjs";

const CONNECT_TIMEOUT_MS = 120000;
const SIGN_TIMEOUT_MS = 180000;

const SDK_VERSIONS = { pera: "1.6.1", defly: "1.2.1", algosdk: "3.7.0" };

let algodServer = null;
let useStub = false;
let activeWalletName = null; // "pera" | "defly" | "stub" | null
let activeAdapter = null;

const pending = new Map(); // requestId -> reject(err)

function sendToNative(str) {
  if (window.FryNative && window.FryNative.onMessage) {
    window.FryNative.onMessage(str);
  }
}

function emitEvent(eventObj) {
  sendToNative(encodeEvent(eventObj));
}

pera.init(emitEvent);
defly.init(emitEvent);

function getAdapterFor(walletName) {
  if (useStub) return stub;
  if (walletName === "pera") return pera;
  if (walletName === "defly") return defly;
  throw new BridgeError(BridgeErrorCode.INVALID_ARGS, "wallet must be 'pera' or 'defly'");
}

/**
 * Race `workPromise` against a timeout and an external cancel() rejection registered
 * under `id`. On a TIMEOUT settlement (whether from the timer or from cancel()),
 * runs `onTimeoutCleanup` before rethrowing.
 */
async function runWithTimeout(id, workPromise, timeoutMs, onTimeoutCleanup) {
  // Avoid an unhandled-rejection warning if workPromise settles after we've already
  // resolved the race via the timeout/cancel path.
  Promise.resolve(workPromise).catch(() => {});

  let timer;
  let externalReject;
  const cancelPromise = new Promise((_resolve, reject) => {
    externalReject = reject;
  });
  if (id !== undefined && id !== null) pending.set(id, externalReject);

  const timeoutPromise = new Promise((_resolve, reject) => {
    timer = setTimeout(() => {
      reject(new BridgeError(BridgeErrorCode.TIMEOUT, "request timed out"));
    }, timeoutMs);
  });

  try {
    return await Promise.race([workPromise, timeoutPromise, cancelPromise]);
  } catch (e) {
    const be = toBridgeError(e);
    if (be.code === BridgeErrorCode.TIMEOUT && onTimeoutCleanup) {
      try {
        await onTimeoutCleanup();
      } catch {
        // cleanup is best-effort
      }
    }
    throw be;
  } finally {
    clearTimeout(timer);
    if (id !== undefined && id !== null) pending.delete(id);
  }
}

function normalizeSuggestedParams(sp) {
  if (sp && typeof sp.genesisHash === "string") {
    return { ...sp, genesisHash: algosdk.base64ToBytes(sp.genesisHash) };
  }
  return sp;
}

async function resolveSuggestedParams(params) {
  if (params.suggestedParams) return normalizeSuggestedParams(params.suggestedParams);
  if (!algodServer) {
    throw new BridgeError(
      BridgeErrorCode.INVALID_ARGS,
      "no algodServer configured; call init with algodServer or pass suggestedParams"
    );
  }
  return fetchSuggestedParams(algodServer);
}

async function handleInit(params) {
  algodServer = params.algodServer || null;
  useStub = Boolean(params.stub);
  return { version: "1", sdk: SDK_VERSIONS, stub: useStub };
}

async function handleConnect(params, id) {
  const walletName = params.wallet;
  const adapter = getAdapterFor(walletName);
  const accounts = await runWithTimeout(id, adapter.connect(), CONNECT_TIMEOUT_MS, () =>
    adapter.disconnect()
  );
  activeWalletName = useStub ? "stub" : walletName;
  activeAdapter = adapter;
  return { address: (accounts && accounts[0]) || null, accounts: accounts || [] };
}

async function handleReconnect(params) {
  const walletName = params.wallet;
  const adapter = getAdapterFor(walletName);
  const accounts = await adapter.reconnect();
  if (accounts && accounts.length) {
    activeWalletName = useStub ? "stub" : walletName;
    activeAdapter = adapter;
    return { address: accounts[0] };
  }
  return { address: null };
}

async function handleDisconnect() {
  if (activeAdapter) {
    await activeAdapter.disconnect();
  }
  activeAdapter = null;
  activeWalletName = null;
  return {};
}

async function handleBuildPayment(params) {
  const suggestedParams = await resolveSuggestedParams(params);
  const txnB64 = buildPaymentTxn({
    sender: params.sender,
    receiver: params.receiver,
    amountMicro: params.amountMicro,
    noteUtf8: params.noteUtf8,
    suggestedParams
  });
  return { txnB64 };
}

async function handleBuildAssetTransfer(params) {
  const suggestedParams = await resolveSuggestedParams(params);
  const txnB64 = buildAssetTransferTxn({
    sender: params.sender,
    receiver: params.receiver,
    assetId: params.assetId,
    amountMicro: params.amountMicro,
    noteUtf8: params.noteUtf8,
    suggestedParams
  });
  return { txnB64 };
}

async function handleBuildOptIn(params) {
  const suggestedParams = await resolveSuggestedParams(params);
  const txnB64 = buildOptInTxn({
    sender: params.sender,
    assetId: params.assetId,
    suggestedParams
  });
  return { txnB64 };
}

async function handleSignTxns(params, id) {
  if (!activeAdapter) {
    throw new BridgeError(BridgeErrorCode.NOT_CONNECTED, "no wallet connected");
  }
  const groups = params.groups;
  if (!Array.isArray(groups)) {
    throw new BridgeError(BridgeErrorCode.INVALID_ARGS, "groups must be an array");
  }
  const signedB64 = await runWithTimeout(id, activeAdapter.signGroups(groups), SIGN_TIMEOUT_MS, null);
  return { signedB64 };
}

async function handleSubmit(params) {
  const signedB64List = params.signedB64;
  if (!Array.isArray(signedB64List)) {
    throw new BridgeError(BridgeErrorCode.INVALID_ARGS, "signedB64 must be an array");
  }

  if (useStub) {
    const txIds = await stub.submit(signedB64List);
    return { txIds };
  }

  if (!algodServer) {
    throw new BridgeError(BridgeErrorCode.INVALID_ARGS, "no algodServer configured");
  }
  const algod = new algosdk.Algodv2("", algodServer, "");
  const bytesArray = signedB64List.map((b64) => b64decode(b64));
  const { txid } = await algod.sendRawTransaction(bytesArray).do();
  const waitRounds = params.waitRounds || 4;
  await algosdk.waitForConfirmation(algod, txid, waitRounds);
  const txIds = bytesArray.map((bytes) => algosdk.decodeSignedTransaction(bytes).txn.txID());
  return { txIds };
}

async function handleDecodeTxn(params) {
  return decodeTxnSummary(params.txnB64);
}

function handleCancel(params) {
  const targetId = params.id;
  const rejectFn = pending.get(targetId);
  if (rejectFn) {
    rejectFn(new BridgeError(BridgeErrorCode.TIMEOUT, "cancelled by cancel()"));
  }
  return {};
}

async function routeMethod(method, params, id) {
  switch (method) {
    case "init":
      return handleInit(params);
    case "connect":
      return handleConnect(params, id);
    case "reconnect":
      return handleReconnect(params);
    case "disconnect":
      return handleDisconnect();
    case "buildPayment":
      return handleBuildPayment(params);
    case "buildAssetTransfer":
      return handleBuildAssetTransfer(params);
    case "buildOptIn":
      return handleBuildOptIn(params);
    case "signTxns":
      return handleSignTxns(params, id);
    case "submit":
      return handleSubmit(params);
    case "decodeTxn":
      return handleDecodeTxn(params);
    case "ping":
      return { ok: true };
    case "cancel":
      return handleCancel(params);
    default:
      throw new BridgeError(BridgeErrorCode.INVALID_ARGS, `unknown method: ${method}`);
  }
}

async function handleDispatch(json) {
  let id;
  try {
    const req = parseRequest(json);
    id = req.id;
    const result = await routeMethod(req.method, req.params, req.id);
    sendToNative(encodeReply(id, result));
  } catch (e) {
    sendToNative(encodeErrorReply(id, e));
  }
}

window.__fryBridge = {
  dispatch(json) {
    // Fire-and-forget: the reply/error arrives asynchronously via FryNative.onMessage.
    handleDispatch(json);
  }
};

emitEvent({ event: "ready", version: "1" });
