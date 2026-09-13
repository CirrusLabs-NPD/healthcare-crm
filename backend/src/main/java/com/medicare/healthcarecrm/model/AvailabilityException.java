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
 * An override of a provider's normal weekly hours on a specific date.
 *
 * {@code available = false} marks time off — a whole day when the times are null,
 * or a blocked window when they are set. {@code available = true} marks extra
 * hours worked outside the normal weekly rules.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Builder
@Table(name = "availability_exception", indexes = {
        @Index(name = "idx_availability_exception_provider_date", columnList = "provider_id, exception_date")
})
public class AvailabilityException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "Availability exception must belong to a provider")
    @ManyToOne
    @JoinColumn(name = "provider_id", nullable = false)
    private Employee provider;

    @NotNull(message = "Exception date is required")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @Column(name = "exception_date", nullable = false)
    private LocalDate date;

    /** false = time off (blocked); true = extra availability outside the weekly rules. */
    @Column(nullable = false)
    private boolean available;

    /** Null when the exception applies to the whole day. */
    @Column(name = "start_time")
    private LocalTime startTime;

    /** Null when the exception applies to the whole day. */
    @Column(name = "end_time")
    private LocalTime endTime;
}
