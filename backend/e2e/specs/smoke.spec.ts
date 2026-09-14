import { test, expect, testId, PROVIDERS, SEED } from "../fixtures";

/**
 * Harness smoke test (S-9 scaffold).
 *
 * Proves the e2e harness actually drives the delivered S-5 UI end to end: the
 * global-setup session is valid, the calendar page renders, the seeded provider
 * fixture is present, and the data-testid hooks resolve. The six acceptance
 * specs (calendar-views, provider-filter, appointment-block, availability,
 * recurrence, booking-flow) build on exactly these hooks — see
 * design-docs/stories/S-9-e2e-harness-design.md §4.
 */
test.describe("harness smoke", () => {
  test("calendar renders and the seeded fixture is drivable", async ({ page, calendar }) => {
    await calendar.open();

    // The authenticated session reached the calendar (not bounced to /login).
    await expect(page).toHaveURL(/\/admin\/calendar$/);
    await expect(page.locator(testId("toolbar-range-title"))).toBeVisible();

    // Both bookable providers are offered; the non-provider is excluded (AC-5 basis).
    const select = page.locator(testId("toolbar-provider-select"));
    await expect(select).toContainText(PROVIDERS.alice.name);
    await expect(select).toContainText(PROVIDERS.bob.name);
    await expect(select).not.toContainText(PROVIDERS.nonProvider.name);

    // Pick Alice and switch to the week view; her seeded Tuesday blocks render.
    await calendar.selectProvider(PROVIDERS.alice.name);
    await page.locator(testId("toolbar-view-week")).click();
    await expect(calendar.grid()).toHaveAttribute("data-view", "week");

    const tuesday = calendar.dayColumn(SEED.tuesday);
    await expect(tuesday).toBeVisible();
    const blocks = tuesday.locator(testId("appointment-block"));
    await expect(blocks).toHaveCount(2); // the 30-min and the 90-min appointment
    await expect(tuesday).toContainText(SEED.appointments.aliceShort.title);
    await expect(tuesday).toContainText(SEED.appointments.aliceLong.title);

    // The availability hooks are present and typed.
    await expect(
      tuesday.locator(`${testId("availability-band")}[data-kind="available"]`).first(),
    ).toBeAttached();
  });
});
