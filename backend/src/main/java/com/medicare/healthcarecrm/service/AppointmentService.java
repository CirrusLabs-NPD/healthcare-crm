package com.medicare.healthcarecrm.service;

import com.medicare.healthcarecrm.model.Appointment;
import com.medicare.healthcarecrm.model.AppointmentSeries;
import com.medicare.healthcarecrm.model.AppointmentType;
import com.medicare.healthcarecrm.model.Employee;
import com.medicare.healthcarecrm.repository.AppointmentRepository;
import com.medicare.healthcarecrm.repository.AppointmentSeriesRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Appointments, the server-side booking guard, and recurring series.
 *
 * The guard runs here — never in the browser — so the web and API paths share
 * one enforcement point. A refused booking raises {@link BookingConflictException}
 * (409 on the API, a form error on the web). Ownership is enforced on every
 * mutating call: an employee may only act on their own provider records; an
 * admin (currentEmployee == null) is unrestricted, matching the role model in
 * {@code TaskService}.
 */
@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

    private final AppointmentRepository appointmentRepository;
    private final AppointmentSeriesRepository seriesRepository;
    private final AvailabilityService availabilityService;

    public AppointmentService(AppointmentRepository appointmentRepository,
                              AppointmentSeriesRepository seriesRepository,
                              AvailabilityService availabilityService) {
        this.appointmentRepository = appointmentRepository;
        this.seriesRepository = seriesRepository;
        this.availabilityService = availabilityService;
    }

    // === Reads ===

    public List<Appointment> getAllAppointments() {
        return appointmentRepository.findAll();
    }

    public Appointment getAppointmentById(Long id) {
        return appointmentRepository.findById(id).orElse(null);
    }

    public List<Appointment> getAppointmentsByProvider(Employee provider) {
        if (provider == null) {
            return new ArrayList<>();
        }
        return appointmentRepository.findByProvider(provider);
    }

    /** Calendar range query for one provider — the day and week views both use this. */
    public List<Appointment> getCalendar(Employee provider, LocalDateTime from, LocalDateTime to) {
        if (provider == null || from == null || to == null || !to.isAfter(from)) {
            return new ArrayList<>();
        }
        return appointmentRepository.findForProviderInRange(provider, from, to);
    }

    // === Writes ===

    /**
     * Book a single appointment. Runs the guard for real APPOINTMENTs; DEADLINE
     * rows skip it (they occupy no time). Throws {@link BookingConflictException}
     * when refused.
     */
    @Transactional
    public Appointment book(Appointment appointment, Employee currentEmployee) {
        appointment.setId(null);
        validateFields(appointment);
        assertOwnership(appointment.getProvider(), currentEmployee);
        if (appointment.getType() == AppointmentType.APPOINTMENT) {
            enforceGuard(appointment, 0L);
        }
        Appointment saved = appointmentRepository.save(appointment);
        log.info("Appointment {} booked for provider {} at {}", saved.getId(),
                appointment.getProvider().getId(), appointment.getStartTime());
        return saved;
    }

    /**
     * Reschedule / update an existing appointment. Re-runs the guard against the
     * new span, excluding the appointment itself from the overlap check.
     */
    @Transactional
    public Appointment reschedule(Long id, Appointment incoming, Employee currentEmployee) {
        Appointment existing = appointmentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Appointment not found with ID: " + id));
        assertOwnership(existing.getProvider(), currentEmployee);
        assertOwnership(incoming.getProvider(), currentEmployee);

        validateFields(incoming);
        if (incoming.getType() == AppointmentType.APPOINTMENT) {
            enforceGuard(incoming, existing.getId());
        }

        existing.setTitle(incoming.getTitle());
        existing.setCustomer(incoming.getCustomer());
        existing.setProvider(incoming.getProvider());
        existing.setType(incoming.getType());
        existing.setStartTime(incoming.getStartTime());
        existing.setEndTime(incoming.getEndTime());
        existing.setPriority(incoming.getPriority());
        existing.setDescription(incoming.getDescription());
        existing.setStatus(incoming.getStatus());

        Appointment saved = appointmentRepository.save(existing);
        log.info("Appointment {} rescheduled to {}", id, incoming.getStartTime());
        return saved;
    }

    @Transactional
    public void delete(Long id, Employee currentEmployee) {
        Appointment existing = appointmentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Appointment not found with ID: " + id));
        assertOwnership(existing.getProvider(), currentEmployee);
        appointmentRepository.deleteById(id);
        log.info("Appointment {} deleted", id);
    }

    /** Mirror of {@code TaskService.updateTaskStatus}: an employee may only update
     *  the status of an appointment they provide. */
    @Transactional
    public void updateStatus(Long id, String newStatus, Employee currentEmployee) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Appointment not found with ID: " + id));
        if (currentEmployee != null
                && (appointment.getProvider() == null
                || !Objects.equals(appointment.getProvider().getId(), currentEmployee.getId()))) {
            log.warn("Unauthorized status update on appointment {} by employee {}", id, currentEmployee.getId());
            throw new AccessDeniedException("You are not authorized to update this appointment.");
        }
        appointment.setStatus(newStatus);
        appointmentRepository.save(appointment);
        log.info("Appointment {} status updated to '{}'", id, newStatus);
    }

    // === Recurring series ===

    /**
     * Persist a series and materialise its occurrences into concrete Appointment
     * rows. Conflicts are FLAGGED, not dropped: an occurrence that fails the
     * guard is still created with status "Conflict" so nothing is silently lost
     * and an operator can resolve it. Returns the generated appointments.
     */
    @Transactional
    public List<Appointment> createSeries(AppointmentSeries series, Employee currentEmployee) {
        assertOwnership(series.getProvider(), currentEmployee);
        if (series.getUntilDate() == null && series.getOccurrenceCount() == null) {
            throw new IllegalArgumentException(
                    "A series must end by either an until date or an occurrence count.");
        }
        if (series.getDurationMin() == null || series.getDurationMin() < 1) {
            throw new IllegalArgumentException("Series duration must be at least 1 minute.");
        }
        AppointmentSeries savedSeries = seriesRepository.save(series);

        List<LocalDateTime> starts = SchedulingSupport.expandOccurrences(savedSeries);
        List<Appointment> generated = new ArrayList<>();
        int conflicts = 0;
        for (LocalDateTime start : starts) {
            LocalDateTime end = start.plusMinutes(savedSeries.getDurationMin());
            boolean conflict = !isBookable(savedSeries.getProvider(), start, end, 0L);
            if (conflict) {
                conflicts++;
            }
            Appointment occurrence = Appointment.builder()
                    .title(savedSeries.getTitle())
                    .customer(savedSeries.getCustomer())
                    .provider(savedSeries.getProvider())
                    .type(AppointmentType.APPOINTMENT)
                    .startTime(start)
                    .endTime(end)
                    .priority(savedSeries.getPriority())
                    .description(savedSeries.getDescription())
                    .status(conflict ? "Conflict" : "Pending")
                    .series(savedSeries)
                    .build();
            generated.add(appointmentRepository.save(occurrence));
        }
        log.info("Series {} materialised into {} occurrences ({} flagged as conflicts)",
                savedSeries.getId(), generated.size(), conflicts);
        return generated;
    }

    public List<Appointment> getSeriesOccurrences(AppointmentSeries series) {
        return appointmentRepository.findBySeries(series);
    }

    /** Cancel a whole series: deletes its generated occurrences, then the series. */
    @Transactional
    public void deleteSeries(Long seriesId, Employee currentEmployee) {
        AppointmentSeries series = seriesRepository.findById(seriesId)
                .orElseThrow(() -> new EntityNotFoundException("Series not found with ID: " + seriesId));
        assertOwnership(series.getProvider(), currentEmployee);
        List<Appointment> occurrences = appointmentRepository.findBySeries(series);
        appointmentRepository.deleteAll(occurrences);
        seriesRepository.deleteById(seriesId);
        log.info("Series {} and its {} occurrences deleted", seriesId, occurrences.size());
    }

    // === Guard internals ===

    private void enforceGuard(Appointment appointment, Long excludeId) {
        LocalDateTime start = appointment.getStartTime();
        LocalDateTime end = appointment.getEndTime();
        Employee provider = appointment.getProvider();

        DayOfWeek dow = start.getDayOfWeek();
        LocalDate date = start.toLocalDate();
        boolean withinAvailability = SchedulingSupport.isWithinAvailability(start, end,
                availabilityService.rulesForDay(provider, dow),
                availabilityService.exceptionsForDate(provider, date));
        if (!withinAvailability) {
            throw new BookingConflictException(
                    "Requested time is outside the provider's available hours.");
        }
        List<Appointment> clashes = appointmentRepository.findOverlapping(provider, start, end, excludeId);
        if (!clashes.isEmpty()) {
            throw new BookingConflictException(
                    "Requested time overlaps an existing appointment for this provider.");
        }
    }

    /** Non-throwing form of the guard, used by series expansion to flag rather than reject. */
    private boolean isBookable(Employee provider, LocalDateTime start, LocalDateTime end, Long excludeId) {
        boolean withinAvailability = SchedulingSupport.isWithinAvailability(start, end,
                availabilityService.rulesForDay(provider, start.getDayOfWeek()),
                availabilityService.exceptionsForDate(provider, start.toLocalDate()));
        if (!withinAvailability) {
            return false;
        }
        return appointmentRepository.findOverlapping(provider, start, end, excludeId).isEmpty();
    }

    private void validateFields(Appointment appointment) {
        if (appointment.getProvider() == null || appointment.getProvider().getId() == null) {
            throw new IllegalArgumentException("A provider is required.");
        }
        if (appointment.getCustomer() == null || appointment.getCustomer().getId() == null) {
            throw new IllegalArgumentException("A customer is required.");
        }
        if (appointment.getStartTime() == null || appointment.getEndTime() == null) {
            throw new IllegalArgumentException("Start and end times are required.");
        }
        if (!appointment.getEndTime().isAfter(appointment.getStartTime())) {
            throw new IllegalArgumentException("End time must be after start time.");
        }
        if (appointment.getType() == null) {
            appointment.setType(AppointmentType.APPOINTMENT);
        }
    }

    private void assertOwnership(Employee provider, Employee currentEmployee) {
        if (provider == null || provider.getId() == null) {
            throw new IllegalArgumentException("A provider is required.");
        }
        if (currentEmployee == null) {
            return; // admin context
        }
        if (!Objects.equals(provider.getId(), currentEmployee.getId())) {
            log.warn("Employee {} attempted to act on appointments for provider {}",
                    currentEmployee.getId(), provider.getId());
            throw new AccessDeniedException("You are not authorized to manage this provider's schedule.");
        }
    }
}
