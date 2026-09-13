import { test, expect, testId, PROVIDERS, SEED } from "../fixtures";

/**
 * AC-5 — the provider filter shows only the selected provider's appointments
 * and availability.
 *
 * SCOPE NOTE (verified against the delivered S-5 UI, templates/admin/calendar.html
 * + scheduling.js): the toolbar provider `<select>` lists individual bookable
 * providers only. There is NO "all providers" option, and
 * /api/appointments/calendar requires a providerId, so the calendar always shows
 * exactly one provider. The "all-providers shows all" half of AC-5 therefore has
 * no affordance in this UI — it is filed as a defect, not silently passed. These
 * specs verify the half that exists: selecting a provider shows only that
 * provider's blocks and availability, and switching providers swaps the set.
 */
test.describe("AC-5 — provider filter", () => {
  test.beforeEach(async ({ calendar, page }) => {
    await calendar.open();
    await page.locator(testId("toolbar-view-week")).click();
  });

  test("only bookable providers are offered; the non-provider is excluded", async ({ page }) => {
    const select = page.locator(testId("toolbar-provider-select"));
    await expect(select).toContainText(PROVIDERS.alice.name);
    await expect(select).toContainText(PROVIDERS.bob.name);
    await expect(select).not.toContainText(PROVIDERS.nonProvider.name);
  });

  test("selecting Alice shows Alice's appointments and none of Bob's", async ({ calendar }) => {
    await calendar.selectProvider(PROVIDERS.alice.name);
    const tuesday = calendar.dayColumn(SEED.tuesday);

    // Both seeded Alice blocks present (asserted by title — mutation-safe).
    await expect(tuesday.locator(testId("appointment-block"), { hasText: SEED.appointments.aliceShort.title })).toHaveCount(1);
    await expect(tuesday.locator(testId("appointment-block"), { hasText: SEED.appointments.aliceLong.title })).toHaveCount(1);
    // Bob's consult must not appear on Alice's calendar.
    await expect(tuesday).not.toContainText(SEED.appointments.bob.title);
  });

  test("selecting Bob shows Bob's single appointment and none of Alice's", async ({ calendar }) => {
    await calendar.selectProvider(PROVIDERS.bob.name);
    const tuesday = calendar.dayColumn(SEED.tuesday);

    // Bob only ever has his one seeded consult; the mutating specs all book Alice.
    await expect(tuesday.locator(testId("appointment-block"))).toHaveCount(1);
    await expect(tuesday).toContainText(SEED.appointments.bob.title);
    await expect(tuesday).not.toContainText(SEED.appointments.aliceShort.title);
    await expect(tuesday).not.toContainText(SEED.appointments.aliceLong.title);
  });

  test("availability follows the selected provider (Alice 09–17 vs Bob 10–14)", async ({ calendar }) => {
    // Alice's available band and Bob's differ in extent; switching providers
    // must repaint availability, not just appointments.
    await calendar.selectProvider(PROVIDERS.alice.name);
    const aliceMonday = calendar.dayColumn(SEED.monday);
    const aliceBands = aliceMonday.locator(`${testId("availability-band")}[data-kind="available"]`);
    await expect(aliceBands.first()).toBeAttached();
    const aliceBox = await aliceBands.first().boundingBox();

    await calendar.selectProvider(PROVIDERS.bob.name);
    const bobMonday = calendar.dayColumn(SEED.monday);
    const bobBands = bobMonday.locator(`${testId("availability-band")}[data-kind="available"]`);
    await expect(bobBands.first()).toBeAttached();
    const bobBox = await bobBands.first().boundingBox();

    // Alice 09–17 (8h) is a taller available band than Bob 10–14 (4h).
    if (!aliceBox || !bobBox) throw new Error("availability band missing bounding box");
    expect(aliceBox.height).toBeGreaterThan(bobBox.height);
  });
});
