package com.keyloop.scheduler.api.dto;

import com.keyloop.scheduler.domain.Dealership;
import java.time.LocalTime;
import java.util.UUID;

public record DealershipSummaryResponse(
        UUID id,
        String code,
        String name,
        String timezone,
        LocalTime openTime,
        LocalTime closeTime
) {
    public static DealershipSummaryResponse from(Dealership dealership) {
        return new DealershipSummaryResponse(
                dealership.getId(),
                dealership.getCode(),
                dealership.getName(),
                dealership.getTimezone(),
                dealership.getOpenTime(),
                dealership.getCloseTime()
        );
    }
}
