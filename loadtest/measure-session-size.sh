#!/usr/bin/env bash
# Measures the memory of one user session in the running container (see README.md):
#
#   1. warmup: a few users run the scenario once, so one-time costs (class loading, caches) are
#      not counted as session memory
#   2. heap dump "before"; the dump forces a full GC, so the heap then holds only live objects
#   3. USERS virtual users run the scenario once each; every user leaves one session with one UI
#      (one browser tab) behind, which stays in memory until the session timeout (30 minutes)
#   4. heap dump "after"
#
# Memory per session = (live heap after - live heap before) / (sessions after - sessions before)
#
#   loadtest/measure-session-size.sh                 # 100 users
#   USERS=200 loadtest/measure-session-size.sh lazy  # the label is added to the result directory
#   KEEP_DUMPS=false loadtest/measure-session-size.sh
#
# The results are written to results/<timestamp>[-label]/: summary.txt, the heap dumps
# before.hprof and after.hprof (open them in VisualVM or Eclipse MAT) and the k6 logs.
set -euo pipefail

USERS="${USERS:-100}"
WARMUP_USERS="${WARMUP_USERS:-20}"
KEEP_DUMPS="${KEEP_DUMPS:-true}"
APP_IP="${APP_IP:-localhost}"
APP_PORT="${APP_PORT:-8080}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-8081}"
label="${1:-}"

cd "$(dirname "$0")/.."
actuator="http://127.0.0.1:$MANAGEMENT_PORT/actuator"
recordings=src/test/k6/recordings

curl -sf "$actuator/health" >/dev/null \
    || { echo "The app is not running, start it with loadtest/rebuild.sh" >&2; exit 1; }
ls "$recordings"/*.js >/dev/null 2>&1 \
    || { echo "No k6 script in $recordings, record it with loadtest/rebuild.sh" >&2; exit 1; }

results="results/$(date +%Y%m%d-%H%M%S)${label:+-$label}"
mkdir -p "$results"

metric() { # prints the value of an Actuator metric, e.g. metric jvm.memory.used area:heap
    curl -sf "$actuator/metrics/$1${2:+?tag=$2}" \
        | python3 -c 'import json, sys; print(int(json.load(sys.stdin)["measurements"][0]["value"]))'
}

check_app() { # fails if the app is not reachable or was restarted since the script started
    local now
    now="$(metric process.start.time 2>/dev/null)" || {
        echo "The app is not reachable ($1). It may have crashed or be restarting, see:" >&2
        echo "  docker compose logs app; docker events --since 30m --until 0s --filter container=sizing-app" >&2
        exit 1
    }
    [[ "$now" == "$start_time" ]] || {
        echo "The app was restarted ($1), the measurement is invalid. Most likely the container" >&2
        echo "exceeded its memory limit and was killed (exit code 137), check with:" >&2
        echo "  docker events --since 30m --until 0s --filter container=sizing-app --filter event=oom" >&2
        echo "Give it more memory (APP_MEMORY=2g docker compose up -d) or use fewer USERS." >&2
        exit 1
    }
}

snapshot() { # heap dump (full GC), then live heap and sessions; sets heap_<name> and sessions_<name>
    local file="$results/$1.hprof" heap sessions
    [[ "$KEEP_DUMPS" == true ]] || file=/dev/null
    check_app "before heap dump '$1'"
    curl -sf -o "$file" "$actuator/heapdump?live=true" \
        || { check_app "heap dump '$1' failed"; echo "Heap dump '$1' failed" >&2; exit 1; }
    heap="$(metric jvm.memory.used area:heap)" && sessions="$(metric tomcat.sessions.active.current)" \
        || { check_app "reading metrics after heap dump '$1'"; echo "Reading the metrics failed" >&2; exit 1; }
    printf -v "heap_$1" '%s' "$heap"
    printf -v "sessions_$1" '%s' "$sessions"
}

k6run() { # k6run <name> <users> [extra arguments]: every user runs the scenario once
    local name=$1 users=$2
    shift 2
    rm -rf "$recordings/report"
    ./mvnw -Ploadtest loadtest:run \
        -Dk6.vus="$users" -Dk6.executor=per-vu-iterations -Dk6.iterations=1 -Dk6.duration=10m \
        -Dk6.appIp="$APP_IP" -Dk6.appPort="$APP_PORT" "$@" > "$results/$name.log" 2>&1 \
        || { echo "k6 run '$name' failed, see $results/$name.log" >&2; exit 1; }
    [[ -d "$recordings/report" ]] && mv "$recordings/report" "$results/$name-report"
    check_app "during k6 run '$name', see $results/$name.log"
}

start_time="$(metric process.start.time)" # to detect restarts, see check_app

echo "Warmup: $WARMUP_USERS users"
k6run warmup "$WARMUP_USERS" -Dk6.failOnThreshold=false -Dk6.managementPort=-1

echo "Heap dump before"
snapshot before

echo "Load: $USERS users"
k6run load "$USERS" -Dk6.managementPort="$MANAGEMENT_PORT"

echo "Heap dump after"
snapshot after

python3 - "$heap_before" "$sessions_before" "$heap_after" "$sessions_after" <<'EOF' | tee "$results/summary.txt"
import sys
heap_before, sessions_before, heap_after, sessions_after = map(int, sys.argv[1:])
mb = lambda b: b / 1024 / 1024
new_sessions = sessions_after - sessions_before
print(f"                 live heap   sessions")
print(f"before        {mb(heap_before):9.1f} MB {sessions_before:10d}")
print(f"after         {mb(heap_after):9.1f} MB {sessions_after:10d}")
if new_sessions <= 0:
    sys.exit("No new sessions, check the k6 log")
per_session = (heap_after - heap_before) / new_sessions
print(f"\nmemory per session:        {per_session / 1024:7.0f} KB  ({new_sessions} new sessions)")
print(f"live heap without sessions: {mb(heap_before - sessions_before * per_session):6.1f} MB")
EOF
echo "Results: $results"
