package com.keyloop.scheduler.api.dto;

import com.keyloop.scheduler.domain.Appointment;
import com.keyloop.scheduler.domain.AppointmentStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AppointmentResponse(
        UUID id,
        UUID dealershipId,
        String dealershipCode,
        String dealershipName,
        UUID customerId,
        UUID vehicleId,
        UUID serviceTypeId,
        String serviceTypeCode,
        String serviceTypeName,
        int durationMinutes,
        UUID serviceBayId,
        String serviceBayNumber,
        String serviceBayName,
        UUID technicianId,
        String technicianEmployeeCode,
        String technicianName,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        AppointmentStatus status,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime cancelledAt
) {
    public static AppointmentResponse from(Appointment appointment) {
        return new AppointmentResponse(
                appointment.getId(),
                appointment.getDealership().getId(),
                appointment.getDealership().getCode(),
                appointment.getDealership().getName(),
                appointment.getCustomerId(),
                appointment.getVehicleId(),
                appointment.getServiceType().getId(),
                appointment.getServiceType().getCode(),
                appointment.getServiceType().getName(),
                appointment.getServiceType().getDurationMinutes(),
                appointment.getServiceBay().getId(),
                appointment.getServiceBay().getBayNumber(),
                appointment.getServiceBay().getName(),
                appointment.getTechnician().getId(),
                appointment.getTechnician().getEmployeeCode(),
                appointment.getTechnician().getFullName(),
                appointment.getStartTime(),
                appointment.getEndTime(),
                appointment.getStatus(),
                appointment.getNotes(),
                appointment.getCreatedAt(),
                appointment.getCancelledAt()
        );
    }
}
