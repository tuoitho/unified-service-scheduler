package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.Technician;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TechnicianRepository extends JpaRepository<Technician, UUID> {

    @Query("""
            SELECT DISTINCT t
            FROM Technician t
            LEFT JOIN FETCH t.qualifications
            WHERE t.dealership.id = :dealershipId
            ORDER BY t.employeeCode ASC, t.id ASC
            """)
    List<Technician> findByDealershipIdWithQualifications(@Param("dealershipId") UUID dealershipId);
}
