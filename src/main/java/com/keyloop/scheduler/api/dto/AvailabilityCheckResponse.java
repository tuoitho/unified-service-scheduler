package com.keyloop.scheduler.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AvailabilityCheckResponse(
        UUID dealershipId,
        UUID serviceTypeId,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        boolean available,
        int availableBays,
        int availableQualifiedTechnicians,
        int maxConcurrentBookings,
        String unavailableReason
) {
}
