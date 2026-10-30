package com.keyloop.scheduler.api.dto;

import com.keyloop.scheduler.domain.Dealership;
import com.keyloop.scheduler.domain.ServiceBay;
import com.keyloop.scheduler.domain.Technician;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record DealershipDetailResponse(
        UUID id,
        String code,
        String name,
        String timezone,
        LocalTime openTime,
        LocalTime closeTime,
        List<ServiceBayItem> serviceBays,
        List<TechnicianItem> technicians
) {
    public record ServiceBayItem(
            UUID id,
            String bayNumber,
            String name,
            boolean active
    ) {
        public static ServiceBayItem from(ServiceBay bay) {
            return new ServiceBayItem(bay.getId(), bay.getBayNumber(), bay.getName(), bay.isActive());
        }
    }

    public record TechnicianItem(
            UUID id,
            String employeeCode,
            String fullName,
            boolean active,
            Set<String> qualifications
    ) {
        public static TechnicianItem from(Technician technician) {
            return new TechnicianItem(
                    technician.getId(),
                    technician.getEmployeeCode(),
                    technician.getFullName(),
                    technician.isActive(),
                    technician.getQualifications()
            );
        }
    }

    public static DealershipDetailResponse from(
            Dealership dealership,
            List<ServiceBay> bays,
            List<Technician> technicians
    ) {
        return new DealershipDetailResponse(
                dealership.getId(),
                dealership.getCode(),
                dealership.getName(),
                dealership.getTimezone(),
                dealership.getOpenTime(),
                dealership.getCloseTime(),
                bays.stream().map(ServiceBayItem::from).toList(),
                technicians.stream().map(TechnicianItem::from).toList()
        );
    }
}
