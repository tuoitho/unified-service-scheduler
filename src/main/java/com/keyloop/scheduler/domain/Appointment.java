package com.keyloop.scheduler.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Appointment(
        UUID id,
        UUID dealershipId,
        UUID customerId,
        UUID vehicleId,
        UUID serviceTypeId,
        UUID serviceBayId,
        UUID technicianId,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        AppointmentStatus status,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime cancelledAt
) {
}
