package com.keyloop.scheduler.api;

import com.keyloop.scheduler.api.dto.AvailabilityCheckResponse;
import com.keyloop.scheduler.api.dto.DailyAvailabilityResponse;
import com.keyloop.scheduler.service.AvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/availability")
@Tag(name = "Availability", description = "Real-time service bay and qualified technician capacity discovery")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping
    @Operation(
            summary = "Get daily time-slot availability",
            description = "Computes bookable windows across dealership operating hours where at least one active bay "
                    + "and one qualified technician are continuously free for the entire service duration."
    )
    @ApiResponse(responseCode = "200", description = "Daily slot availability grid returned")
    @ApiResponse(
            responseCode = "400",
            description = "Malformed or missing query parameters",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Dealership or ServiceType not found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Invalid slotIntervalMinutes",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    public DailyAvailabilityResponse getDailyAvailability(
            @RequestParam UUID dealershipId,
            @RequestParam UUID serviceTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "30") int slotIntervalMinutes
    ) {
        return availabilityService.getDailyAvailability(dealershipId, serviceTypeId, date, slotIntervalMinutes);
    }

    @GetMapping("/check")
    @Operation(
            summary = "Check exact start-time availability",
            description = "Verifies real-time availability of bays and qualified technicians for an exact desired start time."
    )
    @ApiResponse(responseCode = "200", description = "Instant availability check result returned")
    @ApiResponse(
            responseCode = "400",
            description = "Malformed or missing query parameters",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Dealership or ServiceType not found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    public AvailabilityCheckResponse checkInstantAvailability(
            @RequestParam UUID dealershipId,
            @RequestParam UUID serviceTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startTime
    ) {
        return availabilityService.checkInstantAvailability(dealershipId, serviceTypeId, startTime);
    }
}
