#!/usr/bin/env bash
# Measures the memory of one user session in the running container (see README.md):
#
#   1. warmup: WARMUP_USERS (default: USERS) run the scenario once, so one-time costs (class loading,
#      caches) are not counted as session memory; then all sessions are invalidated (DELETE
#      /actuator/sessions)
#   2. heap dump "before" (baseline, no sessions); the dump forces a full GC, so the heap then holds
#      only live objects
#   3. USERS virtual users run the scenario once each; every user leaves one session with one UI
#      (one browser tab) behind, which stays in memory until the session timeout (30 minutes)
#   4. heap dump "after"
#   5. unless CHECK_BASELINE=false: all sessions are invalidated again and heap dump "cleared";
#      it should be about the baseline, otherwise the load built up one-time costs (caches) that
#      the warmup did not and that are counted as session memory
#
# Memory per session = (live heap after - live heap before) / sessions after
# CPU per run         = JVM CPU time used during step 3 / sessions after
#
# During step 3, without forcing GCs, the memory under load is recorded: the heap after every GC
# (GC log of the JVM, -Xlog:gc in compose.yaml) and the memory of the container (docker stats,
# about every 2 s). It is what the app needs while USERS users are active at the same time, see
# "Project the server size" in README.md:
#
# Extra heap per active user = (peak heap after GC - live heap after) / USERS
#
# The CPU per run is the basis for the number of cores, see loadtest/measure-cpu-capacity.sh.
#
#   loadtest/measure-session-size.sh                 # 100 users
#   USERS=200 loadtest/measure-session-size.sh lazy  # the label is added to the result directory
#   KEEP_DUMPS=false loadtest/measure-session-size.sh
#   CHECK_BASELINE=false loadtest/measure-session-size.sh  # without step 5
#
# The results are written to results/<timestamp>[-label]/: summary.txt, the heap dumps
# before.hprof and after.hprof (open them in VisualVM or Eclipse MAT), the k6 logs and the memory
# under load: gc.csv (one line per GC) and container.csv (container memory over time).
set -euo pipefail

USERS="${USERS:-100}"
WARMUP_USERS="${WARMUP_USERS:-$USERS}"
KEEP_DUMPS="${KEEP_DUMPS:-true}"
CHECK_BASELINE="${CHECK_BASELINE:-true}"
APP_IP="${APP_IP:-localhost}"
APP_PORT="${APP_PORT:-8080}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-8081}"
CONTAINER="${CONTAINER:-sizing-app}"
label="${1:-}"

cd "$(dirname "$0")/.."
actuator="http://127.0.0.1:$MANAGEMENT_PORT/actuator"
recordings=src/test/k6/recordings

curl -sf "$actuator/health" >/dev/null \
    || { echo "The app is not running, start it with loadtest/rebuild.sh" >&2; exit 1; }
ls "$recordings"/*.js >/dev/null 2>&1 \
    || { echo "No k6 script in $recordings, record it with loadtest/rebuild.sh" >&2; exit 1; }

docker logs "$CONTAINER" 2>&1 | grep '\]\[gc' >/dev/null \
    || echo "Warning: no GC log in the container, the heap under load is not measured." \
            "Restart the container with the current compose.yaml: docker compose up -d" >&2

results="results/$(date +%Y%m%d-%H%M%S)${label:+-$label}"
mkdir -p "$results"
trap 'kill $(jobs -p) 2>/dev/null || true' EXIT # stops sample_container if the script fails

metric() { # prints the value of an Actuator metric, e.g. metric jvm.memory.used area:heap
    curl -sf "$actuator/metrics/$1${2:+?tag=$2}" \
        | python3 -c 'import json, sys; print(int(json.load(sys.stdin)["measurements"][0]["value"]))'
}

uptime() { # prints the JVM uptime in seconds with decimals, the time stamp of the GC log lines
    curl -sf "$actuator/metrics/process.uptime" \
        | python3 -c 'import json, sys; print(json.load(sys.stdin)["measurements"][0]["value"])'
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

ui_count() { # prints the number of UIs, from the gauges of testbench-loadtest-support
    # vaadin.view.count without tag sums the UI gauge and the per-view gauges (tag view), which
    # count navigations rather than views, so the per-view values are subtracted
    python3 - "$actuator/metrics/vaadin.view.count" <<'EOF'
import json, sys, urllib.parse, urllib.request
get = lambda url: json.load(urllib.request.urlopen(url))
total = get(sys.argv[1])
views = next((t["values"] for t in total["availableTags"] if t["tag"] == "view"), [])
value = lambda metric: metric["measurements"][0]["value"]
print(int(value(total) - sum(value(get(f"{sys.argv[1]}?tag=view:{urllib.parse.quote(v)}")) for v in views)))
EOF
}

snapshot() { # heap dump (full GC), then live heap, sessions and UIs; sets heap_, sessions_, uis_<name>
    local file="$results/$1.hprof" heap sessions uis
    [[ "$KEEP_DUMPS" == true ]] || file=/dev/null
    check_app "before heap dump '$1'"
    curl -sf -o "$file" "$actuator/heapdump?live=true" \
        || { check_app "heap dump '$1' failed"; echo "Heap dump '$1' failed" >&2; exit 1; }
    heap="$(metric jvm.memory.used area:heap)" && sessions="$(metric tomcat.sessions.active.current)" \
        || { check_app "reading metrics after heap dump '$1'"; echo "Reading the metrics failed" >&2; exit 1; }
    printf -v "heap_$1" '%s' "$heap"
    printf -v "sessions_$1" '%s' "$sessions"
    uis="$(ui_count 2>/dev/null)" || uis=-1
    printf -v "uis_$1" '%s' "$uis"
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

invalidate_sessions() { # ends all HTTP sessions and with them the Vaadin sessions and UIs
    curl -sf -X DELETE "$actuator/sessions" >/dev/null || {
        check_app "invalidating the sessions"
        echo "Invalidating the sessions failed. The endpoint is new, rebuild the image: loadtest/rebuild.sh" >&2
        exit 1
    }
}

sample_container() { # prints "<epoch seconds> <docker MemUsage>" until killed, about every 2 s
    while :; do
        printf '%s %s\n' "$(date +%s)" \
            "$(docker stats --no-stream --format '{{.MemUsage}}' "$CONTAINER" 2>/dev/null)" || sleep 1
    done
}

start_time="$(metric process.start.time)" # to detect restarts, see check_app

echo "Warmup: $WARMUP_USERS users"
k6run warmup "$WARMUP_USERS" -Dk6.failOnThreshold=false -Dk6.managementPort=-1

echo "Invalidating the warmup sessions, heap dump before"
invalidate_sessions
snapshot before

echo "Load: $USERS users"
cpu_before="$(metric process.cpu.time)" # ns; read outside the heap dumps, whose full GC costs CPU
allocated_before="$(metric jvm.gc.memory.allocated)"
load_start="$(date +%s)"
uptime_start="$(uptime)"
sample_container > "$results/container-samples.txt" &
sampler=$!
k6run load "$USERS" -Dk6.managementPort="$MANAGEMENT_PORT"
kill "$sampler"
load_end="$(date +%s)"
uptime_end="$(uptime)"
cpu_after="$(metric process.cpu.time)"
allocated_after="$(metric jvm.gc.memory.allocated)"
heap_committed="$(metric jvm.memory.committed area:heap)"
nonheap="$(metric jvm.memory.used area:nonheap)"
container_limit="$(docker inspect --format '{{.HostConfig.Memory}}' "$CONTAINER")"
# The GCs of the load phase; docker logs only filters by second, so gc_line also checks the uptime
docker logs --since "$load_start" --until "$((load_end + 1))" "$CONTAINER" 2>&1 \
    | grep '\]\[gc' > "$results/gc.log" || true

echo "Heap dump after"
snapshot after

heap_cleared=-1 sessions_cleared=-1 uis_cleared=-1
if [[ "$CHECK_BASELINE" == true ]]; then
    echo "Invalidating the sessions, heap dump cleared"
    invalidate_sessions
    snapshot cleared
fi

python3 - "$results" "$USERS" "$heap_before" "$sessions_before" "$heap_after" "$sessions_after" \
    "$cpu_before" "$cpu_after" "$allocated_before" "$allocated_after" "$load_start" "$load_end" \
    "$heap_committed" "$nonheap" "$container_limit" "$heap_cleared" "$sessions_cleared" "$uptime_start" "$uptime_end" \
    "$uis_before" "$uis_after" "$uis_cleared" \
    <<'EOF' | tee "$results/summary.txt"
import re, sys
results = sys.argv[1]
(users, heap_before, sessions_before, heap_after, sessions_after, cpu_before, cpu_after,
 allocated_before, allocated_after, load_start, load_end, heap_committed, nonheap,
 container_limit, heap_cleared, sessions_cleared) = map(int, sys.argv[2:-5])
uptime_start, uptime_end = map(float, sys.argv[-5:-3])
uis_before, uis_after, uis_cleared = map(int, sys.argv[-3:])
mb = lambda b: b / 1024 / 1024
units = {"B": 1, "K": 1024, "KIB": 1024, "KB": 1000, "M": 1024**2, "MIB": 1024**2, "MB": 1000**2,
         "G": 1024**3, "GIB": 1024**3, "GB": 1000**3}
size = lambda value, unit: float(value) * units[unit.upper()]

new_sessions = sessions_after - sessions_before
print(f"                 live heap   sessions        UIs")
print(f"before        {mb(heap_before):9.1f} MB {sessions_before:10d} {uis_before:10d}")
print(f"after         {mb(heap_after):9.1f} MB {sessions_after:10d} {uis_after:10d}")
if heap_cleared >= 0:
    print(f"cleared       {mb(heap_cleared):9.1f} MB {sessions_cleared:10d} {uis_cleared:10d}")
if sessions_before or sessions_cleared > 0:
    print("Warning: sessions left after invalidating them, the baseline is not without sessions")
if uis_before > 0 or uis_cleared > 0:
    print("Warning: UIs left after invalidating the sessions, they are counted in the baseline")
if uis_after - max(uis_before, 0) != sessions_after - sessions_before:
    print("Warning: not one UI per new session (-1: UI count not available), the memory per session"
          " is not the memory of one user with one browser tab")
if new_sessions <= 0:
    sys.exit("No new sessions, check the k6 log")
per_session = (heap_after - heap_before) / new_sessions
print(f"\nmemory per session:        {per_session / 1024:7.0f} KB  ({new_sessions} new sessions)")
print(f"live heap without sessions: {mb(heap_before):6.1f} MB  (baseline)")
if heap_cleared >= 0:
    growth = heap_cleared - heap_before
    print(f"baseline growth by load:   {mb(growth):7.1f} MB  (cleared - before; {growth / new_sessions / 1024:.0f} KB of the memory per session)")
print(f"CPU per run:               {(cpu_after - cpu_before) / new_sessions / 1e6:7.1f} ms  (JVM CPU time, incl. GC)")

# GC log, e.g. "[12.3s][info][gc] GC(7) Pause Young (Allocation Failure) 210M->65M(742M) 4.1ms"
gc_line = re.compile(r"\[([\d.]+)s\].*GC\(\d+\) (Pause \w+).*? (\d+)([KMG])->(\d+)([KMG])\((\d+)([KMG])\) ([\d.]+)ms")
gcs = []  # (type, heap after, pause ms)
with open(f"{results}/gc.log") as log, open(f"{results}/gc.csv", "w") as out:
    out.write("uptime_s,type,heap_before_mb,heap_after_mb,heap_capacity_mb,pause_ms\n")
    for m in filter(None, map(gc_line.search, log)):
        if not uptime_start <= float(m[1]) <= uptime_end:
            continue  # e.g. the full GC of heap dump "before" in the same second
        before, after, capacity = size(m[3], m[4]), size(m[5], m[6]), size(m[7], m[8])
        gcs.append((m[2], after, float(m[9])))
        out.write(f"{m[1]},{m[2]},{mb(before):.1f},{mb(after):.1f},{mb(capacity):.1f},{m[9]}\n")

container = []  # docker stats MemUsage, e.g. "1759999999 612.3MiB / 1GiB"
with open(f"{results}/container-samples.txt") as samples, open(f"{results}/container.csv", "w") as out:
    out.write("time_s,memory_mb\n")
    for m in filter(None, (re.match(r"(\d+) ([\d.]+)([A-Za-z]+) /", line) for line in samples)):
        container.append(size(m[2], m[3]))
        out.write(f"{int(m[1]) - load_start},{mb(container[-1]):.1f}\n")

duration = max(load_end - load_start, 1)
allocated = allocated_after - allocated_before
print(f"\nunder load ({users} users at the same time, {duration} s)")
if gcs:
    peak = max(after for _, after, _ in gcs)
    pauses = [pause for _, _, pause in gcs]
    full = sum(kind == "Pause Full" for kind, _, _ in gcs)
    print(f"peak heap after GC:        {mb(peak):7.1f} MB  (upper bound of the live heap under load)")
    print(f"extra heap per active user:{max(peak - heap_after, 0) / users / 1024:7.0f} KB  ((peak heap after GC - live heap after) / users)")
    print(f"GCs:                       {len(gcs):7d}     ({full} full, {sum(pauses):.0f} ms pauses in total, max {max(pauses):.0f} ms)")
else:
    print("peak heap after GC:            n/a  (no GC log or no GC during the load, see gc.log)")
print(f"allocated per run:         {mb(allocated / new_sessions):7.1f} MB  ({mb(allocated / duration):.0f} MB/s)")
print(f"heap committed:            {mb(heap_committed):7.1f} MB")
print(f"non-heap used:             {mb(nonheap):7.1f} MB  (metaspace, code cache)")
if container:
    limit = f" of {mb(container_limit):.0f} MB limit" if container_limit else ""
    print(f"peak container memory:     {mb(max(container)):7.1f} MB{limit}")
else:
    print("peak container memory:         n/a  (docker stats failed, see container-samples.txt)")
EOF
echo "Results: $results"
