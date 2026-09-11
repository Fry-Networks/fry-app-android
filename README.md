# fry-app-android

Android companion app for Fry Networks — provisions ESP32 / ESP32-S3 / ESP32-C3 boards over
BLE, and ESP8266 boards over their SoftAP, as Fry dVPN miners.

See [PROTOCOL.md](PROTOCOL.md) for the shared provisioning contract with `fry-firmware`. That
file is byte-identical in both repositories; neither side changes it unilaterally.

## Requirements

- JDK 21
- Android SDK (`compileSdk` 35, `minSdk` 26, `targetSdk` 35)
- The Gradle wrapper is checked in — no local Gradle install needed

## Build

```
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

`local.properties` (git-ignored) must set `sdk.dir` to your Android SDK location. An optional
`HARDWAREAPI_TOKEN` key in the same file sets a per-developer hardwareapi bearer token; it is
empty by default, and the app never embeds a fleet-wide credential — device status always
comes from the device itself over BLE or the SoftAP HTTP endpoint, never from the server.

## Structure

- `app/src/main/java/com/frynetworks/fryapp/ble/` — BLE GATT contract, scanner, provisioner
- `app/src/main/java/com/frynetworks/fryapp/wifi/` — ESP8266 SoftAP provisioner
- `app/src/main/java/com/frynetworks/fryapp/provisioning/` — pure protocol reducer (status
  decoding, write-order planning)
- `app/src/main/java/com/frynetworks/fryapp/util/` — Algorand address validation
- `app/src/main/java/com/frynetworks/fryapp/data/` — Room entity/DAO and repositories
- `app/src/main/java/com/frynetworks/fryapp/api/` — GET-only hardwareapi client and the
  public OTA manifest client
- `app/src/main/java/com/frynetworks/fryapp/di/` — Hilt modules
- `app/src/main/java/com/frynetworks/fryapp/ui/` — screens, navigation, theme

## Testing

Unit tests cover the protocol reducer and Algorand address validation and are written before
their implementation:

```
./gradlew testDebugUnitTest
```

## Miners (dashboard)

The Miners tab manages every miner the signed-in wallet owns on
[dashboard.frynetworks.com](https://dashboard.frynetworks.com) — not only the boards this app
provisions. It is native Compose on top of the dashboard's existing JSON API:

- Sign in with Pera or Defly. The app never shows the web dashboard; the wallet handshake and
  every signature go through an invisible WebView that runs the official `@perawallet/connect`
  and `@blockshake/defly-connect` SDKs (`app/src/main/assets/bridge/`, built reproducibly by
  `tools/bridge/build.mjs` and checked by `.github/workflows/bridge-verify.yml`). The NextAuth
  session cookie lives in an encrypted OkHttp cookie jar, never in the WebView.
- Miners list (totals, fleet online count, search, filters, sort), miner detail (identity,
  rewards, stake tiers with lock countdowns, hardware status), rewards history, miner keys with
  masked IoT credentials, and the activity feed.
- Claim and stake are native sheets that mirror the dashboard's flows (fee payment, group
  signature, ASA transfer to the stake wallet, precheck / verify-txn / record) and open the wallet
  app only to sign.

Test tags for every screen are listed in `docs/TEST_TAGS.md`.

## CI

`.github/workflows/build.yml` runs the unit tests and assembles the debug APK on every push
and pull request, and attaches the APK to a GitHub release on any `v*` tag push.
