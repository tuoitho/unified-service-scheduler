package com.keyloop.scheduler.api;

import com.keyloop.scheduler.api.dto.DealershipDetailResponse;
import com.keyloop.scheduler.api.dto.DealershipSummaryResponse;
import com.keyloop.scheduler.api.dto.ServiceTypeResponse;
import com.keyloop.scheduler.service.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
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
    public List<DealershipSummaryResponse> listDealerships() {
        return catalogService.listDealerships();
    }

    @GetMapping("/dealerships/{id}")
    @Operation(summary = "Get dealership details including service bays and technician qualifications")
    public DealershipDetailResponse getDealershipDetail(@PathVariable UUID id) {
        return catalogService.getDealershipDetail(id);
    }

    @GetMapping("/service-types")
    @Operation(summary = "List supported service types with durations and required qualifications")
    public List<ServiceTypeResponse> listServiceTypes() {
        return catalogService.listServiceTypes();
    }
}
