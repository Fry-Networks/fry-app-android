#!/usr/bin/env bash
# verify_apk.sh <apk> <expected-package> <expected-versionCode>
#
# Release gate (D-6): the APK must be signed with v2 + v3 (v1 off) by exactly one signer whose
# certificate SHA-256 equals the pin in signing/release-cert.sha256, must not be signed by an
# "Android Debug" certificate, must not be debuggable, and must carry the expected package and
# versionCode. Exits non-zero with the reason on the first failed check.
# FRY_RELEASE_CERT_PIN_FILE overrides the pin file (local negative/positive controls only).
set -euo pipefail

[ $# -eq 3 ] || { echo "usage: $0 <apk> <package> <versionCode>" >&2; exit 2; }
APK=$1 PKG=$2 VC=$3
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PIN_FILE=${FRY_RELEASE_CERT_PIN_FILE:-$ROOT/signing/release-cert.sha256}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
BT=${ANDROID_BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)}

fail() { echo "verify_apk: FAIL: $*" >&2; exit 1; }

[ -f "$APK" ] || fail "no such APK: $APK"
[ -x "$BT/apksigner" ] && [ -x "$BT/aapt2" ] || fail "build-tools not found (set ANDROID_HOME or ANDROID_BUILD_TOOLS)"
PIN=$(tr -d ' \r\n' < "$PIN_FILE" | tr 'A-F' 'a-f')
[[ "$PIN" =~ ^[0-9a-f]{64}$ ]] || fail "pin file $PIN_FILE is not one SHA-256 hex digest"

certs=$("$BT/apksigner" verify --verbose --print-certs "$APK" 2>&1) || fail "apksigner verify failed: $(tail -1 <<<"$certs")"
grep -q '^Verified using v1 scheme (JAR signing): false' <<<"$certs" || fail "v1 (JAR) signing must be off"
# apksigner reports v1 as unused for minSdk >= 24 even when JAR signature files are present, so check the archive too.
if unzip -Z1 "$APK" | grep -qE '^META-INF/[^/]+\.(RSA|DSA|EC|SF)$'; then fail "v1 (JAR) signing must be off (signature files in META-INF)"; fi
grep -q '^Verified using v2 scheme (APK Signature Scheme v2): true' <<<"$certs" || fail "not signed with scheme v2"
grep -q '^Verified using v3 scheme (APK Signature Scheme v3): true' <<<"$certs" || fail "not signed with scheme v3"
grep -q '^Number of signers: 1$' <<<"$certs" || fail "expected exactly one signer"
# Newer apksigner prints v3 signers as "Signer (minSdkVersion=…, maxSdkVersion=…) certificate …" instead of "Signer #1 …".
if grep -qE '^Signer (#1|\([^)]*\)) certificate DN: .*CN=Android Debug' <<<"$certs"; then fail "signed with an Android Debug certificate"; fi
digests=$(sed -nE 's/^Signer (#1|\([^)]*\)) certificate SHA-256 digest: ([0-9a-fA-F]+)$/\2/p' <<<"$certs" | tr 'A-F' 'a-f' | sort -u)
[ "$(grep -c . <<<"$digests")" = 1 ] || fail "expected one signer certificate digest, got: $(tr '\n' ' ' <<<"${digests:-none}")"
digest=$digests
[ "$digest" = "$PIN" ] || fail "signer certificate SHA-256 ${digest:-none} is not the pinned $PIN"

badging=$("$BT/aapt2" dump badging "$APK" 2>&1) || fail "aapt2 could not read the APK"
pkgline=$(grep -m1 '^package:' <<<"$badging") || fail "no package line"
[[ "$pkgline" == *" name='$PKG' "* ]] || fail "package is not $PKG ($pkgline)"
[[ "$pkgline" == *" versionCode='$VC' "* ]] || fail "versionCode is not $VC ($pkgline)"
if grep -q '^application-debuggable' <<<"$badging"; then fail "APK is debuggable"; fi

echo "verify_apk: OK $APK package=$PKG versionCode=$VC signer=$digest"
