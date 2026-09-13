package com.medicare.healthcarecrm.repository;

import com.medicare.healthcarecrm.model.Appointment;
import com.medicare.healthcarecrm.model.AppointmentSeries;
import com.medicare.healthcarecrm.model.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    /**
     * All appointments for one provider that start within a [from, to) window —
     * the single query behind the day and week calendar views. Uses the
     * (provider_id, start_time) composite index.
     */
    @Query("SELECT a FROM Appointment a WHERE a.provider = :provider "
            + "AND a.startTime >= :from AND a.startTime < :to ORDER BY a.startTime")
    List<Appointment> findForProviderInRange(@Param("provider") Employee provider,
                                             @Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to);

    /**
     * Appointments for a provider that overlap the half-open span [start, end),
     * excluding a given appointment id (0 when creating). Two spans overlap when
     * existing.start < newEnd AND existing.end > newStart. DEADLINE rows occupy
     * no time and are excluded so they never block a booking.
     */
    @Query("SELECT a FROM Appointment a WHERE a.provider = :provider "
            + "AND a.type = com.medicare.healthcarecrm.model.AppointmentType.APPOINTMENT "
            + "AND a.id <> :excludeId "
            + "AND a.startTime < :end AND a.endTime > :start")
    List<Appointment> findOverlapping(@Param("provider") Employee provider,
                                      @Param("start") LocalDateTime start,
                                      @Param("end") LocalDateTime end,
                                      @Param("excludeId") Long excludeId);

    List<Appointment> findByProvider(Employee provider);

    List<Appointment> findBySeries(AppointmentSeries series);

    long countByStatus(String status);

    @Query("SELECT a FROM Appointment a WHERE a.startTime < :now AND a.status <> 'Completed' "
            + "AND a.type = com.medicare.healthcarecrm.model.AppointmentType.DEADLINE")
    List<Appointment> findOverdueDeadlines(@Param("now") LocalDateTime now);

    @Query("SELECT a FROM Appointment a WHERE a.startTime >= :now AND a.startTime <= :futureDate "
            + "AND a.status <> 'Completed'")
    List<Appointment> findStartingSoon(@Param("now") LocalDateTime now,
                                       @Param("futureDate") LocalDateTime futureDate);
}
