import { test, expect, testId, PROVIDERS, CUSTOMER, SEED } from "../fixtures";

/**
 * AC-6 — an appointment block shows its time and status and links to its edit
 * form, where the customer, provider, time and status are shown.
 *
 * Verified against scheduling.js §paintEvents + §fillEdit: the block is a
 * <button> carrying data-status and an aria-label with the status; its visible
 * text is the title and the start–end time. Customer and provider are not
 * printed on the compact block itself — the block *links to the edit form*
 * (clicking it opens the modal), which is where customer/provider/time/status
 * are shown. This spec asserts both surfaces.
 */
test.describe("AC-6 — appointment block details & edit link", () => {
  test.beforeEach(async ({ calendar, page }) => {
    await calendar.open();
    await page.locator(testId("toolbar-view-week")).click();
    await calendar.selectProvider(PROVIDERS.alice.name);
  });

  test("the block shows its time and status", async ({ calendar }) => {
    const tuesday = calendar.dayColumn(SEED.tuesday);
    const short = tuesday.locator(testId("appointment-block"), { hasText: SEED.appointments.aliceShort.title });

    // Time is printed on the block.
    await expect(short).toContainText(
      `${SEED.appointments.aliceShort.start}–${SEED.appointments.aliceShort.end}`,
    );
    // Status is carried as data-status (drives the colour) and in the aria-label.
    await expect(short).toHaveAttribute("data-status", SEED.appointments.aliceShort.status);
    const aria = await short.getAttribute("aria-label");
    expect(aria).toContain(SEED.appointments.aliceShort.status);
    expect(aria).toContain(SEED.appointments.aliceShort.title);
  });

  test("the two blocks carry their distinct statuses", async ({ calendar }) => {
    const tuesday = calendar.dayColumn(SEED.tuesday);
    const short = tuesday.locator(testId("appointment-block"), { hasText: SEED.appointments.aliceShort.title });
    const long = tuesday.locator(testId("appointment-block"), { hasText: SEED.appointments.aliceLong.title });
    await expect(short).toHaveAttribute("data-status", "Scheduled");
    await expect(long).toHaveAttribute("data-status", "In Progress");
  });

  test("clicking a block links to its edit form with customer/provider/time/status", async ({ page, calendar }) => {
    const tuesday = calendar.dayColumn(SEED.tuesday);
    const short = tuesday.locator(testId("appointment-block"), { hasText: SEED.appointments.aliceShort.title });
    const apptId = await short.getAttribute("data-appointment-id");
    expect(apptId).toBeTruthy();

    await short.click();

    // The edit modal (the block's edit form) opens, populated for THIS appointment.
    const form = page.locator(testId("appointment-form"));
    await expect(form).toBeVisible();
    await expect(page.locator("#apptModalTitle")).toHaveText(/edit/i);
    await expect(page.locator("#apptId")).toHaveValue(String(apptId));

    // Customer & provider are shown as the selected options.
    await expect(page.locator(testId("field-customer"))).toHaveValue(/\d+/);
    const customerLabel = await page.locator(testId("field-customer")).locator("option:checked").textContent();
    expect(customerLabel?.trim()).toBe(CUSTOMER.name);
    const providerLabel = await page.locator(testId("field-provider")).locator("option:checked").textContent();
    expect(providerLabel?.trim()).toBe(PROVIDERS.alice.name);

    // Time & status round-trip into the form.
    await expect(page.locator(testId("field-start"))).toHaveValue(
      new RegExp(`T${SEED.appointments.aliceShort.start}$`),
    );
    await expect(page.locator(testId("field-end"))).toHaveValue(
      new RegExp(`T${SEED.appointments.aliceShort.end}$`),
    );
    await expect(page.locator(testId("field-status"))).toHaveValue(SEED.appointments.aliceShort.status);
  });
});
