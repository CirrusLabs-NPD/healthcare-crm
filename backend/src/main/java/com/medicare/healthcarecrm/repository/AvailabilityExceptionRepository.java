package com.medicare.healthcarecrm.repository;

import com.medicare.healthcarecrm.model.AvailabilityException;
import com.medicare.healthcarecrm.model.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AvailabilityExceptionRepository extends JpaRepository<AvailabilityException, Long> {

    List<AvailabilityException> findByProvider(Employee provider);

    List<AvailabilityException> findByProviderAndDate(Employee provider, LocalDate date);
}
