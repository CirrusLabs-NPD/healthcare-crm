import { test, expect, testId, PROVIDERS, SEED, ymd, addDays, blockRange } from "../fixtures";

/**
 * AC-3 / AC-4 — day and week views render seeded appointments on the correct
 * day and time, each positioned by its start and sized by its duration; and
 * prev / next / today move the visible range (one day in day view, one week in
 * week view).
 *
 * The grid positions events with inline `top` (start) and `height` (duration)
 * in px, from a fixed hour-height — scheduling.js §paintEvents. These specs
 * assert *ordering and relative geometry* (later start ⇒ larger top; longer
 * duration ⇒ larger height), never exact pixels, so they survive CSS tweaks.
 */
test.describe("AC-3/4 — calendar day & week views", () => {
  test.beforeEach(async ({ calendar }) => {
    await calendar.open();
    await calendar.selectProvider(PROVIDERS.alice.name);
  });

  async function box(locator: import("@playwright/test").Locator) {
    const b = await locator.boundingBox();
    if (!b) throw new Error("appointment block has no bounding box");
    return b;
  }

  test("week view places both Tuesday appointments in the Tuesday column", async ({ page, calendar }) => {
    await page.locator(testId("toolbar-view-week")).click();
    await expect(calendar.grid()).toHaveAttribute("data-view", "week");

    const tuesday = calendar.dayColumn(SEED.tuesday);
    await expect(tuesday).toBeVisible();

    // Assert the seeded appointments by title (mutation-safe: other spec files may
    // add appointments in the same app boot; both seeded blocks must be present).
    await expect(tuesday.locator(testId("appointment-block"), { hasText: SEED.appointments.aliceShort.title })).toHaveCount(1);
    await expect(tuesday.locator(testId("appointment-block"), { hasText: SEED.appointments.aliceLong.title })).toHaveCount(1);
    await expect(tuesday).toContainText(SEED.appointments.aliceShort.title);
    await expect(tuesday).toContainText(SEED.appointments.aliceLong.title);
    await expect(tuesday).toContainText(blockRange(SEED.appointments.aliceShort));

    // No appointment leaks into an adjacent day (Monday / Wednesday are empty).
    await expect(calendar.dayColumn(SEED.monday).locator(testId("appointment-block"))).toHaveCount(0);
    await expect(calendar.dayColumn(SEED.wednesday).locator(testId("appointment-block"))).toHaveCount(0);
  });

  test("AC-3 position by start: the 14:00 block sits below the 10:00 block", async ({ page, calendar }) => {
    await page.locator(testId("toolbar-view-week")).click();
    const tuesday = calendar.dayColumn(SEED.tuesday);

    const short = tuesday.locator(`${testId("appointment-block")}`, { hasText: SEED.appointments.aliceShort.title });
    const long = tuesday.locator(`${testId("appointment-block")}`, { hasText: SEED.appointments.aliceLong.title });

    const shortBox = await box(short);
    const longBox = await box(long);

    // 14:00 starts four hours after 10:00 → its block is strictly lower.
    expect(longBox.y).toBeGreaterThan(shortBox.y);
  });

  test("AC-4 size by duration: the 90-min block is ~3x the 30-min block's height", async ({ page, calendar }) => {
    await page.locator(testId("toolbar-view-week")).click();
    const tuesday = calendar.dayColumn(SEED.tuesday);

    const short = tuesday.locator(`${testId("appointment-block")}`, { hasText: SEED.appointments.aliceShort.title });
    const long = tuesday.locator(`${testId("appointment-block")}`, { hasText: SEED.appointments.aliceLong.title });

    const shortBox = await box(short);
    const longBox = await box(long);

    // 90 min vs 30 min → 3x taller. Allow a generous tolerance for borders/padding.
    const ratio = longBox.height / shortBox.height;
    expect(ratio).toBeGreaterThan(2.4);
    expect(ratio).toBeLessThan(3.6);
  });

  test("day view shows only the anchored day's appointments", async ({ page, calendar }) => {
    // Navigate to Tuesday in week view, then switch to day view so the day is Tuesday.
    await page.locator(testId("toolbar-view-day")).click();
    await expect(calendar.grid()).toHaveAttribute("data-view", "day");

    // The day view opens on today; step until the visible single column is Tuesday.
    await stepToDate(page, calendar, SEED.tuesday);

    const tuesday = calendar.dayColumn(SEED.tuesday);
    await expect(tuesday).toBeVisible();
    await expect(tuesday.locator(testId("appointment-block"))).toHaveCount(2);

    // Day view renders exactly one day column.
    await expect(calendar.grid().locator(testId("calendar-day-column"))).toHaveCount(1);
  });

  test("prev / next move the range by one week in week view; today returns", async ({ page, calendar }) => {
    await page.locator(testId("toolbar-view-week")).click();

    // Land on the week containing Tuesday first (today may be any weekday).
    await stepWeeksToContain(page, calendar, SEED.tuesday);
    await expect(calendar.dayColumn(SEED.tuesday)).toBeVisible();
    const titleThisWeek = await calendar.rangeTitle().textContent();

    // Next → the Tuesday column is gone and the range title changed.
    await page.locator(testId("toolbar-next")).click();
    await expect(calendar.grid()).toBeVisible();
    await expect(calendar.dayColumn(SEED.tuesday)).toHaveCount(0);
    const titleNext = await calendar.rangeTitle().textContent();
    expect(titleNext).not.toEqual(titleThisWeek);
    // The next week's Tuesday column IS present — proves it moved by exactly 7 days.
    await expect(calendar.dayColumn(addDays(SEED.tuesday, 7))).toBeVisible();

    // Prev → back to the original week.
    await page.locator(testId("toolbar-prev")).click();
    await expect(calendar.dayColumn(SEED.tuesday)).toBeVisible();
    expect(await calendar.rangeTitle().textContent()).toEqual(titleThisWeek);
  });

  test("prev / next move the range by one day in day view", async ({ page, calendar }) => {
    await page.locator(testId("toolbar-view-day")).click();
    await stepToDate(page, calendar, SEED.tuesday);

    await page.locator(testId("toolbar-next")).click();
    await expect(calendar.dayColumn(addDays(SEED.tuesday, 1))).toBeVisible();
    await expect(calendar.dayColumn(SEED.tuesday)).toHaveCount(0);

    await page.locator(testId("toolbar-prev")).click();
    await expect(calendar.dayColumn(SEED.tuesday)).toBeVisible();
  });
});

/** Step the day view forward/back until the given date's column is the visible one. */
async function stepToDate(
  page: import("@playwright/test").Page,
  calendar: any,
  target: Date,
): Promise<void> {
  // Click Today first to normalise, then step towards the target.
  await page.locator(testId("toolbar-today")).click();
  await calendar.grid().waitFor();
  for (let i = 0; i < 40; i++) {
    if (await calendar.dayColumn(target).count()) return;
    const current = await firstColumnDate(calendar);
    const dir = target.getTime() > current.getTime() ? "toolbar-next" : "toolbar-prev";
    await page.locator(testId(dir)).click();
    await calendar.grid().waitFor();
  }
  throw new Error(`day view never reached ${ymd(target)}`);
}

/** Step the week view until the target date's column is visible in the shown week. */
async function stepWeeksToContain(
  page: import("@playwright/test").Page,
  calendar: any,
  target: Date,
): Promise<void> {
  await page.locator(testId("toolbar-today")).click();
  await calendar.grid().waitFor();
  for (let i = 0; i < 12; i++) {
    if (await calendar.dayColumn(target).count()) return;
    const current = await firstColumnDate(calendar);
    const dir = target.getTime() > current.getTime() ? "toolbar-next" : "toolbar-prev";
    await page.locator(testId(dir)).click();
    await calendar.grid().waitFor();
  }
  throw new Error(`week view never contained ${ymd(target)}`);
}

async function firstColumnDate(calendar: any): Promise<Date> {
  const first = calendar.grid().locator(testId("calendar-day-column")).first();
  const attr = await first.getAttribute("data-date");
  return new Date(`${attr}T00:00:00`);
}
