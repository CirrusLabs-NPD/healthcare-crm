package com.medicare.healthcarecrm.controller;

import com.medicare.healthcarecrm.model.Employee;
import com.medicare.healthcarecrm.service.CustomerService;
import com.medicare.healthcarecrm.service.EmployeeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

/**
 * Serves the server-rendered scheduling screens (calendar day/week, provider
 * availability, recurrence editor). The page is a thin shell: it renders the
 * chrome, the pickers and the empty grid, then reads and writes the data
 * through the existing {@code /api/appointments}, {@code /api/availability} and
 * {@code /api/appointment-series} endpoints. Keeping the data on the JSON API
 * that {@code S-4} already exposes means this change adds no second read/write
 * path — the booking guard stays in one place (the service), never the browser.
 */
@RequestMapping("/admin")
@Controller
public class SchedulingController {

    private static final Logger log = LoggerFactory.getLogger(SchedulingController.class);

    private final EmployeeService employeeService;
    private final CustomerService customerService;

    public SchedulingController(EmployeeService employeeService, CustomerService customerService) {
        this.employeeService = employeeService;
        this.customerService = customerService;
    }

    @GetMapping("/calendar")
    public String calendar(Model model) {
        model.addAttribute("activePage", "calendar");
        try {
            // Only bookable employees can be a calendar provider (S-2). The list is
            // rendered into the provider picker; the grid data itself is fetched by id.
            List<Employee> providers = employeeService.getAllEmployees().stream()
                    .filter(Employee::isBookableProvider)
                    .toList();
            model.addAttribute("providers", providers);
            model.addAttribute("customers", customerService.getAllCustomers());
        } catch (Exception e) {
            log.error("Error loading scheduling page data: {}", e.getMessage(), e);
            model.addAttribute("schedulingError", "Could not load providers or customers.");
        }
        return "admin/calendar";
    }
}
