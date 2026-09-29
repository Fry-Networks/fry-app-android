#!/usr/bin/env bash
# test_verify_apk.sh <unsigned-release-apk> <debug-apk>
#
# Exercises tools/release/verify_apk.sh with throwaway keys made here (keytool): a copy of the
# unsigned release APK signed v2 + v3 by a fresh key, pinned through FRY_RELEASE_CERT_PIN_FILE,
# must pass; then every refusal must fire with its own reason: the real pin against a foreign
# key, a CN=Android Debug certificate, a debuggable APK, wrong package, wrong versionCode, v1
# signing on, v3 signing off, a malformed pin file and a missing APK. The release key and
# signing/release-cert.sha256 are only ever read. Needs build-tools (apksigner, aapt2) and the
# JDK's keytool.
#   ./gradlew :app:assembleRelease :app:assembleDebug   # (release stays unsigned)
#   tools/release/test_verify_apk.sh app/build/outputs/apk/release/app-release-unsigned.apk \
#                                    app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail

UNSIGNED=${1:?usage: $0 <unsigned-release-apk> <debug-apk>}
DEBUG=${2:?usage: $0 <unsigned-release-apk> <debug-apk>}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
V=$ROOT/tools/release/verify_apk.sh
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
BT=${ANDROID_BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)}
KEYTOOL=${KEYTOOL:-${JAVA_HOME:+$JAVA_HOME/bin/}keytool}
[ -x "$BT/apksigner" ] && [ -x "$BT/aapt2" ] || { echo "build-tools not found (set ANDROID_HOME or ANDROID_BUILD_TOOLS)" >&2; exit 2; }
command -v "$KEYTOOL" >/dev/null || { echo "keytool not found (set JAVA_HOME or KEYTOOL)" >&2; exit 2; }

T=$(mktemp -d)
trap 'rm -rf "${T:?}"' EXIT
bad=0

badging() { "$BT/aapt2" dump badging "$1" | sed -n "s/^package: name='\([^']*\)' versionCode='\([0-9]*\)'.*/\1 \2/p"; }
read -r PKG VC < <(badging "$UNSIGNED")
read -r DPKG DVC < <(badging "$DEBUG")
[ -n "$PKG" ] && [ -n "$VC" ] || { echo "cannot read package/versionCode from $UNSIGNED" >&2; exit 2; }

# Throwaway signers; the store password is test-only and never a real secret.
genkey() { "$KEYTOOL" -genkeypair -keystore "$T/$1.jks" -storepass testonly -keypass testonly -alias k -keyalg RSA -keysize 2048 -validity 1 -dname "$2" >/dev/null 2>&1; }
sign() { local ks=$1; shift; "$BT/apksigner" sign --ks "$T/$ks.jks" --ks-pass pass:testonly --ks-key-alias k "$@"; }
pin_of() { "$BT/apksigner" verify --print-certs "$1" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'; }
genkey test "CN=verify_apk test"
genkey debugcn "CN=Android Debug, O=Android, C=US"

sign test --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --out "$T/good.apk" "$UNSIGNED"
sign test --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out "$T/v1on.apk" "$UNSIGNED"
sign test --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false --out "$T/v3off.apk" "$UNSIGNED"
sign test --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --out "$T/debuggable.apk" "$DEBUG"
sign debugcn --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --out "$T/debugcn.apk" "$UNSIGNED"
pin_of "$T/good.apk" > "$T/pin"
pin_of "$T/debugcn.apk" > "$T/pin-debugcn"
tr 'a-f' 'A-F' < "$T/pin" > "$T/pin-upper"
echo "not-a-digest" > "$T/pin-bad"

run() { FRY_RELEASE_CERT_PIN_FILE=$1 "$V" "$2" "$3" "$4" 2>&1; }
ok() {
    local out
    if out=$(run "$1" "$2" "$3" "$4"); then echo "PASS  ok: $5"; else echo "FAIL  expected OK for $5:"; echo "$out"; bad=$((bad+1)); fi
}
refused() {
    local why=$1 out; shift
    if out=$(run "$1" "$2" "$3" "$4"); then echo "FAIL  expected refusal ($why) for $5"; bad=$((bad+1))
    elif grep -qF -- "$why" <<<"$out"; then echo "PASS  refused ($why): $5"
    else echo "FAIL  wrong reason for $5, wanted '$why':"; echo "$out"; bad=$((bad+1)); fi
}

ok      "$T/pin"       "$T/good.apk"       "$PKG" "$VC"  "release APK signed v2+v3 by the pinned key"
ok      "$T/pin-upper" "$T/good.apk"       "$PKG" "$VC"  "pin file in upper case"
refused "not the pinned"                  "$ROOT/signing/release-cert.sha256" "$T/good.apk" "$PKG" "$VC" "throwaway key against the real release pin"
refused "Android Debug certificate"       "$T/pin-debugcn" "$T/debugcn.apk"   "$PKG" "$VC"  "CN=Android Debug signer, even when pinned"
refused "APK is debuggable"               "$T/pin"       "$T/debuggable.apk" "$DPKG" "$DVC" "debuggable APK signed by the pinned key"
refused "package is not"                  "$T/pin"       "$T/good.apk"       "com.example.other" "$VC" "wrong package"
refused "versionCode is not"              "$T/pin"       "$T/good.apk"       "$PKG" "999999" "wrong versionCode"
refused "v1 (JAR) signing must be off"    "$T/pin"       "$T/v1on.apk"       "$PKG" "$VC"  "v1 signing enabled"
refused "not signed with scheme v3"       "$T/pin"       "$T/v3off.apk"      "$PKG" "$VC"  "v3 signing disabled"
refused "apksigner verify failed"         "$T/pin"       "$UNSIGNED"         "$PKG" "$VC"  "unsigned APK"
refused "not one SHA-256 hex digest"      "$T/pin-bad"   "$T/good.apk"       "$PKG" "$VC"  "malformed pin file"
refused "no such APK"                     "$T/pin"       "$T/missing.apk"    "$PKG" "$VC"  "missing APK"

echo "test_verify_apk: $bad failure(s)"
[ "$bad" -eq 0 ]
