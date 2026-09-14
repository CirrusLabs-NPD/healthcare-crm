import { test as base, expect, type Page } from "@playwright/test";

/**
 * The seed contract the browser specs assert against — kept in lockstep with
 * com.medicare.healthcarecrm.config.e2e.E2eDataSeeder and
 * design-docs/stories/S-9-e2e-harness-design.md §2.3.
 *
 * Dates are computed from the Monday of the current ISO week, the same anchor
 * the seeder uses, so nothing is hard-coded to a calendar date and the suite
 * never rots.
 */

export const ADMIN = { email: "admin@clinic.com", password: "password123" };

export const PROVIDERS = {
  alice: { name: "Dr. Alice Adams", hours: { start: "09:00", end: "17:00" } },
  bob: { name: "Dr. Bob Barnes", hours: { start: "10:00", end: "14:00" } },
  // Not bookable — must never appear in the provider picker or filter.
  nonProvider: { name: "Nora Non-Provider" },
};

export const CUSTOMER = { name: "Jordan Client" };

/** Monday of the current ISO week (local time), matching E2eDataSeeder.anchorMonday(). */
export function anchorMonday(now: Date = new Date()): Date {
  const d = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const dow = d.getDay(); // 0=Sun..6=Sat
  const deltaToMonday = dow === 0 ? -6 : 1 - dow;
  d.setDate(d.getDate() + deltaToMonday);
  return d;
}

export function addDays(date: Date, n: number): Date {
  const d = new Date(date);
  d.setDate(d.getDate() + n);
  return d;
}

/** yyyy-MM-dd in local time — matches the `data-date` attribute the grid emits. */
export function ymd(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

/** The seeded fixture dates, as the specs reference them. */
export const SEED = {
  monday: anchorMonday(),
  get tuesday() {
    return addDays(this.monday, 1);
  },
  /** Alice's all-day time-off (AC-8). */
  get wednesday() {
    return addDays(this.monday, 2);
  },
  appointments: {
    aliceShort: { title: "Alice — intake", start: "10:00", end: "10:30", status: "Pending" },
    aliceLong: { title: "Alice — procedure", start: "14:00", end: "15:30", status: "In Progress" },
    bob: { title: "Bob — consult", start: "11:00", end: "11:30", status: "Pending" },
  },
  /** Guaranteed-free window on the anchor Tuesday for the booking-flow spec. */
  freeWindow: { start: "15:30", end: "16:00" },
};

/** data-testid selector helper. */
export const testId = (id: string) => `[data-testid="${id}"]`;

/**
 * The clock string an appointment block prints, mirroring scheduling.js
 * `hm()` → `fmtClock()`: 12-hour, lower-case am/pm, minutes omitted on the hour
 * (e.g. "10:00" → "10am", "10:30" → "10:30am", "15:30" → "3:30pm"). Specs assert
 * against the format the UI actually renders rather than a reformatted copy of it.
 */
export function blockClock(hhmm: string): string {
  const [h, m] = hhmm.split(":").map(Number);
  const ampm = h < 12 ? "am" : "pm";
  let hr = h % 12;
  if (hr === 0) hr = 12;
  return `${hr}${m ? ":" + String(m).padStart(2, "0") : ""}${ampm}`;
}

/** The "start–end" text a block shows for a seeded appointment (en dash, U+2013). */
export function blockRange(a: { start: string; end: string }): string {
  return `${blockClock(a.start)}\u2013${blockClock(a.end)}`;
}

/**
 * Authenticated `page` fixture. Navigates to the calendar and picks a provider
 * by visible name in the toolbar select, returning once the grid has rendered.
 *
 * `resetFixture` is an auto-fixture: before every test it restores the seeded
 * scheduling data to its baseline via POST /api/e2e/reset (E2eResetController,
 * e2e profile only). The suite shares one app boot and one H2 database, so
 * without this a booking or series created by one spec would leak into a later
 * spec that asserts absolute counts. The reset carries the saved admin session
 * from storageState, so it is authenticated; /api/** is CSRF-exempt.
 */
export const test = base.extend<{ calendar: CalendarDriver; resetFixture: void }>({
  resetFixture: [
    async ({ page }, use) => {
      // Use the page's own request context so the reset unambiguously carries the
      // authenticated session cookies from storageState.
      const res = await page.request.post("/api/e2e/reset");
      if (!res.ok()) {
        throw new Error(`e2e fixture reset failed: HTTP ${res.status()} ${await res.text()}`);
      }
      await use();
    },
    { auto: true },
  ],
  calendar: async ({ page }, use) => {
    await use(new CalendarDriver(page));
  },
});

export { expect };

/** Thin page-object over the calendar's data-testid hooks. */
export class CalendarDriver {
  constructor(private readonly page: Page) {}

  async open(): Promise<void> {
    await this.page.goto("/admin/calendar");
    await this.page.locator(testId("toolbar-provider-select")).waitFor();
  }

  async selectProvider(name: string): Promise<void> {
    await this.page.locator(testId("toolbar-provider-select")).selectOption({ label: name });
    await this.grid().waitFor();
  }

  grid() {
    return this.page.locator(testId("calendar-grid"));
  }

  dayColumn(date: Date) {
    return this.page.locator(`${testId("calendar-day-column")}[data-date="${ymd(date)}"]`);
  }

  appointmentBlocks() {
    return this.page.locator(testId("appointment-block"));
  }

  rangeTitle() {
    return this.page.locator(testId("toolbar-range-title"));
  }
}
