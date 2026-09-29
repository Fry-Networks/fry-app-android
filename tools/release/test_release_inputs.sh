#!/usr/bin/env bash
# test_release_inputs.sh - runs release.yml's "id: v" step (tag + dispatch inputs -> outputs that
# later steps use unquoted) under bash with scripted inputs. Valid inputs must produce the expected
# outputs; a non-numeric version_code or min_supported, a bad tag or a bad promote_from must be
# refused before any output is written. Needs python3 with PyYAML.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
YML=$ROOT/.github/workflows/release.yml
T=$(mktemp -d)
trap 'rm -rf "${T:?}"' EXIT

python3 - "$YML" "$T/step.sh" <<'EOF'
import sys, yaml
wf = yaml.safe_load(open(sys.argv[1]))
steps = [s for job in wf["jobs"].values() for s in job["steps"] if s.get("id") == "v"]
assert len(steps) == 1, "release.yml must have exactly one step with id: v"
open(sys.argv[2], "w").write(steps[0]["run"])
EOF

bad=0
# run <event> <tag> <version_code> <promote_from> <min_supported> -> prints the step's exit code
run() {
    : > "$T/out"
    ( cd "$T" && GITHUB_EVENT_NAME=$1 GITHUB_REF_NAME=$2 INPUT_TAG=$2 INPUT_CODE=$3 INPUT_PROMOTE=$4 INPUT_MIN=$5 \
        GITHUB_OUTPUT=$T/out bash step.sh >/dev/null 2>&1 )
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

echo "test_release_inputs: $bad failure(s)"
[ "$bad" -eq 0 ]
