package com.medicare.healthcarecrm.repository;

import com.medicare.healthcarecrm.model.AvailabilityRule;
import com.medicare.healthcarecrm.model.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.DayOfWeek;
import java.util.List;

@Repository
public interface AvailabilityRuleRepository extends JpaRepository<AvailabilityRule, Long> {

    List<AvailabilityRule> findByProvider(Employee provider);

    List<AvailabilityRule> findByProviderAndDayOfWeek(Employee provider, DayOfWeek dayOfWeek);
}
