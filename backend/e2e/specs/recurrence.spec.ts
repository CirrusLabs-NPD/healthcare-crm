import { test, expect, testId, PROVIDERS, CUSTOMER, SEED, addDays } from "../fixtures";

/**
 * Recurrence editor — the modal creates a series and the generated occurrences
 * appear on the calendar.
 *
 * Verified against scheduling.js §saveSeries: the recurrence editor posts to
 * /api/appointment-series with frequency, startDate/startTime, durationMin and
 * an end (untilDate or occurrenceCount); the server generates the occurrences
 * and the calendar refreshes. We create a WEEKLY series of 3, starting on the
 * anchor Monday 09:00 (inside Alice's hours, no seeded clash), and assert an
 * occurrence lands on the anchor Monday and on the following two Mondays.
 */
const TITLE = "Weekly physio — e2e series";

test.describe("recurrence editor — series creation", () => {
  test("creates a weekly series whose occurrences appear on successive weeks", async ({ page, calendar }) => {
    await calendar.open();
    await page.locator(testId("toolbar-view-week")).click();
    await calendar.selectProvider(PROVIDERS.alice.name);

    // Open the New-appointment modal.
    await page.locator(testId("toolbar-new-appointment")).click();
    const form = page.locator(testId("appointment-form"));
    await expect(form).toBeVisible();

    // Fill the appointment fields. Start = anchor Monday 09:00 (30-min default).
    await page.locator(testId("field-customer")).selectOption({ label: CUSTOMER.name });
    await page.locator(testId("field-provider")).selectOption({ label: PROVIDERS.alice.name });
    await page.fill("#apptTitle", TITLE);
    await page.fill("#apptDesc", "Seeded by the recurrence e2e spec.");
    const startLocal = `${isoDate(SEED.monday)}T09:00`;
    await page.locator(testId("field-start")).fill(startLocal);
    await page.locator(testId("field-end")).fill(`${isoDate(SEED.monday)}T09:30`);

    // Turn on recurrence, pick WEEKLY, end after 3 occurrences.
    await page.locator(testId("recurrence-toggle")).check();
    await expect(page.locator(testId("recurrence-editor"))).toBeVisible();
    await page.locator(testId("recurrence-frequency")).selectOption("WEEKLY");
    await page.locator("#endAfter").check();
    await page.fill("#recurCount", "3");

    // Submit → series created, modal closes, calendar refreshes.
    await page.locator(testId("appointment-submit")).click();
    await expect(form).toBeHidden();

    // Occurrence 1 lands on the anchor Monday (visible in the current week).
    const monday = calendar.dayColumn(SEED.monday);
    await expect(monday.locator(testId("appointment-block"), { hasText: TITLE })).toHaveCount(1);

    // Occurrence 2 → next week's Monday.
    await page.locator(testId("toolbar-next")).click();
    await expect(calendar.dayColumn(addDays(SEED.monday, 7)).locator(testId("appointment-block"), { hasText: TITLE })).toHaveCount(1);

    // Occurrence 3 → the week after.
    await page.locator(testId("toolbar-next")).click();
    await expect(calendar.dayColumn(addDays(SEED.monday, 14)).locator(testId("appointment-block"), { hasText: TITLE })).toHaveCount(1);

    // A fourth week has none — the series stopped at 3.
    await page.locator(testId("toolbar-next")).click();
    await expect(calendar.dayColumn(addDays(SEED.monday, 21)).locator(testId("appointment-block"), { hasText: TITLE })).toHaveCount(0);
  });
});

function isoDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}
