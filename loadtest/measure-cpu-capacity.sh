#!/usr/bin/env bash
# Measures how many scenario runs per minute the app handles with a given number of CPU cores
# (see README.md):
#
#   1. restarts the container with APP_CPUS cores and a short session timeout, so the sessions of
#      the finished runs leave the heap and the memory does not limit the test
#   2. warmup: the first rate for WARMUP_DURATION (JIT, class loading, caches)
#   3. one step per rate in RATES: k6 starts the scenario at that rate for STEP_DURATION, no matter
#      how fast the app answers (constant-arrival-rate, an open model like real users)
#   4. stops after the first step in which the app is saturated: p95 > P95_LIMIT ms, more than 1 %
#      failed requests, or k6 could not start all runs (all VUs busy)
#
# Per step: CPU per run = JVM CPU time / completed runs, cores used = CPU per run × runs per second.
# k6 lets all started runs finish, so the CPU time covers exactly the completed runs.
#
#   loadtest/measure-cpu-capacity.sh                                 # 2 cores
#   APP_CPUS=1 RATES="30 60 90 120" loadtest/measure-cpu-capacity.sh 1-core
#
# The container keeps running with these settings; restart it with docker compose up -d for the
# session size measurement (it needs the default session timeout of 30 minutes).
#
# The results are written to results/<timestamp>-cpu[-label]/: summary.txt and per step the k6
# log and the k6 report (HTML and JSON).
set -euo pipefail

APP_CPUS="${APP_CPUS:-2.0}"
APP_MEMORY="${APP_MEMORY:-2g}"
SESSION_TIMEOUT="${SESSION_TIMEOUT:-2m}"
RATES="${RATES:-30 60 120 180 240 360 480 720}" # scenario runs per minute
STEP_DURATION="${STEP_DURATION:-3m}"
WARMUP_DURATION="${WARMUP_DURATION:-1m}"
P95_LIMIT="${P95_LIMIT:-500}"
APP_IP="${APP_IP:-localhost}"
APP_PORT="${APP_PORT:-8080}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-8081}"
label="${1:-}"

cd "$(dirname "$0")/.."
actuator="http://127.0.0.1:$MANAGEMENT_PORT/actuator"
recordings=src/test/k6/recordings

ls "$recordings"/*.js >/dev/null 2>&1 \
    || { echo "No k6 script in $recordings, record it with loadtest/rebuild.sh" >&2; exit 1; }

results="$PWD/results/$(date +%Y%m%d-%H%M%S)-cpu${label:+-$label}"
mkdir -p "$results"

metric() { # prints the value of an Actuator metric, e.g. metric process.cpu.time
    curl -sf "$actuator/metrics/$1${2:+?tag=$2}" \
        | python3 -c 'import json, sys; print(int(json.load(sys.stdin)["measurements"][0]["value"]))'
}

check_app() { # fails if the app is not reachable or was restarted since the script started
    local now
    now="$(metric process.start.time 2>/dev/null)" || {
        echo "The app is not reachable ($1), see: docker compose logs app" >&2
        exit 1
    }
    [[ "$now" == "$start_time" ]] || {
        echo "The app was restarted ($1), most likely killed for exceeding its memory limit:" >&2
        echo "  docker events --since 30m --until 0s --filter container=sizing-app --filter event=oom" >&2
        echo "Use a higher APP_MEMORY or a shorter SESSION_TIMEOUT." >&2
        exit 1
    }
}

k6run() { # k6run <name> <runs per minute> <duration> [extra arguments]
    local name=$1 rate=$2 duration=$3 vus
    shift 3
    # One run takes about 15 s (recorded think times), so rate/60 × 15 VUs are busy at a time.
    # Twice that as reserve; if even those are busy, k6 drops runs and the app is saturated.
    vus=$(( rate / 2 + 10 ))
    rm -rf "$recordings/report"
    ./mvnw -Ploadtest loadtest:run \
        -Dk6.executor=constant-arrival-rate -Dk6.rate="$rate" -Dk6.timeUnit=1m \
        -Dk6.duration="$duration" -Dk6.preAllocatedVUs="$vus" -Dk6.maxVUs=$(( vus * 2 )) \
        -Dk6.threshold.checksAbortOnFail=false -Dk6.failOnThreshold=false \
        -Dk6.appIp="$APP_IP" -Dk6.appPort="$APP_PORT" "$@" > "$results/$name.log" 2>&1 \
        || { echo "k6 run '$name' failed, see $results/$name.log" >&2; exit 1; }
    [[ -d "$recordings/report" ]] && mv "$recordings/report" "$results/$name-report"
    check_app "during k6 run '$name', see $results/$name.log"
}

echo "Restarting the container: $APP_CPUS cores, $APP_MEMORY, session timeout $SESSION_TIMEOUT"
APP_CPUS="$APP_CPUS" APP_MEMORY="$APP_MEMORY" \
    APP_JAVA_OPTS="-Dserver.servlet.session.timeout=$SESSION_TIMEOUT ${APP_JAVA_OPTS:-}" \
    docker compose up -d --no-build --force-recreate >/dev/null
for _ in $(seq 1 120); do
    curl -sf "$actuator/health" >/dev/null && break
    sleep 1
done
curl -sf "$actuator/health" >/dev/null \
    || { echo "The app did not start, see: docker compose logs app" >&2; exit 1; }
start_time="$(metric process.start.time)" # to detect restarts, see check_app

read -r first_rate _ <<< "$RATES"
echo "Warmup: $first_rate runs/min for $WARMUP_DURATION"
k6run warmup "$first_rate" "$WARMUP_DURATION" -Dk6.managementPort=-1

{
    echo "$APP_CPUS cores, $APP_MEMORY, session timeout $SESSION_TIMEOUT, steps of $STEP_DURATION"
    echo
    echo "runs/min  completed  dropped  failed    p95 ms  CPU/run ms  cores used  utilization"
} | tee "$results/summary.txt"

for rate in $RATES; do
    cpu_before="$(metric process.cpu.time)"
    k6run "rate-$rate" "$rate" "$STEP_DURATION" -Dk6.managementPort="$MANAGEMENT_PORT"
    cpu_after="$(metric process.cpu.time)"

    status=0
    python3 - "$results/rate-$rate-report"/*-summary.json "$rate" "$cpu_before" "$cpu_after" "$APP_CPUS" \
        "$P95_LIMIT" > "$results/rate-$rate.txt" <<'EOF' || status=$?
import json, sys
summary_file, rate, cpu_before, cpu_after, cpus, p95_limit = sys.argv[1:]
data = json.load(open(summary_file))
metrics = data["metrics"]
value = lambda metric, stat, default=0: metrics.get(metric, {}).get("values", {}).get(stat, default)
completed = int(value("iterations", "count"))
dropped = int(value("dropped_iterations", "count"))
failed = max(value("http_req_failed", "rate"), 1 - value("checks", "rate", 1))
p95 = value("http_req_duration", "p(95)")
# The JVM CPU time also includes the idle app during the Maven start, which is negligible
cpu_per_run = (int(cpu_after) - int(cpu_before)) / 1e6 / completed if completed else 0
cores = cpu_per_run / 1000 * int(rate) / 60
print(f"{int(rate):8d} {completed:10d} {dropped:8d} {failed:6.1%} {p95:9.0f} {cpu_per_run:11.1f}"
      f" {cores:11.2f} {cores / float(cpus):12.0%}")
saturated = p95 > float(p95_limit) or failed > 0.01 or dropped > 0
sys.exit(3 if saturated else 0)
EOF
    tee -a "$results/summary.txt" < "$results/rate-$rate.txt"
    [[ $status -eq 0 ]] || {
        [[ $status -eq 3 ]] || exit "$status"
        echo "Saturated at $rate runs/min (p95 > $P95_LIMIT ms, failed requests or dropped runs)" \
            | tee -a "$results/summary.txt"
        break
    }
done
echo "Results: $results"
