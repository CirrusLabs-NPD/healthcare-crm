import { test, expect, testId, PROVIDERS, SEED, addDays } from "../fixtures";

/**
 * AC-7 / AC-8 — weekly working hours render as available and time outside them
 * as unavailable; a date-specific time-off exception removes the available
 * block on exactly the affected date.
 *
 * Verified against scheduling.js §paintAvailability: each column gets a full-day
 * `availability-band[data-kind="unavailable"]` base, then an
 * `availability-band[data-kind="available"]` for each weekly rule window. A
 * whole-day off exception paints only the unavailable band and NO available
 * band on that date. The seeded fixture gives Alice Mon–Fri 09:00–17:00 and a
 * whole-day time-off on the anchor Wednesday.
 */
test.describe("AC-7/8 — availability rendering", () => {
  test.beforeEach(async ({ calendar, page }) => {
    await calendar.open();
    await page.locator(testId("toolbar-view-week")).click();
    await calendar.selectProvider(PROVIDERS.alice.name);
  });

  test("AC-7: a normal weekday shows an available band and an unavailable base", async ({ calendar }) => {
    const tuesday = calendar.dayColumn(SEED.tuesday);
    await expect(tuesday.locator(`${testId("availability-band")}[data-kind="available"]`).first()).toBeAttached();
    await expect(tuesday.locator(`${testId("availability-band")}[data-kind="unavailable"]`).first()).toBeAttached();
  });

  test("AC-7: the available band sits inside working hours, not the whole day", async ({ calendar }) => {
    const tuesday = calendar.dayColumn(SEED.tuesday);
    const avail = tuesday.locator(`${testId("availability-band")}[data-kind="available"]`).first();
    const base = tuesday.locator(`${testId("availability-band")}[data-kind="unavailable"]`).first();
    const availBox = await avail.boundingBox();
    const baseBox = await base.boundingBox();
    if (!availBox || !baseBox) throw new Error("availability band missing bounding box");
    // 09–17 (8h) is a strict subset of the 07–20 (13h) shown day → shorter.
    expect(availBox.height).toBeLessThan(baseBox.height);
    // And it starts below the top of the day (09:00 is after the 07:00 grid start).
    expect(availBox.y).toBeGreaterThan(baseBox.y);
  });

  test("AC-8: the anchor Wednesday time-off removes Alice's available band", async ({ calendar }) => {
    const wednesday = calendar.dayColumn(SEED.wednesday);
    await expect(wednesday).toBeVisible();
    // The whole-day off exception → no available band on Wednesday...
    await expect(wednesday.locator(`${testId("availability-band")}[data-kind="available"]`)).toHaveCount(0);
    // ...but the unavailable base is still painted.
    await expect(wednesday.locator(`${testId("availability-band")}[data-kind="unavailable"]`).first()).toBeAttached();
  });

  test("AC-8: the exception is date-specific — Thursday still shows availability", async ({ calendar }) => {
    // Thursday is a normal weekday for Alice → available band present, proving
    // the Wednesday removal is scoped to that one date, not the weekly rule.
    const thursday = calendar.dayColumn(addDays(SEED.monday, 3));
    await expect(thursday.locator(`${testId("availability-band")}[data-kind="available"]`).first()).toBeAttached();
  });
});
