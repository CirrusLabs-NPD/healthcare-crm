import { chromium, type FullConfig } from "@playwright/test";
import { ADMIN } from "./fixtures";

/**
 * Logs in once through the real Spring Security form (`/login`, CSRF-protected)
 * as the seeded admin and saves the authenticated session to storageState.json.
 * Every spec then loads that state via the `storageState` option, so the auth
 * path is exercised for real once and the specs stay fast.
 */
export default async function globalSetup(config: FullConfig) {
  const baseURL =
    config.projects[0]?.use?.baseURL ??
    process.env.E2E_BASE_URL ??
    "http://localhost:8080";

  const browser = await chromium.launch();
  const page = await browser.newPage();
  try {
    await page.goto(`${baseURL}/login`);
    // The form posts username/password with a hidden CSRF field; filling and
    // submitting the real form carries the token exactly as a user would.
    await page.fill("#username", ADMIN.email);
    await page.fill("#password", ADMIN.password);
    await Promise.all([
      page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 15_000 }),
      page.click('button[type="submit"], input[type="submit"]'),
    ]);
    // Spring sends the admin through /default -> /admin; land on the calendar to
    // confirm the saved session is genuinely authorised for the app under test.
    await page.goto(`${baseURL}/admin/calendar`);
    await page.waitForURL(/\/admin\/calendar$/, { timeout: 15_000 });
    await page.context().storageState({ path: "storageState.json" });
  } finally {
    await browser.close();
  }
}
