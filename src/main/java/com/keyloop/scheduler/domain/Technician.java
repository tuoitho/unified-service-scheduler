package com.keyloop.scheduler.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "technicians")
public class Technician {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dealership_id", nullable = false, updatable = false)
    private Dealership dealership;

    @Column(name = "employee_code", length = 50, nullable = false, unique = true)
    private String employeeCode;

    @Column(name = "full_name", length = 120, nullable = false)
    private String fullName;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "technician_skills", joinColumns = @JoinColumn(name = "technician_id"))
    @Column(name = "qualification_code", length = 50, nullable = false)
    private Set<String> qualifications = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Technician() {
    }

    public Technician(
            UUID id,
            Dealership dealership,
            String employeeCode,
            String fullName,
            boolean active,
            Set<String> qualifications
    ) {
        this.id = id;
        this.dealership = dealership;
        this.employeeCode = employeeCode;
        this.fullName = fullName;
        this.active = active;
        if (qualifications != null) {
            this.qualifications = new LinkedHashSet<>(qualifications);
        }
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

    public String getEmployeeCode() {
        return employeeCode;
    }

    public String getFullName() {
        return fullName;
    }

    public boolean isActive() {
        return active;
    }

    public Set<String> getQualifications() {
        return Collections.unmodifiableSet(qualifications);
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
