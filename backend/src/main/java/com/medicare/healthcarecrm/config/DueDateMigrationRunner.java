package com.medicare.healthcarecrm.config;

import com.medicare.healthcarecrm.model.Appointment;
import com.medicare.healthcarecrm.model.AppointmentType;
import com.medicare.healthcarecrm.model.Tasks;
import com.medicare.healthcarecrm.repository.AppointmentRepository;
import com.medicare.healthcarecrm.repository.TasksRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * One-off backfill that replaces the flat free-text {@code Tasks.dueDate} with
 * structured {@link Appointment} rows. Each task becomes a DEADLINE appointment
 * (start == end == dueDate) so the deadline is preserved losslessly and never
 * occupies provider time — real bookings are created going forward.
 *
 * <p>The schema itself is created additively by {@code ddl-auto=update}; this
 * runner only moves data. It is:
 * <ul>
 *   <li><b>opt-in</b> — runs only when {@code scheduling.migrate-due-dates=true},
 *       so it never fires unexpectedly in a normal boot;</li>
 *   <li><b>idempotent</b> — if the appointment table already holds rows it does
 *       nothing, so a second run cannot double-insert.</li>
 * </ul>
 *
 * <p>Ordered after {@link DataInitializer} so seeded tasks exist before backfill.
 */
@Component
@Order(100)
public class DueDateMigrationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DueDateMigrationRunner.class);

    private final TasksRepository tasksRepository;
    private final AppointmentRepository appointmentRepository;
    private final boolean enabled;

    public DueDateMigrationRunner(TasksRepository tasksRepository,
                                  AppointmentRepository appointmentRepository,
                                  @Value("${scheduling.migrate-due-dates:false}") boolean enabled) {
        this.tasksRepository = tasksRepository;
        this.appointmentRepository = appointmentRepository;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!enabled) {
            log.debug("Due-date migration disabled (set scheduling.migrate-due-dates=true to run it).");
            return;
        }
        if (appointmentRepository.count() > 0) {
            log.info("Due-date migration skipped: appointment table already populated ({} rows).",
                    appointmentRepository.count());
            return;
        }

        List<Tasks> tasks = tasksRepository.findAll();
        if (tasks.isEmpty()) {
            log.info("Due-date migration: no tasks to migrate.");
            return;
        }

        List<Appointment> migrated = new ArrayList<>();
        int skipped = 0;
        for (Tasks task : tasks) {
            if (task.getDueDate() == null || task.getEmployee() == null || task.getCustomer() == null) {
                skipped++;
                continue;
            }
            Appointment appointment = Appointment.builder()
                    .title(task.getTaskName())
                    .customer(task.getCustomer())
                    .provider(task.getEmployee())
                    .type(AppointmentType.DEADLINE)
                    .startTime(task.getDueDate())
                    .endTime(task.getDueDate())
                    .priority(task.getPriority())
                    .description(task.getDescription())
                    .status(task.getStatus())
                    .build();
            migrated.add(appointment);
        }
        appointmentRepository.saveAll(migrated);
        log.info("Due-date migration complete: {} tasks migrated to DEADLINE appointments, {} skipped (missing data).",
                migrated.size(), skipped);
    }
}
