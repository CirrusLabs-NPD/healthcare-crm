# S-2 — Scheduling: design & migration approach

**Ticket:** S-2 · **Status:** design · **Author:** Ivy (Staff Eng)
**Depends on:** S-1 story (`design-docs/stories/S-1-scheduling.md`, PR #1)
**Scope of this doc:** the *approach* — data model, calendar views, provider availability, the migration off free-text due dates, and the recurrence/series model. Trade-offs are named against each choice. This sets the pattern the feature stories build on.

> Diagrams (source is committed alongside, render with `diagram_render`):
> - Data model — `diagrams/S-2-scheduling-data-model.svg`
> - Migration phases + booking guard — `diagrams/S-2-migration-and-booking.svg`

---

## 1. What exists today (grounded in the code)

Stack: **Spring Boot 3.4.2, Java 21, Spring Data JPA, Thymeleaf + Bootstrap 5, MySQL 8**. App root is `backend/`, package `com.medicare.healthcarecrm`. Schema is Hibernate-managed: `spring.jpa.hibernate.ddl-auto=update` (`application.properties`) — **no Flyway/Liquibase**.

The scheduling anchor is `Tasks.dueDate` — a single `LocalDateTime` (`model/Tasks.java`). It is a flat deadline: no start/end, no duration, no booking. Its blast radius, all confirmed by search:

| Layer | File | Use |
|---|---|---|
| Entity | `model/Tasks.java` | `private LocalDateTime dueDate;` |
| Repository | `repository/TasksRepository.java` | `findOverdueTasks` / `findTasksDueSoon` JPQL compare `dueDate` to `now()` |
| Service | `service/TaskService.java` | passes `dueDate` through |
| Web | `controller/AdminController.java` | `/admin/tasks/**` binds `dueDate` from a `datetime-local` input |
| REST | `controller/api/TaskApiController.java` | `/api/tasks/**` binds `dueDate` on the JSON body |
| Seed | `config/DataInitializer.java` | sets `dueDate` on mock tasks (runs only when tables are empty) |
| Views | `admin/addTask.html`, `admin/tasks.html`, `admin/followup.html`, `employee.html` | one input + four read sites |

**Provider = `Employee`** (`model/Employee.java`) — and it has **no availability model**. There is **no recurrence** anywhere. Both S-1's assumption and mine: not every employee is necessarily a bookable provider, so we make that explicit rather than implicit.

---

## 2. Design decisions

### 2.1 New `Appointment` entity replaces the flat due date

A first-class `Appointment` with `startTime` + `endTime` (both `LocalDateTime`), a `provider` (Employee), a `customer`, a `type` (`APPOINTMENT | DEADLINE`), plus the fields Tasks already carries (title, priority, description, status). It keeps a nullable `series_id` back-reference.

- **Why a new entity, not new columns on `Tasks`:** the semantics change from "a task with a deadline" to "a booked slot with duration and a provider you can double-book". Overloading `Tasks` would leave the flat `dueDate` in place and force every reader to understand two meanings. A clean entity lets the old table be retired in one release.
- **Why keep a `DEADLINE` type:** S-1 flagged that not every current task is a real appointment — some are just deadlines with no duration. `type=DEADLINE` (start==end, or start=null when unscheduled) preserves those rows losslessly and keeps the Follow-Up Center working. This is the answer to S-1's open "deadline-vs-appointment classification" question.
- **Trade-off accepted:** two concepts (`APPOINTMENT`, `DEADLINE`) live in one table. Simpler than two tables and matches how the Follow-Up Center already treats everything uniformly; the cost is a `type` check in a couple of queries.

### 2.2 Calendar day/week views — server-rendered, query by range

Views are Thymeleaf pages (matching the existing admin UI), backed by one repository query: `findByProviderAndStartTimeBetween(provider, from, to)`. Day view = one provider × 24h; week view = provider(s) × 7 days. The controller computes the range from a `date` request param and renders a time-grid; no calendar JS framework is introduced.

- **Why server-side, no new frontend framework:** the app is Thymeleaf + Bootstrap today. Introducing FullCalendar/React here is a new infrastructure dependency that needs sign-off and that the rest of the team would have to learn. A CSS-grid time table rendered from the range query is boring, testable, and consistent. **Trade-off:** no drag-to-reschedule (already out of scope in S-1); rescheduling is an edit form. Revisit if interactivity becomes a stated requirement.
- **Indexing:** add a composite index `(provider_id, start_time)` — every calendar and conflict query filters on exactly that.

### 2.3 Provider availability — recurring weekly rules + dated exceptions

Two small entities keyed to a provider:
- **`AvailabilityRule`** — `(dayOfWeek, startTime, endTime)`: the normal weekly working hours (e.g. Mon–Fri 09:00–17:00). Multiple rows allow split shifts.
- **`AvailabilityException`** — `(date, available, startTime, endTime)`: overrides a specific date — `available=false` for time off (whole day if times null), `available=true` for extra hours.

`Employee` gains `bookableProvider` (default `true`, so migration is zero-config) and `defaultDurationMin` (default slot length, e.g. 30).

- **Why rules + exceptions and not a materialised slot table:** slots are derived on the fly from rules minus exceptions minus booked appointments. A pre-generated slot table has to be regenerated whenever hours change and is a stale-data trap. **Trade-off:** availability is computed per request rather than looked up; cheap at this scale (one provider, one day) and correct by construction.
- **Out of scope (from S-1):** rooms/resources, cross-timezone. All times are the server's local zone, consistent with today's `LocalDateTime` usage.

### 2.4 The booking guard — one place, enforced server-side

A single `AppointmentService.book(...)` / `.reschedule(...)` runs the guard for **both** the web and API paths (never in the browser):
1. within an `AvailabilityRule` for that weekday, **and**
2. not blocked by an `AvailabilityException`, **and**
3. no overlap with an existing appointment for the same provider.

Fail → reject with a reason: web = form error, API = **HTTP 409 Conflict** with a message. The overlap check is a repository count query inside the transaction; to close the last race between two identical concurrent requests it takes a short provider-scoped lock (or a DB unique/exclusion guard). This is the answer to S-1's AC-7…AC-10 (no double-booking on web **and** API).

- **Trade-off accepted:** the guard is application-level, not a pure DB constraint (MySQL has no range-exclusion constraint like Postgres). We accept a transactional check + lock instead. Named so the engineer doesn't reach for a constraint that doesn't exist on MySQL 8.

### 2.5 Recurrence / series

**`AppointmentSeries`** holds the rule; concrete `Appointment` rows are **generated** from it (materialised), each linking back via `series_id`.
- Rule fields: `freq (DAILY|WEEKLY|MONTHLY)`, `intervalCount` (every N), `byDay` (e.g. `MO,TU`) for weekly, and **one** end condition — `untilDate` **or** `occurrenceCount`. A deliberate, small subset of RFC 5545 (S-1 put full iCal out of scope).
- **Generation is bounded**: materialise a rolling horizon (e.g. next 90 days / next N occurrences), not infinitely — a scheduled admin process extends it.
- **Edit/cancel scopes** (answers S-1's open question): **this occurrence**, **this and following**, **whole series**. "This occurrence" detaches one row; "this and following" splits the series at a date; "whole series" edits the rule and regenerates future unbooked rows.
- **Conflict during generation** (S-1's open policy question): if a generated occurrence would violate the booking guard, **create it flagged `status=CONFLICT` and skip the block, surfacing it for the admin to resolve** — never silently drop it and never fail the whole series. Named as the chosen policy; revisit if admins prefer hard-skip.

- **Why materialise occurrences vs. compute virtually:** materialised rows can be individually moved, cancelled and conflict-checked like any appointment, and the calendar range query stays a single simple `BETWEEN`. **Trade-off:** a generation/extension job and more rows; acceptable and far simpler than virtual-occurrence logic threaded through every query.

---

## 3. Migration path (replacing free-text due dates)

Because there is **no migration tool**, `ddl-auto=update` handles *additive* schema (new tables, new nullable columns) automatically but will **not** drop/rename/backfill. So the cutover is explicit and phased (see `diagrams/S-2-migration-and-booking.svg`):

**Phase 1 — Additive, zero downtime.** Add `Appointment`, `AvailabilityRule`, `AvailabilityException`, `AppointmentSeries` tables and the two new `Employee` columns. Hibernate creates them on boot. Nothing reads them yet; `Tasks` untouched.

**Phase 2 — Backfill (one-off, idempotent).** A guarded `ApplicationRunner` (same mechanism as `DataInitializer`, skips if `Appointment` already populated) copies every `Tasks` row into an `Appointment`: `type=DEADLINE`, `start=end=dueDate` (or `start=null` when `dueDate` is null), carrying title/priority/employee/customer/status. Idempotent so a redeploy is safe.

**Phase 3 — Cutover.** Point controllers, templates and the Follow-Up Center at `Appointment` (`findOverdue`/`findDueSoon` re-expressed on `startTime`). Keep `Tasks` **read-only for one release** as a safety net, then drop it in a later ticket.

- **Trade-off accepted:** we carry `Tasks` and `Appointment` in parallel for one release rather than a hard swap. Costs a redundant table briefly; buys a rollback that doesn't need a DB restore. This is the safe default given no migration framework.
- **Recommendation (own ticket, not this design):** adopt **Flyway** before Phase 3 so the drop and future schema changes are versioned. Flagged as debt, not smuggled into this work.

---

## 4. What the engineers build (sequencing)

1. Entities + repositories + the two `Employee` columns (Phase 1). *Proves: schema created, app boots.*
2. `AppointmentService` with the booking guard + unit tests (guard is the risky logic — test overlap, edge of availability, exception days).
3. Backfill runner + test that a seeded `Tasks` row lands as a `DEADLINE` appointment (Phase 2).
4. Calendar day/week controller + Thymeleaf views (range query).
5. Availability CRUD (rules + exceptions) for admins.
6. Recurrence: `AppointmentSeries` + bounded generator + edit/cancel scopes.
7. Cutover: repoint Follow-Up Center + templates; retire `Tasks` read-only.

Each step follows the existing controller/service/repository/Thymeleaf conventions — no new frameworks, no new datastore.

## 5. Explicitly out of scope (inherited from S-1)
Patient self-booking, reminders/notifications, external calendar sync, drag-and-drop, rooms/resources, cross-timezone, full RFC 5545. Named so no story assumes them.
