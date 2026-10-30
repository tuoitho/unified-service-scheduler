package com.keyloop.scheduler.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.domain.Appointment;
import com.keyloop.scheduler.domain.AppointmentStatus;
import com.keyloop.scheduler.domain.Dealership;
import com.keyloop.scheduler.domain.ServiceBay;
import com.keyloop.scheduler.domain.ServiceType;
import com.keyloop.scheduler.domain.Technician;
import com.keyloop.scheduler.support.AbstractPostgresIntegrationTest;
import com.keyloop.scheduler.support.TestFixtures;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class AppointmentRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DealershipRepository dealershipRepository;

    @Autowired
    private ServiceBayRepository serviceBayRepository;

    @Autowired
    private TechnicianRepository technicianRepository;

    @Autowired
    private ServiceTypeRepository serviceTypeRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void dealershipAndServiceTypeJpaRepositoriesLoadCatalogAndTechnicianSkillsWithoutNPlusOne() {
        String code = "KL-" + UUID.randomUUID().toString().substring(0, 8);
        UUID dealershipId = TestFixtures.insertDealership(
                jdbcClient, code, "Keyloop Milton Keynes", "Europe/London",
                LocalTime.of(8, 30), LocalTime.of(17, 30));
        UUID bay1 = TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-01", "Main Lift 1", true);
        TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-02", "Offline Lift", false);
        UUID techId = TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "EMP-" + UUID.randomUUID().toString().substring(0, 8),
                "Sarah Connor", true, List.of("GENERAL_MAINT", "EV_HIGH_VOLTAGE"));

        String svcCode = "EV-" + UUID.randomUUID().toString().substring(0, 8);
        UUID svcId = TestFixtures.insertServiceType(
                jdbcClient, svcCode, "EV Battery Diagnostic", 90, "EV_HIGH_VOLTAGE");

        Optional<Dealership> dealership = dealershipRepository.findById(dealershipId);
        assertThat(dealership).isPresent();
        assertThat(dealership.get().getCode()).isEqualTo(code);
        assertThat(dealership.get().getTimezone()).isEqualTo("Europe/London");
        assertThat(dealership.get().getOpenTime()).isEqualTo(LocalTime.of(8, 30));

        List<ServiceBay> bays = serviceBayRepository.findByDealershipIdOrderByBayNumberAscIdAsc(dealershipId);
        assertThat(bays).hasSize(2);
        assertThat(bays.get(0).getId()).isEqualTo(bay1);

        // With open-in-view=false, accessing getQualifications() outside @Transactional proves JOIN FETCH worked
        List<Technician> technicians = technicianRepository.findByDealershipIdWithQualifications(dealershipId);
        assertThat(technicians).hasSize(1);
        assertThat(technicians.get(0).getId()).isEqualTo(techId);
        assertThat(technicians.get(0).getQualifications())
                .isEqualTo(Set.of("GENERAL_MAINT", "EV_HIGH_VOLTAGE"));

        Optional<ServiceType> serviceType = serviceTypeRepository.findById(svcId);
        assertThat(serviceType).isPresent();
        assertThat(serviceType.get().getDurationMinutes()).isEqualTo(90);
        assertThat(serviceType.get().getRequiredQualification()).isEqualTo("EV_HIGH_VOLTAGE");
    }

    @Test
    void rejectsSplitBayPartialAvailabilityWhenNoSingleBayIsFreeForEntireDuration() {
        UUID dealershipId = TestFixtures.insertDealership(
                jdbcClient, "DL-" + UUID.randomUUID(), "Split Bay Test Site", "UTC",
                LocalTime.of(8, 0), LocalTime.of(18, 0));
        UUID svc30Id = TestFixtures.insertServiceType(
                jdbcClient, "S30-" + UUID.randomUUID(), "Quick Check", 30, "GENERAL");
        UUID bay1Id = TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-01", "Bay 1", true);
        UUID bay2Id = TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-02", "Bay 2", true);
        TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-03", "Inactive Bay", false);

        UUID tech1Id = TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "T1-" + UUID.randomUUID(), "Tech 1", true, List.of("GENERAL"));
        UUID tech2Id = TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "T2-" + UUID.randomUUID(), "Tech 2", true, List.of("GENERAL"));

        transactionTemplate.executeWithoutResult(status -> {
            Dealership dealership = dealershipRepository.getReferenceById(dealershipId);
            ServiceType svc30 = serviceTypeRepository.getReferenceById(svc30Id);
            ServiceBay bay1 = serviceBayRepository.getReferenceById(bay1Id);
            ServiceBay bay2 = serviceBayRepository.getReferenceById(bay2Id);
            Technician tech1 = technicianRepository.getReferenceById(tech1Id);
            Technician tech2 = technicianRepository.getReferenceById(tech2Id);

            // Bay 1 busy [09:00, 09:30)
            appointmentRepository.saveAndFlush(new Appointment(
                    UUID.randomUUID(), dealership, UUID.randomUUID(), UUID.randomUUID(),
                    svc30, bay1, tech1,
                    OffsetDateTime.parse("2026-12-05T09:00:00Z"),
                    OffsetDateTime.parse("2026-12-05T09:30:00Z"),
                    AppointmentStatus.CONFIRMED, null, null));

            // Bay 2 busy [09:30, 10:00)
            appointmentRepository.saveAndFlush(new Appointment(
                    UUID.randomUUID(), dealership, UUID.randomUUID(), UUID.randomUUID(),
                    svc30, bay2, tech2,
                    OffsetDateTime.parse("2026-12-05T09:30:00Z"),
                    OffsetDateTime.parse("2026-12-05T10:00:00Z"),
                    AppointmentStatus.CONFIRMED, null, null));
        });

        OffsetDateTime fullHourStart = OffsetDateTime.parse("2026-12-05T09:00:00Z");
        OffsetDateTime fullHourEnd = OffsetDateTime.parse("2026-12-05T10:00:00Z");

        assertThat(appointmentRepository.countAvailableBays(dealershipId, fullHourStart, fullHourEnd))
                .isZero();

        Optional<ServiceBay> acquired = transactionTemplate.execute(status ->
                appointmentRepository.acquireAvailableServiceBay(dealershipId, fullHourStart, fullHourEnd));
        assertThat(acquired).isEmpty();

        // Meanwhile [09:30, 10:00) DOES have Bay 1 free
        Optional<ServiceBay> halfHourAcquired = transactionTemplate.execute(status ->
                appointmentRepository.acquireAvailableServiceBay(
                        dealershipId,
                        OffsetDateTime.parse("2026-12-05T09:30:00Z"),
                        OffsetDateTime.parse("2026-12-05T10:00:00Z")));
        assertThat(halfHourAcquired).isPresent();
        assertThat(halfHourAcquired.get().getId()).isEqualTo(bay1Id);
    }

    @Test
    void prefersLeastSpecializedQualifiedTechnicianAndReleasesCapacityUponJpaCancellation() {
        UUID dealershipId = TestFixtures.insertDealership(
                jdbcClient, "DL-" + UUID.randomUUID(), "Specialist Test Site", "UTC",
                LocalTime.of(8, 0), LocalTime.of(18, 0));
        UUID oilServiceId = TestFixtures.insertServiceType(
                jdbcClient, "OIL-" + UUID.randomUUID(), "Oil Change", 60, "GENERAL");
        UUID bay1Id = TestFixtures.insertServiceBay(jdbcClient, dealershipId, "BAY-01", "Bay 1", true);

        TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "INACTIVE-" + UUID.randomUUID(), "Inactive Tech", false, List.of("GENERAL"));
        UUID masterEvTechId = TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "EV-" + UUID.randomUUID(), "Master EV Tech", true,
                List.of("GENERAL", "BRAKES", "EV_HIGH_VOLTAGE"));
        UUID generalistTechId = TestFixtures.insertTechnician(
                jdbcClient, dealershipId, "GEN-" + UUID.randomUUID(), "Generalist Tech", true,
                List.of("GENERAL"));

        OffsetDateTime start = OffsetDateTime.parse("2026-12-06T10:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-12-06T11:00:00Z");

        Optional<Technician> selected = transactionTemplate.execute(status ->
                appointmentRepository.acquireAvailableQualifiedTechnician(dealershipId, "GENERAL", start, end));

        assertThat(selected).isPresent();
        assertThat(selected.get().getId()).isEqualTo(generalistTechId);

        UUID customerId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(status -> {
            appointmentRepository.saveAndFlush(new Appointment(
                    appointmentId,
                    dealershipRepository.getReferenceById(dealershipId),
                    customerId,
                    vehicleId,
                    serviceTypeRepository.getReferenceById(oilServiceId),
                    serviceBayRepository.getReferenceById(bay1Id),
                    technicianRepository.getReferenceById(masterEvTechId),
                    start,
                    end,
                    AppointmentStatus.CONFIRMED,
                    "Check battery coolant",
                    null));
        });

        assertThat(appointmentRepository.countAvailableQualifiedTechnicians(
                dealershipId, "EV_HIGH_VOLTAGE", start, end)).isZero();

        Boolean hasVehicleOverlap = transactionTemplate.execute(status ->
                appointmentRepository.lockVehicleAndHasOverlap(vehicleId, start, end));
        assertThat(hasVehicleOverlap).isTrue();

        OffsetDateTime cancelledAt = OffsetDateTime.parse("2026-12-06T08:00:00Z");
        Boolean cancelled = transactionTemplate.execute(status -> {
            Appointment appt = appointmentRepository.findById(appointmentId).orElseThrow();
            boolean result = appt.cancel(cancelledAt);
            appointmentRepository.flush();
            return result;
        });
        assertThat(cancelled).isTrue();

        assertThat(appointmentRepository.countAvailableQualifiedTechnicians(
                dealershipId, "EV_HIGH_VOLTAGE", start, end)).isEqualTo(1);

        Optional<Appointment> detailed = appointmentRepository.findDetailedById(appointmentId);
        assertThat(detailed).isPresent();
        assertThat(detailed.get().getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(detailed.get().getCancelledAt()).isEqualTo(cancelledAt);
        // Verify JOIN FETCH populated lazy relations cleanly with open-in-view=false
        assertThat(detailed.get().getDealership().getName()).isEqualTo("Specialist Test Site");
        assertThat(detailed.get().getTechnician().getFullName()).isEqualTo("Master EV Tech");

        List<Appointment> filtered = appointmentRepository.findDetailedByFilters(
                dealershipId,
                OffsetDateTime.parse("2026-12-06T00:00:00Z"),
                OffsetDateTime.parse("2026-12-07T00:00:00Z"),
                AppointmentStatus.CANCELLED,
                customerId,
                vehicleId);
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).getId()).isEqualTo(appointmentId);
    }
}
