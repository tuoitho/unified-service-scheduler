package com.keyloop.scheduler.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DailyAvailabilityResponse(
        UUID dealershipId,
        String dealershipCode,
        String timezone,
        LocalDate date,
        UUID serviceTypeId,
        String serviceTypeCode,
        int durationMinutes,
        int slotIntervalMinutes,
        List<TimeSlotAvailability> slots
) {
}
