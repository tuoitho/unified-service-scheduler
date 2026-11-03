# Unified Service Scheduler

A production-grade backend scheduling engine for automotive dealerships (**Domain: Ownership**). It replaces manual workshop booking sheets with atomic, resource-constrained appointment scheduling—verifying and allocating both a physical **ServiceBay** and a qualified **Technician** across the full service duration with **zero double-booking under high concurrency**.

---

## Key Capabilities

- **Dual-Resource Atomic Allocation:** Every confirmed appointment is backed by an active `ServiceBay` and an active `Technician` holding the required `ServiceType` qualification across the half-open UTC interval `[start_time, end_time)`.
- **3-Layer Defense-in-Depth Concurrency Control:**
  1. **Deadlock-Free Lock Hierarchy:** Strict global lock order (`pg_advisory_xact_lock` on `vehicle_id` $\rightarrow$ `service_bays` $\rightarrow$ `technicians`).
  2. **Fast-Path `FOR UPDATE SKIP LOCKED` + Contention Fallback:** Non-blocking parallel bay/technician allocation with post-lock fresh-snapshot verification (eliminating PostgreSQL `READ COMMITTED` subquery snapshot races) and deterministic ordered fallback (preventing false `409 Conflict` responses when concurrent requests target non-overlapping times on the same bay).
  3. **Database Physical Guarantee (`btree_gist`):** Partial PostgreSQL GiST exclusion constraints (`WHERE status = 'CONFIRMED'`) on `service_bay_id`, `technician_id`, and `vehicle_id` using `tstzrange(start_time, end_time, '[)')`, plus composite foreign keys preventing cross-dealership resource assignment.
- **Specialist Preservation Scheduling:** Automatically prioritizes qualified technicians with the fewest total certifications (`skill_count ASC`), keeping multi-skilled EV High-Voltage and MOT specialists available for specialized bookings.
- **Timezone-Aware Operating Hours:** Evaluates dealership opening/closing hours in each dealership's IANA timezone (`Europe/London`, `UTC`, etc.) while storing all timestamps normalized in UTC (`TIMESTAMPTZ`).
- **Production Observability:** `X-Correlation-ID` propagation, structured Logstash JSON logs in Docker, OpenTelemetry trace/span bridge, Prometheus histograms/counters (`/actuator/prometheus`), and RFC 7807 `ProblemDetail` error responses.

---

## Documentation Index

- **[System Design Document (`docs/SYSTEM_DESIGN.md`)](docs/SYSTEM_DESIGN.md):** Architecture diagram, component roles, ER schema, concurrency sequence diagram, technology justifications, observability strategy, explicit assumptions, and GenAI design collaboration.
- **[API & Interactive Demo Guide (`docs/API_AND_DEMO_GUIDE.md`)](docs/API_AND_DEMO_GUIDE.md):** Seeded UUID reference tables, cURL examples, RFC 7807 error catalog, and walkthrough guide.
- **[OpenAPI 3.1 Specification (`docs/openapi.json`)](docs/openapi.json):** Exported OpenAPI contract (also served live at `/v3/api-docs` and `/swagger-ui.html`).

---

## Quick Start (Docker Compose)

### Prerequisites
- **Docker** (24+) and **Docker Compose** (v2+)

### 1. Start PostgreSQL 16, Scheduler API, Prometheus & Grafana
```bash
docker compose up -d --build
```
Flyway automatically applies [`V1__init_schema.sql`](src/main/resources/db/migration/V1__init_schema.sql) (including `btree_gist` exclusion constraints) and seeds two realistic dealerships (`Keyloop Flagship London West` and `Keyloop Manchester EV Hub`) via [`R__seed_demo_data.sql`](src/main/resources/db/seed/R__seed_demo_data.sql). Prometheus and Grafana start automatically with a pre-provisioned real-time dashboard.

### 2. Explore Interactive Client & Observability UIs
- **Swagger UI:** [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **Grafana Real-Time Dashboard (No login required):** [http://localhost:3000](http://localhost:3000)
- **Prometheus UI:** [http://localhost:9090](http://localhost:9090)
- **OpenAPI JSON:** [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)
- **Health & Readiness:** [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
- **Raw Prometheus Metrics:** [http://localhost:8080/actuator/prometheus](http://localhost:8080/actuator/prometheus)

### 3. Run the End-to-End & Concurrency Demo Harness
On **Windows (PowerShell)**:
```powershell
.\scripts\demo.ps1
```
On **macOS / Linux (Bash)**:
```bash
chmod +x scripts/demo.sh
./scripts/demo.sh
```
The script verifies health, discovers catalog data, checks availability, books an appointment, fires **12 simultaneous HTTP requests** for an EV diagnostic slot with only **2 qualified technicians** (verifying `2 Created` and `10 Conflict`), cancels and re-books a freed slot, and prints live Prometheus counters.

### 4. Stop & Clean Up
```bash
docker compose down -v
```

---

## Local Development & Running Tests

### Prerequisites
- **JDK 21** (Maven 3.9.13 is bundled via `./mvnw` / `.\mvnw.cmd`)
- **Docker** (used automatically by **Testcontainers** to spin up an isolated `postgres:16-alpine` instance—no manual local database installation required)

### Run the Full Automated Test Suite
```bash
# macOS / Linux
./mvnw clean test

# Windows PowerShell
.\mvnw.cmd clean test
```

### Run Only the High-Concurrency Virtual-Thread Suite
```powershell
.\mvnw.cmd test "-Dtest=AppointmentConcurrencyIntegrationTest"
```

### What the Test Suite Verifies (21 Automated Tests across 7 Suites)
| Test Suite | Type | Key Invariants Verified |
| :--- | :--- | :--- |
| `TimeWindowValidatorTest` | Unit | Dealership local operating hours, `Europe/London` BST summer offset conversion, midnight-spanning rejection, past-timestamp rejection |
| `SchemaConstraintIntegrationTest` | DB / Flyway | PostgreSQL `btree_gist` overlap rejection (`23P01`) on `CONFIRMED` rows, `CANCELLED` row exemption, composite FK rejection of cross-dealership bays/technicians |
| `AppointmentRepositoryIntegrationTest` | JPA + SQL | Half-open `[start, end)` boundary adjacency, whole-duration continuity across split bookings, `skill_count ASC` specialist preservation, inactive bay exclusion |
| `AppointmentServiceIntegrationTest` | Domain Service | End-to-end booking, distinct conflict codes, lock-timeout translation (`RESOURCE_CONTENTION_TIMEOUT`), bounded Prometheus metric cardinality on unknown `dealershipId`, cancellation & re-booking, daily slot grid generation |
| `AppointmentConcurrencyIntegrationTest` | Stress (`CyclicBarrier` + Java 21 Virtual Threads) | **50 simultaneous threads** on 3 bays + 2 qualified techs $\rightarrow$ exact 2 confirmed / 48 conflicts; **20 simultaneous threads** for same `vehicleId` $\rightarrow$ exact 1 confirmed; **10 concurrent non-overlapping bookings** on 1-bay dealership $\rightarrow$ all 10 confirmed (zero false `SKIP LOCKED` conflicts); concurrent specialist preservation |
| `AppointmentApiIntegrationTest` | Full HTTP | `201/200/400/404/409/422` HTTP contracts, RFC 7807 `ProblemDetail` fields, `X-Correlation-ID` propagation, Actuator/Prometheus/OpenAPI 3.1 endpoints |
| `SeedDataSmokeIntegrationTest` | Seed Smoke | Repeatable `R__seed_demo_data.sql` execution and catalog API discovery |

---

## API Overview

| Method | Path | Description |
| :--- | :--- | :--- |
| `GET` | `/api/v1/dealerships` | List dealerships and operating hours |
| `GET` | `/api/v1/dealerships/{id}` | Get dealership details, bays, and technician qualification matrix |
| `GET` | `/api/v1/service-types` | List service catalog (`durationMinutes`, `requiredQualification`) |
| `GET` | `/api/v1/availability` | Compute bookable daily time slots (`dealershipId`, `serviceTypeId`, `date`, `slotIntervalMinutes`) |
| `GET` | `/api/v1/availability/check` | Instant real-time capacity check for an exact `startTime` |
| `POST` | `/api/v1/appointments` | Atomically validate, lock, allocate resources, and confirm an appointment (`201 Created` or `409 Conflict`) |
| `GET` | `/api/v1/appointments/{id}` | Retrieve appointment details by ID |
| `GET` | `/api/v1/appointments` | Filter appointments by `dealershipId`, `date`, `status`, `customerId`, or `vehicleId` |
| `POST` | `/api/v1/appointments/{id}/cancel` | Idempotently cancel an appointment and immediately release its bay and technician |

---

## Project Structure

```text
├── docs/
│   ├── SYSTEM_DESIGN.md          # Architecture, concurrency, observability & AI design notes
│   ├── API_AND_DEMO_GUIDE.md     # Seed reference tables, cURL examples, error catalog & demo walkthrough
│   └── openapi.json              # Exported OpenAPI 3.1 contract
├── observability/
│   ├── prometheus/               # Prometheus 5s scrape configuration (/actuator/prometheus)
│   └── grafana/                  # Auto-provisioned datasource & Unified Service Scheduler dashboard
├── scripts/
│   ├── demo.ps1                  # PowerShell E2E & 12-request concurrent burst harness
│   └── demo.sh                   # Bash E2E & 12-request concurrent burst harness
├── src/
│   ├── main/
│   │   ├── java/com/keyloop/scheduler/
│   │   │   ├── api/              # REST controllers & request/response Java record DTOs
│   │   │   ├── config/           # Clock & OpenAPI 3.1 configuration
│   │   │   ├── domain/           # JPA @Entity aggregates & AppointmentStatus enum
│   │   │   ├── exception/        # Domain exceptions & RFC 7807 GlobalExceptionHandler
│   │   │   ├── observability/    # CorrelationIdFilter & Micrometer SchedulerMetrics
│   │   │   ├── repository/       # Spring Data JPA repositories + PostgreSQL locking fragment
│   │   │   └── service/          # AppointmentService, AvailabilityService, TimeWindowValidator
│   │   └── resources/
│   │       ├── db/migration/     # V1__init_schema.sql (relational schema + btree_gist constraints)
│   │       └── db/seed/          # R__seed_demo_data.sql (repeatable demo dealerships & technicians)
│   └── test/                     # Testcontainers PostgreSQL integration, API, unit & concurrency suites
├── Dockerfile                    # Multi-stage layered Java 21 Alpine build (non-root runtime)
└── docker-compose.yml            # One-command PostgreSQL 16 + Scheduler API + Prometheus + Grafana stack
```

---

## AI Collaboration Narrative

Generative AI (**Google Antigravity**) was used throughout this project as an interactive engineering pair programmer under strict human architectural direction and test-driven verification. Rather than accepting one-shot generated code, collaboration followed a disciplined **Design $\rightarrow$ Challenge Assumptions $\rightarrow$ Failing Test (RED) $\rightarrow$ Implementation (GREEN) $\rightarrow$ Adversarial Verification** loop.

### 1. High-Level Strategy for Directing the AI
- **Architecture-First Grounding:** Before generating application code, we established [`docs/SYSTEM_DESIGN.md`](docs/SYSTEM_DESIGN.md) to lock down explicit domain invariants: half-open `[start_time, end_time)` UTC time intervals, dealership-local operating hour validation, atomic dual-resource booking (`ServiceBay` + qualified `Technician`), and RFC 7807 error contracts.
- **Idiomatic Enterprise Persistence Direction:** When evaluating data-access options, an initial pure-JDBC draft was challenged and redirected toward an idiomatic **hybrid Spring Data JPA / Hibernate 6 architecture** (`ddl-auto: validate`, `open-in-view: false`, explicit `JOIN FETCH` queries) paired with a focused custom repository fragment ([`AppointmentLockingRepositoryImpl`](src/main/java/com/keyloop/scheduler/repository/AppointmentLockingRepositoryImpl.java)) strictly for PostgreSQL's `pg_advisory_xact_lock`, `FOR UPDATE SKIP LOCKED`, and `tstzrange` operators. This kept 80% of catalog and lifecycle persistence concise and type-safe without sacrificing deterministic SQL locking on the hot booking path.
- **Rejecting Unnecessary Infrastructure Complexity:** Early AI brainstorming options included Redis distributed locks (`Redlock`) or coarse-grained `SERIALIZABLE` retries. Both were rejected: Redis introduces dual-system split-brain failure modes when lock TTLs expire mid-transaction, whereas PostgreSQL row locks + `btree_gist` exclusion constraints enforce correctness directly inside the ACID source of truth with zero extra moving parts.

### 2. Verifying, Debugging & Refining AI Output
Several subtle distributed-systems and framework edge cases surfaced during review and automated testing, requiring targeted human-guided refinement:

1. **Eliminating `SKIP LOCKED` False Conflicts & `READ COMMITTED` Snapshot Races:**
   - *Issue Identified:* A naive `SELECT ... WHERE NOT EXISTS (overlapping appointment) FOR UPDATE SKIP LOCKED LIMIT 1` suffers from two production bugs:
     - Under PostgreSQL `READ COMMITTED`, `FOR UPDATE` waits/skips locked rows on `service_bays`, **not** `appointments`. If Transaction A commits a new appointment *while* a fallback lock wait occurs, Transaction B's statement snapshot on `appointments` can miss the newly committed overlap until hitting a raw constraint exception.
     - On a 1-bay dealership, if two customers concurrently book **non-overlapping** slots (`09:00` and `14:00`), `SKIP LOCKED` skips the sole bay row locked by the other transaction and falsely returns `409 No Bay Available`.
   - *Refinement & Proof:* Redesigned [`AppointmentLockingRepositoryImpl`](src/main/java/com/keyloop/scheduler/repository/AppointmentLockingRepositoryImpl.java) with a two-phase algorithm: a non-blocking `SKIP LOCKED` fast path wrapped in candidate `SAVEPOINT`s followed by a post-lock fresh-snapshot overlap re-check, plus a deterministic `ORDER BY id ASC FOR NO KEY UPDATE` contention fallback when bays exist without committed overlaps. Verified via [`AppointmentConcurrencyIntegrationTest`](src/test/java/com/keyloop/scheduler/concurrency/AppointmentConcurrencyIntegrationTest.java) (`tenConcurrentNonOverlappingBookingsOnSingleBayDealershipAllSucceedWithoutFalseConflicts`).

2. **Hibernate 6 `LocalTime` Timezone Shift on Non-UTC Hosts:**
   - *Issue Identified:* Configuring legacy `hibernate.jdbc.time_zone=UTC` caused `LocalTime.of(8, 30)` (`SQL TIME WITHOUT TIME ZONE`) to shift through `java.sql.Time` `Calendar` conversion on a `UTC+07:00` workstation, failing repository assertions immediately.
   - *Refinement & Proof:* Replaced the legacy JDBC Calendar property with Hibernate 6's native `hibernate.timezone.default_storage: NORMALIZE_UTC`, keeping `OffsetDateTime` normalized in UTC while preserving wall-clock `LocalTime` fidelity across all host OS timezones.

3. **Spring Boot 3 `ProblemDetailsExceptionHandler` Precedence Subtlety:**
   - *Issue Identified:* Enabling `spring.mvc.problemdetails.enabled: true` registered Spring's built-in `ProblemDetailsExceptionHandler` at `@Order(0)`, intercepting `MethodArgumentNotValidException` before `@RestControllerAdvice` could attach `errorCode`, `fieldErrors`, and `correlationId`—caught immediately by `AppointmentApiIntegrationTest`.
   - *Refinement & Proof:* Refactored [`GlobalExceptionHandler`](src/main/java/com/keyloop/scheduler/exception/GlobalExceptionHandler.java) to extend `ResponseEntityExceptionHandler` and enrich `createResponseEntity`, unifying framework-level HTTP errors (`400`, `405`, `415`) and domain exceptions under a single RFC 7807 pipeline.

### 3. Ensuring Final Production Quality
- **Adversarial Concurrency Verification:** Used Java 21 Virtual Threads synchronized on a `CyclicBarrier` to release **50 simultaneous booking requests** at the exact same instant against Testcontainers `PostgreSQL 16`, proving exact capacity bounds (`2 confirmed`, `48 conflicts`, distinct bays/technicians, `0` unexpected errors).
- **Database-Level Invariant Tests:** Directly tested raw SQL inserts bypassing application services in [`SchemaConstraintIntegrationTest`](src/test/java/com/keyloop/scheduler/repository/SchemaConstraintIntegrationTest.java) to prove PostgreSQL's `btree_gist` exclusion constraints and composite foreign keys physically block double-bookings and cross-dealership resource mismatches.
- **Live Container & Contract Validation:** Built and booted the multi-stage Docker stack, exported the live `/v3/api-docs` contract to [`docs/openapi.json`](docs/openapi.json), and executed [`scripts/demo.ps1`](scripts/demo.ps1) against the running containers.
