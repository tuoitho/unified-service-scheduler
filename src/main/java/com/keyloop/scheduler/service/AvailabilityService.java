package com.keyloop.scheduler.service;

import com.keyloop.scheduler.api.dto.AvailabilityCheckResponse;
import com.keyloop.scheduler.api.dto.DailyAvailabilityResponse;
import com.keyloop.scheduler.api.dto.TimeSlotAvailability;
import com.keyloop.scheduler.domain.Dealership;
import com.keyloop.scheduler.domain.ServiceBay;
import com.keyloop.scheduler.domain.ServiceType;
import com.keyloop.scheduler.domain.Technician;
import com.keyloop.scheduler.exception.BusinessValidationException;
import com.keyloop.scheduler.exception.ResourceNotFoundException;
import com.keyloop.scheduler.repository.AppointmentLockingRepository.ResourceBookingWindow;
import com.keyloop.scheduler.repository.AppointmentRepository;
import com.keyloop.scheduler.repository.DealershipRepository;
import com.keyloop.scheduler.repository.ServiceBayRepository;
import com.keyloop.scheduler.repository.ServiceTypeRepository;
import com.keyloop.scheduler.repository.TechnicianRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AvailabilityService {

    private final DealershipRepository dealershipRepository;
    private final ServiceTypeRepository serviceTypeRepository;
    private final ServiceBayRepository serviceBayRepository;
    private final TechnicianRepository technicianRepository;
    private final AppointmentRepository appointmentRepository;
    private final TimeWindowValidator timeWindowValidator;

    public AvailabilityService(
            DealershipRepository dealershipRepository,
            ServiceTypeRepository serviceTypeRepository,
            ServiceBayRepository serviceBayRepository,
            TechnicianRepository technicianRepository,
            AppointmentRepository appointmentRepository,
            TimeWindowValidator timeWindowValidator
    ) {
        this.dealershipRepository = dealershipRepository;
        this.serviceTypeRepository = serviceTypeRepository;
        this.serviceBayRepository = serviceBayRepository;
        this.technicianRepository = technicianRepository;
        this.appointmentRepository = appointmentRepository;
        this.timeWindowValidator = timeWindowValidator;
    }

    @Transactional(readOnly = true)
    public AvailabilityCheckResponse checkInstantAvailability(
            UUID dealershipId,
            UUID serviceTypeId,
            OffsetDateTime desiredStartTime
    ) {
        Dealership dealership = findDealershipOrThrow(dealershipId);
        ServiceType serviceType = findServiceTypeOrThrow(serviceTypeId);

        OffsetDateTime startUtc = desiredStartTime.withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime endUtc = startUtc.plusMinutes(serviceType.getDurationMinutes());

        if (timeWindowValidator.isInPast(startUtc)) {
            return new AvailabilityCheckResponse(
                    dealershipId, serviceTypeId, startUtc, endUtc, false, 0, 0, 0, "PAST_START_TIME");
        }

        if (!timeWindowValidator.isWithinOperatingHours(dealership, startUtc, endUtc)) {
            return new AvailabilityCheckResponse(
                    dealershipId, serviceTypeId, startUtc, endUtc, false, 0, 0, 0, "OUTSIDE_OPERATING_HOURS");
        }

        int availableBays = appointmentRepository.countAvailableBays(dealershipId, startUtc, endUtc);
        int availableTechs = appointmentRepository.countAvailableQualifiedTechnicians(
                dealershipId, serviceType.getRequiredQualification(), startUtc, endUtc);
        int maxConcurrent = Math.min(availableBays, availableTechs);

        if (availableBays == 0) {
            return new AvailabilityCheckResponse(
                    dealershipId, serviceTypeId, startUtc, endUtc,
                    false, 0, availableTechs, 0, "NO_SERVICE_BAY_AVAILABLE");
        }
        if (availableTechs == 0) {
            return new AvailabilityCheckResponse(
                    dealershipId, serviceTypeId, startUtc, endUtc,
                    false, availableBays, 0, 0, "NO_QUALIFIED_TECHNICIAN_AVAILABLE");
        }

        return new AvailabilityCheckResponse(
                dealershipId, serviceTypeId, startUtc, endUtc,
                true, availableBays, availableTechs, maxConcurrent, null);
    }

    @Transactional(readOnly = true)
    public DailyAvailabilityResponse getDailyAvailability(
            UUID dealershipId,
            UUID serviceTypeId,
            LocalDate date,
            int slotIntervalMinutes
    ) {
        if (slotIntervalMinutes < 5 || slotIntervalMinutes > 240) {
            throw new BusinessValidationException(
                    "INVALID_SLOT_INTERVAL",
                    "slotIntervalMinutes must be between 5 and 240 minutes");
        }

        Dealership dealership = findDealershipOrThrow(dealershipId);
        ServiceType serviceType = findServiceTypeOrThrow(serviceTypeId);

        ZoneId zoneId = ZoneId.of(dealership.getTimezone());
        ZonedDateTime dayOpen = ZonedDateTime.of(date, dealership.getOpenTime(), zoneId);
        ZonedDateTime dayClose = ZonedDateTime.of(date, dealership.getCloseTime(), zoneId);

        OffsetDateTime dayOpenUtc = dayOpen.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime dayCloseUtc = dayClose.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);

        List<UUID> activeBayIds = serviceBayRepository.findByDealershipIdOrderByBayNumberAscIdAsc(dealershipId)
                .stream()
                .filter(ServiceBay::isActive)
                .map(ServiceBay::getId)
                .toList();

        String requiredQual = serviceType.getRequiredQualification();
        List<UUID> qualifiedTechIds = technicianRepository.findByDealershipIdWithQualifications(dealershipId)
                .stream()
                .filter(Technician::isActive)
                .filter(t -> t.getQualifications().contains(requiredQual))
                .map(Technician::getId)
                .toList();

        List<ResourceBookingWindow> dayBookings = appointmentRepository.findConfirmedWindowsOverlapping(
                dealershipId, dayOpenUtc, dayCloseUtc);

        List<TimeSlotAvailability> slots = new ArrayList<>();
        int durationMinutes = serviceType.getDurationMinutes();
        ZonedDateTime cursor = dayOpen;

        while (!cursor.plusMinutes(durationMinutes).isAfter(dayClose)) {
            OffsetDateTime slotStartUtc = cursor.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
            OffsetDateTime slotEndUtc = cursor.plusMinutes(durationMinutes)
                    .toOffsetDateTime()
                    .withOffsetSameInstant(ZoneOffset.UTC);

            if (timeWindowValidator.isInPast(slotStartUtc)) {
                slots.add(new TimeSlotAvailability(slotStartUtc, slotEndUtc, false, 0, 0, 0));
            } else {
                int freeBays = countContinuouslyFreeResources(activeBayIds, dayBookings, slotStartUtc, slotEndUtc, true);
                int freeTechs = countContinuouslyFreeResources(
                        qualifiedTechIds, dayBookings, slotStartUtc, slotEndUtc, false);
                int maxConcurrent = Math.min(freeBays, freeTechs);
                slots.add(new TimeSlotAvailability(
                        slotStartUtc,
                        slotEndUtc,
                        maxConcurrent > 0,
                        freeBays,
                        freeTechs,
                        maxConcurrent));
            }

            cursor = cursor.plusMinutes(slotIntervalMinutes);
        }

        return new DailyAvailabilityResponse(
                dealership.getId(),
                dealership.getCode(),
                dealership.getTimezone(),
                date,
                serviceType.getId(),
                serviceType.getCode(),
                durationMinutes,
                slotIntervalMinutes,
                slots);
    }

    private int countContinuouslyFreeResources(
            List<UUID> resourceIds,
            List<ResourceBookingWindow> bookings,
            OffsetDateTime slotStart,
            OffsetDateTime slotEnd,
            boolean checkBay
    ) {
        int freeCount = 0;
        for (UUID resourceId : resourceIds) {
            boolean hasOverlap = false;
            for (ResourceBookingWindow window : bookings) {
                UUID bookedResource = checkBay ? window.serviceBayId() : window.technicianId();
                if (resourceId.equals(bookedResource)
                        && window.startTime().isBefore(slotEnd)
                        && window.endTime().isAfter(slotStart)) {
                    hasOverlap = true;
                    break;
                }
            }
            if (!hasOverlap) {
                freeCount++;
            }
        }
        return freeCount;
    }

    private Dealership findDealershipOrThrow(UUID dealershipId) {
        return dealershipRepository.findById(dealershipId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "DEALERSHIP_NOT_FOUND",
                        "Dealership not found: " + dealershipId));
    }

    private ServiceType findServiceTypeOrThrow(UUID serviceTypeId) {
        return serviceTypeRepository.findById(serviceTypeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "SERVICE_TYPE_NOT_FOUND",
                        "Service type not found: " + serviceTypeId));
    }
}
