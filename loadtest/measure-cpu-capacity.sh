#!/usr/bin/env bash
# Measures how many scenario runs per minute the app handles with a given number of CPU cores
# (see README.md):
#
#   1. restarts the container with APP_CPUS cores and a short session timeout, so the sessions of
#      the finished runs leave the heap and the memory does not limit the test
#   2. warmup: steps of WARMUP_STEP at WARMUP_RATE runs/min until the CPU per run changed by less
#      than WARMUP_TOLERANCE (0.10 = 10 %) twice in a row, i.e. three steps in a row have about the
#      same CPU per run (a single small change can be chance), at most WARMUP_MAX_STEPS steps; the
#      table in summary.txt marks the step from which it is stable. A fixed duration does not ensure
#      that the JIT is done: the JVM interprets the code at first, compiles hot methods only after
#      many calls, and the compiler threads need CPU themselves. All steps run at the same rate,
#      otherwise the base load (metrics polling, GC) distorts the comparison, and k6 polls the
#      metrics (MANAGEMENT_PORT) as in the rate steps. Saturation is not checked during the warmup,
#      only logged.
#   3. one step per rate in RATES: k6 starts the scenario at that rate for STEP_DURATION, no matter
#      how fast the app answers (constant-arrival-rate, an open model like real users)
#   4. stops after the first step in which the app is saturated: p95 > P95_LIMIT ms, more than 1 %
#      failed requests, or k6 could not start all runs (all VUs busy)
#
# Per step (warmup and rates): CPU per run = JVM CPU time / completed runs, cores used = CPU per
# run × runs per second. k6 lets all started runs finish, so the CPU time covers exactly the
# completed runs.
#
#   loadtest/measure-cpu-capacity.sh                                 # 2 cores
#   APP_CPUS=1 RATES="30 60 90 120" loadtest/measure-cpu-capacity.sh 1-core
#   SCENARIO=hello-world loadtest/measure-cpu-capacity.sh hello      # the other k6 script
#
# Without Docker, against an app started locally with java -jar: LOCAL_PID is its process ID. The
# script does not restart it, so start it with the short session timeout yourself. Without a CPU
# limit, the utilization refers to APP_CPUS, by default all cores of the machine, which k6 shares:
#
#   java -Dserver.servlet.session.timeout=2m -jar target/vaadin-sizing-demo-1.0-SNAPSHOT.jar
#   LOCAL_PID=$(pgrep -f vaadin-sizing-demo-1.0-SNAPSHOT.jar) loadtest/measure-cpu-capacity.sh local
#
# The container keeps running with these settings; restart it with docker compose up -d for the
# session size measurement (it needs the default session timeout of 30 minutes).
#
# The results are written to results/<timestamp>-cpu[-label]/: summary.txt (the warmup steps, then
# the rate steps) and per step the k6 log and the k6 report (HTML and JSON).
set -euo pipefail

LOCAL_PID="${LOCAL_PID:-}"
APP_CPUS="${APP_CPUS:-$([[ -n "$LOCAL_PID" ]] && getconf _NPROCESSORS_ONLN || echo 2.0)}"
APP_MEMORY="${APP_MEMORY:-2g}"
SESSION_TIMEOUT="${SESSION_TIMEOUT:-2m}"
RATES="${RATES:-50 100 50}" # scenario runs per minute
STEP_DURATION="${STEP_DURATION:-1m}"
WARMUP_RATE="${WARMUP_RATE:-50}" # scenario runs per minute
WARMUP_STEP="${WARMUP_STEP:-1m}" # one run takes ~15 s, shorter steps complete too few runs
WARMUP_TOLERANCE="${WARMUP_TOLERANCE:-0.10}" # max. relative change of the CPU per run
WARMUP_MAX_STEPS="${WARMUP_MAX_STEPS:-10}"
P95_LIMIT="${P95_LIMIT:-500}"
SCENARIO="${SCENARIO:-edit-person}" # the k6 script src/test/k6/recordings/<SCENARIO>.js
APP_IP="${APP_IP:-localhost}"
APP_PORT="${APP_PORT:-8080}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-8081}"
label="${1:-}"

cd "$(dirname "$0")/.."
actuator="http://127.0.0.1:$MANAGEMENT_PORT/actuator"
recordings=src/test/k6/recordings

[[ -f "$recordings/$SCENARIO.js" ]] \
    || { echo "No k6 script $recordings/$SCENARIO.js, record it with loadtest/rebuild.sh" >&2; exit 1; }

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
        -Dk6.testFile="$recordings/$SCENARIO.js" \
        -Dk6.executor=constant-arrival-rate -Dk6.rate="$rate" -Dk6.timeUnit=1m \
        -Dk6.duration="$duration" -Dk6.preAllocatedVUs="$vus" -Dk6.maxVUs=$(( vus * 2 )) \
        -Dk6.threshold.checksAbortOnFail=false -Dk6.failOnThreshold=false \
        -Dk6.appIp="$APP_IP" -Dk6.appPort="$APP_PORT" "$@" > "$results/$name.log" 2>&1 \
        || { echo "k6 run '$name' failed, see $results/$name.log" >&2; exit 1; }
    [[ -d "$recordings/report" ]] && mv "$recordings/report" "$results/$name-report"
    check_app "during k6 run '$name', see $results/$name.log"
}

k6step() { # k6step <name> <runs per minute> <duration> [extra arguments]: k6run, then sets
    # completed, dropped, failed, p95 and cpu_per_run (ms) from the k6 summary and the JVM CPU time
    local cpu_before cpu_after stats
    cpu_before="$(metric process.cpu.time)"
    k6run "$@"
    cpu_after="$(metric process.cpu.time)"
    stats="$(python3 - "$results/$1-report"/*-summary.json "$cpu_before" "$cpu_after" <<'EOF'
import json, sys
summary_file, cpu_before, cpu_after = sys.argv[1:]
metrics = json.load(open(summary_file))["metrics"]
value = lambda metric, stat, default=0: metrics.get(metric, {}).get("values", {}).get(stat, default)
completed = int(value("iterations", "count"))
dropped = int(value("dropped_iterations", "count"))
failed = max(value("http_req_failed", "rate"), 1 - value("checks", "rate", 1))
p95 = value("http_req_duration", "p(95)")
# The JVM CPU time also includes the idle app during the Maven start, which is negligible
cpu_per_run = (int(cpu_after) - int(cpu_before)) / 1e6 / completed if completed else 0
print(completed, dropped, failed, p95, cpu_per_run)
EOF
)"
    read -r completed dropped failed p95 cpu_per_run <<< "$stats"
}

if [[ -n "$LOCAL_PID" ]]; then
    kill -0 "$LOCAL_PID" 2>/dev/null || { echo "No process with LOCAL_PID $LOCAL_PID" >&2; exit 1; }
    command jcmd "$LOCAL_PID" VM.system_properties 2>/dev/null \
        | grep -q '^server\.servlet\.session\.timeout=' \
        || echo "Warning: the app has no -Dserver.servlet.session.timeout, the sessions of the" \
                "finished runs stay 30 minutes in the heap, see the start command at the top" >&2
    echo "Local app (PID $LOCAL_PID), utilization relative to $APP_CPUS cores"
else
    echo "Restarting the container: $APP_CPUS cores, $APP_MEMORY, session timeout $SESSION_TIMEOUT"
    APP_CPUS="$APP_CPUS" APP_MEMORY="$APP_MEMORY" \
        APP_JAVA_OPTS="-Dserver.servlet.session.timeout=$SESSION_TIMEOUT ${APP_JAVA_OPTS:-}" \
        docker compose up -d --no-build --force-recreate >/dev/null
fi
for _ in $(seq 1 120); do
    curl -sf "$actuator/health" >/dev/null && break
    sleep 1
done
curl -sf "$actuator/health" >/dev/null \
    || { echo "The app did not start, see: docker compose logs app" >&2; exit 1; }
start_time="$(metric process.start.time)" # to detect restarts, see check_app

tolerance="$(awk -v t="$WARMUP_TOLERANCE" 'BEGIN { printf "%g %%", t * 100 }')"
{
    echo "warmup: $WARMUP_RATE runs/min, steps of $WARMUP_STEP, tolerance $tolerance"
    echo "step  completed  dropped  failed    p95 ms  CPU/run ms    change"
} | tee "$results/summary.txt"

previous=0 # CPU per run of the previous warmup step
within=false # the change of the previous step was within the tolerance
stable=false
for step in $(seq 1 "$WARMUP_MAX_STEPS"); do
    # The metrics polling as in the rate steps, it costs CPU, too
    k6step "warmup-$step" "$WARMUP_RATE" "$WARMUP_STEP" -Dk6.managementPort="$MANAGEMENT_PORT"
    # change: relative to the previous step; exit 2 if it is within the tolerance, 0 (stable) if
    # the change of the previous step was, too
    status=0
    awk -v step="$step" -v completed="$completed" -v dropped="$dropped" -v failed="$failed" \
        -v p95="$p95" -v cpu_per_run="$cpu_per_run" -v previous="$previous" \
        -v tolerance="$WARMUP_TOLERANCE" -v within_before="$within" 'BEGIN {
            printf "%4d %10d %8d %6.1f%% %9.0f %11.1f", step, completed, dropped, failed * 100, p95,
                cpu_per_run
            if (previous == 0 || cpu_per_run == 0) { print "         –"; exit 1 }
            change = cpu_per_run / previous - 1
            within = change < tolerance && change > -tolerance
            stable = within && within_before == "true"
            printf " %+8.1f%%%s\n", change * 100, stable ? "   stable" : ""
            exit stable ? 0 : within ? 2 : 1
        }' | tee -a "$results/summary.txt" || status=$?
    [[ $status -eq 0 ]] && { stable=true; break; }
    [[ $status -eq 2 ]] && within=true || within=false
    previous="$cpu_per_run"
done
$stable || echo "Warning: the CPU per run is not stable after $WARMUP_MAX_STEPS warmup steps, the" \
    "CPU values below are too high (JIT not done?). Use a higher WARMUP_MAX_STEPS." \
    | tee -a "$results/summary.txt"

{
    echo
    echo "scenario: $SCENARIO"
    if [[ -n "$LOCAL_PID" ]]; then
        echo "local app (PID $LOCAL_PID), utilization of $APP_CPUS cores, steps of $STEP_DURATION"
    else
        echo "$APP_CPUS cores, $APP_MEMORY, session timeout $SESSION_TIMEOUT, steps of $STEP_DURATION"
    fi
    echo
    echo "runs/min  completed  dropped  failed    p95 ms  CPU/run ms  cores used  utilization"
} | tee -a "$results/summary.txt"

for rate in $RATES; do
    k6step "rate-$rate" "$rate" "$STEP_DURATION" -Dk6.managementPort="$MANAGEMENT_PORT"

    status=0
    awk -v rate="$rate" -v completed="$completed" -v dropped="$dropped" -v failed="$failed" \
        -v p95="$p95" -v cpu_per_run="$cpu_per_run" -v cpus="$APP_CPUS" -v p95_limit="$P95_LIMIT" \
        'BEGIN {
            cores = cpu_per_run / 1000 * rate / 60
            printf "%8d %10d %8d %5.1f%% %9.0f %11.1f %11.2f %11.0f%%\n", rate, completed, dropped,
                failed * 100, p95, cpu_per_run, cores, cores / cpus * 100
            exit (p95 > p95_limit || failed > 0.01 || dropped > 0) ? 3 : 0
        }' > "$results/rate-$rate.txt" || status=$?
    tee -a "$results/summary.txt" < "$results/rate-$rate.txt"
    [[ $status -eq 0 ]] || {
        [[ $status -eq 3 ]] || exit "$status"
        echo "Saturated at $rate runs/min (p95 > $P95_LIMIT ms, failed requests or dropped runs)" \
            | tee -a "$results/summary.txt"
        break
    }
done
echo "Results: $results"
