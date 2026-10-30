package com.keyloop.scheduler.api.dto;

import com.keyloop.scheduler.domain.ServiceType;
import java.util.UUID;

public record ServiceTypeResponse(
        UUID id,
        String code,
        String name,
        String description,
        int durationMinutes,
        String requiredQualification
) {
    public static ServiceTypeResponse from(ServiceType serviceType) {
        return new ServiceTypeResponse(
                serviceType.getId(),
                serviceType.getCode(),
                serviceType.getName(),
                serviceType.getDescription(),
                serviceType.getDurationMinutes(),
                serviceType.getRequiredQualification()
        );
    }
}
