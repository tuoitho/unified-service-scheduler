package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.Appointment;
import com.keyloop.scheduler.domain.AppointmentStatus;
import com.keyloop.scheduler.domain.ServiceBay;
import com.keyloop.scheduler.domain.Technician;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AppointmentLockingRepositoryImpl implements AppointmentLockingRepository {

    private final EntityManager entityManager;
    private final JdbcClient jdbcClient;

    public AppointmentLockingRepositoryImpl(EntityManager entityManager, JdbcClient jdbcClient) {
        this.entityManager = entityManager;
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void configureBookingLockTimeout() {
        jdbcClient.sql("SET LOCAL lock_timeout = '5s'").update();
    }

    @Override
    public boolean lockVehicleAndHasOverlap(UUID vehicleId, OffsetDateTime startTime, OffsetDateTime endTime) {
        entityManager.flush();

        jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:vehicleKey, 0))")
                .param("vehicleKey", vehicleId.toString())
                .query(Object.class)
                .single();

        return jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM appointments a
                    WHERE a.vehicle_id = :vehicleId
                      AND a.status = 'CONFIRMED'
                      AND tstzrange(a.start_time, a.end_time, '[)')
                          && tstzrange(CAST(:startTime AS TIMESTAMPTZ), CAST(:endTime AS TIMESTAMPTZ), '[)')
                )
                """)
                .param("vehicleId", vehicleId)
                .param("startTime", startTime)
                .param("endTime", endTime)
                .query(Boolean.class)
                .single();
    }

    @Override
    public Optional<ServiceBay> acquireAvailableServiceBay(
            UUID dealershipId,
            OffsetDateTime startTime,
            OffsetDateTime endTime
    ) {
        entityManager.flush();
        Set<UUID> inspectedBayIds = new HashSet<>();

        // Fast path: non-blocking FOR UPDATE SKIP LOCKED + post-lock fresh-snapshot verification
        while (true) {
            Optional<UUID> candidateId = selectUnlockedCandidateBayId(
                    dealershipId, startTime, endTime, inspectedBayIds);
            if (candidateId.isEmpty()) {
                break;
            }
            UUID bayId = candidateId.get();
            if (isBayStillFreeUnderFreshSnapshot(bayId, startTime, endTime)) {
                return Optional.ofNullable(entityManager.find(ServiceBay.class, bayId));
            }
            inspectedBayIds.add(bayId);
        }

        // Contention fallback: lock active dealership bays in deterministic ID order so concurrent
        // non-overlapping bookings on a 1-bay dealership wait ~1ms instead of falsely returning 409.
        List<UUID> lockedBayIds = jdbcClient.sql("""
                SELECT sb.id
                FROM service_bays sb
                WHERE sb.dealership_id = :dealershipId
                  AND sb.is_active = TRUE
                ORDER BY sb.id ASC
                FOR UPDATE OF sb
                """)
                .param("dealershipId", dealershipId)
                .query(UUID.class)
                .list();

        if (lockedBayIds.isEmpty()) {
            return Optional.empty();
        }

        Optional<UUID> fallbackBayId = jdbcClient.sql("""
                SELECT sb.id
                FROM service_bays sb
                WHERE sb.dealership_id = :dealershipId
                  AND sb.is_active = TRUE
                  AND NOT EXISTS (
                      SELECT 1
                      FROM appointments a
                      WHERE a.service_bay_id = sb.id
                        AND a.status = 'CONFIRMED'
                        AND tstzrange(a.start_time, a.end_time, '[)')
                            && tstzrange(CAST(:startTime AS TIMESTAMPTZ), CAST(:endTime AS TIMESTAMPTZ), '[)')
                  )
                ORDER BY sb.bay_number ASC, sb.id ASC
                LIMIT 1
                """)
                .param("dealershipId", dealershipId)
                .param("startTime", startTime)
                .param("endTime", endTime)
                .query(UUID.class)
                .optional();

        return fallbackBayId.map(id -> entityManager.find(ServiceBay.class, id));
    }

    @Override
    public Optional<Technician> acquireAvailableQualifiedTechnician(
            UUID dealershipId,
            String qualificationCode,
            OffsetDateTime startTime,
            OffsetDateTime endTime
    ) {
        entityManager.flush();
        Set<UUID> inspectedTechnicianIds = new HashSet<>();

        // Fast path: non-blocking SKIP LOCKED preferring least-specialized qualified technician
        while (true) {
            Optional<UUID> candidateId = selectUnlockedCandidateTechnicianId(
                    dealershipId, qualificationCode, startTime, endTime, inspectedTechnicianIds);
            if (candidateId.isEmpty()) {
                break;
            }
            UUID techId = candidateId.get();
            if (isTechnicianStillFreeUnderFreshSnapshot(techId, startTime, endTime)) {
                return loadManagedTechnicianWithSkills(techId);
            }
            inspectedTechnicianIds.add(techId);
        }

        // Contention fallback: lock active qualified technicians in deterministic ID order
        List<UUID> lockedTechIds = jdbcClient.sql("""
                SELECT t.id
                FROM technicians t
                WHERE t.dealership_id = :dealershipId
                  AND t.is_active = TRUE
                  AND EXISTS (
                      SELECT 1
                      FROM technician_skills ts
                      WHERE ts.technician_id = t.id
                        AND ts.qualification_code = :qualificationCode
                  )
                ORDER BY t.id ASC
                FOR UPDATE OF t
                """)
                .param("dealershipId", dealershipId)
                .param("qualificationCode", qualificationCode)
                .query(UUID.class)
                .list();

        if (lockedTechIds.isEmpty()) {
            return Optional.empty();
        }

        Optional<UUID> fallbackTechId = jdbcClient.sql("""
                SELECT t.id
                FROM technicians t
                WHERE t.dealership_id = :dealershipId
                  AND t.is_active = TRUE
                  AND EXISTS (
                      SELECT 1
                      FROM technician_skills ts
                      WHERE ts.technician_id = t.id
                        AND ts.qualification_code = :qualificationCode
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM appointments a
                      WHERE a.technician_id = t.id
                        AND a.status = 'CONFIRMED'
                        AND tstzrange(a.start_time, a.end_time, '[)')
                            && tstzrange(CAST(:startTime AS TIMESTAMPTZ), CAST(:endTime AS TIMESTAMPTZ), '[)')
                  )
                ORDER BY (
                    SELECT COUNT(*)
                    FROM technician_skills ts_count
                    WHERE ts_count.technician_id = t.id
                ) ASC, t.employee_code ASC, t.id ASC
                LIMIT 1
                """)
                .param("dealershipId", dealershipId)
                .param("qualificationCode", qualificationCode)
                .param("startTime", startTime)
                .param("endTime", endTime)
                .query(UUID.class)
                .optional();

        return fallbackTechId.flatMap(this::loadManagedTechnicianWithSkills);
    }

    @Override
    public List<Appointment> findDetailedByFilters(
            UUID dealershipId,
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd,
            AppointmentStatus status,
            UUID customerId,
            UUID vehicleId
    ) {
        StringBuilder jpql = new StringBuilder("""
                SELECT a
                FROM Appointment a
                JOIN FETCH a.dealership
                JOIN FETCH a.serviceType
                JOIN FETCH a.serviceBay
                JOIN FETCH a.technician
                WHERE 1 = 1
                """);
        Map<String, Object> params = new HashMap<>();

        if (dealershipId != null) {
            jpql.append(" AND a.dealership.id = :dealershipId");
            params.put("dealershipId", dealershipId);
        }
        if (windowStart != null && windowEnd != null) {
            jpql.append(" AND a.startTime < :windowEnd AND a.endTime > :windowStart");
            params.put("windowStart", windowStart);
            params.put("windowEnd", windowEnd);
        }
        if (status != null) {
            jpql.append(" AND a.status = :status");
            params.put("status", status);
        }
        if (customerId != null) {
            jpql.append(" AND a.customerId = :customerId");
            params.put("customerId", customerId);
        }
        if (vehicleId != null) {
            jpql.append(" AND a.vehicleId = :vehicleId");
            params.put("vehicleId", vehicleId);
        }

        jpql.append(" ORDER BY a.startTime ASC, a.createdAt ASC");

        TypedQuery<Appointment> query = entityManager.createQuery(jpql.toString(), Appointment.class);
        params.forEach(query::setParameter);
        return query.getResultList();
    }

    @Override
    public int countAvailableBays(UUID dealershipId, OffsetDateTime startTime, OffsetDateTime endTime) {
        return jdbcClient.sql("""
                SELECT COUNT(*)
                FROM service_bays sb
                WHERE sb.dealership_id = :dealershipId
                  AND sb.is_active = TRUE
                  AND NOT EXISTS (
                      SELECT 1
                      FROM appointments a
                      WHERE a.service_bay_id = sb.id
                        AND a.status = 'CONFIRMED'
                        AND tstzrange(a.start_time, a.end_time, '[)')
                            && tstzrange(CAST(:startTime AS TIMESTAMPTZ), CAST(:endTime AS TIMESTAMPTZ), '[)')
                  )
                """)
                .param("dealershipId", dealershipId)
                .param("startTime", startTime)
                .param("endTime", endTime)
                .query(Integer.class)
                .single();
    }

    @Override
    public int countAvailableQualifiedTechnicians(
            UUID dealershipId,
            String qualificationCode,
            OffsetDateTime startTime,
            OffsetDateTime endTime
    ) {
        return jdbcClient.sql("""
                SELECT COUNT(*)
                FROM technicians t
                WHERE t.dealership_id = :dealershipId
                  AND t.is_active = TRUE
                  AND EXISTS (
                      SELECT 1
                      FROM technician_skills ts
                      WHERE ts.technician_id = t.id
                        AND ts.qualification_code = :qualificationCode
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM appointments a
                      WHERE a.technician_id = t.id
                        AND a.status = 'CONFIRMED'
                        AND tstzrange(a.start_time, a.end_time, '[)')
                            && tstzrange(CAST(:startTime AS TIMESTAMPTZ), CAST(:endTime AS TIMESTAMPTZ), '[)')
                  )
                """)
                .param("dealershipId", dealershipId)
                .param("qualificationCode", qualificationCode)
                .param("startTime", startTime)
                .param("endTime", endTime)
                .query(Integer.class)
                .single();
    }

    @Override
    public List<ResourceBookingWindow> findConfirmedWindowsOverlapping(
            UUID dealershipId,
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd
    ) {
        return jdbcClient.sql("""
                SELECT a.service_bay_id, a.technician_id, a.start_time, a.end_time
                FROM appointments a
                WHERE a.dealership_id = :dealershipId
                  AND a.status = 'CONFIRMED'
                  AND tstzrange(a.start_time, a.end_time, '[)')
                      && tstzrange(CAST(:windowStart AS TIMESTAMPTZ), CAST(:windowEnd AS TIMESTAMPTZ), '[)')
                ORDER BY a.start_time ASC
                """)
                .param("dealershipId", dealershipId)
                .param("windowStart", windowStart)
                .param("windowEnd", windowEnd)
                .query((rs, rowNum) -> new ResourceBookingWindow(
                        rs.getObject("service_bay_id", UUID.class),
                        rs.getObject("technician_id", UUID.class),
                        rs.getObject("start_time", OffsetDateTime.class),
                        rs.getObject("end_time", OffsetDateTime.class)
                ))
                .list();
    }

    private Optional<UUID> selectUnlockedCandidateBayId(
            UUID dealershipId,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            Set<UUID> excludedIds
    ) {
        String exclusionFilter = excludedIds.isEmpty() ? "" : " AND sb.id NOT IN (:excludedIds)";
        String sql = """
                SELECT sb.id
                FROM service_bays sb
                WHERE sb.dealership_id = :dealershipId
                  AND sb.is_active = TRUE
                """ + exclusionFilter + """
                  AND NOT EXISTS (
                      SELECT 1
                      FROM appointments a
                      WHERE a.service_bay_id = sb.id
                        AND a.status = 'CONFIRMED'
                        AND tstzrange(a.start_time, a.end_time, '[)')
                            && tstzrange(CAST(:startTime AS TIMESTAMPTZ), CAST(:endTime AS TIMESTAMPTZ), '[)')
                  )
                ORDER BY sb.bay_number ASC, sb.id ASC
                FOR UPDATE OF sb SKIP LOCKED
                LIMIT 1
                """;

        JdbcClient.StatementSpec spec = jdbcClient.sql(sql)
                .param("dealershipId", dealershipId)
                .param("startTime", startTime)
                .param("endTime", endTime);
        if (!excludedIds.isEmpty()) {
            spec = spec.param("excludedIds", excludedIds);
        }
        return spec.query(UUID.class).optional();
    }

    private boolean isBayStillFreeUnderFreshSnapshot(
            UUID bayId,
            OffsetDateTime startTime,
            OffsetDateTime endTime
    ) {
        return jdbcClient.sql("""
                SELECT NOT EXISTS (
                    SELECT 1
                    FROM appointments a
                    WHERE a.service_bay_id = :bayId
                      AND a.status = 'CONFIRMED'
                      AND tstzrange(a.start_time, a.end_time, '[)')
                          && tstzrange(CAST(:startTime AS TIMESTAMPTZ), CAST(:endTime AS TIMESTAMPTZ), '[)')
                )
                """)
                .param("bayId", bayId)
                .param("startTime", startTime)
                .param("endTime", endTime)
                .query(Boolean.class)
                .single();
    }

    private Optional<UUID> selectUnlockedCandidateTechnicianId(
            UUID dealershipId,
            String qualificationCode,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            Set<UUID> excludedIds
    ) {
        String exclusionFilter = excludedIds.isEmpty() ? "" : " AND t.id NOT IN (:excludedIds)";
        String sql = """
                SELECT t.id
                FROM technicians t
                WHERE t.dealership_id = :dealershipId
                  AND t.is_active = TRUE
                """ + exclusionFilter + """
                  AND EXISTS (
                      SELECT 1
                      FROM technician_skills ts
                      WHERE ts.technician_id = t.id
                        AND ts.qualification_code = :qualificationCode
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM appointments a
                      WHERE a.technician_id = t.id
                        AND a.status = 'CONFIRMED'
                        AND tstzrange(a.start_time, a.end_time, '[)')
                            && tstzrange(CAST(:startTime AS TIMESTAMPTZ), CAST(:endTime AS TIMESTAMPTZ), '[)')
                  )
                ORDER BY (
                    SELECT COUNT(*)
                    FROM technician_skills ts_count
                    WHERE ts_count.technician_id = t.id
                ) ASC, t.employee_code ASC, t.id ASC
                FOR UPDATE OF t SKIP LOCKED
                LIMIT 1
                """;

        JdbcClient.StatementSpec spec = jdbcClient.sql(sql)
                .param("dealershipId", dealershipId)
                .param("qualificationCode", qualificationCode)
                .param("startTime", startTime)
                .param("endTime", endTime);
        if (!excludedIds.isEmpty()) {
            spec = spec.param("excludedIds", excludedIds);
        }
        return spec.query(UUID.class).optional();
    }

    private boolean isTechnicianStillFreeUnderFreshSnapshot(
            UUID technicianId,
            OffsetDateTime startTime,
            OffsetDateTime endTime
    ) {
        return jdbcClient.sql("""
                SELECT NOT EXISTS (
                    SELECT 1
                    FROM appointments a
                    WHERE a.technician_id = :technicianId
                      AND a.status = 'CONFIRMED'
                      AND tstzrange(a.start_time, a.end_time, '[)')
                          && tstzrange(CAST(:startTime AS TIMESTAMPTZ), CAST(:endTime AS TIMESTAMPTZ), '[)')
                )
                """)
                .param("technicianId", technicianId)
                .param("startTime", startTime)
                .param("endTime", endTime)
                .query(Boolean.class)
                .single();
    }

    private Optional<Technician> loadManagedTechnicianWithSkills(UUID techId) {
        return entityManager.createQuery("""
                        SELECT DISTINCT t
                        FROM Technician t
                        LEFT JOIN FETCH t.qualifications
                        WHERE t.id = :techId
                        """, Technician.class)
                .setParameter("techId", techId)
                .getResultStream()
                .findFirst();
    }
}
