package com.medicare.healthcarecrm.model;

/**
 * Distinguishes a booked slot (has a real duration and occupies provider time)
 * from a plain deadline migrated from the legacy free-text due date.
 *
 * DEADLINE preserves the pre-scheduling {@code Tasks.dueDate} rows losslessly:
 * the Follow-Up Center keeps working against them and they never block a slot.
 */
public enum AppointmentType {
    APPOINTMENT,
    DEADLINE
}
