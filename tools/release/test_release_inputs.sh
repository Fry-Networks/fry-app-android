#!/usr/bin/env bash
# test_release_inputs.sh - runs two steps of release.yml under bash with scripted inputs:
#  1. the "id: v" step (tag + dispatch inputs -> outputs that later steps use unquoted): valid
#     inputs must produce the expected outputs; a non-numeric version_code or min_supported, a bad
#     tag or a bad promote_from must be refused before any output is written;
#  2. the "Fetch the test release APK to promote" step, with `gh` and `aapt2` replaced by stubs:
#     a numeric versionCode is exported as PROMOTED_CODE; a non-numeric or empty one, or a
#     versionName that does not match the tag, is refused and exports nothing.
# Needs python3 with PyYAML. RELEASE_YML overrides the workflow file (mutant runs only).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
YML=${RELEASE_YML:-$ROOT/.github/workflows/release.yml}
T=$(mktemp -d)
trap 'rm -rf "${T:?}"' EXIT

python3 - "$YML" "$T/step-v.sh" "$T/step-promote.sh" <<'EOF'
import sys, yaml
wf = yaml.safe_load(open(sys.argv[1]))
steps = [s for job in wf["jobs"].values() for s in job["steps"]]
v = [s for s in steps if s.get("id") == "v"]
assert len(v) == 1, "release.yml must have exactly one step with id: v"
open(sys.argv[2], "w").write(v[0]["run"])
promote = [s for s in steps if str(s.get("name", "")).startswith("Fetch the test release APK to promote")]
assert len(promote) == 1, "release.yml must have exactly one 'Fetch the test release APK to promote' step"
body = promote[0]["run"]
for expr, value in (("steps.v.outputs.promote", "app-v0.4.1-rc.1"), ("steps.v.outputs.asset", "fryapp-0.4.1.apk"), ("steps.v.outputs.name", "0.4.1")):
    body = body.replace("${{ %s }}" % expr, value)
assert "${{" not in body, "unsubstituted expression in the promote step: " + body
open(sys.argv[3], "w").write(body)
EOF

bad=0

# --- step v -------------------------------------------------------------------------------------
# run <event> <tag> <version_code> <promote_from> <min_supported> -> prints the step's exit code
run() {
    : > "$T/out"
    ( cd "$T" && GITHUB_EVENT_NAME=$1 GITHUB_REF_NAME=$2 INPUT_TAG=$2 INPUT_CODE=$3 INPUT_PROMOTE=$4 INPUT_MIN=$5 \
        GITHUB_OUTPUT=$T/out bash step-v.sh >/dev/null 2>&1 )
    echo $?
}
ok() {
    local rc; rc=$(run "$@")
    if [ "$rc" = 0 ]; then echo "PASS  accepted: $*"; else echo "FAIL  expected accept (rc=$rc): $*"; bad=$((bad+1)); fi
}
refused() {
    local rc; rc=$(run "$@")
    if [ "$rc" != 0 ] && [ ! -s "$T/out" ]; then echo "PASS  refused: $*"
    else echo "FAIL  expected refusal (rc=$rc, $(wc -c < "$T/out") bytes of outputs): $*"; bad=$((bad+1)); fi
}
expect_output() {
    if grep -qx -- "$1" "$T/out"; then echo "PASS  output $1"; else echo "FAIL  missing output $1 in: $(tr '\n' ' ' < "$T/out")"; bad=$((bad+1)); fi
}

ok      workflow_dispatch app-v0.4.1-rc.1 7 "" 6
expect_output "channel=test"; expect_output "name=0.4.1-rc.1"; expect_output "code=7"; expect_output "min=6"; expect_output "asset=fryapp-0.4.1-rc.1.apk"
ok      workflow_dispatch app-v0.4.1 8 "" ""
expect_output "channel=stable"; expect_output "name=0.4.1"; expect_output "min=6"
ok      workflow_dispatch app-v0.4.1 "" app-v0.4.1-rc.1 6
expect_output "promote=app-v0.4.1-rc.1"
refused workflow_dispatch app-v0.4.1-rc.1 "7;id" "" 6
refused workflow_dispatch app-v0.4.1-rc.1 "" "" 6
refused workflow_dispatch app-v0.4.1-rc.1 7 "" "6 || id"
refused workflow_dispatch app-v0.4.1-rc.1 7 "" abc
refused workflow_dispatch app-v0.4.1 "x'" app-v0.4.1-rc.1 6
refused workflow_dispatch "app-v0.4.1; id" 7 "" 6
refused workflow_dispatch app-v0.4.1-rc.1 7 app-v0.4.0-rc.1 6
refused workflow_dispatch app-v0.4.1 8 v0.4.0 6

# --- promote step, with gh and aapt2 stubbed ------------------------------------------------------
mkdir -p "$T/bin" "$T/sdk/build-tools/99.0.0"
printf 'PK\003\004stub-apk' > "$T/stub.apk"
cat > "$T/bin/gh" <<'EOF'
#!/usr/bin/env bash
# gh release download <tag> --repo <repo> --pattern 'fryapp-*.apk' --dir promote
[ "$1" = release ] && [ "$2" = download ] || { echo "stub gh: unexpected $*" >&2; exit 2; }
dir=promote; while [ $# -gt 0 ]; do [ "$1" = --dir ] && dir=$2; shift; done
mkdir -p "$dir" && cp "$STUB_APK" "$dir/fryapp-0.4.1-rc.1.apk"
EOF
cat > "$T/sdk/build-tools/99.0.0/aapt2" <<'EOF'
#!/usr/bin/env bash
# aapt2 dump badging <apk>
[ "$1" = dump ] && [ "$2" = badging ] || { echo "stub aapt2: unexpected $*" >&2; exit 2; }
echo "package: name='com.frynetworks.fryapp' versionCode='${STUB_VC}' versionName='${STUB_VN}' platformBuildVersionName='15' platformBuildVersionCode='35'"
echo "application-label:'Fry'"
EOF
chmod +x "$T/bin/gh" "$T/sdk/build-tools/99.0.0/aapt2"

# promote_run <versionCode> <versionName> -> prints the step's exit code; GITHUB_ENV in $T/env
promote_run() {
    rm -rf "${T:?}/work"; mkdir -p "$T/work"; : > "$T/env"
    ( cd "$T/work" && PATH="$T/bin:$PATH" STUB_APK=$T/stub.apk STUB_VC=$1 STUB_VN=$2 ANDROID_HOME=$T/sdk ANDROID_BUILD_TOOLS=$T/sdk/build-tools/99.0.0 \
        GITHUB_REPOSITORY=Fry-Networks/fry-app-android GITHUB_ENV=$T/env bash "$T/step-promote.sh" >/dev/null 2>&1 )
    echo $?
}
promote_ok() {
    local rc; rc=$(promote_run "$1" "$2")
    if [ "$rc" = 0 ] && grep -qx "PROMOTED_CODE=$1" "$T/env"; then echo "PASS  promote accepted: versionCode=$1 versionName=$2"
    else echo "FAIL  expected promote accept (rc=$rc, env: $(tr '\n' ' ' < "$T/env")): versionCode=$1 versionName=$2"; bad=$((bad+1)); fi
}
promote_refused() {
    local rc; rc=$(promote_run "$1" "$2")
    if [ "$rc" != 0 ] && ! grep -q "PROMOTED_CODE" "$T/env"; then echo "PASS  promote refused: versionCode='$1' versionName=$2"
    else echo "FAIL  expected promote refusal (rc=$rc, env: $(tr '\n' ' ' < "$T/env")): versionCode='$1' versionName=$2"; bad=$((bad+1)); fi
}

promote_ok      8 0.4.1
promote_refused "8abc" 0.4.1
promote_refused "" 0.4.1
promote_refused "8; id" 0.4.1
promote_refused 8 0.4.0

echo "test_release_inputs: $bad failure(s)"
[ "$bad" -eq 0 ]
