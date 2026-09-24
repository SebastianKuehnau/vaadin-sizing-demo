#!/usr/bin/env bash
# k6 wrapper that additionally writes the k6 metrics to Prometheus (remote write), so Grafana shows
# the client-side view of a load test next to the server metrics.
#
# The testbench-converter-plugin calls k6 with its own "--out csv=..." for the HTML report, which
# replaces a K6_OUT environment variable. That is why the pom.xml sets this script as k6Binary: it
# adds a second output to every "k6 run" and passes all other calls through unchanged.
#
#   K6_PROMETHEUS=false                        # disable the Prometheus output
#   K6_PROMETHEUS_RW_SERVER_URL=http://...     # other Prometheus, default: localhost:9090
#   K6_TESTID=step-50                          # tag of the run in Prometheus, default: timestamp
set -euo pipefail

k6="${K6_REAL_BINARY:-$(command -v k6 || true)}"
if [[ -z "$k6" ]]; then
    echo "k6 not found. Install it (brew install k6) or set K6_REAL_BINARY." >&2
    exit 127
fi

if [[ "${1:-}" == run && "${K6_PROMETHEUS:-true}" != false ]]; then
    shift
    export K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://localhost:9090/api/v1/write}"
    export K6_PROMETHEUS_RW_TREND_STATS="${K6_PROMETHEUS_RW_TREND_STATS:-p(95),p(99),avg,max}"
    export K6_PROMETHEUS_RW_PUSH_INTERVAL="${K6_PROMETHEUS_RW_PUSH_INTERVAL:-5s}"
    exec "$k6" run --out experimental-prometheus-rw --tag "testid=${K6_TESTID:-$(date +%Y%m%d-%H%M%S)}" "$@"
fi
exec "$k6" "$@"
