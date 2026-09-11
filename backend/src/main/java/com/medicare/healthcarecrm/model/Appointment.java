package com.medicare.healthcarecrm.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * A scheduled slot for a provider, replacing the flat {@code Tasks.dueDate}.
 *
 * An APPOINTMENT has a real {@code startTime}/{@code endTime} span and is subject
 * to the availability + double-booking guard. A DEADLINE (migrated legacy task)
 * carries {@code startTime == endTime} and never occupies provider time.
 *
 * A composite index on {@code (provider_id, start_time)} backs every calendar
 * range query and the overlap check — both filter on exactly those columns.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Builder
@Table(name = "appointment", indexes = {
        @Index(name = "idx_appointment_provider_start", columnList = "provider_id, start_time")
})
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotEmpty(message = "Title cannot be empty")
    @Size(max = 150, message = "Title is too long (max 150 chars)")
    @Column(nullable = false)
    private String title;

    @NotNull(message = "Appointment must be assigned to a customer")
    @ManyToOne
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @NotNull(message = "Appointment must be assigned to a provider")
    @ManyToOne
    @JoinColumn(name = "provider_id", nullable = false)
    private Employee provider;

    @NotNull(message = "Appointment type is required")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppointmentType type;

    @NotNull(message = "Start time cannot be null")
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @NotNull(message = "End time cannot be null")
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @NotEmpty(message = "Priority cannot be empty")
    @Column(nullable = false)
    private String priority;

    @NotEmpty(message = "Description cannot be empty")
    @Size(max = 2000, message = "Description is too long (max 2000 chars)")
    @Column(nullable = false, length = 2000)
    private String description;

    @NotEmpty(message = "Status cannot be empty")
    @Column(nullable = false)
    private String status;

    /** Nullable back-reference: set when this appointment was generated from a recurring series. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "series_id")
    private AppointmentSeries series;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (this.status == null || this.status.isEmpty()) {
            this.status = "Pending";
        }
        if (this.type == null) {
            this.type = AppointmentType.APPOINTMENT;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
