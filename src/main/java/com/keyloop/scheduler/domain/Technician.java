package com.keyloop.scheduler.domain;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

public record Technician(
        UUID id,
        UUID dealershipId,
        String employeeCode,
        String fullName,
        boolean active,
        Set<String> qualifications,
        OffsetDateTime createdAt
) {
}
