package com.medicare.healthcarecrm.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-20 — the generated OpenAPI document ({@code /v3/api-docs}, which backs the
 * Swagger UI) lists the scheduling endpoints and the appointment time fields.
 *
 * <p>springdoc builds the document at runtime from the annotated MVC controllers,
 * so this asserts the surface an integrator sees: the appointment CRUD + calendar
 * paths, the availability rule/exception paths, and that the {@code Appointment}
 * schema carries {@code startTime} and {@code endTime}. {@code /v3/api-docs} is
 * public per {@code SecurityConfig}, so no authentication is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiDocsSchedulingSurfaceTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Test
    void apiDocsDocumentsSchedulingEndpointsAndAppointmentTimeFields() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode doc = objectMapper.readTree(body);
        JsonNode paths = doc.get("paths");
        assertThat(paths).as("OpenAPI document must expose paths").isNotNull();

        // Appointment endpoints (day/week calendar views, booking, edit).
        assertThat(paths.has("/api/appointments")).as("POST/GET appointments path").isTrue();
        assertThat(paths.get("/api/appointments").has("post")).as("book operation").isTrue();
        assertThat(paths.get("/api/appointments").has("get")).as("list operation").isTrue();
        assertThat(paths.has("/api/appointments/{id}")).as("appointment-by-id path").isTrue();
        assertThat(paths.has("/api/appointments/calendar")).as("calendar range path").isTrue();

        // Availability endpoints (working hours + time-off exceptions).
        assertThat(paths.has("/api/availability/rules")).as("availability rules path").isTrue();
        assertThat(paths.has("/api/availability/exceptions")).as("availability exceptions path").isTrue();

        // The appointment time span is documented on the Appointment schema.
        JsonNode appointmentProps = doc.path("components").path("schemas")
                .path("Appointment").path("properties");
        assertThat(appointmentProps.isMissingNode())
                .as("Appointment schema must be present in components/schemas").isFalse();
        assertThat(appointmentProps.has("startTime")).as("startTime field documented").isTrue();
        assertThat(appointmentProps.has("endTime")).as("endTime field documented").isTrue();
    }
}
