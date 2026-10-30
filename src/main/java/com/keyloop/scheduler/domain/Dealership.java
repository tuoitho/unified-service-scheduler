package com.keyloop.scheduler.domain;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Dealership(
        UUID id,
        String code,
        String name,
        String timezone,
        LocalTime openTime,
        LocalTime closeTime,
        OffsetDateTime createdAt
) {
}
