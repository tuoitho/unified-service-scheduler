package com.keyloop.scheduler.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Request payload to schedule a resource-constrained service appointment")
public record BookAppointmentRequest(
        @Schema(description = "Target dealership identifier", example = "11111111-1111-1111-1111-111111111111")
        @NotNull(message = "dealershipId is required")
        UUID dealershipId,

        @Schema(description = "Customer identifier from CRM", example = "aaaa1111-1111-1111-1111-111111111111")
        @NotNull(message = "customerId is required")
        UUID customerId,

        @Schema(description = "Vehicle identifier", example = "bbbb1111-1111-1111-1111-111111111111")
        @NotNull(message = "vehicleId is required")
        UUID vehicleId,

        @Schema(description = "Requested service type identifier", example = "22222222-2222-2222-2222-222222222221")
        @NotNull(message = "serviceTypeId is required")
        UUID serviceTypeId,

        @Schema(description = "Desired appointment start timestamp in ISO-8601 UTC", example = "2026-12-01T09:00:00Z")
        @NotNull(message = "desiredStartTime is required")
        OffsetDateTime desiredStartTime,

        @Schema(description = "Optional customer or service advisor notes", example = "Front brake pad wear warning light on")
        @Size(max = 500, message = "notes must not exceed 500 characters")
        String notes
) {
}
