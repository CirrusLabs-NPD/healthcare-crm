package com.medicare.healthcarecrm.config;

import com.medicare.healthcarecrm.model.Appointment;
import com.medicare.healthcarecrm.model.AppointmentType;
import com.medicare.healthcarecrm.model.Customer;
import com.medicare.healthcarecrm.model.Employee;
import com.medicare.healthcarecrm.model.Insurance;
import com.medicare.healthcarecrm.model.Tasks;
import com.medicare.healthcarecrm.repository.AppointmentRepository;
import com.medicare.healthcarecrm.repository.CustomerRepository;
import com.medicare.healthcarecrm.repository.EmployeeRepository;
import com.medicare.healthcarecrm.repository.TasksRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the due-date migration ({@link DueDateMigrationRunner}) actually moves
 * data correctly against a real (H2) persistence layer — the gate the pure
 * {@code SchedulingSupportTest} does not cover. Exercises the lossless backfill,
 * the disabled-by-default guard, one-appointment-per-task, and idempotency on a
 * second run.
 *
 * <p>The runner reads its {@code enabled} flag from a constructor {@code @Value}
 * that is fixed at bean-construction time, so this test constructs the runner
 * directly with real, autowired repositories (a {@code @DataJpaTest} slice would
 * not wire the runner, and flipping the property needs a fresh bean anyway).
 */
@SpringBootTest
@Transactional
class DueDateMigrationRunnerTest {

    @Autowired
    private TasksRepository tasksRepository;
    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private EmployeeRepository employeeRepository;

    private Employee provider;
    private Customer customer;

    @BeforeEach
    void setUp() {
        // Clean slate so counts are deterministic regardless of DataInitializer.
        appointmentRepository.deleteAll();
        tasksRepository.deleteAll();

        provider = employeeRepository.save(Employee.builder()
                .name("Test Provider").role("Nurse")
                .email("migration.provider@test.local").password("x")
                .build());

        Insurance insurance = Insurance.builder()
                .provider("BlueCross").policyNumber("AB1234567")
                .coverageDetails("Basic").expiryDate(LocalDateTime.now().plusMonths(12))
                .build();
        customer = customerRepository.save(Customer.builder()
                .name("Test Patient").age(40).gender("Other")
                .email("migration.patient@test.local").medicalHistory("None")
                .contactDetails("(555) 010-2000").insurance(insurance)
                .build());
    }

    private Tasks task(String name, LocalDateTime due, Customer c, Employee e) {
        return Tasks.builder()
                .taskName(name).customer(c).employee(e).dueDate(due)
                .priority("Medium").description("desc for " + name).status("Pending")
                .build();
    }

    private DueDateMigrationRunner runner(boolean enabled) {
        return new DueDateMigrationRunner(tasksRepository, appointmentRepository, enabled);
    }

    @Test
    void disabledByDefault_doesNothing() throws Exception {
        LocalDateTime due = LocalDateTime.now().plusDays(3);
        tasksRepository.save(task("Follow up", due, customer, provider));

        runner(false).run();

        assertThat(appointmentRepository.count()).isZero();
    }

    @Test
    void backfillsDueDatesLosslesslyAsDeadlineAppointments() throws Exception {
        LocalDateTime dueA = LocalDateTime.now().plusDays(3).withNano(0);
        LocalDateTime dueB = LocalDateTime.now().minusDays(1).withNano(0);
        tasksRepository.save(task("Task A", dueA, customer, provider));
        tasksRepository.save(task("Task B", dueB, customer, provider));

        runner(true).run();

        List<Appointment> migrated = appointmentRepository.findAll();
        assertThat(migrated).hasSize(2);
        // Every migrated row is a DEADLINE that occupies no time (start == end == dueDate).
        assertThat(migrated).allSatisfy(a -> {
            assertThat(a.getType()).isEqualTo(AppointmentType.DEADLINE);
            assertThat(a.getStartTime()).isEqualTo(a.getEndTime());
            assertThat(a.getProvider().getId()).isEqualTo(provider.getId());
            assertThat(a.getCustomer().getId()).isEqualTo(customer.getId());
        });
        // The deadline instant is preserved exactly — nothing shifted.
        assertThat(migrated).extracting(Appointment::getStartTime)
                .containsExactlyInAnyOrder(dueA, dueB);
        assertThat(migrated).extracting(Appointment::getTitle)
                .containsExactlyInAnyOrder("Task A", "Task B");
    }

    @Test
    void migratesEveryEligibleTask_oneAppointmentPerTask() throws Exception {
        // Tasks that satisfy the NOT NULL FKs (customer + employee) and carry a due
        // date are all eligible; the runner produces exactly one DEADLINE per task.
        tasksRepository.save(task("T1", LocalDateTime.now().plusDays(1).withNano(0), customer, provider));
        tasksRepository.save(task("T2", LocalDateTime.now().plusDays(4).withNano(0), customer, provider));
        tasksRepository.save(task("T3", LocalDateTime.now().plusDays(9).withNano(0), customer, provider));

        runner(true).run();

        List<Appointment> migrated = appointmentRepository.findAll();
        assertThat(migrated).hasSize(3);
        assertThat(migrated).extracting(Appointment::getTitle)
                .containsExactlyInAnyOrder("T1", "T2", "T3");
    }

    @Test
    void isIdempotent_secondRunDoesNotDoubleInsert() throws Exception {
        tasksRepository.save(task("Once", LocalDateTime.now().plusDays(2).withNano(0), customer, provider));

        runner(true).run();
        long afterFirst = appointmentRepository.count();
        assertThat(afterFirst).isEqualTo(1);

        // Second run: the table is already populated, so the count-based guard no-ops.
        runner(true).run();
        assertThat(appointmentRepository.count()).isEqualTo(afterFirst);
    }
}
