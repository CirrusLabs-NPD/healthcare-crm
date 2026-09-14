package com.medicare.healthcarecrm.config.e2e;

import com.medicare.healthcarecrm.model.Appointment;
import com.medicare.healthcarecrm.model.AppointmentType;
import com.medicare.healthcarecrm.model.AvailabilityException;
import com.medicare.healthcarecrm.model.AvailabilityRule;
import com.medicare.healthcarecrm.model.Customer;
import com.medicare.healthcarecrm.model.Employee;
import com.medicare.healthcarecrm.model.Insurance;
import com.medicare.healthcarecrm.repository.AppointmentRepository;
import com.medicare.healthcarecrm.repository.AppointmentSeriesRepository;
import com.medicare.healthcarecrm.repository.AvailabilityExceptionRepository;
import com.medicare.healthcarecrm.repository.AvailabilityRuleRepository;
import com.medicare.healthcarecrm.repository.CustomerRepository;
import com.medicare.healthcarecrm.repository.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;

/**
 * Deterministic scheduling fixture for the Playwright browser suite.
 *
 * Active <b>only</b> under the {@code e2e} Spring profile, so it never runs in
 * production or in the JUnit tests. {@link com.medicare.healthcarecrm.config.DataInitializer}
 * seeds users but no availability, time-off or appointments, and the calendar is
 * empty without them — the browser specs need a fixed, documented data contract
 * to assert against, which this bean establishes.
 *
 * <b>Anchoring.</b> Absolute dates rot. Everything is computed relative to the
 * <b>Monday of the current ISO week</b> ({@link #anchorMonday()}); {@code fixtures.ts}
 * recomputes the same anchor so the specs never hard-code a calendar date. The
 * fixture therefore stays green every week with no edits.
 *
 * The contract the specs rely on (kept in sync with {@code fixtures.ts} and
 * {@code design-docs/stories/S-9-e2e-harness-design.md} §2.3):
 * <ul>
 *   <li>Providers: <b>Dr. Alice Adams</b> and <b>Dr. Bob Barnes</b> (both bookable),
 *       plus <b>Nora Non-Provider</b> (bookableProvider=false) to prove the filter excludes her.</li>
 *   <li>Weekly hours: Alice Mon–Fri 09:00–17:00; Bob Mon–Fri 10:00–14:00.</li>
 *   <li>Time-off: Alice unavailable all day on the anchor <b>Wednesday</b>.</li>
 *   <li>Appointments on the anchor <b>Tuesday</b>: Alice 10:00–10:30 (30&nbsp;min) and
 *       Alice 14:00–15:30 (90&nbsp;min) — proves start-position and duration-sizing differ;
 *       Bob 11:00–11:30 — proves the provider filter.</li>
 *   <li>A guaranteed-free window (Alice, anchor Tuesday 15:30–17:00) for the booking
 *       flow, and Alice's 10:00–10:30 booking as a guaranteed overlap/refusal target.</li>
 * </ul>
 */
@Component
@Profile("e2e")
@Order(Ordered.LOWEST_PRECEDENCE) // run after DataInitializer's admin/user seed
public class E2eDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(E2eDataSeeder.class);

    private final EmployeeRepository employeeRepository;
    private final CustomerRepository customerRepository;
    private final AvailabilityRuleRepository ruleRepository;
    private final AvailabilityExceptionRepository exceptionRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentSeriesRepository seriesRepository;
    private final PasswordEncoder passwordEncoder;

    private static final String ALICE_EMAIL = "alice.adams@clinic.com";

    public E2eDataSeeder(EmployeeRepository employeeRepository,
                         CustomerRepository customerRepository,
                         AvailabilityRuleRepository ruleRepository,
                         AvailabilityExceptionRepository exceptionRepository,
                         AppointmentRepository appointmentRepository,
                         AppointmentSeriesRepository seriesRepository,
                         PasswordEncoder passwordEncoder) {
        this.employeeRepository = employeeRepository;
        this.customerRepository = customerRepository;
        this.ruleRepository = ruleRepository;
        this.exceptionRepository = exceptionRepository;
        this.appointmentRepository = appointmentRepository;
        this.seriesRepository = seriesRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** Monday of the current ISO week — the fixture's stable anchor. */
    static LocalDate anchorMonday() {
        return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    @Override
    @Transactional
    public void run(String... args) {
        // ddl-auto=create-drop gives a fresh schema each boot, but guard anyway so a
        // re-run (e.g. devtools restart) never double-seeds.
        if (employeeRepository.findByEmail(ALICE_EMAIL) != null) {
            log.info("[e2e] Fixture already present — skipping seed.");
            return;
        }
        seedFixture();
    }

    /**
     * Re-establish the deterministic scheduling fixture from a clean slate.
     *
     * The browser suite shares one app boot and one H2 database, so an appointment
     * a mutating spec creates (a booking, a recurring series) would otherwise leak
     * into later specs that assert absolute counts. {@link com.medicare.healthcarecrm.web.e2e.E2eResetController}
     * calls this between specs so every test starts from the same baseline; it is
     * only reachable under the {@code e2e} profile.
     */
    @Transactional
    public void reset() {
        // Scheduling data only — the users seeded by DataInitializer stay put so the
        // saved Playwright session (storageState) remains valid across resets.
        appointmentRepository.deleteAllInBatch();
        seriesRepository.deleteAllInBatch();
        exceptionRepository.deleteAllInBatch();
        ruleRepository.deleteAllInBatch();
        // Providers and the customer are re-seeded fresh so ids and rows are identical
        // every run; remove the ones this fixture owns before re-seeding.
        Employee existing = employeeRepository.findByEmail(ALICE_EMAIL);
        if (existing != null) {
            employeeRepository.deleteAll(employeeRepository.findAll().stream()
                    .filter(e -> e.getEmail() != null && e.getEmail().endsWith("@clinic.com")
                            && !"admin@clinic.com".equals(e.getEmail()))
                    .toList());
            customerRepository.deleteAll(customerRepository.findAll().stream()
                    .filter(c -> "jordan.client@example.com".equals(c.getEmail()))
                    .toList());
        }
        seedFixture();
        log.info("[e2e] Fixture reset to baseline.");
    }

    protected void seedFixture() {
        String pw = passwordEncoder.encode("password123");

        Employee alice = employeeRepository.save(Employee.builder()
                .name("Dr. Alice Adams").role("Physician").email(ALICE_EMAIL)
                .password(pw).bookableProvider(true).defaultDurationMin(30).build());
        Employee bob = employeeRepository.save(Employee.builder()
                .name("Dr. Bob Barnes").role("Physician").email("bob.barnes@clinic.com")
                .password(pw).bookableProvider(true).defaultDurationMin(30).build());
        employeeRepository.save(Employee.builder()
                .name("Nora Non-Provider").role("Receptionist").email("nora.n@clinic.com")
                .password(pw).bookableProvider(false).defaultDurationMin(30).build());

        Customer customer = customerRepository.save(Customer.builder()
                .name("Jordan Client").age(42).gender("Other").email("jordan.client@example.com")
                .medicalHistory("No significant history.").contactDetails("(555) 010-2020")
                .insurance(Insurance.builder()
                        .provider("BlueCross").policyNumber("AB1234567")
                        .coverageDetails("Standard group plan.")
                        .expiryDate(LocalDateTime.now().plusYears(2)).build())
                .build());

        // Weekly working hours (AC-7): Alice 09–17, Bob 10–14, Mon–Fri.
        for (DayOfWeek d : new DayOfWeek[]{DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY}) {
            ruleRepository.save(rule(alice, d, LocalTime.of(9, 0), LocalTime.of(17, 0)));
            ruleRepository.save(rule(bob, d, LocalTime.of(10, 0), LocalTime.of(14, 0)));
        }

        LocalDate monday = anchorMonday();
        LocalDate tuesday = monday.plusDays(1);
        LocalDate wednesday = monday.plusDays(2);

        // Time-off (AC-8): Alice off the whole anchor Wednesday — her available band
        // is present every other weekday and gone on exactly this date.
        exceptionRepository.save(AvailabilityException.builder()
                .provider(alice).date(wednesday).available(false)
                .startTime(null).endTime(null).build());

        // Appointments on the anchor Tuesday (AC-3/4/6):
        //  - Alice 10:00–10:30 (30 min): the short block and the overlap/refusal target.
        //  - Alice 14:00–15:30 (90 min): later and 3× the height of the short block.
        //  - Bob   11:00–11:30: proves the provider filter.
        appointmentRepository.save(appointment(customer, alice, "Alice — intake",
                tuesday.atTime(10, 0), tuesday.atTime(10, 30), "Pending"));
        appointmentRepository.save(appointment(customer, alice, "Alice — procedure",
                tuesday.atTime(14, 0), tuesday.atTime(15, 30), "In Progress"));
        appointmentRepository.save(appointment(customer, bob, "Bob — consult",
                tuesday.atTime(11, 0), tuesday.atTime(11, 30), "Pending"));
        // Alice's Tuesday 15:30–17:00 window is intentionally left free for the
        // booking-flow spec to book into.

        log.info("[e2e] Seeded scheduling fixture. anchorMonday={} tuesday={} wednesday={} "
                        + "providers=[{}, {}] customer={}",
                monday, tuesday, wednesday, alice.getId(), bob.getId(), customer.getId());
    }

    private AvailabilityRule rule(Employee p, DayOfWeek d, LocalTime from, LocalTime to) {
        return AvailabilityRule.builder().provider(p).dayOfWeek(d).startTime(from).endTime(to).build();
    }

    private Appointment appointment(Customer c, Employee p, String title,
                                    LocalDateTime start, LocalDateTime end, String status) {
        return Appointment.builder()
                .title(title).customer(c).provider(p).type(AppointmentType.APPOINTMENT)
                .startTime(start).endTime(end).priority("Medium")
                .description("Seeded e2e fixture appointment.").status(status)
                .build();
    }
}
