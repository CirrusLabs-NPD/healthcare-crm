package com.medicare.healthcarecrm.service;

import com.medicare.healthcarecrm.model.Appointment;
import com.medicare.healthcarecrm.model.AppointmentSeries;
import com.medicare.healthcarecrm.model.AppointmentType;
import com.medicare.healthcarecrm.model.AvailabilityException;
import com.medicare.healthcarecrm.model.AvailabilityRule;
import com.medicare.healthcarecrm.model.Customer;
import com.medicare.healthcarecrm.model.Employee;
import com.medicare.healthcarecrm.model.Insurance;
import com.medicare.healthcarecrm.model.RecurrenceFrequency;
import com.medicare.healthcarecrm.repository.AppointmentRepository;
import com.medicare.healthcarecrm.repository.CustomerRepository;
import com.medicare.healthcarecrm.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end scheduling behaviour through the WIRED services against a real
 * (H2) persistence layer — the layer the pure {@code SchedulingSupportTest}
 * cannot reach. It proves the acceptance criteria that only hold once the
 * service, the repository queries and the JPA mapping run together:
 *
 * <ul>
 *   <li>AC-1  end &le; start is rejected as a validation error, not a 500</li>
 *   <li>AC-3/4/5  the calendar range query returns only the selected provider's
 *       appointments inside the window</li>
 *   <li>AC-9  a booking outside availability is refused and nothing persists</li>
 *   <li>AC-10 an overlapping booking for the same provider is refused and nothing
 *       persists; two providers may overlap freely</li>
 *   <li>AC-12 the follow-up queries (overdue / due-soon) read the new time field
 *       with the documented boundary semantics</li>
 *   <li>AC-15 a weekly series generates exactly the expected occurrences, linked
 *       to the series</li>
 *   <li>AC-16 editing one occurrence leaves the rest of the series untouched</li>
 *   <li>AC-17 cancelling the whole series removes all occurrences; cancelling one
 *       removes only that one</li>
 *   <li>AC-18 a generated occurrence that clashes is FLAGGED (status "Conflict"),
 *       never silently dropped or silently double-booked</li>
 * </ul>
 *
 * The booking guard lives in {@link AppointmentService}; an admin acts with a
 * {@code null} current employee (matching the app's role model), so these tests
 * pass {@code null} to exercise the guard without the ownership branch.
 */
@SpringBootTest
@Transactional
class SchedulingServiceIntegrationTest {

    @Autowired private AppointmentService appointmentService;
    @Autowired private AvailabilityService availabilityService;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private EmployeeRepository employeeRepository;

    /** Monday 2026-01-05 — the fixed anchor day the availability rule covers. */
    private static final LocalDate MON = LocalDate.of(2026, 1, 5);

    private Employee providerA;
    private Employee providerB;
    private Customer customer;

    @BeforeEach
    void setUp() {
        appointmentRepository.deleteAll();

        providerA = employeeRepository.save(Employee.builder()
                .name("Dr A").role("Doctor")
                .email("sched.a@test.local").password("x").build());
        providerB = employeeRepository.save(Employee.builder()
                .name("Dr B").role("Doctor")
                .email("sched.b@test.local").password("x").build());

        Insurance insurance = Insurance.builder()
                .provider("BlueCross").policyNumber("AB1234567")
                .coverageDetails("Basic").expiryDate(LocalDateTime.now().plusMonths(12))
                .build();
        customer = customerRepository.save(Customer.builder()
                .name("Pat").age(30).gender("Other")
                .email("sched.pat@test.local").medicalHistory("None")
                .contactDetails("(555) 010-3000").insurance(insurance)
                .build());

        // Both providers work Monday 09:00-17:00 so a same-day span is bookable.
        availabilityService.addRule(AvailabilityRule.builder()
                .provider(providerA).dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).build(), null);
        availabilityService.addRule(AvailabilityRule.builder()
                .provider(providerB).dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).build(), null);
    }

    private Appointment appt(Employee provider, LocalDateTime start, LocalDateTime end) {
        return Appointment.builder()
                .title("Visit").customer(customer).provider(provider)
                .type(AppointmentType.APPOINTMENT).startTime(start).endTime(end)
                .priority("Medium").description("desc").status("Pending").build();
    }

    private LocalDateTime mon(int hour, int min) {
        return LocalDateTime.of(MON, LocalTime.of(hour, min));
    }

    // === AC-1: field validation is a validation error, not a 500 ===

    @Test
    void endBeforeOrEqualStartIsRejected() {
        // end == start
        assertThatThrownBy(() -> appointmentService.book(appt(providerA, mon(10, 0), mon(10, 0)), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("End time must be after start time");
        // end < start
        assertThatThrownBy(() -> appointmentService.book(appt(providerA, mon(11, 0), mon(10, 0)), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(appointmentRepository.count()).isZero();
    }

    // === AC-3 / AC-4 / AC-5: calendar range query scoping ===

    @Test
    void calendarReturnsOnlyThatProvidersAppointmentsInsideTheWindow() {
        Appointment inA = appointmentService.book(appt(providerA, mon(10, 0), mon(10, 30)), null);
        appointmentService.book(appt(providerB, mon(11, 0), mon(11, 30)), null); // other provider
        // Outside the day window (next Monday) — must not appear.
        appointmentService.book(appt(providerA, mon(10, 0).plusWeeks(1), mon(10, 30).plusWeeks(1)), null);

        LocalDateTime from = LocalDateTime.of(MON, LocalTime.MIN);
        LocalDateTime to = from.plusDays(1);
        List<Appointment> dayForA = appointmentService.getCalendar(providerA, from, to);

        assertThat(dayForA).extracting(Appointment::getId).containsExactly(inA.getId());
    }

    // === AC-9: booking outside availability is refused, nothing persists ===

    @Test
    void bookingOutsideAvailabilityIsRefused() {
        // 08:00 is before the 09:00-17:00 Monday window.
        assertThatThrownBy(() -> appointmentService.book(appt(providerA, mon(8, 0), mon(8, 30)), null))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("available hours");
        assertThat(appointmentRepository.count()).isZero();
    }

    @Test
    void wholeDayOffExceptionRefusesAnOtherwiseValidBooking() {
        availabilityService.addException(AvailabilityException.builder()
                .provider(providerA).date(MON).available(false).build(), null);
        assertThatThrownBy(() -> appointmentService.book(appt(providerA, mon(10, 0), mon(10, 30)), null))
                .isInstanceOf(BookingConflictException.class);
        assertThat(appointmentRepository.count()).isZero();
    }

    // === AC-10: overlap is refused for the same provider; two providers may overlap ===

    @Test
    void overlappingBookingForSameProviderIsRefused() {
        appointmentService.book(appt(providerA, mon(10, 0), mon(11, 0)), null);
        assertThatThrownBy(() -> appointmentService.book(appt(providerA, mon(10, 30), mon(11, 30)), null))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("overlaps");
        // Only the first booking survived.
        assertThat(appointmentRepository.count()).isEqualTo(1);
    }

    @Test
    void touchingEdgeIsNotAnOverlap() {
        appointmentService.book(appt(providerA, mon(10, 0), mon(11, 0)), null);
        // 11:00-12:00 starts exactly when the first ends — half-open, so no clash.
        Appointment second = appointmentService.book(appt(providerA, mon(11, 0), mon(12, 0)), null);
        assertThat(second.getId()).isNotNull();
        assertThat(appointmentRepository.count()).isEqualTo(2);
    }

    @Test
    void twoProvidersMayHoldOverlappingAppointments() {
        appointmentService.book(appt(providerA, mon(10, 0), mon(11, 0)), null);
        Appointment b = appointmentService.book(appt(providerB, mon(10, 0), mon(11, 0)), null);
        assertThat(b.getId()).isNotNull();
        assertThat(appointmentRepository.count()).isEqualTo(2);
    }

    // === AC-12: follow-up boundary queries read the new time field ===

    @Test
    void followUpQueriesReadTheNewTimeFieldWithBoundarySemantics() {
        LocalDateTime nowRef = mon(12, 0);

        // A past DEADLINE, not Completed -> overdue.
        Appointment overdue = appointmentRepository.save(Appointment.builder()
                .title("Overdue").customer(customer).provider(providerA)
                .type(AppointmentType.DEADLINE).startTime(mon(9, 0)).endTime(mon(9, 0))
                .priority("High").description("d").status("Pending").build());
        // A past DEADLINE that IS Completed -> excluded from overdue.
        appointmentRepository.save(Appointment.builder()
                .title("Done").customer(customer).provider(providerA)
                .type(AppointmentType.DEADLINE).startTime(mon(9, 0)).endTime(mon(9, 0))
                .priority("High").description("d").status("Completed").build());
        // A future appointment within 7 days, not Completed -> due-soon.
        Appointment soon = appointmentService.book(appt(providerA, mon(14, 0), mon(14, 30)), null);

        List<Appointment> overdueList = appointmentRepository.findOverdueDeadlines(nowRef);
        assertThat(overdueList).extracting(Appointment::getId).containsExactly(overdue.getId());

        List<Appointment> dueSoon = appointmentRepository.findStartingSoon(nowRef, nowRef.plusDays(7));
        assertThat(dueSoon).extracting(Appointment::getId).contains(soon.getId());
        // The completed and overdue rows are not "starting soon".
        assertThat(dueSoon).extracting(Appointment::getStatus).doesNotContain("Completed");
    }

    // === AC-15 / AC-18: series generation, linkage, and conflict flagging ===

    private AppointmentSeries weeklySeries(int count, LocalTime at) {
        return AppointmentSeries.builder()
                .title("Weekly follow-up").customer(customer).provider(providerA)
                .frequency(RecurrenceFrequency.WEEKLY).intervalCount(1)
                .startDate(MON).startTime(at).durationMin(30)
                .occurrenceCount(count).priority("Medium").description("series").build();
    }

    @Test
    void weeklySeriesGeneratesLinkedOccurrences() {
        List<Appointment> generated = appointmentService.createSeries(weeklySeries(3, LocalTime.of(10, 0)), null);

        assertThat(generated).hasSize(3);
        assertThat(generated).allSatisfy(a -> {
            assertThat(a.getSeries()).isNotNull();
            assertThat(a.getType()).isEqualTo(AppointmentType.APPOINTMENT);
        });
        // Every occurrence is a Monday one week apart, all inside availability -> no conflict.
        assertThat(generated).extracting(a -> a.getStartTime().toLocalDate())
                .containsExactly(MON, MON.plusWeeks(1), MON.plusWeeks(2));
        assertThat(generated).extracting(Appointment::getStatus).containsOnly("Pending");
    }

    @Test
    void occurrenceThatClashesIsFlaggedNotDropped() {
        // Pre-book 10:00-10:30 on the first Monday so the series' first occurrence clashes.
        appointmentService.book(appt(providerA, mon(10, 0), mon(10, 30)), null);

        List<Appointment> generated = appointmentService.createSeries(weeklySeries(2, LocalTime.of(10, 0)), null);

        // Nothing dropped: both occurrences exist.
        assertThat(generated).hasSize(2);
        // The clashing first one is flagged, not silently overlapped or removed.
        Appointment first = generated.get(0);
        assertThat(first.getStartTime().toLocalDate()).isEqualTo(MON);
        assertThat(first.getStatus()).isEqualTo("Conflict");
        // The second week is clear -> Pending.
        assertThat(generated.get(1).getStatus()).isEqualTo("Pending");
    }

    @Test
    void seriesWithNoEndConditionIsRejected() {
        AppointmentSeries open = weeklySeries(3, LocalTime.of(10, 0));
        open.setOccurrenceCount(null);
        open.setUntilDate(null);
        assertThatThrownBy(() -> appointmentService.createSeries(open, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("until date or an occurrence count");
    }

    // === AC-16: editing one occurrence leaves the rest of the series untouched ===

    @Test
    void editingOneOccurrenceDoesNotChangeTheOthers() {
        List<Appointment> generated = appointmentService.createSeries(weeklySeries(3, LocalTime.of(10, 0)), null);
        Appointment target = generated.get(1);
        // Capture BEFORE rescheduling: target is a managed entity, so reschedule
        // mutates this same instance in place — reading it back afterwards would
        // return the new value, not the original.
        LocalDateTime originalStart = target.getStartTime();
        Long targetId = target.getId();

        Appointment edited = appt(providerA, originalStart.plusHours(2), target.getEndTime().plusHours(2));
        edited.setTitle("Rescheduled one");
        appointmentService.reschedule(targetId, edited, null);

        Appointment reloaded = appointmentService.getAppointmentById(targetId);
        assertThat(reloaded.getStartTime()).isEqualTo(originalStart.plusHours(2));
        // The other two occurrences keep their original 10:00 start.
        assertThat(appointmentService.getAppointmentById(generated.get(0).getId()).getStartTime().toLocalTime())
                .isEqualTo(LocalTime.of(10, 0));
        assertThat(appointmentService.getAppointmentById(generated.get(2).getId()).getStartTime().toLocalTime())
                .isEqualTo(LocalTime.of(10, 0));
    }

    // === AC-17: cancel whole series vs cancel one occurrence ===

    @Test
    void cancellingOneOccurrenceRemovesOnlyThatOne() {
        List<Appointment> generated = appointmentService.createSeries(weeklySeries(3, LocalTime.of(10, 0)), null);
        appointmentService.delete(generated.get(0).getId(), null);

        assertThat(appointmentService.getAppointmentById(generated.get(0).getId())).isNull();
        assertThat(appointmentService.getAppointmentById(generated.get(1).getId())).isNotNull();
        assertThat(appointmentService.getAppointmentById(generated.get(2).getId())).isNotNull();
        assertThat(appointmentRepository.count()).isEqualTo(2);
    }

    @Test
    void cancellingWholeSeriesRemovesAllOccurrences() {
        List<Appointment> generated = appointmentService.createSeries(weeklySeries(3, LocalTime.of(10, 0)), null);
        Long seriesId = generated.get(0).getSeries().getId();

        appointmentService.deleteSeries(seriesId, null);

        assertThat(appointmentRepository.count()).isZero();
    }
}
