package com.keyloop.scheduler.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.api.dto.AppointmentResponse;
import com.keyloop.scheduler.api.dto.BookAppointmentRequest;
import com.keyloop.scheduler.exception.SchedulingConflictException;
import com.keyloop.scheduler.service.AppointmentService;
import com.keyloop.scheduler.support.AbstractPostgresIntegrationTest;
import com.keyloop.scheduler.support.TestFixtures;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class AppointmentConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AppointmentService appointmentService;

    @Test
    void fiftyConcurrentVirtualThreadsForSameSlotBookExactCapacityWithoutDoubleBooking() throws Exception {
        UUID dealershipId = TestFixtures.insertDealership(
                jdbcClient, "CONC-50-" + UUID.randomUUID().toString().substring(0, 8),
                "High Concurrency Dealership", "UTC", LocalTime.of(8, 0), LocalTime.of(18, 0));

        // 3 active bays, 2 EV-qualified technicians, 2 unqualified generalists -> max capacity = 2
        TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-01", "Bay 1", true);
        TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-02", "Bay 2", true);
        TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-03", "Bay 3", true);

        TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "EV1-" + UUID.randomUUID().toString().substring(0, 8),
                "EV Specialist 1", true, List.of("EV_HIGH_VOLTAGE"));
        TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "EV2-" + UUID.randomUUID().toString().substring(0, 8),
                "EV Specialist 2", true, List.of("EV_HIGH_VOLTAGE"));
        TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "GEN1-" + UUID.randomUUID().toString().substring(0, 8),
                "Generalist 1", true, List.of("GENERAL_MAINT"));
        TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "GEN2-" + UUID.randomUUID().toString().substring(0, 8),
                "Generalist 2", true, List.of("GENERAL_MAINT"));

        UUID evServiceId = TestFixtures.insertServiceType(
                jdbcClient, "EVDIAG-" + UUID.randomUUID().toString().substring(0, 8),
                "EV High Voltage Diagnostic", 60, "EV_HIGH_VOLTAGE");

        int threadCount = 50;
        OffsetDateTime targetStart = OffsetDateTime.parse("2026-12-15T09:00:00Z");

        CyclicBarrier startGate = new CyclicBarrier(threadCount);
        List<AppointmentResponse> confirmed = new CopyOnWriteArrayList<>();
        List<SchedulingConflictException> conflicts = new CopyOnWriteArrayList<>();
        List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        startGate.await(10, TimeUnit.SECONDS);
                        AppointmentResponse res = appointmentService.bookAppointment(new BookAppointmentRequest(
                                dealershipId,
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                evServiceId,
                                targetStart,
                                "Concurrent booking test"));
                        confirmed.add(res);
                    } catch (SchedulingConflictException conflict) {
                        conflicts.add(conflict);
                    } catch (Throwable t) {
                        unexpectedErrors.add(t);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(unexpectedErrors).isEmpty();
        assertThat(confirmed).hasSize(2);
        assertThat(conflicts).hasSize(48);

        Set<UUID> assignedBays = confirmed.stream()
                .map(AppointmentResponse::serviceBayId)
                .collect(Collectors.toSet());
        Set<UUID> assignedTechs = confirmed.stream()
                .map(AppointmentResponse::technicianId)
                .collect(Collectors.toSet());

        assertThat(assignedBays).hasSize(2);
        assertThat(assignedTechs).hasSize(2);

        Integer persistedCount = jdbcClient.sql("""
                SELECT COUNT(*) FROM appointments
                WHERE dealership_id = :dealershipId AND status = 'CONFIRMED'
                """)
                .param("dealershipId", dealershipId)
                .query(Integer.class)
                .single();
        assertThat(persistedCount).isEqualTo(2);
    }

    @Test
    void twentyConcurrentDuplicateVehicleSubmissionsConfirmExactlyOne() throws Exception {
        UUID dealershipId = TestFixtures.insertDealership(
                jdbcClient, "CONC-VEH-" + UUID.randomUUID().toString().substring(0, 8),
                "Duplicate Vehicle Guard Dealership", "UTC", LocalTime.of(8, 0), LocalTime.of(18, 0));

        for (int i = 1; i <= 5; i++) {
            TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-0" + i, "Bay " + i, true);
            TestFixtures.insertTechnician(
                    jdbcClient, dealershipId, "TECH-" + i + "-" + UUID.randomUUID().toString().substring(0, 6),
                    "Tech " + i, true, List.of("GENERAL_MAINT"));
        }

        UUID oilServiceId = TestFixtures.insertServiceType(
                jdbcClient, "OIL-" + UUID.randomUUID().toString().substring(0, 8),
                "Oil Change", 60, "GENERAL_MAINT");

        UUID customerId = UUID.randomUUID();
        UUID sharedVehicleId = UUID.randomUUID();
        OffsetDateTime targetStart = OffsetDateTime.parse("2026-12-16T11:00:00Z");

        int threadCount = 20;
        CyclicBarrier startGate = new CyclicBarrier(threadCount);
        List<AppointmentResponse> confirmed = new CopyOnWriteArrayList<>();
        List<SchedulingConflictException> conflicts = new CopyOnWriteArrayList<>();
        List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        startGate.await(10, TimeUnit.SECONDS);
                        confirmed.add(appointmentService.bookAppointment(new BookAppointmentRequest(
                                dealershipId, customerId, sharedVehicleId, oilServiceId, targetStart, null)));
                    } catch (SchedulingConflictException conflict) {
                        conflicts.add(conflict);
                    } catch (Throwable t) {
                        unexpectedErrors.add(t);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(unexpectedErrors).isEmpty();
        assertThat(confirmed).hasSize(1);
        assertThat(conflicts).hasSize(19);
        assertThat(conflicts)
                .extracting(SchedulingConflictException::getErrorCode)
                .containsOnly("VEHICLE_ALREADY_BOOKED");
    }

    @Test
    void tenConcurrentNonOverlappingBookingsOnSingleBayDealershipAllSucceedWithoutFalseConflicts() throws Exception {
        UUID dealershipId = TestFixtures.insertDealership(
                jdbcClient, "CONC-1BAY-" + UUID.randomUUID().toString().substring(0, 8),
                "Single Bay Boutique Dealership", "UTC", LocalTime.of(8, 0), LocalTime.of(18, 0));

        TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-01", "Sole Bay", true);
        TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "SOLE-" + UUID.randomUUID().toString().substring(0, 8),
                "Sole Master Tech", true, List.of("GENERAL_MAINT"));

        UUID serviceId = TestFixtures.insertServiceType(
                jdbcClient, "SVC60-" + UUID.randomUUID().toString().substring(0, 8),
                "1h Inspection", 60, "GENERAL_MAINT");

        int slotCount = 10; // 08:00, 09:00, ..., 17:00
        CyclicBarrier startGate = new CyclicBarrier(slotCount);
        List<AppointmentResponse> confirmed = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int hourOffset = 0; hourOffset < slotCount; hourOffset++) {
                OffsetDateTime slotStart = OffsetDateTime.parse("2026-12-17T08:00:00Z").plusHours(hourOffset);
                futures.add(executor.submit(() -> {
                    try {
                        startGate.await(10, TimeUnit.SECONDS);
                        confirmed.add(appointmentService.bookAppointment(new BookAppointmentRequest(
                                dealershipId, UUID.randomUUID(), UUID.randomUUID(), serviceId, slotStart, null)));
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(errors).isEmpty();
        assertThat(confirmed).hasSize(10);
    }

    @Test
    void specialistTechnicianPreservedWhenGeneralServiceIsBookedFirstInSameWindow() {
        UUID dealershipId = TestFixtures.insertDealership(
                jdbcClient, "SPEC-" + UUID.randomUUID().toString().substring(0, 8),
                "Specialist Preservation Site", "UTC", LocalTime.of(8, 0), LocalTime.of(18, 0));

        TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-01", "Bay 1", true);
        TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-02", "Bay 2", true);

        // Insert EV Master FIRST so naive ordering would mistakenly pick EV Master for routine oil change
        UUID evMasterId = TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "AA-MASTER-" + UUID.randomUUID().toString().substring(0, 6),
                "EV Master", true, List.of("GENERAL_MAINT", "EV_HIGH_VOLTAGE"));
        UUID generalistId = TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "ZZ-GEN-" + UUID.randomUUID().toString().substring(0, 6),
                "Generalist", true, List.of("GENERAL_MAINT"));

        UUID oilServiceId = TestFixtures.insertServiceType(
                jdbcClient, "OIL-" + UUID.randomUUID().toString().substring(0, 8),
                "Oil Change", 60, "GENERAL_MAINT");
        UUID evServiceId = TestFixtures.insertServiceType(
                jdbcClient, "EV-" + UUID.randomUUID().toString().substring(0, 8),
                "EV Battery Diagnostic", 60, "EV_HIGH_VOLTAGE");

        OffsetDateTime start = OffsetDateTime.parse("2026-12-18T14:00:00Z");

        AppointmentResponse oilAppt = appointmentService.bookAppointment(new BookAppointmentRequest(
                dealershipId, UUID.randomUUID(), UUID.randomUUID(), oilServiceId, start, null));
        AppointmentResponse evAppt = appointmentService.bookAppointment(new BookAppointmentRequest(
                dealershipId, UUID.randomUUID(), UUID.randomUUID(), evServiceId, start, null));

        assertThat(oilAppt.technicianId()).isEqualTo(generalistId);
        assertThat(evAppt.technicianId()).isEqualTo(evMasterId);
    }
}
