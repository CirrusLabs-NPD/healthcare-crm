package com.medicare.healthcarecrm.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * The recurrence rule for a set of appointments. Materialised: expanding a
 * series produces concrete {@link Appointment} rows (each carrying a back-ref
 * via {@code series}) rather than being computed on every read, so an
 * individual occurrence can be edited or cancelled independently.
 *
 * A series ends by either {@code untilDate} or {@code occurrenceCount}; at least
 * one must be set, and expansion is additionally capped by a bounded horizon in
 * the service to keep an unbounded rule from generating an unbounded table.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Builder
@Table(name = "appointment_series", indexes = {
        @Index(name = "idx_appointment_series_provider", columnList = "provider_id")
})
public class AppointmentSeries {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotEmpty(message = "Title cannot be empty")
    @Size(max = 150, message = "Title is too long (max 150 chars)")
    @Column(nullable = false)
    private String title;

    @NotNull(message = "Series must be assigned to a customer")
    @ManyToOne
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @NotNull(message = "Series must be assigned to a provider")
    @ManyToOne
    @JoinColumn(name = "provider_id", nullable = false)
    private Employee provider;

    @NotNull(message = "Recurrence frequency is required")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RecurrenceFrequency frequency;

    /** Repeat every N units of the frequency (every 1 week, every 2 days, ...). */
    @NotNull(message = "Interval is required")
    @Min(value = 1, message = "Interval must be at least 1")
    @Column(name = "interval_count", nullable = false)
    private Integer intervalCount;

    /** The date the first occurrence falls on. */
    @NotNull(message = "Series start date is required")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Time of day each occurrence starts. */
    @NotNull(message = "Series start time is required")
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    /** Duration of each occurrence, in minutes. */
    @NotNull(message = "Duration is required")
    @Min(value = 1, message = "Duration must be at least 1 minute")
    @Column(name = "duration_min", nullable = false)
    private Integer durationMin;

    /** End condition A: repeat until this date inclusive. Null when using occurrenceCount. */
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @Column(name = "until_date")
    private LocalDate untilDate;

    /** End condition B: this many occurrences. Null when using untilDate. */
    @Min(value = 1, message = "Occurrence count must be at least 1")
    @Column(name = "occurrence_count")
    private Integer occurrenceCount;

    @Column(nullable = false)
    private String priority;

    @Column(nullable = false, length = 2000)
    private String description;

    private LocalDate createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDate.now();
        if (this.intervalCount == null) {
            this.intervalCount = 1;
        }
    }
}
