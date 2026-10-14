#!/usr/bin/env bash
# Measures the memory of one user session in the running container (see README.md):
#
#   1. warmup: WARMUP_USERS (default: USERS) run the scenario once, so one-time costs (class loading,
#      caches) are not counted as session memory; then all sessions are invalidated (DELETE
#      /actuator/sessions)
#   2. class histogram "before" (baseline, no sessions)
#   3. USERS virtual users run the scenario once each; every user leaves one session with one UI
#      (one browser tab) behind, which stays in memory until the session timeout (30 minutes)
#   4. class histogram "after"
#   5. only with CHECK_BASELINE=true: all sessions are invalidated again and class histogram
#      "cleared"; it should be about the baseline, otherwise the load built up one-time costs
#      (caches) that the warmup did not and that are counted as session memory
#
# A class histogram (jcmd GC.class_histogram) runs a full GC and sums the size of all reachable
# objects per class: the live heap. The image is a JRE without jcmd, so jcmd runs in a JDK container
# (JCMD_IMAGE) that shares the PID namespace of the app. Before every histogram, the script runs a
# GC and waits IDLE_WAIT seconds (default 65): objects with a Cleaner (e.g. ZipFileSystem and its
# buffers) are freed only by the GC after the one that found them unreachable, and Tomcat ends
# worker threads that have been idle for 60 s, with about 100 KB of buffers each. Without this,
# they are in some histograms and not in others.
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
#   CHECK_BASELINE=true loadtest/measure-session-size.sh   # with step 5
#
# Without Docker, against an app started locally with java -jar: LOCAL_PID is its process ID and
# GC_LOG its GC log file. jcmd of the local JDK is used, and the RSS of the process (ps) replaces
# the container memory. Start the JVM like in compose.yaml (the serial GC, which the JVM picks in
# the container, a heap of 75 % of its 1 GB and an initial heap of 1/64 of it; without -Xms, the
# initial heap is 1/64 of the RAM of the machine, with fewer GCs under load), otherwise the results
# are not comparable:
#
#   java -XX:+UseSerialGC -Xms16m -Xmx768m -XX:MarkSweepAlwaysCompactCount=1 -Xlog:gc:file=gc.log \
#       -jar target/vaadin-sizing-demo-1.0-SNAPSHOT.jar
#   LOCAL_PID=$(pgrep -f vaadin-sizing-demo-1.0-SNAPSHOT.jar) GC_LOG=gc.log \
#       loadtest/measure-session-size.sh local
#
# Without a CPU limit and with k6 on the same machine, the CPU per run differs from the container.
#
# The results are written to results/<timestamp>[-label]/: summary.txt, the class histograms
# before.txt, after.txt and cleared.txt, the k6 logs and the memory under load: gc.csv (one line
# per GC) and container.csv (container memory over time).
set -euo pipefail

USERS="${USERS:-100}"
WARMUP_USERS="${WARMUP_USERS:-$USERS}"
CHECK_BASELINE="${CHECK_BASELINE:-false}"
IDLE_WAIT="${IDLE_WAIT:-65}"
JCMD_IMAGE="${JCMD_IMAGE:-eclipse-temurin:25-jdk-alpine}"
APP_IP="${APP_IP:-localhost}"
APP_PORT="${APP_PORT:-8080}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-8081}"
CONTAINER="${CONTAINER:-sizing-app}"
export LOCAL_PID="${LOCAL_PID:-}" # also read by the summary
GC_LOG="${GC_LOG:-}"
label="${1:-}"

cd "$(dirname "$0")/.."
actuator="http://127.0.0.1:$MANAGEMENT_PORT/actuator"
recordings=src/test/k6/recordings

curl -sf "$actuator/health" >/dev/null \
    || { echo "The app is not running, start it with loadtest/rebuild.sh" >&2; exit 1; }
# The Actuator can be reachable while the app port is not, e.g. if another process held the port
# when the container started; then every k6 request fails and no session is created
curl -s -o /dev/null "http://$APP_IP:$APP_PORT/" \
    || { echo "The app port $APP_IP:$APP_PORT is not reachable (the Actuator is). Is another app" \
              "using the port? Restart the container: docker compose up -d --force-recreate" >&2; exit 1; }
ls "$recordings"/*.js >/dev/null 2>&1 \
    || { echo "No k6 script in $recordings, record it with loadtest/rebuild.sh" >&2; exit 1; }

if [[ -n "$LOCAL_PID" ]]; then
    kill -0 "$LOCAL_PID" 2>/dev/null || { echo "No process with LOCAL_PID $LOCAL_PID" >&2; exit 1; }
    command jcmd "$LOCAL_PID" VM.flags 2>/dev/null | grep -q -- '-XX:+UseSerialGC' \
        || echo "Warning: the app does not use the serial GC like in the container, see the start" \
                "command at the top of this script" >&2
    grep -q '\]\[gc' "${GC_LOG:-/dev/null}" 2>/dev/null \
        || echo "Warning: no GC log in GC_LOG, the heap under load is not measured. Start the app" \
                "with -Xlog:gc:file=gc.log and run this script with GC_LOG=gc.log" >&2
else
    docker logs "$CONTAINER" 2>&1 | grep '\]\[gc' >/dev/null \
        || echo "Warning: no GC log in the container, the heap under load is not measured." \
                "Restart the container with the current compose.yaml: docker compose up -d" >&2
fi

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
        if [[ -n "$LOCAL_PID" ]]; then
            echo "  the console output of the app (OutOfMemoryError?)" >&2
        else
            echo "  docker compose logs app; docker events --since 30m --until 0s --filter container=sizing-app" >&2
        fi
        exit 1
    }
    [[ "$now" == "$start_time" ]] || {
        [[ -n "$LOCAL_PID" ]] && { echo "The app was restarted ($1), the measurement is invalid" >&2; exit 1; }
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

jcmd() { # runs a jcmd command in the app, which is PID 1 in its container, or LOCAL_PID
    if [[ -n "$LOCAL_PID" ]]; then
        command jcmd "$LOCAL_PID" "$@"
    else
        docker run --rm --pid="container:$CONTAINER" "$JCMD_IMAGE" jcmd 1 "$@"
    fi
}

snapshot() { # class histogram (full GC), then sessions and UIs; sets heap_, sessions_, uis_<name>
    local file="$results/$1.txt" heap sessions uis
    jcmd GC.run > /dev/null # the Cleaners run during the wait, see above
    sleep "$IDLE_WAIT" # until Tomcat has ended the idle worker threads
    check_app "before class histogram '$1'"
    jcmd GC.class_histogram > "$file" 2>&1 \
        && heap="$(awk '$1 == "Total" { print $3 }' "$file")" && [[ -n "$heap" ]] \
        || { check_app "class histogram '$1'"; echo "Class histogram '$1' failed, see $file" >&2; exit 1; }
    sessions="$(metric tomcat.sessions.active.current)" \
        || { check_app "reading metrics after class histogram '$1'"; echo "Reading the metrics failed" >&2; exit 1; }
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
        if [[ -n "$LOCAL_PID" ]]; then # RSS of the process in the format of docker stats
            printf '%s %sKiB / -\n' "$(date +%s)" "$(ps -o rss= -p "$LOCAL_PID" | tr -d ' ')"
            sleep 2
        else
            printf '%s %s\n' "$(date +%s)" \
                "$(docker stats --no-stream --format '{{.MemUsage}}' "$CONTAINER" 2>/dev/null)" || sleep 1
        fi
    done
}

start_time="$(metric process.start.time)" # to detect restarts, see check_app

echo "Warmup: $WARMUP_USERS users"
k6run warmup "$WARMUP_USERS" -Dk6.failOnThreshold=false -Dk6.managementPort=-1

echo "Invalidating the warmup sessions, class histogram before (in ${IDLE_WAIT} s)"
invalidate_sessions
snapshot before

echo "Load: $USERS users"
cpu_before="$(metric process.cpu.time)" # ns; read outside the class histograms, whose full GC costs CPU
allocated_before="$(metric jvm.gc.memory.allocated)"
load_start="$(date +%s)"
uptime_start="$(uptime)"
sample_container > "$results/container-samples.txt" &
sampler=$!
k6run load "$USERS" -Dk6.managementPort="$MANAGEMENT_PORT"
kill "$sampler"
# k6 passes even if every request failed, as the thresholds only check durations
[[ "$(metric tomcat.sessions.active.current)" -gt 0 ]] \
    || { echo "No sessions after the load, the k6 requests failed, see $results/load.log" >&2; exit 1; }
load_end="$(date +%s)"
uptime_end="$(uptime)"
cpu_after="$(metric process.cpu.time)"
allocated_after="$(metric jvm.gc.memory.allocated)"
heap_committed="$(metric jvm.memory.committed area:heap)"
nonheap="$(metric jvm.memory.used area:nonheap)"
if [[ -n "$LOCAL_PID" ]]; then
    container_limit=0
    # The whole GC log; gc_line keeps the GCs of the load phase by their uptime
    grep -h '\]\[gc' "${GC_LOG:-/dev/null}" > "$results/gc.log" 2>/dev/null || true
else
    container_limit="$(docker inspect --format '{{.HostConfig.Memory}}' "$CONTAINER")"
    # The GCs of the load phase; docker logs only filters by second, so gc_line also checks the uptime
    docker logs --since "$load_start" --until "$((load_end + 1))" "$CONTAINER" 2>&1 \
        | grep '\]\[gc' > "$results/gc.log" || true
fi

echo "Class histogram after (in ${IDLE_WAIT} s)"
snapshot after

heap_cleared=-1 sessions_cleared=-1 uis_cleared=-1
if [[ "$CHECK_BASELINE" == true ]]; then
    echo "Invalidating the sessions, class histogram cleared (in ${IDLE_WAIT} s)"
    invalidate_sessions
    snapshot cleared
fi

python3 - "$results" "$USERS" "$heap_before" "$sessions_before" "$heap_after" "$sessions_after" \
    "$cpu_before" "$cpu_after" "$allocated_before" "$allocated_after" "$load_start" "$load_end" \
    "$heap_committed" "$nonheap" "$container_limit" "$heap_cleared" "$sessions_cleared" "$uptime_start" "$uptime_end" \
    "$uis_before" "$uis_after" "$uis_cleared" \
    <<'EOF' | tee "$results/summary.txt"
import os, re, sys
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
    print("No new sessions, check the k6 log"); sys.exit(1)
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
            continue  # e.g. the full GC of class histogram "before" in the same second
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
local = bool(os.environ.get("LOCAL_PID"))
if container:
    limit = f" of {mb(container_limit):.0f} MB limit" if container_limit else ""
    print(f"peak {'process RSS:      ' if local else 'container memory:'}     {mb(max(container)):7.1f} MB{limit}")
else:
    print(f"peak {'process RSS:      ' if local else 'container memory:'}         n/a"
          f"  ({'ps' if local else 'docker stats'} failed, see container-samples.txt)")

def histogram(name):  # {class: (instances, bytes)}
    with open(f"{results}/{name}.txt") as file:
        return {m[3]: (int(m[1]), int(m[2]))
                for m in (re.match(r"\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)", line) for line in file) if m}

before, after = histogram("before"), histogram("after")
fillers = max(sum(size_ for c, (_, size_) in h.items() if c.startswith("[Ljdk.internal.vm.Filler")) for h in (before, after))
if fillers > 1024 * 1024:
    print(f"\nWarning: {mb(fillers):.1f} MB of filler objects (dead space) counted as live heap, the heap"
          " was not compacted completely; start the container with the current compose.yaml")
growth = sorted(((size_ - before.get(c, (0, 0))[1], count - before.get(c, (0, 0))[0], c)
                 for c, (count, size_) in after.items()), reverse=True)
print(f"\nclasses that grew most per session:  bytes  instances")
for size_, count, name in growth[:15]:
    print(f"  {size_ / new_sessions:10.0f} B {count / new_sessions:9.1f}  {name}")
EOF
echo "Results: $results"
