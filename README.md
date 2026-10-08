# vaadin-sizing-demo

How much memory does one user session of a Vaadin app need, and how large must the server be for
the expected number of users? This project measures the session size of a small **Vaadin Flow +
Spring Boot** CRUD app under load and projects it onto a server size.

1. **Measure:** k6 virtual users replay a recorded browser scenario against the app in a Docker
   container; heap dumps before and after give the memory per session.
2. **Project:** memory per session × concurrent sessions + fixed JVM costs = server memory.

## Tech Stack

* **Java 25**, **Spring Boot 4.1**, **Vaadin Flow 25.3** (Aura theme)
* **Spring Data JPA** with an in-memory **H2** database (1,000 sample persons)
* **Spring Boot Actuator** for heap dumps and metrics
* **Vaadin TestBench** for browser tests, Vaadin's **k6** tooling for load tests
* **Docker** for the app with a memory limit

## The app

A single view at `/` (alias of `/crud-example[/<id>/edit]`): a lazy-loading grid of persons
(`PersonGrid`) and an editor form (`PersonForm`) in a split layout. A saved person stays selected;
**Delete** asks for confirmation.

```bash
./mvnw                  # development mode, http://localhost:8080
./mvnw -Pit verify      # browser tests (TestBench, needs Chrome)
```

## 1. Measure the session size

Prerequisites: Docker, [k6](https://grafana.com/docs/k6/latest/get-started/installation/)
(`brew install k6`) and Chrome.

```bash
loadtest/rebuild.sh                 # build the image, (re)start the container, record the k6 script
loadtest/measure-session-size.sh    # warmup, heap dump, 100 users, heap dump
```

After a code change, run both again: `rebuild.sh` builds the new code and records the scenario
again, because the k6 script must always match the build under test. Add a label to tell the
results apart, e.g. `loadtest/measure-session-size.sh in-memory`.

### The scenario

`EditPersonScenario` is one user in one browser tab: open the view, scroll to the middle of the grid, create a person, change a
random field, save, delete the person. `loadtest:record` (`testbench-converter-plugin`) runs it
once in Chrome through a recording proxy and turns the traffic into the k6 script `src/test/k6/recordings/edit-person.js`. k6 replays it with any number of virtual users;
every virtual user leaves exactly **one session with one UI** behind.

### How the measurement works

`measure-session-size.sh` (options: `USERS`, `WARMUP_USERS`, `KEEP_DUMPS`, `CHECK_BASELINE`):

1. **Warmup:** `WARMUP_USERS` users (default: `USERS`) run the scenario once. The first requests load classes and
   fill caches, and Tomcat creates buffers for every concurrent connection. None of this is
   session memory, so it must be in the heap before the first measurement. Then all sessions are
   invalidated (`DELETE /actuator/sessions`, which ends the Vaadin sessions and UIs, too; only in
   builds with the `loadtest` profile).
2. **Heap dump before** (`/actuator/heapdump`): the baseline without sessions. The dump forces a
   full GC, so afterwards the heap only holds live objects.
3. **Load:** `USERS` virtual users run the scenario once each.
4. **Heap dump after.**
5. **Baseline check** (off with `CHECK_BASELINE=false`): invalidate all sessions again, heap dump
   `cleared`. It should be about the baseline; if it is higher, the load built up one-time costs
   that the warmup did not, and they are counted as session memory.

Besides the sessions, `summary.txt` shows the UIs (from the gauges of `testbench-loadtest-support`)
and warns unless every new session has exactly one UI and no UIs are left after invalidating.
The per-view gauge (`vaadin.view.count` with tag `view`) counts navigations, not views (3 per UI
in this scenario), so the script subtracts it from the total.

```
memory per session = (live heap after − live heap before) / sessions after
```

Result (100 users, `results/<timestamp>/summary.txt`), on a freshly started container:

```
                 live heap   sessions
before             53.4 MB          0
after              76.4 MB        100
cleared            52.7 MB          0

memory per session:            235 KB  (100 new sessions)
live heap without sessions:   53.4 MB  (baseline)
baseline growth by load:      -0.7 MB  (cleared - before; -7 KB of the memory per session)
```

Six runs, each on a freshly started container, gave 233, 234, 234, 235, 238 and 239 KB per
session; the baseline growth stayed between −0.9 and −0.2 MB.

The warmup must be as large as the load: with only 20 warmup users, the baseline grew by 7.6 MB
during the load, and the memory per session came out at 322 KB instead of about 240 KB (a second
run on the then warm app gave 248 KB). If the baseline growth in `summary.txt` is more than a few
KB per session, raise `WARMUP_USERS`.

The heap dumps `before.hprof` and `after.hprof` stay in the result directory. In VisualVM or
Eclipse MAT, the **retained size of one `com.vaadin.flow.component.UI`** is the component tree and
data of one tab, here **186 KB**; the rest of the measured value is the session itself (Tomcat
and Vaadin session, a few KB) and some noise. The retained size of `VaadinSession`
is useless here (2 KB): `testbench-loadtest-support` references every UI from a global object, so
the UIs are no longer "owned" by their session.

### Memory under load

The session size is what stays in memory between the requests; it does not grow with the load.
While users are active, the app needs more: objects of running requests and sessions in the middle
of the scenario. `jvm.memory.used` does not show this, as it is mostly garbage and depends on
when the last GC ran. During step 3, without forcing GCs, the script therefore records:

- **the heap after every GC** from the GC log of the JVM (`-Xlog:gc` in `compose.yaml`), in
  `gc.csv`. Its peak is the live heap under load, as an upper bound: after a young GC, the old
  generation may still hold dead objects.
- **the container memory** (`docker stats`, about every 2 s), in `container.csv`. This is what the
  memory limit (and the kernel's OOM kill) applies to.
- **the allocation rate** (`jvm.gc.memory.allocated`): how fast garbage is produced, i.e. how often
  the GC has to run.

```
extra heap per active user = (peak heap after GC − live heap after) / USERS
```

In `summary.txt`, below the session size:

```
under load (100 users at the same time, 21 s)
peak heap after GC:          100.0 MB  (upper bound of the live heap under load)
extra heap per active user:    237 KB  ((peak heap after GC - live heap after) / users)
GCs:                            25     (0 full, 252 ms pauses in total, max 18 ms)
allocated per run:            16.0 MB  (76 MB/s)
heap committed:              232.4 MB
non-heap used:               165.8 MB  (metaspace, code cache)
peak container memory:       521.8 MB of 1024 MB limit
```

In six runs, the extra heap per active user was 180–260 KB and the peak container memory
518–533 MB. No full GC ran during the load, so the extra heap is an upper bound. All `USERS` start
at the same time, so they are all active during the run; the values depend on the pace of the
scenario (think times in the k6 script).

### Things to know

- **The sessions stay in memory.** The session timeout is Spring Boot's default of 30 minutes, so
  the number of sessions in the heap is exactly known.
- **Take the dumps soon after the run.** k6 does not send heartbeats; Vaadin removes a UI after
  three missed heartbeats (about 15 minutes by default), and the session would look smaller.
- **The heap must be compacted completely.** For small containers the JVM picks the serial GC,
  whose full GC leaves up to 5 % of dead objects in the heap and compacts completely only every
  4th time. The dead objects count as used heap and add up to ±80 KB per session of noise, so
  `compose.yaml` sets `-XX:MarkSweepAlwaysCompactCount=1`.
- **It is the state at the end of the scenario.** A user in the middle of their work (open dialog,
  filled form) holds somewhat more. Scenarios should end in a typical state.
- **The code decides.** The same view with the grid filled in memory (`setItems(list)`, all 1,000
  persons) and a component column instead of the `LitRenderer`:

  | Grid | Retained size of one UI |
  |---|---|
  | lazy loading + `LitRenderer` (this code) | 169 KB |
  | all items in memory + component column | 991 KB |

## 2. Project the server size

```
container memory = non-heap + native + max heap
max heap        ≥ (live heap without sessions
                   + concurrent sessions × memory per session
                   + active users × extra heap per active user) × 2
```

| Part | Value (measured here) | Note |
|---|---|---|
| non-heap (metaspace, code cache) | ~160 MB | `summary.txt` (non-heap used); grows with the amount of code, not with users |
| native (threads, GC, buffers) | ~100 MB | |
| live heap without sessions | ~50 MB | `summary.txt` |
| memory per session | ~240 KB | `summary.txt` |
| extra heap per active user | | `summary.txt` (under load) |
| factor 2 | | headroom, so the GC does not run constantly |

**Concurrent sessions** (Little's law): new sessions per minute × (minutes in the app + session
timeout). A session lives on for the whole timeout after the user left. **Active users** are only
those in the app right now: new sessions per minute × minutes in the app.

Example: 20 logins per minute, 15 minutes in the app, 30 minutes timeout, and an assumed 0.3 MB
extra heap per active user:

```
sessions     = 20 × (15 + 30)                            =   900
active users = 20 × 15                                   =   300
max heap     = (50 MB + 900 × 0.24 MB + 300 × 0.3 MB) × 2 ≈   710 MB
container    = 160 MB + 100 MB + 710 MB                  ≈   970 MB  → 1.5 GB, heap limited to ~750 MB
```

### Check the projection under load

The projection is a model; a load test with the projected container confirms it. Start the
container with the projected limits and run as many users as will be active at the same time:

```bash
APP_MEMORY=1536m APP_MAX_RAM_PERCENTAGE=50 docker compose up -d
USERS=300 loadtest/measure-session-size.sh check-1.5g
```

The projection holds if, in `summary.txt` (under load):

- **peak container memory** stays clearly below the limit (otherwise more memory besides the heap:
  lower `APP_MAX_RAM_PERCENTAGE` or raise `APP_MEMORY`),
- **peak heap after GC** is at most about half of the max heap (`APP_MEMORY` × `APP_MAX_RAM_PERCENTAGE`),
- there are **no or only few full GCs** and no long pauses (`gc.csv`); many full GCs mean the heap
  is too small for the load, even if the app does not crash.

`container.csv` and `gc.csv` show the course over time, e.g. to plot them.

The JVM does not size the heap on its own terms: in a container it takes `MaxRAMPercentage` of
the memory limit (default 25 %, 75 % in `compose.yaml`). Too little room besides the heap and the
kernel kills the container (exit code 137), e.g. at 512 MB and 75 %:

```bash
APP_MEMORY=512m APP_MAX_RAM_PERCENTAGE=75 docker compose up -d
```

## 3. Measure the CPU

The memory measurement only gives the **CPU per run**: the JVM CPU time (`process.cpu.time`, incl.
GC) during the load phase divided by the number of runs, in `summary.txt`. How many runs per
minute the app handles before it slows down is a separate test:

```bash
loadtest/measure-cpu-capacity.sh                                  # 2 cores
APP_CPUS=1 RATES="30 60 90 120" loadtest/measure-cpu-capacity.sh 1-core
```

It restarts the container with `APP_CPUS` cores and a session timeout of 2 minutes (otherwise
the sessions of the finished runs fill the heap) and runs one k6 step per rate in `RATES`. In
every step, k6 starts the scenario at a fixed rate (runs per minute, `constant-arrival-rate`),
no matter how fast the app answers, like real users. The test stops after the first step in which
the app is saturated: p95 above `P95_LIMIT` (500 ms), more than 1 % failed requests or checks,
or runs that k6 could not start because all virtual users were busy.

Result (`results/<timestamp>-cpu/summary.txt`):

```
runs/min  completed  dropped  failed    p95 ms  CPU/run ms  cores used  utilization
      30         16        0   0.0%        26       384.8        0.19           2%
     240        120        0   0.0%        13       175.2        0.70           6%
    2000       1000        0   0.0%         5        51.8        1.73          14%
```

(A short test run with 30 s steps on 12 cores, not a reference measurement.)

**cores used** = CPU per run × runs per second. At low rates, the CPU per run is too high: the
JIT compiler, the GC and the metrics polling need CPU even without users, and it is spread over
few runs. The value at high rates is the one that counts per user.

**Project the cores:**

```
cores = CPU per run × runs per second at peak / target utilization (0.5–0.7)
```

Example: 20 runs per second × 0.05 s = 1 core, at 60 % utilization → 2 cores. The runs per
second come from production: user actions per minute × concurrently active users. The scenario
must match what users do; the more typical it is, the better the projection.

Things to know:

- **The saturation point confirms the formula.** If the app slows down well below 100 %
  utilization, something else limits it (locks, database connections, GC).
- **The GC depends on the cores.** With fewer than 2 CPUs or less than ~1.8 GB of memory, the JVM
  picks the serial GC, otherwise G1. Measure with the limits of production.
- **The H2 database runs in the app** and its CPU counts as app CPU. With a separate database in
  production, the app needs less CPU.
- **k6 needs CPU, too.** On the same machine, k6 competes with the app at high rates; run it on
  another machine or make sure the machine has more cores than `APP_CPUS`.

## Project structure

```
src/main/java/org/vaadin/demo/sizing
 ├─ Application.java        # Spring Boot entry point, app shell
 └─ app
     ├─ data/...            # entities (SamplePerson, Skill) and repositories
     ├─ service/...         # SamplePersonService
     └─ ui/crud/...         # the view (PersonGrid + PersonForm)
src/test/java/.../it/       # TestBench browser tests
src/test/java/.../load/     # EditPersonScenario, the basis of the load test
src/test/k6/recordings/     # recorded k6 script and its test data
loadtest/                   # rebuild.sh, measure-session-size.sh, measure-cpu-capacity.sh
Dockerfile, compose.yaml    # the app in a container with memory and CPU limits
```

The Docker image is built with the `loadtest` Maven profile, which adds
`testbench-loadtest-support` to the app. Never deploy it to production.

## License

Public domain (Unlicense) – see [LICENSE.md](LICENSE.md).
