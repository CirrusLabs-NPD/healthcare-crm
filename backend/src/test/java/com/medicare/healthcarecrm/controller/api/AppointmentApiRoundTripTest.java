package com.medicare.healthcarecrm.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicare.healthcarecrm.model.AvailabilityRule;
import com.medicare.healthcarecrm.model.Customer;
import com.medicare.healthcarecrm.model.Employee;
import com.medicare.healthcarecrm.model.Insurance;
import com.medicare.healthcarecrm.repository.AppointmentRepository;
import com.medicare.healthcarecrm.repository.CustomerRepository;
import com.medicare.healthcarecrm.repository.EmployeeRepository;
import com.medicare.healthcarecrm.service.AvailabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-2 — REST create + read round-trip of the appointment time span.
 *
 * <p>The AC text (S-1 §5, mirrored in the S-6 traceability doc) is worded against
 * {@code POST /api/tasks}, written before the scheduling feature split the timed
 * slot out of the flat {@code Tasks.dueDate}. The delivered endpoint that persists
 * a real {@code startTime}/{@code endTime} span is {@code AppointmentApiController}
 * (<code>/api/appointments</code>) — see design-docs/stories/S-9-e2e-harness-design.md.
 * This test asserts the round-trip against that endpoint.
 *
 * <p>Security filters are disabled ({@link AutoConfigureMockMvc#addFilters()} = false):
 * AC-2 is about the 201 + persisted-time round-trip, not authorisation, which is
 * covered by the security rules themselves. The service's booking guard still runs,
 * so a valid in-availability span is required.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Transactional
class AppointmentApiRoundTripTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private AvailabilityService availabilityService;
    @Autowired private AppointmentRepository appointmentRepository;

    /** Monday — the day the seeded availability rule covers, so the span is bookable. */
    private static final LocalDate MON = LocalDate.of(2026, 1, 5);
    private static final LocalDateTime START = LocalDateTime.of(MON, LocalTime.of(10, 0));
    private static final LocalDateTime END = LocalDateTime.of(MON, LocalTime.of(10, 30));

    private Long customerId;
    private Long providerId;

    @BeforeEach
    void setUp() {
        appointmentRepository.deleteAll();

        Employee provider = employeeRepository.save(Employee.builder()
                .name("Dr Round").role("Doctor")
                .email("ac2.provider@test.local").password("x").build());
        providerId = provider.getId();

        Insurance insurance = Insurance.builder()
                .provider("BlueCross").policyNumber("AB1234567")
                .coverageDetails("Basic").expiryDate(LocalDateTime.now().plusMonths(12))
                .build();
        Customer customer = customerRepository.save(Customer.builder()
                .name("Pat Round").age(30).gender("Other")
                .email("ac2.customer@test.local").medicalHistory("None")
                .contactDetails("(555) 010-2000").insurance(insurance)
                .build());
        customerId = customer.getId();

        availabilityService.addRule(AvailabilityRule.builder()
                .provider(provider).dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).build(), null);
    }

    @Test
    void postValidAppointmentReturns201AndGetRoundTripsStartAndEnd() throws Exception {
        String startIso = START.toString();
        String endIso = END.toString();

        // POST /api/appointments -> 201 with the persisted start/end echoed back.
        MvcResult created = mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.startTime").value(startIso))
                .andExpect(jsonPath("$.endTime").value(endIso))
                .andReturn();

        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        assertThat(appointmentRepository.findById(id)).isPresent();

        // GET /api/appointments/{id} -> the same start/end come back unchanged.
        mockMvc.perform(get("/api/appointments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.startTime").value(startIso))
                .andExpect(jsonPath("$.endTime").value(endIso));
    }

    /**
     * Mirrors what the S-5 UI posts: the customer and provider are referenced by id
     * on nested objects, which {@code AppointmentApiController.resolveRelations}
     * hydrates before booking.
     */
    private String bookingJson() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("title", "Round-trip visit");
        root.put("type", "APPOINTMENT");
        root.put("startTime", START.toString());
        root.put("endTime", END.toString());
        root.put("priority", "Medium");
        root.put("description", "AC-2 round-trip");
        root.put("status", "Pending");
        root.putObject("customer").put("id", customerId);
        root.putObject("provider").put("id", providerId);
        return root.toString();
    }
}
