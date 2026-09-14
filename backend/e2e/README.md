# Scheduling e2e (Playwright) — S-9

Browser end-to-end suite for the scheduling calendar and booking flow. Self-contained
Node project: **Maven never sees it**, and it drives the *delivered S-5 UI* through a real
Chromium against the packaged application jar.

## How it runs

1. **Package the app with the `e2e` Maven profile.** That profile promotes the H2 driver
   from `test` to `runtime` scope so `java -jar` can boot on the `e2e` Spring profile
   (in-memory H2, no secrets). The default build leaves H2 test-scoped, so the production
   artifact never carries it.

   ```bash
   cd backend
   ./mvnw -Pe2e -DskipTests package
   ```

2. **Install and run the suite.** Playwright's `webServer` boots the jar
   (`--spring.profiles.active=e2e`) and waits for `/login` before running specs.

   ```bash
   cd backend/e2e
   npm ci
   npx playwright install --with-deps chromium
   npm test
   ```

The app is authenticated once through the real Spring Security form (`global-setup.ts`,
admin `admin@clinic.com` / `password123`, CSRF-protected) and the session is saved to
`storageState.json`; every spec reuses it.

## Data contract

Test data comes from `com.medicare.healthcarecrm.config.e2e.E2eDataSeeder`
(`@Profile("e2e")`), anchored to the **Monday of the current ISO week** so the fixture
never rots. `fixtures.ts` recomputes the same anchor — specs never hard-code a date.
Keep the two in lockstep; the exact fixture is documented in
`design-docs/stories/S-9-e2e-harness-design.md` §2.3.

### Per-test isolation

The whole suite shares **one app boot and one in-memory H2 database**, so an appointment
a mutating spec creates (a booking, a recurring series) would otherwise leak into a later
spec that asserts absolute counts. An auto-fixture in `fixtures.ts` calls
`POST /api/e2e/reset` before every test — `E2eResetController` (`@Profile("e2e")`,
absent from the production artifact) clears the scheduling tables and re-runs the seeder,
so every spec starts from the identical baseline and the suite is order-independent. The
reset carries the saved admin session; `/api/**` is CSRF-exempt.

## Selectors

Specs address the UI through the additive `data-testid` hooks on `calendar.html` and the
`scheduling.js` builders (see the design doc for the full table). Prefer these over CSS
classes or ids so the specs survive styling changes.

## Scripts

| Command | What it does |
|---|---|
| `npm test` | Run the suite (boots the jar via `webServer`). |
| `npm run test:headed` | Same, with a visible browser. |
| `npm run typecheck` | `tsc --noEmit` over the specs and fixtures. |
| `npm run report` | Open the last HTML report. |

## Overriding the target

- `E2E_PORT` / `E2E_BASE_URL` — point the suite at a different host/port.
- `E2E_WEBSERVER_CMD` — replace the boot command (e.g. to reuse an already-running app).

## Scope

This directory is the **harness + smoke spec**. The six acceptance specs
(calendar-views, provider-filter, appointment-block, availability, recurrence,
booking-flow) and the AC-2/13/20 JUnit gap tests are tracked on S-9 and drop into
`specs/` (Playwright) and `src/test/java` (JUnit, run by `mvnw verify`) respectively.
