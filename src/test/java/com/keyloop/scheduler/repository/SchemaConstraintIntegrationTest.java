package com.keyloop.scheduler.repository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keyloop.scheduler.support.AbstractPostgresIntegrationTest;
import com.keyloop.scheduler.support.TestFixtures;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class SchemaConstraintIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void bayExclusionConstraintRejectsOverlappingConfirmedAppointmentsButAllowsBackToBackAndCancelled() {
        UUID dealershipId = TestFixtures.insertDealership(
                jdbcClient, "DL-" + UUID.randomUUID(), "Keyloop Central", "UTC",
                LocalTime.of(8, 0), LocalTime.of(18, 0));
        UUID serviceTypeId = TestFixtures.insertServiceType(
                jdbcClient, "SVC-" + UUID.randomUUID(), "Oil Change", 60, "GENERAL");
        UUID bay1 = TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-01", "Bay 1", true);
        UUID tech1 = TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "EMP-" + UUID.randomUUID(), "Alice", true, List.of("GENERAL"));
        UUID tech2 = TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "EMP-" + UUID.randomUUID(), "Bob", true, List.of("GENERAL"));

        UUID firstAppointmentId = insertRawAppointment(
                dealershipId, UUID.randomUUID(), UUID.randomUUID(), serviceTypeId,
                bay1, tech1,
                OffsetDateTime.parse("2026-12-01T09:00:00Z"),
                OffsetDateTime.parse("2026-12-01T10:00:00Z"),
                "CONFIRMED");

        // Overlapping [09:30, 10:30) on the same bay1 (even with different tech2 & vehicle) must fail
        assertThatThrownBy(() -> insertRawAppointment(
                dealershipId, UUID.randomUUID(), UUID.randomUUID(), serviceTypeId,
                bay1, tech2,
                OffsetDateTime.parse("2026-12-01T09:30:00Z"),
                OffsetDateTime.parse("2026-12-01T10:30:00Z"),
                "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Back-to-back [10:00, 11:00) on the same bay1 and tech1 must succeed due to half-open '[)' range
        assertThatCode(() -> insertRawAppointment(
                dealershipId, UUID.randomUUID(), UUID.randomUUID(), serviceTypeId,
                bay1, tech1,
                OffsetDateTime.parse("2026-12-01T10:00:00Z"),
                OffsetDateTime.parse("2026-12-01T11:00:00Z"),
                "CONFIRMED"))
                .doesNotThrowAnyException();

        // Cancelling the [09:00, 10:00) appointment releases bay1 & tech1 for that window
        jdbcClient.sql("UPDATE appointments SET status = 'CANCELLED', cancelled_at = NOW() WHERE id = :id")
                .param("id", firstAppointmentId)
                .update();

        assertThatCode(() -> insertRawAppointment(
                dealershipId, UUID.randomUUID(), UUID.randomUUID(), serviceTypeId,
                bay1, tech1,
                OffsetDateTime.parse("2026-12-01T09:00:00Z"),
                OffsetDateTime.parse("2026-12-01T10:00:00Z"),
                "CONFIRMED"))
                .doesNotThrowAnyException();
    }

    @Test
    void technicianAndVehicleExclusionConstraintsRejectOverlapsAndCrossDealershipForeignKeyRejectsMismatch() {
        UUID dealershipA = TestFixtures.insertDealership(
                jdbcClient, "DLA-" + UUID.randomUUID(), "Dealership A", "UTC",
                LocalTime.of(8, 0), LocalTime.of(18, 0));
        UUID dealershipB = TestFixtures.insertDealership(
                jdbcClient, "DLB-" + UUID.randomUUID(), "Dealership B", "UTC",
                LocalTime.of(8, 0), LocalTime.of(18, 0));
        UUID serviceTypeId = TestFixtures.insertServiceType(
                jdbcClient, "SVC-" + UUID.randomUUID(), "Brake Check", 60, "BRAKES");

        UUID bayA1 = TestFixtures.insertServiceBay(jdbcClient, dealershipA, "BAY-01", "Bay A1", true);
        UUID bayA2 = TestFixtures.insertServiceBay(jdbcClient, dealershipA, "BAY-02", "Bay A2", true);
        UUID bayB1 = TestFixtures.insertServiceBay(jdbcClient, dealershipB, "BAY-01", "Bay B1", true);

        UUID techA1 = TestFixtures.insertTechnician(
                jdbcClient, dealershipA, "EMP-" + UUID.randomUUID(), "Tech A1", true, List.of("BRAKES"));
        UUID techA2 = TestFixtures.insertTechnician(
                jdbcClient, dealershipA, "EMP-" + UUID.randomUUID(), "Tech A2", true, List.of("BRAKES"));

        UUID sharedVehicleId = UUID.randomUUID();

        insertRawAppointment(
                dealershipA, UUID.randomUUID(), sharedVehicleId, serviceTypeId,
                bayA1, techA1,
                OffsetDateTime.parse("2026-12-02T13:00:00Z"),
                OffsetDateTime.parse("2026-12-02T14:00:00Z"),
                "CONFIRMED");

        // Same technician (techA1) on a different bay (bayA2) during overlapping window must fail
        assertThatThrownBy(() -> insertRawAppointment(
                dealershipA, UUID.randomUUID(), UUID.randomUUID(), serviceTypeId,
                bayA2, techA1,
                OffsetDateTime.parse("2026-12-02T13:15:00Z"),
                OffsetDateTime.parse("2026-12-02T14:15:00Z"),
                "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Same vehicle (sharedVehicleId) on different bay (bayA2) and different tech (techA2) must fail
        assertThatThrownBy(() -> insertRawAppointment(
                dealershipA, UUID.randomUUID(), sharedVehicleId, serviceTypeId,
                bayA2, techA2,
                OffsetDateTime.parse("2026-12-02T13:00:00Z"),
                OffsetDateTime.parse("2026-12-02T14:00:00Z"),
                "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Cross-dealership assignment (dealershipA with bayB1 from dealershipB) must fail composite FK
        assertThatThrownBy(() -> insertRawAppointment(
                dealershipA, UUID.randomUUID(), UUID.randomUUID(), serviceTypeId,
                bayB1, techA2,
                OffsetDateTime.parse("2026-12-02T15:00:00Z"),
                OffsetDateTime.parse("2026-12-02T16:00:00Z"),
                "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertRawAppointment(
            UUID dealershipId,
            UUID customerId,
            UUID vehicleId,
            UUID serviceTypeId,
            UUID serviceBayId,
            UUID technicianId,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            String status
    ) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO appointments (
                    id, dealership_id, customer_id, vehicle_id, service_type_id,
                    service_bay_id, technician_id, start_time, end_time, status
                ) VALUES (
                    :id, :dealershipId, :customerId, :vehicleId, :serviceTypeId,
                    :serviceBayId, :technicianId, :startTime, :endTime, :status
                )
                """)
                .param("id", id)
                .param("dealershipId", dealershipId)
                .param("customerId", customerId)
                .param("vehicleId", vehicleId)
                .param("serviceTypeId", serviceTypeId)
                .param("serviceBayId", serviceBayId)
                .param("technicianId", technicianId)
                .param("startTime", startTime)
                .param("endTime", endTime)
                .param("status", status)
                .update();
        return id;
    }
}
