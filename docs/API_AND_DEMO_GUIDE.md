# API Reference & Interactive Demo Guide

This guide documents the HTTP contract, seeded reference fixtures, cURL / PowerShell client examples, and a structured walkthrough flow for demonstrating the **Unified Service Scheduler**.

---

## 1. Interactive Client Harnesses

Because this repository implements the **Backend Service & Persistence Layer** of the Unified Service Scheduler, three client-side harnesses are bundled out of the box:

| Harness | Location / Endpoint | Purpose |
| :--- | :--- | :--- |
| **Swagger UI** | `http://localhost:8080/swagger-ui.html` | Interactive browser-based API explorer with pre-populated request schemas |
| **OpenAPI 3.1 Spec** | [`docs/openapi.json`](openapi.json) & `http://localhost:8080/v3/api-docs` | Machine-readable contract for client SDK generation or Postman import |
| **Grafana Dashboard & Prometheus UI** | `http://localhost:3000` (Grafana) & `http://localhost:9090` (Prometheus) | Auto-provisioned real-time observability dashboard tracking booking outcomes, latency quantiles, RPS, and HikariCP pool utilization |
| **Automated CLI Demo & Burst Harness** | [`scripts/demo.ps1`](../scripts/demo.ps1) (PowerShell) / [`scripts/demo.sh`](../scripts/demo.sh) (Bash) | Executes catalog discovery, availability checks, booking, a **12-request concurrent burst**, cancellation, capacity recovery, and Prometheus metric inspection |

---

## 2. Seeded Reference Data (`R__seed_demo_data.sql`)

When running in the `default` or `docker` profile, Flyway automatically seeds two dealerships, four service types, six bays, and six technicians with realistic skill matrices:

### Dealerships
| Dealership ID | Code | Name | Timezone | Operating Hours | Active Bays | Technicians |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `11111111-1111-1111-1111-111111111111` | `KL-LON-01` | Keyloop Flagship London West | `Europe/London` | `08:00–18:00` | 3 (`BAY-01..03`; `BAY-04` inactive) | 4 (`Oliver`, `Amelia`, `Priya`, `Liam`) |
| `11111111-1111-1111-1111-111111111112` | `KL-MAN-02` | Keyloop Manchester EV Hub | `UTC` | `08:00–18:00` | 2 (`BAY-01..02`) | 2 (`Hannah`, `Daniel`) |

### Service Types & Required Qualifications
| Service Type ID | Code | Duration | Required Qualification | Qualified Technicians at London (`KL-LON-01`) |
| :--- | :--- | :--- | :--- | :--- |
| `22222222-2222-2222-2222-222222222221` | `OIL_CHANGE` | `60 min` | `GENERAL_MAINT` | Oliver (1 skill), Amelia (2), Priya (2) |
| `22222222-2222-2222-2222-222222222222` | `BRAKE_OVERHAUL` | `120 min` | `BRAKE_SPEC` | Amelia (2 skills), Liam (3) |
| `22222222-2222-2222-2222-222222222223` | `EV_BATTERY_DIAG` | `90 min` | `EV_HIGH_VOLTAGE` | Priya (2 skills), Liam (3) |
| `22222222-2222-2222-2222-222222222224` | `MOT_INSPECTION` | `45 min` | `MOT_TESTER` | Liam (3 skills) |

> **Specialist Preservation Policy:** When multiple qualified technicians are free, the scheduler allocates the technician with the **fewest total qualifications** first (e.g., `Oliver` is assigned `OIL_CHANGE` before `Priya`), preserving multi-skilled EV and MOT specialists for high-skill bookings.

---

## 3. Core API Walkthrough (cURL Examples)

### 3.1 Discover Dealerships & Technician Matrix
```bash
curl -s http://localhost:8080/api/v1/dealerships/11111111-1111-1111-1111-111111111111
```

### 3.2 Check Daily Time-Slot Availability
Computes bookable windows across the dealership's local operating hours where at least one active `ServiceBay` and one qualified `Technician` remain **continuously free** across the entire service duration:
```bash
curl -s "http://localhost:8080/api/v1/availability?dealershipId=11111111-1111-1111-1111-111111111111&serviceTypeId=22222222-2222-2222-2222-222222222223&date=2026-12-15&slotIntervalMinutes=30"
```

### 3.3 Check Exact Instant Availability
```bash
curl -s "http://localhost:8080/api/v1/availability/check?dealershipId=11111111-1111-1111-1111-111111111111&serviceTypeId=22222222-2222-2222-2222-222222222223&startTime=2026-12-15T10:00:00Z"
```

### 3.4 Book a Resource-Constrained Appointment (`201 Created`)
```bash
curl -i -X POST http://localhost:8080/api/v1/appointments \
  -H "Content-Type: application/json" \
  -H "X-Correlation-ID: demo-req-001" \
  -d '{
    "dealershipId": "11111111-1111-1111-1111-111111111111",
    "customerId": "aaaa1111-1111-1111-1111-111111111111",
    "vehicleId": "bbbb1111-1111-1111-1111-111111111111",
    "serviceTypeId": "22222222-2222-2222-2222-222222222223",
    "desiredStartTime": "2026-12-15T10:00:00Z",
    "notes": "High-voltage battery cell balance check"
  }'
```

Sample `201 Created` response:
```json
{
  "id": "9f62f2c8-64d0-4a8e-9b29-14fd72c2a1b4",
  "dealershipId": "11111111-1111-1111-1111-111111111111",
  "dealershipCode": "KL-LON-01",
  "dealershipName": "Keyloop Flagship London West",
  "customerId": "aaaa1111-1111-1111-1111-111111111111",
  "vehicleId": "bbbb1111-1111-1111-1111-111111111111",
  "serviceTypeId": "22222222-2222-2222-2222-222222222223",
  "serviceTypeCode": "EV_BATTERY_DIAG",
  "serviceTypeName": "High-Voltage EV Battery & Thermal Diagnostic",
  "durationMinutes": 90,
  "serviceBayId": "33333333-3333-3333-3333-333333333331",
  "serviceBayNumber": "BAY-01",
  "serviceBayName": "Hydraulic Lift 1",
  "technicianId": "44444444-4444-4444-4444-444444444443",
  "technicianEmployeeCode": "LON-TECH-03",
  "technicianName": "Priya Patel",
  "startTime": "2026-12-15T10:00:00Z",
  "endTime": "2026-12-15T11:30:00Z",
  "status": "CONFIRMED",
  "notes": "High-voltage battery cell balance check",
  "createdAt": "2026-10-30T14:25:00Z",
  "cancelledAt": null
}
```

### 3.5 Cancel an Appointment & Release Resources (`200 OK`)
```bash
curl -s -X POST http://localhost:8080/api/v1/appointments/<APPOINTMENT_ID>/cancel
```

---

## 4. RFC 7807 Error Catalog (`application/problem+json`)

Every error response includes `type`, `title`, `status`, `detail`, `instance`, `errorCode`, `timestamp`, and `correlationId`:

| HTTP Status | `errorCode` | Trigger Condition |
| :--- | :--- | :--- |
| `400 Bad Request` | `VALIDATION_FAILED` | Bean validation failure (includes `fieldErrors` map) or constraint violation |
| `400 Bad Request` | `MALFORMED_REQUEST` | Unparseable JSON, invalid UUID/ISO-8601 format, or missing required query param |
| `404 Not Found` | `DEALERSHIP_NOT_FOUND` | Unknown `dealershipId` |
| `404 Not Found` | `SERVICE_TYPE_NOT_FOUND` | Unknown `serviceTypeId` |
| `404 Not Found` | `APPOINTMENT_NOT_FOUND` | Unknown `appointmentId` |
| `409 Conflict` | `VEHICLE_ALREADY_BOOKED` | Vehicle already holds an overlapping `CONFIRMED` appointment |
| `409 Conflict` | `NO_SERVICE_BAY_AVAILABLE` | All active bays at the dealership are occupied during `[startTime, endTime)` |
| `409 Conflict` | `NO_QUALIFIED_TECHNICIAN_AVAILABLE` | All active technicians holding `required_qualification` are occupied |
| `409 Conflict` | `RESOURCE_CONFLICT` | Database GiST exclusion constraint safety net caught a concurrent overlap |
| `409 Conflict` | `RESOURCE_CONTENTION_TIMEOUT` | Lock wait exceeded configured `lock_timeout` under extreme contention |
| `422 Unprocessable Entity` | `PAST_START_TIME` | `desiredStartTime` is in the past |
| `422 Unprocessable Entity` | `OUTSIDE_OPERATING_HOURS` | `[startTime, endTime)` falls outside dealership local `open_time..close_time` or spans midnight |
| `422 Unprocessable Entity` | `INVALID_SLOT_INTERVAL` | `slotIntervalMinutes` outside `[5, 240]` |
| `500 Internal Server Error` | `INTERNAL_ERROR` | Unexpected unhandled runtime error |

---

## 5. Suggested 7-Minute Video Walkthrough Flow

1. **0:00 – 1:00 | Context & Problem Statement**
   - Introduce **Unified Service Scheduler** (Ownership domain): replacing manual dealership diaries with atomic, dual-resource (`ServiceBay` + qualified `Technician`) scheduling.
2. **1:00 – 3:00 | System Design & Defense-in-Depth Concurrency (`docs/SYSTEM_DESIGN.md`)**
   - Walk through the architecture diagram, ER schema (`[start_time, end_time)` half-open UTC intervals), and the 3-layer concurrency model:
     1. Deadlock-free lock acquisition order (`vehicle` advisory lock $\rightarrow$ `service_bays` $\rightarrow$ `technicians`).
     2. Fast-path `FOR UPDATE SKIP LOCKED` + post-lock fresh-snapshot verification + deterministic contention fallback (`AppointmentLockingRepositoryImpl`).
     3. PostgreSQL `btree_gist` partial exclusion constraints (`V1__init_schema.sql`) guaranteeing zero double-booking physically in storage.
3. **3:00 – 4:30 | AI Engineering & Verification Story (`README.md`)**
   - Highlight key human architectural interventions:
     - Rejecting distributed Redis locks in favor of single-source-of-truth PostgreSQL row locks + GiST exclusion constraints.
     - Catching subtle `SKIP LOCKED` false `409` conflicts on single-bay non-overlapping concurrent bookings and `READ COMMITTED` subquery snapshot staleness.
     - Selecting hybrid Spring Data JPA (`ddl-auto: validate`, `open-in-view: false`, `NORMALIZE_UTC`) + custom native SQL locking fragment, and catching the `+07:00` host `LocalTime` offset trap via integration tests.
4. **4:30 – 6:15 | Live Demo (`scripts/demo.ps1` + Grafana UI + Swagger UI + Concurrency Tests)**
   - Run `.\scripts\demo.ps1` against `docker compose up -d`: show single booking, **12-request concurrent EV burst** (`2 Created`, `10 Conflict`), cancellation & immediate slot recovery, and watch the live charts update on **Grafana (`http://localhost:3000`)**.
   - Briefly show `.\mvnw.cmd test "-Dtest=AppointmentConcurrencyIntegrationTest"` passing 50 simultaneous Virtual Threads synchronized on a `CyclicBarrier`.
5. **6:15 – 7:00 | Takeaways & Production Evolution**
   - Discuss key learnings around database snapshot isolation vs row locks, observability (`X-Correlation-ID`, Logstash JSON logs, OpenTelemetry bridge), and future extensions (multi-day repair orders, technician shift calendars, outbox event publishing).
