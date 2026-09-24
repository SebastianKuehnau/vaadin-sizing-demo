#!/usr/bin/env bash
# Run A, cost per session: a long run with a constant number of virtual users against an app with
# generous limits, so the memory and CPU per active session can be read off in Grafana
# ("Live data per session", "CPU") once the curves are flat.
#
#   loadtest/cost-per-session.sh                     # 50 VUs for 10 minutes
#   VUS=100 DURATION=15m loadtest/cost-per-session.sh
set -euo pipefail

VUS="${VUS:-50}"
DURATION="${DURATION:-10m}"
RAMP_UP="${RAMP_UP:-1m}"
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
results="target/k6/results/cost-per-session-$run"
mkdir -p "$results"
echo "Cost per session: $VUS VUs for $DURATION against $APP_IP:$APP_PORT. Results: $results"

rm -rf "$tests/report"
status=0
K6_TESTID="cost-per-session-$run" ./mvnw -Ploadtest loadtest:run \
    -Dk6.vus="$VUS" -Dk6.duration="$DURATION" -Dk6.rampUp="$RAMP_UP" \
    -Dk6.appIp="$APP_IP" -Dk6.appPort="$APP_PORT" -Dk6.managementPort="$MANAGEMENT_PORT" \
    2>&1 | tee "$results/run.log" || status=$?
if [[ -d "$tests/report" ]]; then
    cp -R "$tests/report" "$results/report"
fi
exit $status
