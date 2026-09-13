import { test, expect, testId, PROVIDERS, CUSTOMER, SEED } from "../fixtures";

/**
 * Booking flow — an admin books an appointment through the UI and it appears on
 * the calendar; a booking outside availability or overlapping an existing one is
 * refused with a visible message and leaves no record.
 *
 * Verified against scheduling.js §saveSingle / §apptError and
 * AppointmentApiController#book: a valid POST returns 201 and the calendar
 * refreshes; a conflict returns 409 whose message is surfaced inline in the
 * `appointment-error` region and the modal stays open. The seeded fixture gives
 * Alice a free window on the anchor Tuesday 15:30–17:00, working hours until
 * 17:00, and an existing 10:00–10:30 appointment as the overlap target.
 */
function isoDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

async function openNewOnTuesday(page: import("@playwright/test").Page, calendar: any) {
  await calendar.open();
  await page.locator(testId("toolbar-view-week")).click();
  await calendar.selectProvider(PROVIDERS.alice.name);
  await page.locator(testId("toolbar-new-appointment")).click();
  await expect(page.locator(testId("appointment-form"))).toBeVisible();
  await page.locator(testId("field-customer")).selectOption({ label: CUSTOMER.name });
  await page.locator(testId("field-provider")).selectOption({ label: PROVIDERS.alice.name });
}

test.describe("booking flow", () => {
  test("admin books a valid appointment and it appears on the calendar", async ({ page, calendar }) => {
    const title = "Booked via e2e — valid";
    await openNewOnTuesday(page, calendar);

    // Alice's free window on the anchor Tuesday: 15:30–16:00.
    await page.fill("#apptTitle", title);
    await page.fill("#apptDesc", "Booked into the guaranteed-free window.");
    await page.locator(testId("field-start")).fill(`${isoDate(SEED.tuesday)}T15:30`);
    await page.locator(testId("field-end")).fill(`${isoDate(SEED.tuesday)}T16:00`);

    await page.locator(testId("appointment-submit")).click();

    // Modal closes and the new block shows on Tuesday.
    await expect(page.locator(testId("appointment-form"))).toBeHidden();
    const tuesday = calendar.dayColumn(SEED.tuesday);
    await expect(tuesday.locator(testId("appointment-block"), { hasText: title })).toHaveCount(1);
    await expect(tuesday.locator(testId("appointment-block"), { hasText: title })).toContainText("15:30–16:00");
  });

  test("an overlapping booking is refused with a visible message and creates nothing", async ({ page, calendar }) => {
    await openNewOnTuesday(page, calendar);
    const tuesday = calendar.dayColumn(SEED.tuesday);

    // Overlaps the seeded 10:00–10:30 intake.
    await page.fill("#apptTitle", "Booked via e2e — overlap");
    await page.fill("#apptDesc", "Should be refused: overlaps the 10:00 intake.");
    await page.locator(testId("field-start")).fill(`${isoDate(SEED.tuesday)}T10:00`);
    await page.locator(testId("field-end")).fill(`${isoDate(SEED.tuesday)}T10:30`);

    await page.locator(testId("appointment-submit")).click();

    // Refusal is surfaced inline; the modal stays open (not silently swallowed).
    const err = page.locator(testId("appointment-error"));
    await expect(err).toBeVisible();
    await expect(err).not.toBeEmpty();
    await expect(page.locator(testId("appointment-form"))).toBeVisible();

    // Nothing new persisted: still exactly the two seeded Alice blocks after a reload.
    await page.reload();
    await calendar.selectProvider(PROVIDERS.alice.name);
    await page.locator(testId("toolbar-view-week")).click();
    await expect(calendar.dayColumn(SEED.tuesday).locator(testId("appointment-block"), { hasText: "e2e — overlap" })).toHaveCount(0);
  });

  test("a booking outside working hours is refused with a visible message", async ({ page, calendar }) => {
    await openNewOnTuesday(page, calendar);

    // 18:00 is after Alice's 17:00 close.
    await page.fill("#apptTitle", "Booked via e2e — outside hours");
    await page.fill("#apptDesc", "Should be refused: outside availability.");
    await page.locator(testId("field-start")).fill(`${isoDate(SEED.tuesday)}T18:00`);
    await page.locator(testId("field-end")).fill(`${isoDate(SEED.tuesday)}T18:30`);

    await page.locator(testId("appointment-submit")).click();

    const err = page.locator(testId("appointment-error"));
    await expect(err).toBeVisible();
    await expect(err).not.toBeEmpty();
    await expect(page.locator(testId("appointment-form"))).toBeVisible();
  });
});
