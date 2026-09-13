package com.medicare.healthcarecrm.service;

import com.medicare.healthcarecrm.model.AppointmentSeries;
import com.medicare.healthcarecrm.model.AvailabilityException;
import com.medicare.healthcarecrm.model.AvailabilityRule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, side-effect-free scheduling math: recurrence expansion, availability
 * windows and time-span overlap. Kept free of Spring and JPA so the tricky
 * logic can be unit-tested in isolation and reasoned about on its own.
 *
 * All times are the server's local zone, consistent with the codebase's use of
 * {@code LocalDateTime} throughout (cross-timezone handling is out of scope).
 */
public final class SchedulingSupport {

    /** Hard cap on how many occurrences a single series may expand to, so an
     *  open-ended rule cannot generate an unbounded number of rows. */
    public static final int MAX_OCCURRENCES = 366;

    private SchedulingSupport() {
    }

    /** Half-open overlap test: [startA, endA) intersects [startB, endB). */
    public static boolean overlaps(LocalDateTime startA, LocalDateTime endA,
                                   LocalDateTime startB, LocalDateTime endB) {
        return startA.isBefore(endB) && endA.isAfter(startB);
    }

    /**
     * The start instants a series generates, honouring frequency, interval and
     * whichever end condition is set (untilDate or occurrenceCount), always
     * bounded by {@link #MAX_OCCURRENCES}. Returns start-of-occurrence
     * {@link LocalDateTime}s; the caller adds the duration for the end.
     */
    public static List<LocalDateTime> expandOccurrences(AppointmentSeries series) {
        List<LocalDateTime> starts = new ArrayList<>();
        if (series == null || series.getStartDate() == null || series.getStartTime() == null
                || series.getFrequency() == null) {
            return starts;
        }

        int interval = series.getIntervalCount() == null || series.getIntervalCount() < 1
                ? 1 : series.getIntervalCount();

        // occurrenceCount takes effect if set; otherwise cap at MAX_OCCURRENCES and rely on untilDate.
        int maxCount = MAX_OCCURRENCES;
        if (series.getOccurrenceCount() != null && series.getOccurrenceCount() > 0) {
            maxCount = Math.min(series.getOccurrenceCount(), MAX_OCCURRENCES);
        }

        LocalDate until = series.getUntilDate();
        LocalDate cursor = series.getStartDate();

        for (int i = 0; i < maxCount; i++) {
            if (until != null && cursor.isAfter(until)) {
                break;
            }
            starts.add(LocalDateTime.of(cursor, series.getStartTime()));

            switch (series.getFrequency()) {
                case DAILY -> cursor = cursor.plusDays(interval);
                case WEEKLY -> cursor = cursor.plusWeeks(interval);
                case MONTHLY -> cursor = cursor.plusMonths(interval);
            }
        }
        return starts;
    }

    /**
     * Whether a requested span sits fully inside the provider's available time
     * for its date: covered by a weekly {@link AvailabilityRule} (or an
     * {@code available=true} exception), and not clipped by any blocking
     * {@code available=false} exception. The lists passed in must already be
     * scoped to this provider and this date.
     */
    public static boolean isWithinAvailability(LocalDateTime start, LocalDateTime end,
                                               List<AvailabilityRule> rulesForDay,
                                               List<AvailabilityException> exceptionsForDate) {
        if (start == null || end == null || !end.isAfter(start)) {
            return false;
        }
        // A span crossing midnight is never inside a single day's window.
        if (!start.toLocalDate().equals(end.toLocalDate())) {
            return false;
        }

        LocalTime startT = start.toLocalTime();
        LocalTime endT = end.toLocalTime();

        // Any blocking exception window that intersects the span rejects it outright.
        if (exceptionsForDate != null) {
            for (AvailabilityException ex : exceptionsForDate) {
                if (!ex.isAvailable()) {
                    if (ex.getStartTime() == null || ex.getEndTime() == null) {
                        return false; // whole-day off
                    }
                    if (startT.isBefore(ex.getEndTime()) && endT.isAfter(ex.getStartTime())) {
                        return false; // blocked window overlaps the span
                    }
                }
            }
        }

        // The span must be fully covered by at least one open window (rule or extra-hours exception).
        List<AvailabilityException> extra = new ArrayList<>();
        if (exceptionsForDate != null) {
            for (AvailabilityException ex : exceptionsForDate) {
                if (ex.isAvailable() && ex.getStartTime() != null && ex.getEndTime() != null) {
                    extra.add(ex);
                }
            }
        }

        if (rulesForDay != null) {
            for (AvailabilityRule rule : rulesForDay) {
                if (!startT.isBefore(rule.getStartTime()) && !endT.isAfter(rule.getEndTime())) {
                    return true;
                }
            }
        }
        for (AvailabilityException ex : extra) {
            if (!startT.isBefore(ex.getStartTime()) && !endT.isAfter(ex.getEndTime())) {
                return true;
            }
        }
        return false;
    }
}
