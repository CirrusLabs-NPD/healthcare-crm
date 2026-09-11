# S-1 — Scheduling: appointment calendar, provider availability, and recurring series

**Status:** Ready for design (S-2)
**Repo:** `CirrusLabs-NPD/healthcare-crm` · Spring Boot 3.4.2 / Java 21 / Spring Data JPA / Thymeleaf + Bootstrap 5 / MySQL 8
**Author:** Ivy (PM)

---

## 1. Problem

The CRM schedules work against a single flat timestamp. `Tasks.dueDate` is one
`LocalDateTime` (`model/Tasks.java:43`), edited through a lone `datetime-local`
input (`admin/addTask.html:73`) and rendered as text in four places
(`admin/tasks.html:42`, `admin/followup.html:43,89`, `employee.html:59`). The
Follow-Up Center reasons about it purely as a deadline — overdue vs. due-soon
(`TasksRepository.findOverdueTasks` / `findTasksDueSoon`).

That model cannot express what a healthcare centre actually books:

- **A due date is a point, not an appointment.** There is no start/end, no
  duration, no notion of the provider being *occupied* for a block of time. Two
  tasks can be "due" at the same minute against the same employee and nothing
  objects — there is no double-booking protection because there is no booking.
- **There is no provider availability.** `Employee` (the provider) has no
  working hours, no time off, no concept of when they can be seen
  (`model/Employee.java`). A scheduler cannot answer "when is Dr. Alice free on
  Tuesday?" from the current data.
- **There is no calendar.** Work is only ever seen as flat lists
  (`/admin/tasks`, `/admin/follow-up`, employee task list). There is no day or
  week view to see load, gaps, or clashes.
- **Nothing repeats.** Every `Tasks` row is standalone; a weekly follow-up call
  or a course of appointments must be typed in one at a time, with no link
  between them and no way to edit or cancel the set.

> **Why it matters:** the centre's real unit of work is an *appointment* — a
> customer seen by a provider, for a length of time, that must not collide with
> that provider's other appointments or fall outside their hours, and that often
> recurs. Until the schedule can represent that, the CRM can record intentions
> but cannot run a clinic's calendar.

## 2. Outcome we want

An admin can open a **day or week calendar**, see each provider's booked
appointments and free time, book an appointment into an available slot (blocked
from double-booking or booking outside availability), and create a **recurring
series** that generates the individual appointments — with the existing
deadline-style tasks migrated onto this model so the Follow-Up Center keeps
working.

---

## 3. Scope

Delivered as capabilities. S-2 owns the technical design (entity shape,
endpoints, conflict algorithm, migration mechanism); this story fixes *what must
be true*, not *how*.

### 3.1 Appointment model (replaces the flat due date)
- An appointment has a **provider** (`Employee`), a **customer**, a **start**
  and **end** (or start + duration), plus the fields tasks carry today
  (name/type, description, priority, status).
- The existing task concept is subsumed: what is today a `Tasks` row with a
  `dueDate` becomes an appointment with a start/end. Tasks that are genuinely
  deadlines rather than booked visits are preserved (see 3.4).

### 3.2 Calendar views (day / week)
- A **day view** and a **week view** for a chosen date range.
- Filterable by **provider** (one, several, or all).
- Appointments render as time blocks positioned and sized by their start/end;
  availability and time-off render distinctly from booked time.
- Navigation: previous / next / today; switch between day and week.

### 3.3 Provider availability
- Each provider has **recurring weekly working hours** (e.g. Mon–Fri 09:00–17:00,
  with breaks) and **date-specific exceptions** (time off / extra hours).
- Availability is visible on the calendar and is enforced when booking:
  an appointment **cannot** be booked outside a provider's available time, and
  **cannot overlap** another appointment for the same provider (no
  double-booking).
- Enforcement holds on **both** entry points — the web forms (`AdminController`,
  `/admin/tasks/**`) and the REST API (`TaskApiController`, `/api/tasks/**`) —
  returning a clear validation error, not a 500.

### 3.4 Migration off free-text / flat due dates
- Existing `Tasks.dueDate` values are migrated onto the new model with **no data
  loss**. The migration rule (e.g. `start = dueDate`, `end = dueDate +
  default duration`, or classify as a deadline-type item) is decided in S-2 and
  stated in the migration.
- The **Follow-Up Center keeps working**: overdue and due-soon continue to
  return the correct records after migration (queries move to the new time
  field). No follow-up record silently disappears.
- Seeded/mock data (`config/DataInitializer.java:182`) is updated to produce
  valid appointments/availability under the new model.
- Because the schema is Hibernate-managed with `spring.jpa.hibernate.ddl-auto=update`
  (`application.properties:17`) and there is **no Flyway/Liquibase**, dropping/
  renaming `dueDate` and backfilling is **not** automatic — the migration needs an
  explicit, idempotent, ordered data step (approach chosen in S-2). Adding new
  nullable columns is automatic; removing the old column and moving data is not.

### 3.5 Recurring tasks / appointment series
- A user can define a **recurrence rule** (at minimum: daily / weekly-on-selected-
  days / monthly, an interval, and an end condition — end date or occurrence
  count) that generates individual appointments.
- Generated occurrences are **linked to their series** so the series can be
  identified.
- **Editing / cancelling** offers *this occurrence* vs. *this and future* vs.
  *the whole series* (the exact set of scopes is finalised in S-2; at least
  single-occurrence and whole-series must exist).
- Each generated occurrence is still subject to availability and no-double-booking
  rules (3.3); a generation that would clash is surfaced, not silently dropped or
  silently overlapped — behaviour on conflict is defined in S-2.

---

## 4. Out of scope (explicitly)

Named so they are not assumed in later stories:

- **Patient/customer self-service booking.** Admin/employee-facing only; no
  public booking page or patient login flow.
- **Notifications / reminders** (email, SMS, push) for upcoming appointments.
- **External calendar sync** (Google/Outlook/iCal import or export).
- **Drag-and-drop rescheduling** on the calendar. Booking/editing is via forms
  in this story; direct manipulation can be a follow-up.
- **Resource/room booking** and multi-provider (group) appointments — a single
  provider per appointment.
- **Waitlists, cancellation reasons, no-show tracking, billing/insurance
  interaction** with the schedule.
- **Timezone handling across zones.** The app uses server-local `LocalDateTime`
  today; this story keeps a single clinic timezone and does not introduce
  per-user zones (flagged as a known limitation for a later story).
- **Full RFC 5545 (iCalendar RRULE) coverage.** Only the recurrence patterns in
  3.5; no arbitrary RRULE strings, no complex exceptions beyond per-occurrence
  edit/cancel.

---

## 5. Acceptance criteria

Each is independently testable. "AC-n" is stable for QA (S-6) to reference.

### Appointment model
- **AC-1** An appointment persists a provider, a customer, a start and an end
  (end strictly after start), and rejects a save where end ≤ start with a
  validation error (not a 500) on both the web form and `POST/PUT /api/tasks`.
- **AC-2** Creating an appointment via `POST /api/tasks` with valid data returns
  201 and the persisted record includes the start and end; retrieving it via
  `GET /api/tasks/{id}` returns the same times.

### Calendar views
- **AC-3** `GET` of the day view for a given date shows every appointment whose
  time falls on that date, each positioned by its start and sized by its
  duration, for the selected provider(s).
- **AC-4** `GET` of the week view for a given week shows all seven days with
  appointments placed on the correct day and time; previous/next/today
  navigation moves the range by one day (day view) or one week (week view).
- **AC-5** Filtering the calendar to a single provider shows only that
  provider's appointments and availability; selecting all providers shows all.
- **AC-6** An appointment block on the calendar shows enough to identify it
  (customer, provider, time, status) and links to its edit form.

### Provider availability
- **AC-7** A provider can be given recurring weekly working hours; those hours
  render as available time on the day and week views and time outside them
  renders as unavailable.
- **AC-8** A date-specific exception (time off) for a provider removes that
  block from availability on the affected date, visible on the calendar.
- **AC-9** Attempting to book an appointment **outside** a provider's
  availability is rejected with a clear message on both the web form and the
  API; no record is created.
- **AC-10** Attempting to book an appointment that **overlaps** an existing
  appointment for the same provider is rejected with a clear message on both the
  web form and the API; no record is created. Two providers may hold
  overlapping appointments with no conflict.

### Migration off free-text / flat due dates
- **AC-11** After migration, every pre-existing task/`dueDate` record exists as
  an appointment (or classified deadline item) with its time preserved per the
  S-2 rule; the count of migrated records equals the count before migration
  (no loss).
- **AC-12** The Follow-Up Center (`/admin/follow-up`) returns the same set of
  overdue and due-soon records after migration as the equivalent pre-migration
  data would have — the follow-up queries read the new time field and behave
  identically at the boundaries (overdue = past & not Completed; due-soon = next
  7 days & not Completed).
- **AC-13** The four current read sites (`admin/tasks.html`,
  `admin/followup.html` ×2, `employee.html`) render the appointment time with no
  broken references to the removed `dueDate` field, and the app starts cleanly
  against a migrated schema.
- **AC-14** `DataInitializer` seeds valid appointments and provider availability
  on a fresh database, and the app boots with `ddl-auto=update` without a
  schema error.

### Recurring series
- **AC-15** Creating a weekly series on selected weekdays with an end condition
  (end date **or** occurrence count) generates exactly the expected individual
  appointments, each linked to the series.
- **AC-16** Editing a single occurrence of a series changes only that occurrence
  and leaves the rest of the series unchanged.
- **AC-17** Cancelling the whole series removes (or cancels) all its
  occurrences; cancelling a single occurrence removes only that one.
- **AC-18** When a generated occurrence would violate availability or overlap an
  existing appointment, it is handled per the S-2-defined rule (surfaced to the
  user or flagged), and the outcome is deterministic and observable — never a
  silent double-book and never a silent drop.

### Non-regression
- **AC-19** Existing customer, employee, insurance and auth flows are unchanged;
  the build passes and the app starts.
- **AC-20** Swagger UI (`/swagger-ui.html`) documents the new/changed task
  endpoints including the appointment time fields and any availability
  endpoints.

---

## 6. Current-state reference (what S-2 builds on)

| Concern | Where it lives today | Change implied |
|---|---|---|
| Task time | `Tasks.dueDate` — single `LocalDateTime`, `nullable=false` (`model/Tasks.java:43`) | Replace with start/end (or start+duration) |
| Provider | `Employee` entity (`model/Employee.java`) — no availability | Add weekly hours + exceptions |
| Recurrence | none | Add series + recurrence rule + occurrence link |
| Web entry | `AdminController` `/admin/tasks/**`, form `admin/addTask.html` (`datetime-local`) | Availability/overlap validation; calendar views |
| API entry | `TaskApiController` `/api/tasks/**` | Same validation; expose times |
| Follow-up | `TasksRepository.findOverdueTasks` / `findTasksDueSoon`, `/admin/follow-up` | Repoint to new time field |
| Read sites | `admin/tasks.html:42`, `admin/followup.html:43,89`, `employee.html:59` | Render appointment time |
| Seed data | `DataInitializer.java:182` (`dueDate` = now ± days) | Seed appointments + availability |
| Schema mgmt | Hibernate `ddl-auto=update`, **no Flyway/Liquibase** (`application.properties:17`) | Explicit, idempotent data-migration step for the column change |

## 7. Assumptions & open questions for S-2

- **Assumption:** "provider" = `Employee`. If some employees are not bookable
  providers, S-2 needs a provider flag/role. *(Not confirmed against a stakeholder
  — flagged.)*
- **Assumption:** single clinic timezone, server-local time (matches today's
  `LocalDateTime` usage). Per-user timezones are out of scope.
- **Open:** default appointment duration for migrating a bare `dueDate`.
- **Open:** whether pure "deadline" tasks (no visit) should remain a distinct
  type alongside appointments, or all become zero-duration appointments.
- **Open:** exact edit/cancel scopes for a series beyond single + whole
  (this-and-future?).
- **Open:** conflict policy when generating a recurring occurrence onto an
  unavailable/occupied slot (skip + report, block the whole generation, or force).
