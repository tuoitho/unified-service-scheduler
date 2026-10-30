package com.keyloop.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "service_bays")
public class ServiceBay {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dealership_id", nullable = false, updatable = false)
    private Dealership dealership;

    @Column(name = "bay_number", length = 20, nullable = false)
    private String bayNumber;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ServiceBay() {
    }

    public ServiceBay(
            UUID id,
            Dealership dealership,
            String bayNumber,
            String name,
            boolean active
    ) {
        this.id = id;
        this.dealership = dealership;
        this.bayNumber = bayNumber;
        this.name = name;
        this.active = active;
    }

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public UUID getId() {
        return id;
    }

    public Dealership getDealership() {
        return dealership;
    }

    public String getBayNumber() {
        return bayNumber;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
