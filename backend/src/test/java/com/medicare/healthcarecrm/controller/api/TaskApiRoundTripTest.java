package com.medicare.healthcarecrm.controller.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicare.healthcarecrm.model.Customer;
import com.medicare.healthcarecrm.model.Employee;
import com.medicare.healthcarecrm.model.Insurance;
import com.medicare.healthcarecrm.repository.CustomerRepository;
import com.medicare.healthcarecrm.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-2 (literal wording) — REST create + read round-trip via {@code /api/tasks}.
 *
 * <p>The AC text (S-1 §5, mirrored in the S-6 traceability doc) is worded against
 * {@code POST /api/tasks} → 201 and {@code GET /api/tasks/{id}} round-tripping the
 * persisted time. On the {@code Tasks} entity the timed field is {@code dueDate}
 * (the scheduling feature moved the true start/end span onto {@code Appointment},
 * whose round-trip is asserted by {@link AppointmentApiRoundTripTest}). To close
 * AC-2 honestly this test covers the literal endpoint the criterion names — per the
 * QA decision recorded on S-9 to assert <b>both</b> surfaces — proving the task
 * time survives the create + read round-trip unchanged.
 *
 * <p>Security filters are disabled ({@link AutoConfigureMockMvc#addFilters()} = false):
 * AC-2 is about the 201 + persisted-time round-trip, not authorisation.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Transactional
class TaskApiRoundTripTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private EmployeeRepository employeeRepository;

    /** yyyy-MM-dd'T'HH:mm — the pattern Tasks.dueDate binds with, no seconds/fraction. */
    private static final LocalDateTime DUE = LocalDateTime.of(2026, 1, 5, 10, 0);

    private Long customerId;
    private Long employeeId;

    @BeforeEach
    void setUp() {
        Employee employee = employeeRepository.save(Employee.builder()
                .name("Nurse Round").role("Nurse")
                .email("ac2.task.employee@test.local").password("x").build());
        employeeId = employee.getId();

        Insurance insurance = Insurance.builder()
                .provider("BlueCross").policyNumber("AB1234567")
                .coverageDetails("Basic").expiryDate(LocalDateTime.now().plusMonths(12))
                .build();
        Customer customer = customerRepository.save(Customer.builder()
                .name("Pat Round").age(30).gender("Other")
                .email("ac2.task.customer@test.local").medicalHistory("None")
                .contactDetails("(555) 010-2100").insurance(insurance)
                .build());
        customerId = customer.getId();
    }

    @Test
    void postValidTaskReturns201AndGetRoundTripsDueDate() throws Exception {
        // POST /api/tasks -> 201 with the persisted dueDate echoed back.
        MvcResult created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();

        JsonNode createdNode = objectMapper.readTree(created.getResponse().getContentAsString());
        long id = createdNode.get("id").asLong();
        assertThat(id).isPositive();
        // Compare semantically: parse the wire value back to a LocalDateTime so the
        // assertion holds whatever seconds/fraction form Jackson emits.
        assertThat(LocalDateTime.parse(createdNode.get("dueDate").asText())).isEqualTo(DUE);

        // GET /api/tasks/{id} -> the same dueDate comes back unchanged.
        MvcResult fetched = mockMvc.perform(get("/api/tasks/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andReturn();

        JsonNode fetchedNode = objectMapper.readTree(fetched.getResponse().getContentAsString());
        assertThat(LocalDateTime.parse(fetchedNode.get("dueDate").asText())).isEqualTo(DUE);
    }

    /**
     * Mirrors what the task form posts: customer and employee are referenced by id
     * on nested objects, which {@code TaskApiController.resolveTaskRelations}
     * hydrates before saving.
     */
    private String taskJson() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("taskName", "Round-trip task");
        root.put("dueDate", DUE.toString());
        root.put("priority", "Medium");
        root.put("description", "AC-2 round-trip");
        root.put("status", "Pending");
        root.putObject("customer").put("id", customerId);
        root.putObject("employee").put("id", employeeId);
        return root.toString();
    }
}
