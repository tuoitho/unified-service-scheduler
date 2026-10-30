package com.keyloop.scheduler.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyloop.scheduler.api.dto.AppointmentResponse;
import com.keyloop.scheduler.api.dto.AvailabilityCheckResponse;
import com.keyloop.scheduler.api.dto.BookAppointmentRequest;
import com.keyloop.scheduler.api.dto.DailyAvailabilityResponse;
import com.keyloop.scheduler.domain.AppointmentStatus;
import com.keyloop.scheduler.support.AbstractPostgresIntegrationTest;
import com.keyloop.scheduler.support.TestFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

class AppointmentApiIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullHttpLifecycleBooksQueriesCancelsAndPropagatesCorrelationId() {
        UUID dealershipId = TestFixtures.insertDealership(
                jdbcClient, "API-DL-" + UUID.randomUUID().toString().substring(0, 8),
                "Keyloop Bristol", "UTC", LocalTime.of(8, 0), LocalTime.of(18, 0));
        TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-01", "Bay 1", true);
        TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "API-TECH-" + UUID.randomUUID().toString().substring(0, 8),
                "Marcus Wright", true, List.of("BRAKE_SPEC"));
        UUID serviceTypeId = TestFixtures.insertServiceType(
                jdbcClient, "API-BRK-" + UUID.randomUUID().toString().substring(0, 8),
                "Brake Overhaul", 90, "BRAKE_SPEC");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-ID", "corr-test-12345");

        BookAppointmentRequest bookReq = new BookAppointmentRequest(
                dealershipId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                serviceTypeId,
                OffsetDateTime.parse("2026-12-20T10:00:00Z"),
                "Customer requested OEM pads");

        ResponseEntity<AppointmentResponse> createRes = restTemplate.exchange(
                "/api/v1/appointments",
                HttpMethod.POST,
                new HttpEntity<>(bookReq, headers),
                AppointmentResponse.class);

        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createRes.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("corr-test-12345");
        assertThat(createRes.getHeaders().getLocation()).isNotNull();
        assertThat(createRes.getBody()).isNotNull();

        UUID appointmentId = createRes.getBody().id();
        assertThat(createRes.getBody().status()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(createRes.getBody().endTime()).isEqualTo(OffsetDateTime.parse("2026-12-20T11:30:00Z"));

        // Second overlapping booking on sole bay -> 409 Conflict ProblemDetail with correlationId & errorCode
        BookAppointmentRequest conflictReq = new BookAppointmentRequest(
                dealershipId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                serviceTypeId,
                OffsetDateTime.parse("2026-12-20T10:30:00Z"),
                null);

        ResponseEntity<ProblemDetail> conflictRes = restTemplate.exchange(
                "/api/v1/appointments",
                HttpMethod.POST,
                new HttpEntity<>(conflictReq, headers),
                ProblemDetail.class);

        assertThat(conflictRes.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflictRes.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("corr-test-12345");
        assertThat(conflictRes.getBody()).isNotNull();
        assertThat(conflictRes.getBody().getProperties())
                .containsEntry("errorCode", "NO_SERVICE_BAY_AVAILABLE")
                .containsEntry("correlationId", "corr-test-12345");

        // Availability check reflects busy window
        ResponseEntity<AvailabilityCheckResponse> checkRes = restTemplate.getForEntity(
                "/api/v1/availability/check?dealershipId={d}&serviceTypeId={s}&startTime={t}",
                AvailabilityCheckResponse.class,
                dealershipId, serviceTypeId, "2026-12-20T10:00:00Z");
        assertThat(checkRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(checkRes.getBody()).isNotNull();
        assertThat(checkRes.getBody().available()).isFalse();

        // Daily availability endpoint
        ResponseEntity<DailyAvailabilityResponse> dailyRes = restTemplate.getForEntity(
                "/api/v1/availability?dealershipId={d}&serviceTypeId={s}&date=2026-12-20&slotIntervalMinutes=60",
                DailyAvailabilityResponse.class,
                dealershipId, serviceTypeId);
        assertThat(dailyRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dailyRes.getBody()).isNotNull();
        assertThat(dailyRes.getBody().slots()).isNotEmpty();

        // Cancel appointment via POST /api/v1/appointments/{id}/cancel
        ResponseEntity<AppointmentResponse> cancelRes = restTemplate.postForEntity(
                "/api/v1/appointments/{id}/cancel",
                null,
                AppointmentResponse.class,
                appointmentId);
        assertThat(cancelRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelRes.getBody()).isNotNull();
        assertThat(cancelRes.getBody().status()).isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    void returnsStandardizedProblemDetailsFor400Validation422OperatingHoursAnd404NotFound() {
        // 400 Bad Request for missing required fields
        ResponseEntity<ProblemDetail> invalidRes = restTemplate.postForEntity(
                "/api/v1/appointments",
                Map.of("notes", "missing required ids"),
                ProblemDetail.class);
        assertThat(invalidRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalidRes.getHeaders().getFirst("X-Correlation-ID")).isNotBlank();
        assertThat(invalidRes.getBody()).isNotNull();
        assertThat(invalidRes.getBody().getProperties()).containsEntry("errorCode", "VALIDATION_FAILED");

        // 404 Not Found for unknown appointment
        ResponseEntity<ProblemDetail> notFoundRes = restTemplate.getForEntity(
                "/api/v1/appointments/{id}",
                ProblemDetail.class,
                UUID.randomUUID());
        assertThat(notFoundRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFoundRes.getBody()).isNotNull();
        assertThat(notFoundRes.getBody().getProperties()).containsEntry("errorCode", "APPOINTMENT_NOT_FOUND");

        // 422 Unprocessable Entity for outside operating hours
        UUID dealershipId = TestFixtures.insertDealership(
                jdbcClient, "API-422-" + UUID.randomUUID().toString().substring(0, 8),
                "Keyloop Leeds", "UTC", LocalTime.of(8, 0), LocalTime.of(18, 0));
        UUID serviceTypeId = TestFixtures.insertServiceType(
                jdbcClient, "API-SVC-" + UUID.randomUUID().toString().substring(0, 8),
                "Oil Change", 60, "GENERAL");

        BookAppointmentRequest afterHoursReq = new BookAppointmentRequest(
                dealershipId, UUID.randomUUID(), UUID.randomUUID(), serviceTypeId,
                OffsetDateTime.parse("2026-12-21T20:00:00Z"), null);

        ResponseEntity<ProblemDetail> unprocessableRes = restTemplate.postForEntity(
                "/api/v1/appointments", afterHoursReq, ProblemDetail.class);
        assertThat(unprocessableRes.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(unprocessableRes.getBody()).isNotNull();
        assertThat(unprocessableRes.getBody().getProperties()).containsEntry("errorCode", "OUTSIDE_OPERATING_HOURS");
    }

    @Test
    void exposesActuatorHealthPrometheusMetricsAndOpenApiSpecification() throws Exception {
        ResponseEntity<String> health = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");

        ResponseEntity<String> prometheus = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getBody()).contains("jvm_threads_");

        ResponseEntity<String> openApi = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(openApi.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(openApi.getBody()).contains("Unified Service Scheduler API");
        assertThat(openApi.getBody()).contains("/api/v1/appointments");
        assertThat(openApi.getBody()).contains("application/problem+json");
        assertThat(openApi.getBody()).contains("ProblemDetail");

        Path docsDir = Path.of("docs");
        if (Files.isDirectory(docsDir)) {
            Object parsed = objectMapper.readValue(openApi.getBody(), Object.class);
            String formatted = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(parsed)
                    .replace("\r\n", "\n") + "\n";
            Files.writeString(docsDir.resolve("openapi.json"), formatted);
        }
    }
}
