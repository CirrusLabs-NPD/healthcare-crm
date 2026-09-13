package com.medicare.healthcarecrm.service;

import com.medicare.healthcarecrm.model.AvailabilityException;
import com.medicare.healthcarecrm.model.AvailabilityRule;
import com.medicare.healthcarecrm.model.Employee;
import com.medicare.healthcarecrm.repository.AvailabilityExceptionRepository;
import com.medicare.healthcarecrm.repository.AvailabilityRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Manages provider working hours: recurring weekly {@link AvailabilityRule}s and
 * dated {@link AvailabilityException}s. Every write enforces that the acting
 * employee owns the provider record it touches; an admin (currentEmployee null)
 * bypasses the ownership check, matching the app's role model.
 */
@Service
public class AvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    private final AvailabilityRuleRepository ruleRepository;
    private final AvailabilityExceptionRepository exceptionRepository;

    public AvailabilityService(AvailabilityRuleRepository ruleRepository,
                               AvailabilityExceptionRepository exceptionRepository) {
        this.ruleRepository = ruleRepository;
        this.exceptionRepository = exceptionRepository;
    }

    public List<AvailabilityRule> getRules(Employee provider) {
        return ruleRepository.findByProvider(provider);
    }

    public List<AvailabilityException> getExceptions(Employee provider) {
        return exceptionRepository.findByProvider(provider);
    }

    @Transactional
    public AvailabilityRule addRule(AvailabilityRule rule, Employee currentEmployee) {
        assertOwnership(rule.getProvider(), currentEmployee, "availability rule");
        if (rule.getStartTime() == null || rule.getEndTime() == null
                || !rule.getEndTime().isAfter(rule.getStartTime())) {
            throw new IllegalArgumentException("Rule end time must be after start time.");
        }
        AvailabilityRule saved = ruleRepository.save(rule);
        log.info("Availability rule {} added for provider {} on {}", saved.getId(),
                rule.getProvider().getId(), rule.getDayOfWeek());
        return saved;
    }

    @Transactional
    public void deleteRule(Long id, Employee currentEmployee) {
        AvailabilityRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Availability rule not found: " + id));
        assertOwnership(rule.getProvider(), currentEmployee, "availability rule");
        ruleRepository.deleteById(id);
        log.info("Availability rule {} deleted", id);
    }

    @Transactional
    public AvailabilityException addException(AvailabilityException exception, Employee currentEmployee) {
        assertOwnership(exception.getProvider(), currentEmployee, "availability exception");
        boolean partial = exception.getStartTime() != null || exception.getEndTime() != null;
        if (partial) {
            if (exception.getStartTime() == null || exception.getEndTime() == null
                    || !exception.getEndTime().isAfter(exception.getStartTime())) {
                throw new IllegalArgumentException(
                        "Exception must have both start and end times with end after start, or neither (whole day).");
            }
        }
        AvailabilityException saved = exceptionRepository.save(exception);
        log.info("Availability exception {} added for provider {} on {} (available={})", saved.getId(),
                exception.getProvider().getId(), exception.getDate(), exception.isAvailable());
        return saved;
    }

    @Transactional
    public void deleteException(Long id, Employee currentEmployee) {
        AvailabilityException exception = exceptionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Availability exception not found: " + id));
        assertOwnership(exception.getProvider(), currentEmployee, "availability exception");
        exceptionRepository.deleteById(id);
        log.info("Availability exception {} deleted", id);
    }

    List<AvailabilityRule> rulesForDay(Employee provider, java.time.DayOfWeek dayOfWeek) {
        return ruleRepository.findByProviderAndDayOfWeek(provider, dayOfWeek);
    }

    List<AvailabilityException> exceptionsForDate(Employee provider, LocalDate date) {
        return exceptionRepository.findByProviderAndDate(provider, date);
    }

    /**
     * An admin acts on behalf of anyone (currentEmployee == null). An employee
     * may only touch their own provider record.
     */
    private void assertOwnership(Employee provider, Employee currentEmployee, String what) {
        if (provider == null || provider.getId() == null) {
            throw new IllegalArgumentException("A provider is required for an " + what + ".");
        }
        if (currentEmployee == null) {
            return; // admin context
        }
        if (!Objects.equals(provider.getId(), currentEmployee.getId())) {
            log.warn("Employee {} attempted to modify {} for provider {}", currentEmployee.getId(), what,
                    provider.getId());
            throw new AccessDeniedException("You are not authorized to modify this provider's availability.");
        }
    }
}
