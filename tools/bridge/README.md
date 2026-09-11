# Fry wallet bridge

An invisible-WebView JSON-RPC bridge that lets the Fry Android app sign and submit Algorand
transactions with Pera Wallet or Defly Wallet, without the app implementing any WalletConnect
protocol logic itself.

## How it fits together

1. Kotlin loads `https://appassets.androidplatform.net/bridge/bridge.html` in a hidden
   `WebView` via `WebViewAssetLoader` (an `https` origin so WebCrypto/WSS work).
2. `bridge.html` loads `bridge.js` — the bundle built from this directory, containing the
   official `@perawallet/connect` and `@blockshake/defly-connect` SDKs plus a small RPC shim.
3. Kotlin calls `window.__fryBridge.dispatch('<json>')`. The page replies through a
   `@JavascriptInterface` named `FryNative`: `FryNative.onMessage('<json>')` for replies and
   events, `FryNative.openUri(url)` for wallet deep links, `FryNative.log(level, msg)` for
   console mirroring.
4. The only thing the user ever sees is the Pera/Defly app's own approval sheet, opened via
   an Android intent on the `perawallet-wc://` / `defly-wc://` (sign) or raw `wc:` (connect)
   URI the bridge emits as an `openUri` event.

## Why the bundle is committed

`app/src/main/assets/bridge/bridge.js` ships inside the APK, so it has to exist as a checked-in
build artifact, not something generated at Android build time. CI (`bridge-verify.yml`) rebuilds
it on every change and fails if the committed file doesn't match, so it can never silently drift
from `src/`.

## Building

```sh
npm ci
npm run build     # -> ../../app/src/main/assets/bridge/bridge.js + .sha256
npm test           # node --test over test/
npm run vectors     # regenerates app/src/test/resources/fixtures/canonical/vectors.json
```

The build is deterministic: `npm ci && npm run build` run twice back to back produces an
identical `bridge.js.sha256`.

## Protocol summary

### Requests (`dispatch`)

`{"id": <any>, "method": "<name>", "params": {...}}`

| method | params | result |
|---|---|---|
| `init` | `{algodServer, stub}` | `{version, sdk:{pera,defly,algosdk}, stub}` |
| `connect` | `{wallet: "pera"\|"defly"}` | `{address, accounts}` (120s timeout) |
| `reconnect` | `{wallet}` | `{address\|null}` |
| `disconnect` | `{}` | `{}` |
| `buildPayment` | `{sender, receiver, amountMicro, noteUtf8?, suggestedParams?}` | `{txnB64}` |
| `buildAssetTransfer` | `{sender, receiver, assetId, amountMicro, noteUtf8?, suggestedParams?}` | `{txnB64}` |
| `buildOptIn` | `{sender, assetId, suggestedParams?}` | `{txnB64}` |
| `signTxns` | `{groups: [[{txnB64, sign}]]}` | `{signedB64: [[b64\|null]]}` (180s timeout) |
| `submit` | `{signedB64: [b64], waitRounds?}` | `{txIds}` |
| `decodeTxn` | `{txnB64}` | `{type, sender, receiver, amount, assetId, note, txId}` |
| `ping` | `{}` | `{ok:true}` |
| `cancel` | `{id}` | `{}` (and force-rejects the pending request `id` with `TIMEOUT`) |

If `suggestedParams` is omitted from a `buildX` call, the bridge fetches it live from
`algodServer` (set via `init`). Passing `{stub:true}` to `init` routes all wallet operations to
an in-page ephemeral-keypair signer (`src/stub.mjs`) that never touches the network — useful for
exercising the RPC surface end to end without a real wallet or algod.

### Replies

`{"id": <same id>, "ok": true, "result": {...}}` or
`{"id": <same id>, "ok": false, "error": {"code", "message", "detail"}}`

### Events (unsolicited, no `id`)

- `{"event": "ready", "version": "1"}` — once, on page load.
- `{"event": "openUri", "uri": "<wc: or perawallet-wc:// or defly-wc://>", "wallet": "pera"|"defly"}`
  — emitted during `connect` (the raw `wc:` session URI, polled off `sdk.connector?.uri` at
  50ms/5s) and again right before a `signTxns` call is dispatched to the wallet (the app-scheme
  redirect). Kotlin should forward each to an Android intent.

### Error codes

`USER_REJECTED`, `PENDING_REQUEST`, `NOT_CONNECTED`, `SESSION_EXPIRED`, `TIMEOUT`,
`WALLET_NOT_INSTALLED`, `NETWORK`, `BRIDGE_RESET`, `INVALID_ARGS`, `UNKNOWN` — see
`src/rpc.mjs` for the full mapping from Pera/Defly/WalletConnect error shapes.
