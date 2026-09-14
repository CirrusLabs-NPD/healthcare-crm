package com.medicare.healthcarecrm.web.e2e;

import com.medicare.healthcarecrm.config.e2e.E2eDataSeeder;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-support endpoint for the Playwright browser suite — restores the seeded
 * scheduling fixture to its baseline.
 *
 * <p>The suite shares one app boot and one in-memory H2 database, so an
 * appointment a mutating spec creates (a booking, a recurring series) leaks into
 * later specs that assert absolute counts. Each spec calls this before it runs
 * (see {@code backend/e2e/fixtures.ts}) so every test starts from an identical,
 * order-independent baseline.
 *
 * <p>Guarded by {@code @Profile("e2e")}: the bean does not exist under any other
 * profile, so the route is entirely absent from the production artifact. It is
 * still behind the normal authentication rule ({@code anyRequest().authenticated()}
 * in {@code SecurityConfig}); CSRF is already disabled for {@code /api/**}.
 */
@RestController
@RequestMapping("/api/e2e")
@Profile("e2e")
public class E2eResetController {

    private final E2eDataSeeder seeder;

    public E2eResetController(E2eDataSeeder seeder) {
        this.seeder = seeder;
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        seeder.reset();
        return ResponseEntity.noContent().build();
    }
}
