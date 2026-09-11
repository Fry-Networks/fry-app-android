// Test wallet used only when init was called with {stub:true}. Never touches the network:
// signs with an in-page ephemeral keypair and derives txIds locally so the bridge protocol
// can be exercised end-to-end (connect -> buildX -> signTxns -> submit) without Pera/Defly
// or a live algod.

import algosdk from "algosdk";
import { b64decode, b64encode } from "./txns.mjs";

let account = null;

function ensureAccount() {
  if (!account) account = algosdk.generateAccount();
  return account;
}

export function address() {
  return ensureAccount().addr.toString();
}

export async function connect() {
  return [address()];
}

export async function reconnect() {
  return account ? [address()] : [];
}

export async function disconnect() {
  account = null;
}

/**
 * @param {Array<Array<{txnB64: string, sign: boolean}>>} groups
 * @returns {Promise<Array<Array<string|null>>>}
 */
export async function signGroups(groups) {
  const acct = ensureAccount();
  return groups.map((group) =>
    group.map((entry) => {
      if (!entry.sign) return null;
      const txn = algosdk.decodeUnsignedTransaction(b64decode(entry.txnB64));
      const signed = txn.signTxn(acct.sk);
      return b64encode(signed);
    })
  );
}

/**
 * Fake "submit": no network call, just recovers the txId that would have been assigned
 * on-chain from the signed transaction bytes themselves.
 * @param {string[]} signedB64List
 * @returns {Promise<string[]>}
 */
export async function submit(signedB64List) {
  return signedB64List.map((b64) => {
    const decoded = algosdk.decodeSignedTransaction(b64decode(b64));
    return decoded.txn.txID();
  });
}
