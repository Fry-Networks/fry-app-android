# `:qa` - on-device QA suite

A self-instrumenting test APK (`com.frynetworks.fryqa`) that drives the installed Fry app
with UI Automator by resource-id and visible text only. Because it instruments itself, not
the app, it keeps running while the app replaces itself during a self-update hop, and it
works against the release package (`com.frynetworks.fryapp`, the default) or the debug one
(`-e targetPackage com.frynetworks.fryapp.debug`). It is never part of the app's own test
suites or CI.

## Build and install

```sh
./gradlew :qa:assembleDebug            # qa/build/outputs/apk/debug/qa-debug.apk
adb install -r qa/build/outputs/apk/debug/qa-debug.apk
```

## Inputs

Secrets never travel as instrumentation arguments (they would show in the process list).
Push a JSON file to the suite's external files directory instead:

```sh
adb shell mkdir -p /sdcard/Android/data/com.frynetworks.fryqa/files
adb push provision.json /sdcard/Android/data/com.frynetworks.fryqa/files/provision.json
```

`provision.json`: `{"ssid": "...", "pass": "...", "wallet": "...", "minerKey": "FEM-...", "setupCode": "..."}`
(missing fields are treated as absent).

Arguments (`-e name value`) carry only switches: `targetPackage`, `board` (substring of
the scan label), `iterations`, `keyMode` (`owner` | `none`), `ackActiveElsewhere`,
`expectChannel` (`stable` | `test`), `expectVersionCode`.

## Run

```sh
R=com.frynetworks.fryqa/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.frynetworks.fryqa.FreshInstallDiscoveryTest $R
adb shell am instrument -w -e class com.frynetworks.fryqa.BleProvisionLoopTest -e board FRY-ESP32 -e iterations 5 $R
adb shell am instrument -w -e class com.frynetworks.fryqa.DeviceScreenProofTest $R
adb shell am instrument -w -e class com.frynetworks.fryqa.SelfUpdateControlsTest -e expectChannel test $R
adb shell am instrument -w -e class com.frynetworks.fryqa.SelfUpdateHopTest -e expectVersionCode 7 $R
```

| Test | Proves |
|---|---|
| `FreshInstallDiscoveryTest` | The scan asks for permissions, ends within its window and shows boards, the empty-state help or preflight guidance. |
| `BleProvisionLoopTest` | N provisioning rounds over BLE with the owner's key; each outcome and duration is recorded. |
| `DeviceScreenProofTest` | The device screen shows the dashboard's state words and every action button is at least 48 dp tall (screenshot saved). |
| `SelfUpdateControlsTest` | Settings shows the selected update channel and a manual check reaches a terminal status. |
| `SelfUpdateHopTest` | One self-update hop: the installed versionCode rises after the app's own install prompt. |

## Results

Every test appends one JSON line per observation to the suite's private files directory
(keys masked to 6 characters, never a password):

```sh
adb shell run-as com.frynetworks.fryqa cat files/results.jsonl
```

Screenshots from `DeviceScreenProofTest` land next to it.
