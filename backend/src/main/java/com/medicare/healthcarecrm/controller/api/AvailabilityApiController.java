package com.medicare.healthcarecrm.controller.api;

import com.medicare.healthcarecrm.model.AvailabilityException;
import com.medicare.healthcarecrm.model.AvailabilityRule;
import com.medicare.healthcarecrm.model.Employee;
import com.medicare.healthcarecrm.service.AvailabilityService;
import com.medicare.healthcarecrm.service.EmployeeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * REST API for provider availability: recurring weekly rules and dated
 * exceptions. Every write is ownership-checked in the service — an employee may
 * only manage their own availability; an admin manages anyone's.
 */
@RestController
@RequestMapping("/api/availability")
@Tag(name = "Availability API", description = "API endpoints for provider working hours")
public class AvailabilityApiController {

    private final AvailabilityService availabilityService;
    private final EmployeeService employeeService;

    public AvailabilityApiController(AvailabilityService availabilityService, EmployeeService employeeService) {
        this.availabilityService = availabilityService;
        this.employeeService = employeeService;
    }

    @GetMapping("/rules")
    @Operation(summary = "List a provider's weekly availability rules")
    @ApiResponse(responseCode = "200", description = "Rules retrieved")
    @ApiResponse(responseCode = "404", description = "Provider not found")
    public ResponseEntity<Object> getRules(@RequestParam Long providerId) {
        Employee provider = resolveProvider(providerId);
        if (provider == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Provider not found: " + providerId);
        }
        return ResponseEntity.ok(availabilityService.getRules(provider));
    }

    @PostMapping("/rules")
    @Operation(summary = "Add a weekly availability rule")
    @ApiResponse(responseCode = "201", description = "Rule created")
    @ApiResponse(responseCode = "400", description = "Invalid data")
    public ResponseEntity<Object> addRule(@RequestParam Long providerId,
                                          @Valid @RequestBody AvailabilityRule rule,
                                          Principal principal) {
        Employee provider = resolveProvider(providerId);
        if (provider == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Provider not found: " + providerId);
        }
        rule.setId(null);
        rule.setProvider(provider);
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(availabilityService.addRule(rule, currentEmployee(principal)));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/rules/{id}")
    @Operation(summary = "Delete a weekly availability rule")
    @ApiResponse(responseCode = "204", description = "Rule deleted")
    @ApiResponse(responseCode = "404", description = "Rule not found")
    public ResponseEntity<Object> deleteRule(@PathVariable Long id, Principal principal) {
        try {
            availabilityService.deleteRule(id, currentEmployee(principal));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
    }

    @GetMapping("/exceptions")
    @Operation(summary = "List a provider's dated availability exceptions")
    @ApiResponse(responseCode = "200", description = "Exceptions retrieved")
    @ApiResponse(responseCode = "404", description = "Provider not found")
    public ResponseEntity<Object> getExceptions(@RequestParam Long providerId) {
        Employee provider = resolveProvider(providerId);
        if (provider == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Provider not found: " + providerId);
        }
        return ResponseEntity.ok(availabilityService.getExceptions(provider));
    }

    @PostMapping("/exceptions")
    @Operation(summary = "Add a dated availability exception (time off or extra hours)")
    @ApiResponse(responseCode = "201", description = "Exception created")
    @ApiResponse(responseCode = "400", description = "Invalid data")
    public ResponseEntity<Object> addException(@RequestParam Long providerId,
                                               @Valid @RequestBody AvailabilityException exception,
                                               Principal principal) {
        Employee provider = resolveProvider(providerId);
        if (provider == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Provider not found: " + providerId);
        }
        exception.setId(null);
        exception.setProvider(provider);
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(availabilityService.addException(exception, currentEmployee(principal)));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/exceptions/{id}")
    @Operation(summary = "Delete a dated availability exception")
    @ApiResponse(responseCode = "204", description = "Exception deleted")
    @ApiResponse(responseCode = "404", description = "Exception not found")
    public ResponseEntity<Object> deleteException(@PathVariable Long id, Principal principal) {
        try {
            availabilityService.deleteException(id, currentEmployee(principal));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
    }

    private Employee resolveProvider(Long providerId) {
        try {
            return employeeService.getEmployeeById(providerId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Employee currentEmployee(Principal principal) {
        if (principal == null) {
            return null;
        }
        return employeeService.getEmployeeByEmail(principal.getName());
    }
}
