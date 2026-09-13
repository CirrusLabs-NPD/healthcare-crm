package com.medicare.healthcarecrm.controller.api;

import com.medicare.healthcarecrm.model.Appointment;
import com.medicare.healthcarecrm.model.Customer;
import com.medicare.healthcarecrm.model.Employee;
import com.medicare.healthcarecrm.service.AppointmentService;
import com.medicare.healthcarecrm.service.BookingConflictException;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * REST API for appointments. Mirrors {@link TaskApiController}'s shape: thin
 * controller, customer/provider resolved from their ids on the request body,
 * the service holds the logic. The booking guard is enforced in the service and
 * surfaces here as HTTP 409 Conflict.
 */
@RestController
@RequestMapping("/api/appointments")
@Tag(name = "Appointment API", description = "API endpoints for scheduling appointments")
public class AppointmentApiController {

    private static final Logger log = LoggerFactory.getLogger(AppointmentApiController.class);

    private final AppointmentService appointmentService;
    private final CustomerService customerService;
    private final EmployeeService employeeService;

    public AppointmentApiController(AppointmentService appointmentService,
                                    CustomerService customerService,
                                    EmployeeService employeeService) {
        this.appointmentService = appointmentService;
        this.customerService = customerService;
        this.employeeService = employeeService;
    }

    @GetMapping
    @Operation(summary = "Get all appointments")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved list")
    public List<Appointment> getAll() {
        return appointmentService.getAllAppointments();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get appointment by ID")
    @ApiResponse(responseCode = "200", description = "Appointment found",
            content = @Content(schema = @Schema(implementation = Appointment.class)))
    @ApiResponse(responseCode = "404", description = "Appointment not found")
    public ResponseEntity<Appointment> getById(@PathVariable Long id) {
        Appointment appointment = appointmentService.getAppointmentById(id);
        if (appointment == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(appointment);
    }

    @GetMapping("/calendar")
    @Operation(summary = "Provider calendar for a date range",
            description = "Appointments for one provider between from (inclusive) and to (exclusive). "
                    + "Backs the day and week calendar views.")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved calendar")
    @ApiResponse(responseCode = "404", description = "Provider not found")
    public ResponseEntity<Object> calendar(
            @RequestParam Long providerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        Employee provider;
        try {
            provider = employeeService.getEmployeeById(providerId);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Provider not found: " + providerId);
        }
        return ResponseEntity.ok(appointmentService.getCalendar(provider, from, to));
    }

    @PostMapping
    @Operation(summary = "Book an appointment",
            description = "Creates an appointment. Requires customer and provider ids in the nested objects. "
                    + "Refused with 409 if outside availability or overlapping an existing appointment.")
    @ApiResponse(responseCode = "201", description = "Appointment booked",
            content = @Content(schema = @Schema(implementation = Appointment.class)))
    @ApiResponse(responseCode = "400", description = "Invalid data")
    @ApiResponse(responseCode = "409", description = "Booking conflict (availability or overlap)")
    public ResponseEntity<Object> book(@Valid @RequestBody Appointment appointment, Principal principal) {
        appointment.setId(null);
        String resolveError = resolveRelations(appointment);
        if (resolveError != null) {
            return ResponseEntity.badRequest().body(resolveError);
        }
        try {
            Appointment saved = appointmentService.book(appointment, currentEmployee(principal));
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (BookingConflictException e) {
            log.info("Booking refused: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @Operation(summary = "Reschedule / update an appointment")
    @ApiResponse(responseCode = "200", description = "Appointment updated",
            content = @Content(schema = @Schema(implementation = Appointment.class)))
    @ApiResponse(responseCode = "400", description = "Invalid data")
    @ApiResponse(responseCode = "404", description = "Appointment not found")
    @ApiResponse(responseCode = "409", description = "Booking conflict (availability or overlap)")
    public ResponseEntity<Object> reschedule(@PathVariable Long id,
                                             @Valid @RequestBody Appointment appointment,
                                             Principal principal) {
        String resolveError = resolveRelations(appointment);
        if (resolveError != null) {
            return ResponseEntity.badRequest().body(resolveError);
        }
        try {
            Appointment saved = appointmentService.reschedule(id, appointment, currentEmployee(principal));
            return ResponseEntity.ok(saved);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (BookingConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel an appointment")
    @ApiResponse(responseCode = "204", description = "Appointment deleted")
    @ApiResponse(responseCode = "404", description = "Appointment not found")
    public ResponseEntity<Object> delete(@PathVariable Long id, Principal principal) {
        try {
            appointmentService.delete(id, currentEmployee(principal));
            return ResponseEntity.noContent().build();
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
    }

    // === Helpers ===

    /** Resolve the acting employee from the login principal; null for an admin,
     *  which the service treats as an unrestricted context. */
    private Employee currentEmployee(Principal principal) {
        if (principal == null) {
            return null;
        }
        return employeeService.getEmployeeByEmail(principal.getName());
    }

    private String resolveRelations(Appointment appointment) {
        if (appointment.getCustomer() == null || appointment.getCustomer().getId() == null) {
            return "Customer ID is required.";
        }
        if (appointment.getProvider() == null || appointment.getProvider().getId() == null) {
            return "Provider ID is required.";
        }
        Customer customer = customerService.getCustomerById(appointment.getCustomer().getId());
        if (customer == null) {
            return "Customer with ID " + appointment.getCustomer().getId() + " not found.";
        }
        appointment.setCustomer(customer);
        try {
            Employee provider = employeeService.getEmployeeById(appointment.getProvider().getId());
            appointment.setProvider(provider);
        } catch (RuntimeException e) {
            return "Provider with ID " + appointment.getProvider().getId() + " not found.";
        }
        return null;
    }
}
