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
    aliceShort: { title: "Alice — intake", start: "10:00", end: "10:30", status: "Scheduled" },
    aliceLong: { title: "Alice — procedure", start: "14:00", end: "15:30", status: "In Progress" },
    bob: { title: "Bob — consult", start: "11:00", end: "11:30", status: "Scheduled" },
  },
  /** Guaranteed-free window on the anchor Tuesday for the booking-flow spec. */
  freeWindow: { start: "15:30", end: "16:00" },
};

/** data-testid selector helper. */
export const testId = (id: string) => `[data-testid="${id}"]`;

/**
 * Authenticated `page` fixture. Navigates to the calendar and picks a provider
 * by visible name in the toolbar select, returning once the grid has rendered.
 */
export const test = base.extend<{ calendar: CalendarDriver }>({
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
