package com.keyloop.scheduler.service;

import com.keyloop.scheduler.api.dto.DealershipDetailResponse;
import com.keyloop.scheduler.api.dto.DealershipSummaryResponse;
import com.keyloop.scheduler.api.dto.ServiceTypeResponse;
import com.keyloop.scheduler.domain.Dealership;
import com.keyloop.scheduler.domain.ServiceBay;
import com.keyloop.scheduler.domain.Technician;
import com.keyloop.scheduler.exception.ResourceNotFoundException;
import com.keyloop.scheduler.repository.DealershipRepository;
import com.keyloop.scheduler.repository.ServiceBayRepository;
import com.keyloop.scheduler.repository.ServiceTypeRepository;
import com.keyloop.scheduler.repository.TechnicianRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final DealershipRepository dealershipRepository;
    private final ServiceBayRepository serviceBayRepository;
    private final TechnicianRepository technicianRepository;
    private final ServiceTypeRepository serviceTypeRepository;

    public CatalogService(
            DealershipRepository dealershipRepository,
            ServiceBayRepository serviceBayRepository,
            TechnicianRepository technicianRepository,
            ServiceTypeRepository serviceTypeRepository
    ) {
        this.dealershipRepository = dealershipRepository;
        this.serviceBayRepository = serviceBayRepository;
        this.technicianRepository = technicianRepository;
        this.serviceTypeRepository = serviceTypeRepository;
    }

    @Transactional(readOnly = true)
    public List<DealershipSummaryResponse> listDealerships() {
        return dealershipRepository.findAllByOrderByCodeAsc()
                .stream()
                .map(DealershipSummaryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DealershipDetailResponse getDealershipDetail(UUID dealershipId) {
        Dealership dealership = dealershipRepository.findById(dealershipId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "DEALERSHIP_NOT_FOUND",
                        "Dealership not found: " + dealershipId));

        List<ServiceBay> bays = serviceBayRepository.findByDealershipIdOrderByBayNumberAscIdAsc(dealershipId);
        List<Technician> technicians = technicianRepository.findByDealershipIdWithQualifications(dealershipId);

        return DealershipDetailResponse.from(dealership, bays, technicians);
    }

    @Transactional(readOnly = true)
    public List<ServiceTypeResponse> listServiceTypes() {
        return serviceTypeRepository.findAllByOrderByCodeAsc()
                .stream()
                .map(ServiceTypeResponse::from)
                .toList();
    }
}
