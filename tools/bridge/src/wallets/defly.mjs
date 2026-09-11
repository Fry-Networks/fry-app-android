// Thin adapter over the official Defly Connect SDK (@blockshake/defly-connect 1.2.1).
// Mirrors wallets/pera.mjs: no WalletConnect protocol logic lives here, only param
// shaping, output re-nesting, and connect/sign deep-link event forwarding.

import { DeflyWalletConnect } from "@blockshake/defly-connect";
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
    sdk = new DeflyWalletConnect({
      shouldShowSignTxnToast: false,
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
      emit({ event: "openUri", uri, wallet: "defly" });
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
  }
}

export async function connect() {
  const s = ensureSdk();
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
  emit({ event: "openUri", uri: "defly-wc://", wallet: "defly" });

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
