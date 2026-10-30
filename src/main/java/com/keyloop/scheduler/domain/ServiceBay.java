package com.keyloop.scheduler.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ServiceBay(
        UUID id,
        UUID dealershipId,
        String bayNumber,
        String name,
        boolean active,
        OffsetDateTime createdAt
) {
}
