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

## CI

`.github/workflows/build.yml` runs the unit tests and assembles the debug APK on every push
and pull request, and attaches the APK to a GitHub release on any `v*` tag push.
