package com.keyloop.scheduler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keyloop.scheduler.api.dto.AppointmentResponse;
import com.keyloop.scheduler.api.dto.AvailabilityCheckResponse;
import com.keyloop.scheduler.api.dto.BookAppointmentRequest;
import com.keyloop.scheduler.api.dto.DailyAvailabilityResponse;
import com.keyloop.scheduler.api.dto.TimeSlotAvailability;
import com.keyloop.scheduler.domain.AppointmentStatus;
import com.keyloop.scheduler.exception.ResourceNotFoundException;
import com.keyloop.scheduler.exception.SchedulingConflictException;
import com.keyloop.scheduler.support.AbstractPostgresIntegrationTest;
import com.keyloop.scheduler.support.TestFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class AppointmentServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private AvailabilityService availabilityService;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void booksAppointmentEnforcesDistinctConflictReasonsCancelsAndRecordsMetrics() {
        UUID dealershipId = TestFixtures.insertDealership(
                jdbcClient, "SVC-DL-" + UUID.randomUUID().toString().substring(0, 8),
                "Keyloop Birmingham", "UTC", LocalTime.of(8, 0), LocalTime.of(18, 0));
        UUID bay1 = TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-01", "Bay 1", true);
        TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-02", "Bay 2", true);

        // Only 1 EV technician, 0 MOT technicians
        UUID evTech = TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "EV-" + UUID.randomUUID().toString().substring(0, 8),
                "Elena Vance", true, List.of("EV_HIGH_VOLTAGE"));

        UUID evServiceId = TestFixtures.insertServiceType(
                jdbcClient, "EV-" + UUID.randomUUID().toString().substring(0, 8),
                "EV Diagnostic", 60, "EV_HIGH_VOLTAGE");
        UUID motServiceId = TestFixtures.insertServiceType(
                jdbcClient, "MOT-" + UUID.randomUUID().toString().substring(0, 8),
                "MOT Inspection", 60, "MOT_TESTER");

        UUID customer1 = UUID.randomUUID();
        UUID vehicle1 = UUID.randomUUID();
        OffsetDateTime start0900 = OffsetDateTime.parse("2026-12-10T09:00:00Z");

        AppointmentResponse booked = appointmentService.bookAppointment(new BookAppointmentRequest(
                dealershipId, customer1, vehicle1, evServiceId, start0900, "Annual EV health check"));

        assertThat(booked.status()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(booked.serviceBayId()).isEqualTo(bay1);
        assertThat(booked.technicianId()).isEqualTo(evTech);
        assertThat(booked.startTime()).isEqualTo(start0900);
        assertThat(booked.endTime()).isEqualTo(OffsetDateTime.parse("2026-12-10T10:00:00Z"));

        // Duplicate vehicle in overlapping slot -> VEHICLE_ALREADY_BOOKED
        assertThatThrownBy(() -> appointmentService.bookAppointment(new BookAppointmentRequest(
                dealershipId, customer1, vehicle1, evServiceId,
                OffsetDateTime.parse("2026-12-10T09:30:00Z"), null)))
                .isInstanceOf(SchedulingConflictException.class)
                .extracting("errorCode")
                .isEqualTo("VEHICLE_ALREADY_BOOKED");

        // Different vehicle at 09:00: Bay 2 is free, but the sole EV technician is busy -> NO_QUALIFIED_TECHNICIAN_AVAILABLE
        assertThatThrownBy(() -> appointmentService.bookAppointment(new BookAppointmentRequest(
                dealershipId, UUID.randomUUID(), UUID.randomUUID(), evServiceId, start0900, null)))
                .isInstanceOf(SchedulingConflictException.class)
                .extracting("errorCode")
                .isEqualTo("NO_QUALIFIED_TECHNICIAN_AVAILABLE");

        // Service requiring MOT_TESTER (nobody holds MOT_TESTER) -> NO_QUALIFIED_TECHNICIAN_AVAILABLE
        assertThatThrownBy(() -> appointmentService.bookAppointment(new BookAppointmentRequest(
                dealershipId, UUID.randomUUID(), UUID.randomUUID(), motServiceId,
                OffsetDateTime.parse("2026-12-10T11:00:00Z"), null)))
                .isInstanceOf(SchedulingConflictException.class)
                .extracting("errorCode")
                .isEqualTo("NO_QUALIFIED_TECHNICIAN_AVAILABLE");

        // Verify instant availability reports exact unavailableReason before cancellation
        AvailabilityCheckResponse busyCheck = availabilityService.checkInstantAvailability(
                dealershipId, evServiceId, start0900);
        assertThat(busyCheck.available()).isFalse();
        assertThat(busyCheck.availableBays()).isEqualTo(1);
        assertThat(busyCheck.availableQualifiedTechnicians()).isZero();
        assertThat(busyCheck.unavailableReason()).isEqualTo("NO_QUALIFIED_TECHNICIAN_AVAILABLE");

        // Cancel original appointment and verify immediate re-booking succeeds
        AppointmentResponse cancelled = appointmentService.cancelAppointment(booked.id());
        assertThat(cancelled.status()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isNotNull();

        AppointmentResponse rebooked = appointmentService.bookAppointment(new BookAppointmentRequest(
                dealershipId, UUID.randomUUID(), UUID.randomUUID(), evServiceId, start0900, "Replacement booking"));
        assertThat(rebooked.status()).isEqualTo(AppointmentStatus.CONFIRMED);

        // Verify Micrometer Prometheus metrics recorded outcomes
        double confirmedCount = meterRegistry.counter(
                "scheduler_booking_requests_total",
                "outcome", "confirmed",
                "dealership_id", dealershipId.toString()).count();
        assertThat(confirmedCount).isEqualTo(2.0);

        double techConflictCount = meterRegistry.counter(
                "scheduler_booking_requests_total",
                "outcome", "conflict_technician",
                "dealership_id", dealershipId.toString()).count();
        assertThat(techConflictCount).isEqualTo(2.0);

        assertThat(catalogService.getDealershipDetail(dealershipId).serviceBays()).hasSize(2);
    }

    @Test
    void dailyAvailabilityAccuratelyReflectsWholeDurationContinuityAndUnknownDealershipThrows404() {
        UUID dealershipId = TestFixtures.insertDealership(
                jdbcClient, "AVAIL-DL-" + UUID.randomUUID().toString().substring(0, 8),
                "Keyloop Manchester", "UTC", LocalTime.of(8, 0), LocalTime.of(11, 0));
        TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-01", "Bay 1", true);
        TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-02", "Bay 2", true);
        TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "T1-" + UUID.randomUUID().toString().substring(0, 8),
                "Tech 1", true, List.of("GENERAL"));
        TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "T2-" + UUID.randomUUID().toString().substring(0, 8),
                "Tech 2", true, List.of("GENERAL"));

        UUID svc30 = TestFixtures.insertServiceType(
                jdbcClient, "S30-" + UUID.randomUUID().toString().substring(0, 8),
                "30m Inspection", 30, "GENERAL");
        UUID svc60 = TestFixtures.insertServiceType(
                jdbcClient, "S60-" + UUID.randomUUID().toString().substring(0, 8),
                "60m Service", 60, "GENERAL");

        // Book Bay 1 [09:00, 09:30) and Bay 2? Wait: booking [09:00, 09:30) takes Bay 1,
        // and to occupy Bay 2 at [09:30, 10:00), temporarily book Bay 1 at [09:30, 10:00) AND Bay 2 at [09:30, 10:00),
        // then cancel Bay 1's [09:30, 10:00) so Bay 1=[09:00,09:30) and Bay 2=[09:30,10:00)!
        appointmentService.bookAppointment(new BookAppointmentRequest(
                dealershipId, UUID.randomUUID(), UUID.randomUUID(), svc30,
                OffsetDateTime.parse("2026-12-12T09:00:00Z"), null));
        AppointmentResponse tempBay1At0930 = appointmentService.bookAppointment(new BookAppointmentRequest(
                dealershipId, UUID.randomUUID(), UUID.randomUUID(), svc30,
                OffsetDateTime.parse("2026-12-12T09:30:00Z"), null));
        appointmentService.bookAppointment(new BookAppointmentRequest(
                dealershipId, UUID.randomUUID(), UUID.randomUUID(), svc30,
                OffsetDateTime.parse("2026-12-12T09:30:00Z"), null));
        appointmentService.cancelAppointment(tempBay1At0930.id());

        DailyAvailabilityResponse daily = availabilityService.getDailyAvailability(
                dealershipId, svc60, LocalDate.of(2026, 12, 12), 30);

        // Operating window 08:00..11:00 with 60m duration at 30m steps -> starts at 08:00, 08:30, 09:00, 09:30, 10:00
        assertThat(daily.slots()).hasSize(5);

        TimeSlotAvailability slot0900To1000 = daily.slots().stream()
                .filter(s -> s.startTime().equals(OffsetDateTime.parse("2026-12-12T09:00:00Z")))
                .findFirst()
                .orElseThrow();

        // Neither Bay 1 nor Bay 2 is continuously free across [09:00, 10:00)
        assertThat(slot0900To1000.available()).isFalse();
        assertThat(slot0900To1000.availableBays()).isZero();

        TimeSlotAvailability slot1000To1100 = daily.slots().stream()
                .filter(s -> s.startTime().equals(OffsetDateTime.parse("2026-12-12T10:00:00Z")))
                .findFirst()
                .orElseThrow();
        assertThat(slot1000To1100.available()).isTrue();
        assertThat(slot1000To1100.maxConcurrentBookings()).isEqualTo(2);

        assertThatThrownBy(() -> availabilityService.getDailyAvailability(
                UUID.randomUUID(), svc60, LocalDate.of(2026, 12, 12), 30))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
