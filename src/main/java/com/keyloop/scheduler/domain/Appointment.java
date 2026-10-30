package com.keyloop.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "appointments")
public class Appointment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dealership_id", nullable = false, updatable = false)
    private Dealership dealership;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "vehicle_id", nullable = false, updatable = false)
    private UUID vehicleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_type_id", nullable = false, updatable = false)
    private ServiceType serviceType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_bay_id", nullable = false, updatable = false)
    private ServiceBay serviceBay;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "technician_id", nullable = false, updatable = false)
    private Technician technician;

    @Column(name = "start_time", nullable = false, updatable = false)
    private OffsetDateTime startTime;

    @Column(name = "end_time", nullable = false, updatable = false)
    private OffsetDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AppointmentStatus status;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    protected Appointment() {
    }

    public Appointment(
            UUID id,
            Dealership dealership,
            UUID customerId,
            UUID vehicleId,
            ServiceType serviceType,
            ServiceBay serviceBay,
            Technician technician,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            AppointmentStatus status,
            String notes,
            OffsetDateTime createdAt
    ) {
        this.id = id;
        this.dealership = dealership;
        this.customerId = customerId;
        this.vehicleId = vehicleId;
        this.serviceType = serviceType;
        this.serviceBay = serviceBay;
        this.technician = technician;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = status;
        this.notes = notes;
        this.createdAt = createdAt;
    }

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
        if (this.status == null) {
            this.status = AppointmentStatus.CONFIRMED;
        }
    }

    public boolean cancel(OffsetDateTime cancelledTimestamp) {
        if (this.status == AppointmentStatus.CANCELLED) {
            return false;
        }
        this.status = AppointmentStatus.CANCELLED;
        this.cancelledAt = cancelledTimestamp;
        return true;
    }

    public UUID getId() {
        return id;
    }

    public Dealership getDealership() {
        return dealership;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getVehicleId() {
        return vehicleId;
    }

    public ServiceType getServiceType() {
        return serviceType;
    }

    public ServiceBay getServiceBay() {
        return serviceBay;
    }

    public Technician getTechnician() {
        return technician;
    }

    public OffsetDateTime getStartTime() {
        return startTime;
    }

    public OffsetDateTime getEndTime() {
        return endTime;
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getCancelledAt() {
        return cancelledAt;
    }
}
