package com.medicare.healthcarecrm.controller.api;

import com.medicare.healthcarecrm.model.Appointment;
import com.medicare.healthcarecrm.model.AppointmentSeries;
import com.medicare.healthcarecrm.model.Customer;
import com.medicare.healthcarecrm.model.Employee;
import com.medicare.healthcarecrm.service.AppointmentService;
import com.medicare.healthcarecrm.service.CustomerService;
import com.medicare.healthcarecrm.service.EmployeeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * REST API for recurring appointment series. Creating a series materialises its
 * occurrences into concrete appointments; conflicting occurrences are flagged
 * (status "Conflict"), never silently dropped.
 */
@RestController
@RequestMapping("/api/appointment-series")
@Tag(name = "Appointment Series API", description = "API endpoints for recurring appointment series")
public class AppointmentSeriesApiController {

    private static final Logger log = LoggerFactory.getLogger(AppointmentSeriesApiController.class);

    private final AppointmentService appointmentService;
    private final CustomerService customerService;
    private final EmployeeService employeeService;

    public AppointmentSeriesApiController(AppointmentService appointmentService,
                                          CustomerService customerService,
                                          EmployeeService employeeService) {
        this.appointmentService = appointmentService;
        this.customerService = customerService;
        this.employeeService = employeeService;
    }

    @PostMapping
    @Operation(summary = "Create a recurring series",
            description = "Persists the series and generates its occurrences as appointments. "
                    + "Occurrences that clash with availability or existing appointments are created "
                    + "with status 'Conflict' rather than dropped.")
    @ApiResponse(responseCode = "201", description = "Series created; generated occurrences returned")
    @ApiResponse(responseCode = "400", description = "Invalid data")
    public ResponseEntity<Object> create(@Valid @RequestBody AppointmentSeries series, Principal principal) {
        series.setId(null);
        String resolveError = resolveRelations(series);
        if (resolveError != null) {
            return ResponseEntity.badRequest().body(resolveError);
        }
        try {
            List<Appointment> generated = appointmentService.createSeries(series, currentEmployee(principal));
            return ResponseEntity.status(HttpStatus.CREATED).body(generated);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel a whole series", description = "Deletes the series and all its occurrences.")
    @ApiResponse(responseCode = "204", description = "Series and occurrences deleted")
    @ApiResponse(responseCode = "404", description = "Series not found")
    public ResponseEntity<Object> delete(@PathVariable Long id, Principal principal) {
        try {
            appointmentService.deleteSeries(id, currentEmployee(principal));
            return ResponseEntity.noContent().build();
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
    }

    private Employee currentEmployee(Principal principal) {
        if (principal == null) {
            return null;
        }
        return employeeService.getEmployeeByEmail(principal.getName());
    }

    private String resolveRelations(AppointmentSeries series) {
        if (series.getCustomer() == null || series.getCustomer().getId() == null) {
            return "Customer ID is required.";
        }
        if (series.getProvider() == null || series.getProvider().getId() == null) {
            return "Provider ID is required.";
        }
        Customer customer = customerService.getCustomerById(series.getCustomer().getId());
        if (customer == null) {
            return "Customer with ID " + series.getCustomer().getId() + " not found.";
        }
        series.setCustomer(customer);
        try {
            Employee provider = employeeService.getEmployeeById(series.getProvider().getId());
            series.setProvider(provider);
        } catch (RuntimeException e) {
            return "Provider with ID " + series.getProvider().getId() + " not found.";
        }
        return null;
    }
}
