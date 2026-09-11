// Transaction construction/decoding helpers, mirroring the dashboard's algosdk usage.
//
// algosdk 3.7.0 note: the classic makeXTxnWithSuggestedParamsFromObject() factories still
// exist and accept plain string addresses, but the resulting Transaction exposes fields
// nested per-type (txn.payment.*, txn.assetTransfer.*) with Address objects and BigInt
// amounts/asset indexes, unlike algosdk 2.x's flat txn.to/txn.amount/txn.assetIndex shape.

import algosdk from "algosdk";

const utf8Encode = (s) => new TextEncoder().encode(s);
const utf8Decode = (bytes) => new TextDecoder().decode(bytes);

export function b64encode(bytes) {
  return algosdk.bytesToBase64(bytes);
}

export function b64decode(str) {
  return algosdk.base64ToBytes(str);
}

/**
 * Fetch algod suggested params from `${algodServer}` via a bare Algodv2 client (no token,
 * matching public/no-auth algod endpoints; callers proxying through an authenticated algod
 * should pass a pre-fetched suggestedParams object to the build* functions instead).
 */
export async function fetchSuggestedParams(algodServer) {
  const algod = new algosdk.Algodv2("", algodServer, "");
  return algod.getTransactionParams().do();
}

export function buildPayment({ sender, receiver, amountMicro, noteUtf8, suggestedParams }) {
  const txn = algosdk.makePaymentTxnWithSuggestedParamsFromObject({
    sender,
    receiver,
    amount: amountMicro,
    note: noteUtf8 ? utf8Encode(noteUtf8) : undefined,
    suggestedParams
  });
  return b64encode(algosdk.encodeUnsignedTransaction(txn));
}

export function buildAssetTransfer({ sender, receiver, assetId, amountMicro, noteUtf8, suggestedParams }) {
  const txn = algosdk.makeAssetTransferTxnWithSuggestedParamsFromObject({
    sender,
    receiver,
    assetIndex: assetId,
    amount: amountMicro,
    note: noteUtf8 ? utf8Encode(noteUtf8) : undefined,
    suggestedParams
  });
  return b64encode(algosdk.encodeUnsignedTransaction(txn));
}

/** Opt-in = a 0-amount asset transfer from the account to itself. */
export function buildOptIn({ sender, assetId, suggestedParams }) {
  return buildAssetTransfer({
    sender,
    receiver: sender,
    assetId,
    amountMicro: 0,
    suggestedParams
  });
}

export function decodeTxnSummary(txnB64) {
  const txn = algosdk.decodeUnsignedTransaction(b64decode(txnB64));
  const summary = {
    type: txn.type,
    sender: txn.sender.toString(),
    receiver: undefined,
    amount: undefined,
    assetId: undefined,
    note: txn.note && txn.note.length ? utf8Decode(txn.note) : undefined,
    txId: txn.txID()
  };
  if (txn.type === "pay" && txn.payment) {
    summary.receiver = txn.payment.receiver.toString();
    summary.amount = Number(txn.payment.amount);
  } else if (txn.type === "axfer" && txn.assetTransfer) {
    summary.receiver = txn.assetTransfer.receiver.toString();
    summary.amount = Number(txn.assetTransfer.amount);
    summary.assetId = Number(txn.assetTransfer.assetIndex);
  }
  return summary;
}
