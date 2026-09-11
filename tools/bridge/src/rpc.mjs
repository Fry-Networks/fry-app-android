// Plain JSON-RPC-ish codecs for the Kotlin <-> WebView bridge.
//
// Request:  {id, method, params}
// Reply:    {id, ok:true, result} | {id, ok:false, error:{code, message, detail}}
// Event:    {event, ...}

export const BridgeErrorCode = Object.freeze({
  USER_REJECTED: "USER_REJECTED",
  PENDING_REQUEST: "PENDING_REQUEST",
  NOT_CONNECTED: "NOT_CONNECTED",
  SESSION_EXPIRED: "SESSION_EXPIRED",
  TIMEOUT: "TIMEOUT",
  WALLET_NOT_INSTALLED: "WALLET_NOT_INSTALLED",
  NETWORK: "NETWORK",
  BRIDGE_RESET: "BRIDGE_RESET",
  INVALID_ARGS: "INVALID_ARGS",
  UNKNOWN: "UNKNOWN"
});

/**
 * Parse an incoming request string into {id, method, params}.
 * Throws a BridgeError(INVALID_ARGS) on malformed input.
 */
export function parseRequest(json) {
  let req;
  try {
    req = JSON.parse(json);
  } catch (e) {
    throw new BridgeError(BridgeErrorCode.INVALID_ARGS, "malformed request JSON", String(e && e.message));
  }
  if (!req || typeof req !== "object" || typeof req.method !== "string") {
    throw new BridgeError(BridgeErrorCode.INVALID_ARGS, "request missing method");
  }
  return { id: req.id, method: req.method, params: req.params || {} };
}

export function encodeReply(id, result) {
  return JSON.stringify({ id, ok: true, result });
}

export function encodeErrorReply(id, error) {
  const be = error instanceof BridgeError ? error : toBridgeError(error);
  return JSON.stringify({
    id,
    ok: false,
    error: { code: be.code, message: be.message, detail: be.detail }
  });
}

export function encodeEvent(eventObj) {
  return JSON.stringify(eventObj);
}

export class BridgeError extends Error {
  constructor(code, message, detail) {
    super(message);
    this.name = "BridgeError";
    this.code = code;
    this.detail = detail;
  }
}

/**
 * Map an arbitrary thrown value (typically from the Pera/Defly SDKs, WalletConnect,
 * or a fetch/network failure) to a BridgeError with a stable code.
 */
export function toBridgeError(e) {
  if (e instanceof BridgeError) return e;

  const message = (e && e.message) || String(e);
  const dataType = e && e.data && e.data.type;

  // PeraWalletConnectError-shaped errors carry e.data.type.
  if (dataType === "SESSION_CONNECT" && /rejected|cancel/i.test(message)) {
    return new BridgeError(BridgeErrorCode.USER_REJECTED, message);
  }
  if (dataType === "SIGN_TRANSACTIONS") {
    if (/Confirmation Failed\(4100\)/i.test(message) && /pending/i.test(message)) {
      return new BridgeError(BridgeErrorCode.PENDING_REQUEST, message);
    }
    if (/Confirmation Failed\(4100\)/i.test(message) && /rejected/i.test(message)) {
      return new BridgeError(BridgeErrorCode.USER_REJECTED, message);
    }
  }
  if (/Session currently disconnected|not initialized/i.test(message)) {
    return new BridgeError(BridgeErrorCode.NOT_CONNECTED, message);
  }
  if (dataType === "MESSAGE_NOT_RECEIVED" || dataType === "OPERATION_CANCELLED") {
    return new BridgeError(BridgeErrorCode.USER_REJECTED, message);
  }
  // WalletConnect standard user-rejection code.
  if (e && (e.code === 4001 || e.code === "4001")) {
    return new BridgeError(BridgeErrorCode.USER_REJECTED, message);
  }
  if (/timeout/i.test(message)) {
    return new BridgeError(BridgeErrorCode.TIMEOUT, message);
  }
  if (
    (e && e.name === "TypeError" && /Failed to fetch/i.test(message)) ||
    /NetworkError/i.test(message)
  ) {
    return new BridgeError(BridgeErrorCode.NETWORK, message);
  }

  return new BridgeError(BridgeErrorCode.UNKNOWN, message, message);
}
