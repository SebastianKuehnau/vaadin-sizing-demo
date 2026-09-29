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

`EditPersonScenario` is one user in one browser tab: open the view, create a person, change a
random field, save, delete the person. `EditPersonIT` runs it once in Chrome through a recording
proxy (`loadtest:record` of the `testbench-converter-plugin`), which turns the traffic into the k6
script `src/test/k6/recordings/edit-person.js`. k6 replays it with any number of virtual users;
every virtual user leaves exactly **one session with one UI** behind.

### How the measurement works

`measure-session-size.sh` (options: `USERS`, `WARMUP_USERS`, `KEEP_DUMPS`):

1. **Warmup:** the same number of users run the scenario once. The first requests load classes and
   fill caches, and Tomcat creates buffers for every concurrent connection. None of this is
   session memory, so it must be in the heap before the first measurement.
2. **Heap dump before** (`/actuator/heapdump`). The dump forces a full GC, so afterwards the heap
   only holds live objects.
3. **Load:** `USERS` virtual users run the scenario once each.
4. **Heap dump after.**

```
memory per session = (live heap after − live heap before) / (sessions after − sessions before)
```

Result (100 users, `results/<timestamp>/summary.txt`):

```
                 live heap   sessions
before             99.4 MB        236
after             119.8 MB        336

memory per session:            209 KB  (100 new sessions)
live heap without sessions:   51.1 MB
```

The heap dumps `before.hprof` and `after.hprof` stay in the result directory. In VisualVM or
Eclipse MAT, the **retained size of one `com.vaadin.flow.component.UI`** is the component tree and
data of one tab, here **180 KB**; the difference to the measured 209 KB is the session itself
(Tomcat and Vaadin session, a few KB) and about 10 % noise. The retained size of `VaadinSession`
is useless here (2 KB): `testbench-loadtest-support` references every UI from a global object, so
the UIs are no longer "owned" by their session.

### Things to know

- **The sessions stay in memory.** The session timeout is Spring Boot's default of 30 minutes, so
  the number of sessions in the heap is exactly known.
- **Take the dumps soon after the run.** k6 does not send heartbeats; Vaadin removes a UI after
  three missed heartbeats (about 15 minutes by default), and the session would look smaller.
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
max heap        ≥ (live heap without sessions + concurrent sessions × memory per session) × 2
```

| Part | Value (measured here) | Note |
|---|---|---|
| non-heap (metaspace, code cache) | ~175 MB | grows with the amount of code, not with users |
| native (threads, GC, buffers) | ~100 MB | |
| live heap without sessions | ~50 MB | `summary.txt` |
| memory per session | ~210 KB | `summary.txt` |
| factor 2 | | headroom, so the GC does not run constantly |

**Concurrent sessions** (Little's law): new sessions per minute × (minutes in the app + session
timeout). A session lives on for the whole timeout after the user left.

Example: 20 logins per minute, 15 minutes in the app, 30 minutes timeout:

```
sessions  = 20 × (15 + 30)                 =   900
max heap  = (50 MB + 900 × 0.21 MB) × 2    ≈   480 MB
container = 175 MB + 100 MB + 480 MB       ≈   755 MB  → 1 GB, heap limited to ~500 MB
```

The JVM does not size the heap on its own terms: in a container it takes `MaxRAMPercentage` of
the memory limit (default 25 %, 75 % in `compose.yaml`). Too little room besides the heap and the
kernel kills the container (exit code 137), e.g. at 512 MB and 75 %:

```bash
APP_MEMORY=512m APP_MAX_RAM_PERCENTAGE=75 docker compose up -d
```

**CPU** is a separate question: the HTML report of the k6 run (`load-report/` in the result
directory) shows the CPU usage of the app during the test. An earlier version of the scenario
needed about 55 ms of CPU per run.

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
src/loadtest/java/...       # EditPersonIT, the scenario routed through the recording proxy
src/test/k6/recordings/     # recorded k6 script and its test data
loadtest/                   # rebuild.sh, measure-session-size.sh
Dockerfile, compose.yaml    # the app in a container with memory and CPU limits
```

The Docker image is built with the `loadtest` Maven profile, which adds
`testbench-loadtest-support` to the app. Never deploy it to production.

## License

Public domain (Unlicense) – see [LICENSE.md](LICENSE.md).
