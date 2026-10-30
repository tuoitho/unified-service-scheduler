package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.Appointment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID>, AppointmentLockingRepository {

    @Query("""
            SELECT a
            FROM Appointment a
            JOIN FETCH a.dealership
            JOIN FETCH a.serviceType
            JOIN FETCH a.serviceBay
            JOIN FETCH a.technician
            WHERE a.id = :id
            """)
    Optional<Appointment> findDetailedById(@Param("id") UUID id);
}
