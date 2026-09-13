package com.medicare.healthcarecrm.service;

/**
 * Thrown when a booking or reschedule is refused by the server-side guard:
 * outside the provider's availability, or overlapping an existing appointment.
 * The web layer renders {@link #getMessage()} as a form error; the API maps it
 * to HTTP 409 Conflict.
 */
public class BookingConflictException extends RuntimeException {

    public BookingConflictException(String message) {
        super(message);
    }
}
