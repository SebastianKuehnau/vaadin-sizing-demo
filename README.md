# vaadin-sizing-demo

A small **Vaadin Flow + Spring Boot** application used as a reference workload for
sizing and load-testing Vaadin deployments. It is deliberately simple – a Hello World
view and a CRUD view backed by a database – so that memory, CPU and session footprint
can be measured and compared in a reproducible way.

The project contains everything to size a server for it: the app, PostgreSQL, Prometheus and
Grafana run in separate Docker containers with resource limits, the app is monitored with the
Vaadin Observability Kit, and k6 load tests are recorded from a TestBench scenario with Vaadin's
own tooling (see [Docker](#docker) and [Load tests](#load-tests)).

## Tech Stack

* **Java 25**
* **Spring Boot 4.1**
* **Vaadin Flow 25.3** with the **Aura** theme and `@Push` enabled
* **Spring Data JPA** with an in-memory **H2** database (development, tests) or **PostgreSQL** (Docker)
* **Vaadin Observability Kit**, **Spring Boot Actuator** and **Micrometer / Prometheus**
* **Vaadin TestBench** (JUnit 5) for browser tests, **k6** for load tests
* **Docker Compose** with **PostgreSQL**, **Prometheus** and **Grafana**
* **Maven** (wrapper included, no global installation needed)

## Quick Start

### Prerequisites

* JDK 25
* Internet access on first build (Vaadin pre-release repository, frontend dependencies)

Vaadin manages the frontend tooling (Node.js, npm) automatically.

### Run in development mode

```bash
./mvnw
```

`spring-boot:run` is the default goal. Open [http://localhost:8080](http://localhost:8080).
The port can be changed with the `PORT` environment variable.

### Run from the IDE

Import the project as a **Maven** project and run
`org.vaadin.demo.sizing.Application`.

### Production build

```bash
./mvnw clean package
java -jar target/vaadin-sizing-demo-1.0-SNAPSHOT.jar
```

The frontend bundle is always built in production mode; `vaadin-dev` is optional and not part
of the jar. Without a Spring profile the jar uses H2; see [Docker](#docker) for PostgreSQL.

## Views

| Route                                   | View               | Description                                                                 |
|-----------------------------------------|--------------------|-----------------------------------------------------------------------------|
| `/`, `/hello-world`                     | `HelloWorldView`   | Text field and button that shows a "Hello <name>" notification             |
| `/crud-example[/<id>/edit]`             | `CrudExampleView`  | Lazy-loading grid of `SamplePerson` entities with an editor form (split layout) |

The CRUD form uses bean validation (`@NotEmpty`, `@Email`, `@Past`) and optimistic
locking – concurrent edits of the same record show an error notification. A saved person stays
selected and in the form; **Delete** removes it after a confirmation dialog.

## Sample Data

On startup `data.sql` fills the H2 database with **1,000 persons** and about
**3,000 skills** (one-to-many, eagerly loaded via an entity graph). The script only
runs when the database is empty. With the `prod` profile (Docker), the schema is recreated on
every start and `data-postgresql.sql` loads the same data into PostgreSQL, so every load test
starts from the same state.

## Monitoring

Actuator runs on the separate management port **8081** (`MANAGEMENT_PORT`) and exposes:

* `/actuator/prometheus` – Micrometer metrics in Prometheus format
* `/actuator/vaadin` – Vaadin-specific metrics from the Observability Kit

Besides the defaults, the Observability Kit measures the size of the component trees
(`vaadin.observability.ui-state`) and database queries per route (`vaadin.observability.database`),
and Tomcat's MBeans provide thread and session metrics.

## Docker

`compose.yaml` runs four containers, each with its own resource limits:

| Container | Image | Default limits | Purpose |
|---|---|---|---|
| `sizing-app` | built from the `Dockerfile` | 2 CPUs, 1 GB | the app with Spring profile `prod`, port 8080 |
| `sizing-db` | `postgres:18-alpine` | 1 CPU, 512 MB | database, schema and data recreated on every app start |
| `sizing-prometheus` | `prom/prometheus` | 0.5 CPU, 512 MB | scrapes the app every 5 s, receives the k6 metrics, port 9090 |
| `sizing-grafana` | `grafana/grafana` | 0.5 CPU, 256 MB | dashboard "Vaadin Sizing", port 3000 (admin/admin) |

The Observability Kit is a commercial component, so the image build needs your Vaadin Pro key.
Compose passes `~/.vaadin/proKey` as a build secret (another file: `VAADIN_PRO_KEY_FILE`).

```bash
docker compose up --build -d      # build and start
docker stats                      # CPU and memory of the containers, live
docker compose down               # stop
```

All limits are environment variables, e.g. `APP_CPUS=1 APP_MEMORY=512m docker compose up -d`:
`APP_CPUS`, `APP_MEMORY`, `APP_MAX_RAM_PERCENTAGE` (heap share of `APP_MEMORY`, default 75),
`APP_JAVA_OPTS`, `DB_CPUS`, `DB_MEMORY`, `DB_POOL_SIZE`, `PROMETHEUS_CPUS`, `PROMETHEUS_MEMORY`,
`GRAFANA_CPUS`, `GRAFANA_MEMORY`, and the ports `APP_PORT`, `DB_PORT`, `PROMETHEUS_PORT`,
`GRAFANA_PORT`, `MANAGEMENT_PORT`.

The Grafana dashboard shows sessions and UIs, heap and live data per session, the size of the
component trees, GC pauses, CPU, request rate and duration, session lock times, threads, the
connection pool and the database queries per route, and in the last row the k6 view of a load
test (virtual users, response times, throughput, errors) on the same time axis.

## Load tests

The load tests use Vaadin's own tooling (`testbench-converter-plugin`): the TestBench scenario
`UserNotificationAndEditScenario` is run once through a recording proxy, and the captured traffic
is converted into a k6 script that handles session IDs, CSRF tokens and component IDs. Everything
lives in the `loadtest` Maven profile, so the normal build is unaffected.

One iteration of the scenario is one user: greet in the Hello World view, open the CRUD view,
create a new person, change a random field, save, and delete the person again. It only works on
the person it created and does not click on grid rows, so any number of virtual users can replay
it in parallel.

Prerequisites: Docker, [k6](https://grafana.com/docs/k6/latest/get-started/installation/)
(`brew install k6`), Chrome (only for recording), and the Vaadin Pro key.

### 1. Start the app under load test

```bash
docker compose -f compose.yaml -f compose.loadtest.yaml up --build -d
```

The overlay builds the image with the `loadtest` Maven profile and runs it with the Spring
profiles `prod,loadtest`:

| Where | Change | Why |
|---|---|---|
| Maven profile | adds `testbench-loadtest-support` to the app | server exceptions become visible to the k6 checks; adds view counts |
| Spring profile | push over long polling instead of WebSocket | the recording proxy cannot capture WebSocket traffic |
| Spring profile | session timeout 30 s | every k6 iteration starts a new session and abandons it; see below |
| Spring profile | browser metrics collector off | k6 does not run the page's JavaScript, it would only replay recorded samples |
| Spring profile | Actuator `metrics` endpoint, port 8081 published | `loadtest:run` reads CPU, heap and sessions from it |

Never deploy an image built with the `loadtest` profile to production.

### 2. Record the k6 script

```bash
./mvnw -Ploadtest test-compile loadtest:record
```

This runs `UserNotificationAndEditIT` (`src/loadtest/java`, the scenario routed through the proxy)
against the running container and writes `src/test/k6/recordings/user-notification-and-edit.js`
and its test data `user-notification-and-edit-data.csv`. Re-record after UI changes;
`-Dk6.forceRecord=true` re-records although the scenario did not change. Commit the script, the
CSV and the `.hash` file together with the UI change they were recorded against; the rest in that
directory is generated and ignored by Git.

The scripts contain the hashed file names of the frontend bundle, so they must be recorded
against the same build that is tested.

### 3. Run A: cost per session

How much heap and CPU does one active user need? The app gets generous limits, so it is not
the bottleneck, and a constant number of virtual users runs long enough for the curves to be flat.

```bash
APP_CPUS=4 APP_MEMORY=2g docker compose -f compose.yaml -f compose.loadtest.yaml up -d
loadtest/cost-per-session.sh                        # 50 VUs for 10 minutes
VUS=100 DURATION=15m loadtest/cost-per-session.sh   # other values
```

Read off in Grafana once the curves are flat: **Live data per session** (heap after GC divided by
active sessions; the best approximation of the memory per user), **CPU** against the k6
**iterations/s** (CPU per user action), **UI state** (component tree per user) and the database
panels. The HTML report of the run is in `target/k6/results/cost-per-session-<timestamp>/`.

### 4. Run B: capacity limit

How many users does a given server size carry? The app gets tight limits, and the load rises in
steps until the thresholds fail: **p95 response time above 2 s** or **more than 1 % failed
checks**.

```bash
APP_CPUS=1 APP_MEMORY=512m docker compose -f compose.yaml -f compose.loadtest.yaml up -d
loadtest/steps.sh                                   # 10, 50, 100, 200 VUs, 5 minutes each
STEPS="20 40 80" STEP_DURATION=2m loadtest/steps.sh
```

`steps.sh` stops at the first step whose thresholds fail; the last passed step is the capacity
of this configuration. Each step leaves a log and an HTML report (response times, failed checks,
per-request table, server CPU/heap/sessions) in `target/k6/results/steps-<timestamp>/`. In
Grafana, the point at which the k6 response time starts to rise shows whether CPU (CPU at the
limit), memory (GC pauses rise, live data near heap max) or the database (pending connections)
is the bottleneck. Repeat with other `APP_CPUS`/`APP_MEMORY` combinations.

### Client metrics in Prometheus

`loadtest:run` starts k6 through `loadtest/k6-prometheus.sh` (`k6Binary` in the `pom.xml`), which
adds k6's Prometheus remote-write output to every run, so the k6 metrics appear in the Grafana
dashboard. Each run is tagged with a `testid` label (e.g. `steps-<timestamp>-50`). Set
`K6_PROMETHEUS=false` to switch it off, or `K6_PROMETHEUS_RW_SERVER_URL` for another Prometheus.

### Interpreting the results

- **Only active sessions are measured.** With the 30 s session timeout, the server only holds the
  sessions of users who are active or just left. In production, sessions of users who left live
  on until the real timeout (30 minutes by default). For the memory in production, multiply the
  live data per session by the sessions you expect to be open at the same time, including the
  idle ones.
- **Local results show trends.** k6 on the Mac competes with the Docker VM for CPU. Memory limits
  are hard and transfer well to a server; CPU limits are only an upper bound. For the final
  numbers, run k6 on a separate machine against a server that resembles production:
  `APP_IP=test-server.example.com loadtest/steps.sh` (the app's ports 8080 and 8081 must be
  reachable, and the script must be recorded against the same build, e.g.
  `HOSTNAME=test-server.example.com ./mvnw -Ploadtest test-compile loadtest:record`).
- **Container memory is more than the heap.** The JVM needs metaspace, code cache, thread stacks
  and GC structures on top of the heap. If `APP_MEMORY` is too tight for `APP_MAX_RAM_PERCENTAGE`,
  the kernel kills the container (`docker events` shows `oom`, exit code 137): Grafana shows a gap
  and k6 reports `status 0`.
- **Sessions and UIs:** use the dashboard (Observability Kit metrics). One iteration creates one
  session with two UIs (two page loads). The "UIs" column in the summary of `loadtest:run` counts
  differently and is higher.

### Known issues of the tooling

- The converter inserts the think time after a page load into the error branch of the preceding
  check, where it never runs. `loadtest/fix-think-times.sh` moves it; both run scripts call it.
  Run it yourself before using the scripts with plain `k6 run`.
- The recording contains one data row. `loadtest/expand-test-data.py` (called by both run
  scripts) adds 499 variants with the same format, so the virtual users create different persons.
- Long-polling push requests are replayed with the push ID of the recording, which the server
  rejects. Push is not used for UI updates here, so this only adds a few cheap requests; the
  warnings are muted in the `loadtest` profile.

## Tests

Browser tests are based on Vaadin TestBench and run against a started application.

```bash
./mvnw -Pit verify
```

The `it` profile starts the Spring Boot app, runs all `*IT` classes with the
Failsafe plugin and stops the app again. Tests connect to `localhost:8080`, or to the
host in the `HOSTNAME` environment variable when set.

* `it/hello/HelloWorldViewIT` – Hello World view
* `it/crud/CrudExampleViewIT` – grid, form, validation, create, edit and delete
* `load/UserNotificationAndEditScenario` – end-to-end user scenario used as the
  basis for load tests: greet in the Hello World view, then create a person in the CRUD
  view, change a random field and delete the person again. It does not end with `IT`,
  so run it explicitly: `./mvnw -Pit verify -Dit.test=UserNotificationAndEditScenario`

## Project Structure

```
src/main/java/org/vaadin/demo/sizing
 ├─ Application.java        # Spring Boot entry point, app shell (theme, push)
 ├─ loadtest/...            # Settings of the loadtest Spring profile
 └─ app                     # the application under measurement
     ├─ data/...            # Entities (SamplePerson, Skill) & JPA repositories
     ├─ service/...         # SamplePersonService
     └─ ui/
         ├─ hello/...       # Hello World view
         └─ crud/...        # CRUD view (PersonGrid + PersonForm)

src/test/java/org/vaadin/demo/sizing
 ├─ it/...                  # TestBench integration tests (*IT)
 └─ load/...                # User scenarios used as the basis for load tests
src/loadtest/java/...       # Scenarios routed through the recording proxy (loadtest profile)
src/test/k6/recordings/     # Recorded k6 scripts and their test data

src/main/resources
 ├─ application.properties  # Configuration
 ├─ application-prod.properties, application-loadtest.properties
 ├─ data.sql                # Sample data (H2), data-postgresql.sql for PostgreSQL
 └─ META-INF/resources/     # Global and view-specific CSS, images, icons

Dockerfile, compose.yaml, compose.loadtest.yaml
monitoring/                 # Prometheus configuration, Grafana provisioning and dashboard
loadtest/                   # Run scripts (run A and B) and workarounds for the k6 tooling
```

## Troubleshooting

* Frontend out of sync or strange build errors: run `./mvnw vaadin:dance` to clean
  the generated frontend files and rebuild.
* To use the Vite dev server for frontend changes, set
  `vaadin.frontend.hotdeploy=true` in `application.properties`.

## Useful Links

* [Vaadin Docs](https://vaadin.com/docs)
* [Vaadin Observability Kit](https://vaadin.com/docs/latest/tools/observability)
* [Spring Boot Docs](https://docs.spring.io/spring-boot/reference/)

## License

Public domain (Unlicense) – see [LICENSE.md](LICENSE.md).
