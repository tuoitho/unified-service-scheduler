package com.keyloop.scheduler.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ServiceType(
        UUID id,
        String code,
        String name,
        String description,
        int durationMinutes,
        String requiredQualification,
        OffsetDateTime createdAt
) {
}
