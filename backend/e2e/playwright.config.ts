import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright config for the S-9 scheduling browser suite.
 *
 * The app under test is the PACKAGED JAR, booted on the `e2e` Spring profile
 * (in-memory H2, no secrets) and owned by the `webServer` block below — not a
 * dev server and not `spring-boot:run`. Build it first with the `e2e` Maven
 * profile so the jar carries the H2 driver:
 *
 *   ./mvnw -Pe2e -DskipTests package
 *   cd e2e && npm ci && npx playwright install --with-deps chromium && npm test
 *
 * See design-docs/stories/S-9-e2e-harness-design.md.
 */

const PORT = Number(process.env.E2E_PORT ?? 8080);
const BASE_URL = process.env.E2E_BASE_URL ?? `http://localhost:${PORT}`;

export default defineConfig({
  testDir: "./specs",
  // Serial: the suite shares one seeded fixture and one booted app.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI
    ? [["list"], ["html", { open: "never" }]]
    : [["list"]],

  use: {
    baseURL: BASE_URL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    // Authenticated session produced once by global-setup.
    storageState: "storageState.json",
  },

  // Log in once through the real form and save the session.
  globalSetup: require.resolve("./global-setup"),

  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],

  // Boot the packaged jar and wait for it before running specs. Reused if a
  // server is already up (local iteration); always started fresh in CI.
  webServer: {
    command:
      process.env.E2E_WEBSERVER_CMD ??
      `java -jar ../target/healthcarecrm-0.0.1-SNAPSHOT.jar --spring.profiles.active=e2e --server.port=${PORT}`,
    url: `${BASE_URL}/login`,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    stdout: "pipe",
    stderr: "pipe",
  },
});
