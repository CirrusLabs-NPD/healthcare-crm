package com.medicare.healthcarecrm.model;

/**
 * How often an {@link AppointmentSeries} repeats. Deliberately a small, closed
 * set — full RFC 5545 is out of scope (see S-2 design).
 */
public enum RecurrenceFrequency {
    DAILY,
    WEEKLY,
    MONTHLY
}
