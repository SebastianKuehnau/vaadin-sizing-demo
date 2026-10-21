# vaadin-sizing-demo

How much memory does one user session of a Vaadin app need, and how large must the server be for
the expected number of users? This project measures the session size of a small **Vaadin Flow +
Spring Boot** CRUD app under load and projects it onto a server size.

1. **Measure:** k6 virtual users replay a recorded browser scenario against the app in a Docker
   container; class histograms of the heap before and after give the memory per session.
2. **Project:** memory per session × concurrent sessions + fixed JVM costs = server memory.

## Tech Stack

* **Java 25**, **Spring Boot 4.1**, **Vaadin Flow 25.3** (Aura theme)
* **Spring Data JPA** with an in-memory **H2** database (1,000 sample persons)
* **Spring Boot Actuator** for metrics, `jcmd` for class histograms of the heap
* **Vaadin TestBench** for browser tests, Vaadin's **k6** tooling for load tests
* **Docker** for the app with a memory limit

## The app

A single view at `/` (alias of `/crud-example[/<id>/edit]`): a lazy-loading grid of persons
(`PersonGrid`) and an editor form (`PersonForm`) in a split layout. A saved person stays selected;
**Delete** asks for confirmation.

A second view at `/hello-world` greets the entered name. All other users with that view open get a
notification with the name. This is a small example of state shared across sessions:
`HelloWorldView` holds a static `SharedValueSignal<String>` with the last entered name. The button
writes to it, and an effect bound to the view shows the notification when the change came from
another session (`isBackgroundChange()`). The sessions never reference each other, only the signal.
The change is pushed with WebSocket (`@Push` on `Application`); builds with the `loadtest` profile use long polling instead (see below). The signal lives in one JVM, so in
a cluster it does not reach users on other nodes.

```bash
./mvnw                  # development mode, http://localhost:8080
./mvnw -Pit verify      # browser tests (TestBench, needs Chrome)
```

## 0. Demo

1. build and start the app
```bash
./mvnw clean package -DskipTests -Ploadtest && java -jar target/vaadin-sizing-demo-1.0-SNAPSHOT.jar
```

2. record the k6 scenario
```bash
./mvnw loadtest:record -Ploadtest
```

3a. run the tests with k6
```bash
k6 run -e APP_PORT=8080 /Users/sebastian/Downloads/vaadin-sizing-demo/src/test/k6/recordings/edit-person.js
```

3b. run the tests with loadtest plugin
```bash
./mvnw loadtest:run -Ploadtest -Dk6.vus=100 -Dk6.duration=1m
```

## 1. Measure the session size

Prerequisites: Docker, [k6](https://grafana.com/docs/k6/latest/get-started/installation/)
(`brew install k6`) and Chrome.

```bash
loadtest/rebuild.sh                 # build the image, (re)start the container, record the k6 script
loadtest/measure-session-size.sh    # warmup, class histogram, 100 users, class histogram
```

After a code change, run both again: `rebuild.sh` builds the new code and records the scenario
again, because the k6 script must always match the build under test. Add a label to tell the
results apart, e.g. `loadtest/measure-session-size.sh in-memory`.

### The scenario

`EditPersonScenario` is one user in one browser tab: open the view, scroll to the middle of the grid, create a person, change a
random text field, save, delete the person. `loadtest:record` (`testbench-converter-plugin`) runs it
once in Chrome through a recording proxy and turns the traffic into the k6 script `src/test/k6/recordings/edit-person.js`. k6 replays it with any number of virtual users;
every virtual user leaves exactly **one session with one UI** behind.

Builds with the `loadtest` profile (the Docker image, too) push with **long polling** instead of
WebSocket (`PushTransportInitListener`): Chrome tunnels WebSockets through the recording proxy,
which then fails with `NotSslRecordException`, and k6 only replays the HTTP requests of the
recording. The transport can be changed with the property `app.push.transport`, e.g.
`APP_JAVA_OPTS=-Dapp.push.transport=WEBSOCKET docker compose up -d` (then the recorded script does not match the app).

`HelloWorldScenario` is a second, small scenario: open `/hello-world`, say hello with an empty name
field ("Hello stranger") and then with a random name. `loadtest:record` records it as
`src/test/k6/recordings/hello-world.js`. The measurement scripts run one k6 script, `edit-person` by
default; `SCENARIO` selects another, e.g. `SCENARIO=hello-world loadtest/measure-session-size.sh hello`.

### Record and run the k6 script locally (without Docker)

The scripts above do this in the container. To record and run the k6 script by hand against a
locally started app (ports 8080 and 8081 must be free, so stop the container first with
`docker compose stop`):

```bash
# 1. Build and start the app as in the image: production build with the loadtest profile
./mvnw clean package -DskipTests -Ploadtest
java -jar target/vaadin-sizing-demo-1.0-SNAPSHOT.jar

# 2. In a second terminal: record the k6 scripts (runs the scenarios in headless Chrome)
MAVEN_OPTS="-Dio.netty.leakDetection.level=disabled" \
    ./mvnw -Ploadtest test-compile loadtest:record -Dk6.forceRecord=true

# 3. Run it, either directly with k6 ...
k6 run --vus 10 --iterations 10 src/test/k6/recordings/edit-person.js
# ... or through Maven, which also writes an HTML report to src/test/k6/recordings/report/
./mvnw -Ploadtest loadtest:run -Dk6.vus=10 -Dk6.executor=per-vu-iterations -Dk6.iterations=1
```

Another host or port: `k6 run -e APP_IP=192.168.1.100 -e APP_PORT=8080 …`, or
`-Dk6.appIp=… -Dk6.appPort=…` for `loadtest:run`.

- **Record against the build you test.** The script contains the hashed file names of the frontend
  bundle and the component IDs. It does not work against development mode (`./mvnw`), and after
  every code change it must be recorded again.
- `-Dk6.forceRecord=true` records even if the scenario has not changed (`edit-person.hash`).

### How the measurement works

`measure-session-size.sh` (options: `SCENARIO`, `USERS`, `WARMUP_USERS`, `CHECK_BASELINE`, `IDLE_WAIT`;
`LOCAL_PID` and `GC_LOG` measure an app started with `java -jar` instead of the container, see the
script for the JVM options):

1. **Warmup:** `WARMUP_USERS` users (default: `USERS`) run the scenario once. The first requests load classes and
   fill caches, and Tomcat creates buffers for every concurrent connection. None of this is
   session memory, so it must be in the heap before the first measurement. Then all sessions are
   invalidated (`DELETE /actuator/sessions`, which ends the Vaadin sessions and UIs, too; only in
   builds with the `loadtest` profile).
2. **Class histogram before:** the baseline without sessions. `jcmd <pid> GC.class_histogram`
   runs a full GC and sums the size of all reachable objects per class; the `Total` line is the
   live heap.
3. **Load:** `USERS` virtual users run the scenario once each.
4. **Class histogram after.**
5. **Baseline check** (only with `CHECK_BASELINE=true`): invalidate all sessions again, class
   histogram `cleared`. It should be about the baseline; if it is higher, the load built up one-time costs
   that the warmup did not, and they are counted as session memory.

Besides the sessions, `summary.txt` shows the UIs (from the gauges of `testbench-loadtest-support`)
and warns unless every new session has exactly one UI and no UIs are left after invalidating.
The per-view gauge (`vaadin.view.count` with tag `view`) counts navigations, not views (3 per UI
in this scenario), so the script subtracts it from the total.

The image contains only a JRE, without `jcmd`. The script runs `jcmd` in a temporary JDK
container (`JCMD_IMAGE`, default `eclipse-temurin:25-jdk-alpine`, same Java version as the app)
that shares the PID namespace of the app container; the app is PID 1 there. Before every histogram
the script runs a GC (`jcmd <pid> GC.run`) and waits `IDLE_WAIT` seconds (default 5), see
[Things to know](#things-to-know).

```
memory per session = (live heap after − live heap before) / sessions after
```

Result (100 users, `results/<timestamp>/summary.txt`), on a freshly started container:

```
                 live heap   sessions        UIs
before             49.1 MB          0          0
after              72.4 MB        100        100
cleared            49.2 MB          0          0

memory per session:            238 KB  (100 new sessions)
live heap without sessions:   49.1 MB  (baseline)
baseline growth by load:       0.0 MB  (cleared - before; 0 KB of the memory per session)
```

Five runs per number of users, each on a freshly started container:

| Users | Memory per session (KB) | Mean | Baseline growth | Live heap without sessions |
|---|---|---|---|---|
| 50 | 242, 245, 253, 240, 245 | 245 | 0.1 – 0.7 MB | 45 – 47 MB |
| 100 | 239, 242, 238, 239, 246 | 241 | 0.0 – 0.8 MB | 49 – 52 MB |
| 200 | 238, 235¹, 238, 238, 238 | 237 | 0.0 – 0.1 MB | 58 – 59 MB |

¹ 5 failed requests left 2 sessions without a UI (202 sessions, 200 UIs); `summary.txt` warned.

The more users, the smaller the spread: a baseline growth of a few hundred KB weighs more when it
is divided by fewer sessions. Measure with at least 100 users. The live heap without sessions
grows with the number of users because the warmup is as large as the load (Tomcat threads and
buffers).

The warmup must be as large as the load: with only 20 warmup users, the baseline grew by 7.6 MB
during the load, and the memory per session came out at 322 KB instead of about 240 KB (a second
run on the then warm app gave 248 KB). If the baseline growth in `summary.txt` is more than a few
KB per session, raise `WARMUP_USERS`.

The histograms `before.txt`, `after.txt` and `cleared.txt` stay in the result directory. At the
end of `summary.txt`, the classes that grew most per session show what a session holds. Here,
most of it is the data of the grid and the form: about 2 `SamplePerson` with 6 `Skill`, Hibernate
collections (`PersistentBag`), `Long` IDs and the maps that hold them; the component tree is
spread over generic classes (`Object[]`, `ArrayList`, `HashMap`, `nodefeature.*`):

```
classes that grew most per session:  bytes  instances
       25441 B     795.0  java.util.HashMap$Node
       19234 B     801.4  java.lang.Long
       19200 B     600.0  org.vaadin.demo.sizing.app.data.Skill
       17844 B     551.0  [Ljava.lang.Object;
       16619 B     233.0  [Ljava.util.HashMap$Node;
       14472 B     201.0  org.hibernate.collection.spi.PersistentBag
       13725 B     571.9  java.util.ArrayList
       11256 B     201.0  org.vaadin.demo.sizing.app.data.SamplePerson
       ...
```

Heap dumps with the retained size of a `UI` in VisualVM or Eclipse MAT are not used: the
retained size is misleading when UIs are reachable from shared objects (here,
`testbench-loadtest-support` references every UI from a global object), and the tools do not
account for compact object headers (see the Vaadin docs,
[Capacity and Failover](https://vaadin.com/docs/latest/building-apps/architecture/capacity-and-failover)).

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
under load (100 users at the same time, 19 s)
peak heap after GC:           96.0 MB  (upper bound of the live heap under load)
extra heap per active user:    242 KB  ((peak heap after GC - live heap after) / users)
GCs:                            25     (0 full, 235 ms pauses in total, max 24 ms)
allocated per run:            16.7 MB  (88 MB/s)
heap committed:              241.7 MB
non-heap used:               159.0 MB  (metaspace, code cache)
peak container memory:       531.0 MB of 1024 MB limit
```

| Users | Extra heap per active user | Peak heap after GC | Peak container memory | CPU per run |
|---|---|---|---|---|
| 50 | 226 – 313 KB | 68 – 72 MB | 395 – 407 MB | 163 – 174 ms |
| 100 | 224 – 272 KB | 96 – 100 MB | 525 – 542 MB | 104 – 114 ms |
| 200 | 306 – 323 KB | 165 – 168 MB | 733 – 746 MB | 72 – 78 ms |

No full GC ran during the load, so the extra heap is an upper bound. All `USERS` start
at the same time, so they are all active during the run; the values depend on the pace of the
scenario (think times in the k6 script).

### Things to know

- **The sessions stay in memory.** The session timeout is Spring Boot's default of 30 minutes, so
  the number of sessions in the heap is exactly known.
- **Take the histograms soon after the run.** k6 does not send heartbeats; Vaadin removes a UI after
  three missed heartbeats (about 15 minutes by default), and the session would look smaller.
- **The heap must be compacted completely.** For small containers the JVM picks the serial GC,
  whose full GC leaves up to 5 % of dead space in the heap and compacts completely only every 4th
  time. It fills the dead space with filler objects (`jdk.internal.vm.FillerElement`), which the
  class histogram counts as live heap, here 7–8 MB. `compose.yaml` therefore sets
  `-XX:MarkSweepAlwaysCompactCount=1`; `summary.txt` warns if there are more than 1 MB of fillers.
- **Some objects need two GCs.** Objects with a `Cleaner`, here about 60 `ZipFileSystem`s with
  their buffers (~2 MB after 200 users), are freed only by the GC after the one that found them
  unreachable. With a single GC, they were in the baseline but not in `cleared`, and the memory
  per session came out about 8 KB too low (230 instead of 238 KB at 200 users). The script
  therefore runs a GC at the start of the wait, so the Cleaners have run before the histogram.
- **Idle worker threads end after 60 s.** Tomcat ends worker threads that have been idle for 60 s
  (beyond the 10 spare threads), and with them their thread-local buffers, about 100 KB each
  (1.2 MB after 100 users). If they end between two histograms, they are in one and not in the
  next, which shifts the memory per session by up to 15 KB. The script therefore waits only
  `IDLE_WAIT` seconds (5) before every histogram: the threads of the warmup stay alive in all
  histograms, like in an app under load, and cancel out. This needs at least as many warmup users
  as users (`WARMUP_USERS >= USERS`, the script warns otherwise); the threads that only the load
  creates would be counted as session memory.
- **`jvm.memory.used` is not the live heap.** Even right after a full GC it is 1–3 MB higher than
  the histogram (space not occupied by objects), and the difference changes from GC to GC.
- **It is the state at the end of the scenario.** A user in the middle of their work (open dialog,
  filled form) holds somewhat more. Scenarios should end in a typical state.
- **The code decides.** The same view with the grid filled in memory (`setItems(list)`, all 1,000
  persons) and a component column instead of the `LitRenderer` (measured earlier as the retained
  size in a heap dump, so only the ratio counts, see above):

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
the sessions of the finished runs fill the heap), warms the app up with `WARMUP_RATE` runs per
minute (100) for `WARMUP_DURATION` (1 minute) and runs one k6 step per rate in `RATES`. In
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
     ├─ data/...            # entity (SamplePerson) and repository
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
