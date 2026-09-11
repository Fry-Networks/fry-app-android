// Thin adapter over the official Pera Connect SDK. We implement NO WalletConnect protocol
// ourselves; PeraWalletConnect (@perawallet/connect 1.6.1) does it. This module only:
//   1. shapes bridge RPC params into the SDK's SignerTransaction[][] input,
//   2. re-nests the SDK's flat signed-transaction output back into per-group base64|null,
//   3. surfaces the wc: connect URI and the perawallet-wc:// sign-redirect as bridge events
//      so native code can forward them to the Pera app via an Android intent.

import { PeraWalletConnect } from "@perawallet/connect";
import algosdk from "algosdk";
import { b64decode, b64encode } from "../txns.mjs";

const POLL_INTERVAL_MS = 50;
const POLL_MAX_MS = 5000;

let sdk = null;
let emit = () => {};

export function init(emitFn) {
  if (typeof emitFn === "function") emit = emitFn;
}

function ensureSdk() {
  if (!sdk) {
    sdk = new PeraWalletConnect({
      shouldShowSignTxnToast: false,
      compactMode: false,
      chainId: 416001
    });
  }
  return sdk;
}

async function pollForConnectUri(s) {
  const deadline = Date.now() + POLL_MAX_MS;
  while (Date.now() < deadline) {
    const uri = s.connector && s.connector.uri;
    if (uri) {
      emit({ event: "openUri", uri, wallet: "pera" });
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
  }
}

export async function connect() {
  const s = ensureSdk();
  // Fire-and-forget: must run concurrently with connect(), not after it resolves,
  // since the wc: URI only exists briefly before the wallet approves the session.
  pollForConnectUri(s).catch(() => {});
  return s.connect();
}

export async function reconnect() {
  const s = ensureSdk();
  try {
    return await s.reconnectSession();
  } catch {
    return [];
  }
}

export async function disconnect() {
  if (!sdk) return;
  await sdk.disconnect();
}

/**
 * @param {Array<Array<{txnB64: string, sign: boolean}>>} groups
 * @returns {Promise<Array<Array<string|null>>>}
 */
export async function signGroups(groups) {
  const s = ensureSdk();
  emit({ event: "openUri", uri: "perawallet-wc://", wallet: "pera" });

  const txGroups = groups.map((group) =>
    group.map((entry) => ({
      txn: algosdk.decodeUnsignedTransaction(b64decode(entry.txnB64)),
      signers: entry.sign ? undefined : []
    }))
  );

  const signed = await s.signTransaction(txGroups);

  let cursor = 0;
  return groups.map((group) =>
    group.map(() => {
      const item = signed[cursor++];
      return item && item.length ? b64encode(item) : null;
    })
  );
}
