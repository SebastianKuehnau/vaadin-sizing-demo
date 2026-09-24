# vaadin-sizing-demo

A small **Vaadin Flow + Spring Boot** application used as a reference workload for
sizing and load-testing Vaadin deployments. It is deliberately simple – a Hello World
view and a CRUD view backed by an in-memory database – so that memory, CPU and
session footprint can be measured and compared in a reproducible way.

## Tech Stack

* **Java 25**
* **Spring Boot 4.1**
* **Vaadin Flow 25.4 (snapshot)** with the **Aura** theme and `@Push` enabled
* **Spring Data JPA** with an in-memory **H2** database
* **Vaadin Observability Kit**, **Spring Boot Actuator** and **Micrometer / Prometheus**
* **Vaadin TestBench** (JUnit 5) for browser tests
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
./mvnw -Pproduction clean package
java -jar target/vaadin-sizing-demo-1.0-SNAPSHOT.jar
```

## Views

| Route                                   | View               | Description                                                                 |
|-----------------------------------------|--------------------|-----------------------------------------------------------------------------|
| `/`, `/hello-world`                     | `HelloWorldView`   | Text field and button that shows a "Hello <name>" notification             |
| `/crud-example[/<id>/edit]`             | `CrudExampleView`  | Lazy-loading grid of `SamplePerson` entities with an editor form (split layout) |

The CRUD form uses bean validation (`@NotEmpty`, `@Email`, `@Past`) and optimistic
locking – concurrent edits of the same record show an error notification.

## Sample Data

On startup `data.sql` fills the H2 database with **1,000 persons** and about
**3,000 skills** (one-to-many, eagerly loaded via an entity graph). The script only
runs when the database is empty.

## Monitoring

The following Actuator endpoints are exposed:

* `/actuator/prometheus` – Micrometer metrics in Prometheus format
* `/actuator/vaadin` – Vaadin-specific metrics from the Observability Kit

## Tests

Browser tests are based on Vaadin TestBench and run against a started application.

```bash
./mvnw -Pit verify
```

The `it` profile starts the Spring Boot app, runs all `*IT` classes with the
Failsafe plugin and stops the app again. Tests connect to `localhost:8080`, or to the
host in the `HOSTNAME` environment variable when set.

* `it/hello/HelloWorldViewIT` – Hello World view
* `it/crud/CrudExampleViewIT` – grid, form, validation, create and edit
* `load/UserNotificationAndEditScenario` – end-to-end user scenario used as the
  basis for load tests: greet in the Hello World view, then edit a random property
  of a random person in the CRUD view and revert it again

## Project Structure

```
src/main/java/org/vaadin/demo/sizing
 ├─ Application.java        # Spring Boot entry point, app shell (theme, push)
 └─ app                     # the application under measurement
     ├─ data/...            # Entities (SamplePerson, Skill) & JPA repositories
     ├─ service/...         # SamplePersonService
     └─ ui/
         ├─ hello/...       # Hello World view
         └─ crud/...        # CRUD view (PersonGrid + PersonForm)

src/test/java/org/vaadin/demo/sizing
 ├─ it/...                  # TestBench integration tests (*IT)
 └─ load/...                # User scenarios used as the basis for load tests

src/main/resources
 ├─ application.properties  # Configuration
 ├─ data.sql                # Sample data
 └─ META-INF/resources/     # Global and view-specific CSS, images, icons
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
