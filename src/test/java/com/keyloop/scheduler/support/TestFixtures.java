package com.keyloop.scheduler.support;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class TestFixtures {

    private TestFixtures() {
    }

    public static UUID insertDealership(
            JdbcClient jdbcClient,
            String code,
            String name,
            String timezone,
            LocalTime openTime,
            LocalTime closeTime
    ) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO dealerships (id, code, name, timezone, open_time, close_time)
                VALUES (:id, :code, :name, :timezone, :openTime, :closeTime)
                """)
                .param("id", id)
                .param("code", code)
                .param("name", name)
                .param("timezone", timezone)
                .param("openTime", openTime)
                .param("closeTime", closeTime)
                .update();
        return id;
    }

    public static UUID insertServiceType(
            JdbcClient jdbcClient,
            String code,
            String name,
            int durationMinutes,
            String requiredQualification
    ) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO service_types (id, code, name, description, duration_minutes, required_qualification)
                VALUES (:id, :code, :name, :description, :durationMinutes, :requiredQualification)
                """)
                .param("id", id)
                .param("code", code)
                .param("name", name)
                .param("description", name + " description")
                .param("durationMinutes", durationMinutes)
                .param("requiredQualification", requiredQualification)
                .update();
        return id;
    }

    public static UUID insertServiceBay(
            JdbcClient jdbcClient,
            UUID dealershipId,
            String bayNumber,
            String name,
            boolean active
    ) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO service_bays (id, dealership_id, bay_number, name, is_active)
                VALUES (:id, :dealershipId, :bayNumber, :name, :active)
                """)
                .param("id", id)
                .param("dealershipId", dealershipId)
                .param("bayNumber", bayNumber)
                .param("name", name)
                .param("active", active)
                .update();
        return id;
    }

    public static UUID insertTechnician(
            JdbcClient jdbcClient,
            UUID dealershipId,
            String employeeCode,
            String fullName,
            boolean active,
            List<String> qualifications
    ) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO technicians (id, dealership_id, employee_code, full_name, is_active)
                VALUES (:id, :dealershipId, :employeeCode, :fullName, :active)
                """)
                .param("id", id)
                .param("dealershipId", dealershipId)
                .param("employeeCode", employeeCode)
                .param("fullName", fullName)
                .param("active", active)
                .update();

        for (String qualification : qualifications) {
            jdbcClient.sql("""
                    INSERT INTO technician_skills (technician_id, qualification_code)
                    VALUES (:technicianId, :qualificationCode)
                    """)
                    .param("technicianId", id)
                    .param("qualificationCode", qualification)
                    .update();
        }
        return id;
    }
}
