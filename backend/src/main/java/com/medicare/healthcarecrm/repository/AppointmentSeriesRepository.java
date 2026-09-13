package com.medicare.healthcarecrm.repository;

import com.medicare.healthcarecrm.model.AppointmentSeries;
import com.medicare.healthcarecrm.model.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AppointmentSeriesRepository extends JpaRepository<AppointmentSeries, Long> {

    List<AppointmentSeries> findByProvider(Employee provider);
}
