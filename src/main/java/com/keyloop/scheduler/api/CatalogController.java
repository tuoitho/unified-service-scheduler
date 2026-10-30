package com.keyloop.scheduler.api;

import com.keyloop.scheduler.api.dto.DealershipDetailResponse;
import com.keyloop.scheduler.api.dto.DealershipSummaryResponse;
import com.keyloop.scheduler.api.dto.ServiceTypeResponse;
import com.keyloop.scheduler.service.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Catalog", description = "Dealerships, service bays, technicians, and service type catalog discovery")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/dealerships")
    @Operation(summary = "List dealerships")
    @ApiResponse(responseCode = "200", description = "Dealership summaries returned")
    public List<DealershipSummaryResponse> listDealerships() {
        return catalogService.listDealerships();
    }

    @GetMapping("/dealerships/{id}")
    @Operation(summary = "Get dealership details including service bays and technician qualifications")
    @ApiResponse(responseCode = "200", description = "Dealership details returned")
    @ApiResponse(
            responseCode = "404",
            description = "Dealership not found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    public DealershipDetailResponse getDealershipDetail(@PathVariable UUID id) {
        return catalogService.getDealershipDetail(id);
    }

    @GetMapping("/service-types")
    @Operation(summary = "List supported service types with durations and required qualifications")
    @ApiResponse(responseCode = "200", description = "Service types returned")
    public List<ServiceTypeResponse> listServiceTypes() {
        return catalogService.listServiceTypes();
    }
}
