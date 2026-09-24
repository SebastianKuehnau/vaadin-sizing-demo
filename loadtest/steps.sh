#!/usr/bin/env bash
# Run B, capacity limit: runs the recorded k6 scenario in load steps with increasing virtual users
# and keeps the HTML report of every step. The run stops at the first step whose k6 thresholds fail
# (p95 > 2 s or more than 1 % failed checks), which marks the load limit.
#
#   loadtest/steps.sh                                # 10, 50, 100, 200 VUs, 5 minutes each
#   STEPS="20 40" STEP_DURATION=2m loadtest/steps.sh
#   APP_IP=staging.example.com loadtest/steps.sh     # remote server, see README.md
set -euo pipefail

STEPS="${STEPS:-10 50 100 200}"
STEP_DURATION="${STEP_DURATION:-5m}"
APP_IP="${APP_IP:-localhost}"
APP_PORT="${APP_PORT:-8080}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-8081}"

cd "$(dirname "$0")/.."
tests=src/test/k6/recordings
if ! ls "$tests"/*.js >/dev/null 2>&1; then
    echo "No k6 scripts in $tests. Record them first, see README.md." >&2
    exit 1
fi
loadtest/fix-think-times.sh "$tests"
loadtest/expand-test-data.py "$tests"

run="$(date +%Y%m%d-%H%M%S)"
results="target/k6/results/steps-$run"
mkdir -p "$results"
echo "Load steps: $STEPS VUs, $STEP_DURATION each, against $APP_IP:$APP_PORT. Results: $results"

for vus in $STEPS; do
    echo "--- $(date +%H:%M:%S) step: $vus VUs"
    rm -rf "$tests/report"
    status=0
    K6_TESTID="steps-$run-$vus" ./mvnw -Ploadtest loadtest:run \
        -Dk6.vus="$vus" -Dk6.duration="$STEP_DURATION" \
        -Dk6.appIp="$APP_IP" -Dk6.appPort="$APP_PORT" -Dk6.managementPort="$MANAGEMENT_PORT" \
        2>&1 | tee "$results/$vus-vus.log" || status=$?
    if [[ -d "$tests/report" ]]; then
        cp -R "$tests/report" "$results/$vus-vus"
    fi
    if [[ $status != 0 ]]; then
        echo "--- Step $vus VUs failed its thresholds or aborted; stopping. See $results/$vus-vus.log" >&2
        exit $status
    fi
done
echo "--- All steps passed. Reports: $results/*-vus/"
