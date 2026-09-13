package com.medicare.healthcarecrm.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * A provider's normal weekly working hours for one weekday, e.g. Mon 09:00-17:00.
 * Multiple rows per weekday allow split shifts (e.g. 09:00-12:00 and 13:00-17:00).
 *
 * Bookable slots are derived on the fly from these rules minus
 * {@link AvailabilityException}s minus existing appointments — there is no
 * materialised slot table to fall stale.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Builder
@Table(name = "availability_rule", indexes = {
        @Index(name = "idx_availability_rule_provider", columnList = "provider_id")
})
public class AvailabilityRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "Availability rule must belong to a provider")
    @ManyToOne
    @JoinColumn(name = "provider_id", nullable = false)
    private Employee provider;

    @NotNull(message = "Day of week is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 10)
    private DayOfWeek dayOfWeek;

    @NotNull(message = "Start time is required")
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @NotNull(message = "End time is required")
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;
}
