package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.Appointment;
import com.keyloop.scheduler.domain.AppointmentStatus;
import com.keyloop.scheduler.domain.ServiceBay;
import com.keyloop.scheduler.domain.Technician;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentLockingRepository {

    record ResourceBookingWindow(
            UUID serviceBayId,
            UUID technicianId,
            OffsetDateTime startTime,
            OffsetDateTime endTime
    ) {
    }

    void configureBookingLockTimeout();

    boolean lockVehicleAndHasOverlap(UUID vehicleId, OffsetDateTime startTime, OffsetDateTime endTime);

    Optional<ServiceBay> acquireAvailableServiceBay(
            UUID dealershipId,
            OffsetDateTime startTime,
            OffsetDateTime endTime
    );

    Optional<Technician> acquireAvailableQualifiedTechnician(
            UUID dealershipId,
            String qualificationCode,
            OffsetDateTime startTime,
            OffsetDateTime endTime
    );

    List<Appointment> findDetailedByFilters(
            UUID dealershipId,
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd,
            AppointmentStatus status,
            UUID customerId,
            UUID vehicleId
    );

    int countAvailableBays(UUID dealershipId, OffsetDateTime startTime, OffsetDateTime endTime);

    int countAvailableQualifiedTechnicians(
            UUID dealershipId,
            String qualificationCode,
            OffsetDateTime startTime,
            OffsetDateTime endTime
    );

    List<ResourceBookingWindow> findConfirmedWindowsOverlapping(
            UUID dealershipId,
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd
    );
}
