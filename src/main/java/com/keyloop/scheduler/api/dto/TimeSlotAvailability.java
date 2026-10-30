package com.keyloop.scheduler.api.dto;

import java.time.OffsetDateTime;

public record TimeSlotAvailability(
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        boolean available,
        int availableBays,
        int availableQualifiedTechnicians,
        int maxConcurrentBookings
) {
}
