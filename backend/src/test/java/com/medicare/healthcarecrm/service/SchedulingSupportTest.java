package com.medicare.healthcarecrm.service;

import com.medicare.healthcarecrm.model.AppointmentSeries;
import com.medicare.healthcarecrm.model.AvailabilityException;
import com.medicare.healthcarecrm.model.AvailabilityRule;
import com.medicare.healthcarecrm.model.RecurrenceFrequency;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure scheduling math — no Spring context, no database, so
 * they run fast and deterministically in CI. They pin the overlap edge cases,
 * recurrence expansion (counts, spacing, month clamping, the horizon cap) and
 * the availability window logic that the booking guard relies on.
 */
class SchedulingSupportTest {

    private static final LocalDate MON = LocalDate.of(2026, 1, 5); // a Monday
    private static final LocalDateTime T9 = LocalDateTime.of(2026, 1, 5, 9, 0);
    private static final LocalDateTime T930 = LocalDateTime.of(2026, 1, 5, 9, 30);
    private static final LocalDateTime T10 = LocalDateTime.of(2026, 1, 5, 10, 0);
    private static final LocalDateTime T11 = LocalDateTime.of(2026, 1, 5, 11, 0);

    // === overlap ===

    @Test
    void touchingEdgesDoNotOverlap() {
        assertFalse(SchedulingSupport.overlaps(T9, T10, T10, T11));
    }

    @Test
    void spansThatIntersectOverlap() {
        assertTrue(SchedulingSupport.overlaps(T9, T930, T9.plusMinutes(15), T10));
    }

    @Test
    void containedSpanOverlaps() {
        assertTrue(SchedulingSupport.overlaps(T9, T11, T930, T10));
    }

    @Test
    void disjointSpansDoNotOverlap() {
        assertFalse(SchedulingSupport.overlaps(T9, T930, T10, T11));
    }

    // === recurrence ===

    @Test
    void weeklyByCountProducesEvenlySpacedStarts() {
        AppointmentSeries s = AppointmentSeries.builder()
                .frequency(RecurrenceFrequency.WEEKLY).intervalCount(1)
                .startDate(MON).startTime(LocalTime.of(9, 0))
                .occurrenceCount(3).durationMin(30).build();
        List<LocalDateTime> starts = SchedulingSupport.expandOccurrences(s);
        assertEquals(3, starts.size());
        assertEquals(LocalDate.of(2026, 1, 12), starts.get(1).toLocalDate());
        assertEquals(LocalDate.of(2026, 1, 19), starts.get(2).toLocalDate());
    }

    @Test
    void dailyEveryTwoDaysUntilDateIsInclusive() {
        AppointmentSeries s = AppointmentSeries.builder()
                .frequency(RecurrenceFrequency.DAILY).intervalCount(2)
                .startDate(LocalDate.of(2026, 1, 1)).startTime(LocalTime.of(8, 0))
                .untilDate(LocalDate.of(2026, 1, 7)).durationMin(30).build();
        // Jan 1, 3, 5, 7
        assertEquals(4, SchedulingSupport.expandOccurrences(s).size());
    }

    @Test
    void openEndedSeriesIsCappedAtHorizon() {
        AppointmentSeries s = AppointmentSeries.builder()
                .frequency(RecurrenceFrequency.DAILY).intervalCount(1)
                .startDate(LocalDate.of(2026, 1, 1)).startTime(LocalTime.of(8, 0))
                .durationMin(30).build();
        assertEquals(SchedulingSupport.MAX_OCCURRENCES, SchedulingSupport.expandOccurrences(s).size());
    }

    @Test
    void monthlyClampsToShortMonth() {
        AppointmentSeries s = AppointmentSeries.builder()
                .frequency(RecurrenceFrequency.MONTHLY).intervalCount(1)
                .startDate(LocalDate.of(2026, 1, 31)).startTime(LocalTime.of(8, 0))
                .occurrenceCount(2).durationMin(30).build();
        List<LocalDateTime> starts = SchedulingSupport.expandOccurrences(s);
        assertEquals(LocalDate.of(2026, 2, 28), starts.get(1).toLocalDate());
    }

    // === availability ===

    private List<AvailabilityRule> mon9to5() {
        return List.of(AvailabilityRule.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).build());
    }

    @Test
    void spanInsideWeeklyWindowIsAvailable() {
        assertTrue(SchedulingSupport.isWithinAvailability(T9, T10, mon9to5(), List.of()));
    }

    @Test
    void spanOutsideWeeklyWindowIsNotAvailable() {
        assertFalse(SchedulingSupport.isWithinAvailability(
                LocalDateTime.of(2026, 1, 5, 8, 0), LocalDateTime.of(2026, 1, 5, 8, 30),
                mon9to5(), List.of()));
    }

    @Test
    void blockingExceptionRejectsOverlappingSpan() {
        AvailabilityException off = AvailabilityException.builder()
                .available(false).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0)).build();
        assertFalse(SchedulingSupport.isWithinAvailability(T9, T10, mon9to5(), List.of(off)));
    }

    @Test
    void wholeDayOffRejectsEverything() {
        AvailabilityException off = AvailabilityException.builder().available(false).build();
        assertFalse(SchedulingSupport.isWithinAvailability(T9, T10, mon9to5(), List.of(off)));
    }

    @Test
    void extraHoursExceptionMakesSpanAvailable() {
        AvailabilityException extra = AvailabilityException.builder()
                .available(true).startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(20, 0)).build();
        assertTrue(SchedulingSupport.isWithinAvailability(
                LocalDateTime.of(2026, 1, 5, 18, 0), LocalDateTime.of(2026, 1, 5, 18, 30),
                List.of(), List.of(extra)));
    }

    @Test
    void spanCrossingMidnightIsRejected() {
        assertFalse(SchedulingSupport.isWithinAvailability(
                LocalDateTime.of(2026, 1, 5, 23, 30), LocalDateTime.of(2026, 1, 6, 0, 30),
                mon9to5(), List.of()));
    }

    @Test
    void zeroLengthSpanIsRejected() {
        assertFalse(SchedulingSupport.isWithinAvailability(T9, T9, mon9to5(), List.of()));
    }
}
