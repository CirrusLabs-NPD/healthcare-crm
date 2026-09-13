package com.medicare.healthcarecrm.web;

import com.medicare.healthcarecrm.model.Customer;
import com.medicare.healthcarecrm.model.Employee;
import com.medicare.healthcarecrm.model.Insurance;
import com.medicare.healthcarecrm.model.Tasks;
import com.medicare.healthcarecrm.repository.CustomerRepository;
import com.medicare.healthcarecrm.repository.EmployeeRepository;
import com.medicare.healthcarecrm.repository.TasksRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-13 — the four read sites that show a task's time render with no unresolved
 * {@code dueDate} reference, and the app serves them against the migrated schema.
 *
 * <p>The read sites (S-1 §5 / S-6 traceability doc) are:
 * <ul>
 *   <li>{@code admin/tasks.html} — one {@code task.dueDate} cell in the task table</li>
 *   <li>{@code admin/followup.html} — two cells: the overdue table and the due-soon table</li>
 *   <li>{@code employee.html} — the assigned-task table</li>
 * </ul>
 * Every cell formats {@code task.dueDate} with Thymeleaf's {@code #temporals.format}.
 * If the S-4/S-5 scheduling work had removed or renamed {@code Tasks.dueDate}, that
 * expression would raise a {@code TemplateProcessingException} and the view would
 * return HTTP 500; asserting 200 plus the presence of the formatted timestamp proves
 * the reference still resolves.
 *
 * <p>Security filters are disabled — this test asserts rendering, not authorisation.
 * {@code EmployeeController} reads the login name straight from the {@link SecurityContextHolder},
 * so a token for the seeded employee is placed in the context for the {@code /employee} render.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Transactional
class ReadSiteTemplatesRenderTest {

    private static final DateTimeFormatter DUE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private TasksRepository tasksRepository;

    private String overdueDueDateText;
    private String dueSoonDueDateText;
    private String employeeEmail;

    @BeforeEach
    void setUp() {
        tasksRepository.deleteAll();

        Employee employee = employeeRepository.save(Employee.builder()
                .name("Nurse Read").role("Nurse")
                .email("ac13.employee@test.local").password("x").build());
        employeeEmail = employee.getEmail();

        Insurance insurance = Insurance.builder()
                .provider("BlueCross").policyNumber("AB1234567")
                .coverageDetails("Basic").expiryDate(LocalDateTime.now().plusMonths(12))
                .build();
        Customer customer = customerRepository.save(Customer.builder()
                .name("Pat Read").age(40).gender("Other")
                .email("ac13.customer@test.local").medicalHistory("None")
                .contactDetails("(555) 010-1300").insurance(insurance)
                .build());

        // One overdue task (feeds admin/tasks, followup overdue table, employee list)
        // and one due-soon task (feeds the followup due-soon table). Both non-Completed
        // so the overdue/due-soon queries return them.
        LocalDateTime overdue = LocalDateTime.now().minusDays(2);
        LocalDateTime dueSoon = LocalDateTime.now().plusDays(1);
        overdueDueDateText = overdue.format(DUE_DATE_FORMAT);
        dueSoonDueDateText = dueSoon.format(DUE_DATE_FORMAT);

        tasksRepository.save(task("Overdue task", customer, employee, overdue));
        tasksRepository.save(task("Due-soon task", customer, employee, dueSoon));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminTasksRendersDueDate() throws Exception {
        authenticateAs("admin@clinic.com", "ROLE_ADMIN");
        mockMvc.perform(get("/admin/tasks"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(overdueDueDateText)));
    }

    @Test
    void adminFollowUpRendersOverdueAndDueSoonDueDates() throws Exception {
        authenticateAs("admin@clinic.com", "ROLE_ADMIN");
        String html = mockMvc.perform(get("/admin/follow-up"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html)
                .contains(overdueDueDateText)   // overdue table cell
                .contains(dueSoonDueDateText);  // due-soon table cell
    }

    @Test
    void employeeTasksRendersDueDate() throws Exception {
        authenticateAs(employeeEmail, "ROLE_EMPLOYEE");
        mockMvc.perform(get("/employee"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(overdueDueDateText)));
    }

    private void authenticateAs(String email, String role) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                email, "n/a", List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Tasks task(String name, Customer customer, Employee employee, LocalDateTime dueDate) {
        return Tasks.builder()
                .taskName(name).customer(customer).employee(employee)
                .dueDate(dueDate).priority("Medium").description("AC-13 render")
                .status("Pending").build();
    }
}
