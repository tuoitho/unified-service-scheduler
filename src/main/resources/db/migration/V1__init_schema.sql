CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE dealerships (
    id          UUID         PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(150) NOT NULL,
    timezone    VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    open_time   TIME         NOT NULL DEFAULT '08:00:00',
    close_time  TIME         NOT NULL DEFAULT '18:00:00',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_dealership_hours CHECK (open_time < close_time)
);

CREATE TABLE service_types (
    id                      UUID         PRIMARY KEY,
    code                    VARCHAR(50)  NOT NULL UNIQUE,
    name                    VARCHAR(120) NOT NULL,
    description             TEXT,
    duration_minutes        INTEGER      NOT NULL,
    required_qualification  VARCHAR(50)  NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_service_duration CHECK (duration_minutes > 0 AND duration_minutes <= 480)
);

CREATE TABLE service_bays (
    id             UUID         PRIMARY KEY,
    dealership_id  UUID         NOT NULL REFERENCES dealerships(id) ON DELETE CASCADE,
    bay_number     VARCHAR(20)  NOT NULL,
    name           VARCHAR(100) NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_dealership_bay_number UNIQUE (dealership_id, bay_number),
    CONSTRAINT uq_service_bay_dealership UNIQUE (id, dealership_id)
);

CREATE INDEX idx_service_bays_dealership_active
    ON service_bays (dealership_id, bay_number)
    WHERE is_active = TRUE;

CREATE TABLE technicians (
    id             UUID         PRIMARY KEY,
    dealership_id  UUID         NOT NULL REFERENCES dealerships(id) ON DELETE CASCADE,
    employee_code  VARCHAR(50)  NOT NULL UNIQUE,
    full_name      VARCHAR(120) NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_technician_dealership UNIQUE (id, dealership_id)
);

CREATE INDEX idx_technicians_dealership_active
    ON technicians (dealership_id)
    WHERE is_active = TRUE;

CREATE TABLE technician_skills (
    technician_id       UUID        NOT NULL REFERENCES technicians(id) ON DELETE CASCADE,
    qualification_code  VARCHAR(50) NOT NULL,
    certified_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (technician_id, qualification_code)
);

CREATE INDEX idx_technician_skills_qualification
    ON technician_skills (qualification_code, technician_id);

CREATE TABLE appointments (
    id               UUID         PRIMARY KEY,
    dealership_id    UUID         NOT NULL REFERENCES dealerships(id),
    customer_id      UUID         NOT NULL,
    vehicle_id       UUID         NOT NULL,
    service_type_id  UUID         NOT NULL REFERENCES service_types(id),
    service_bay_id   UUID         NOT NULL,
    technician_id    UUID         NOT NULL,
    start_time       TIMESTAMPTZ  NOT NULL,
    end_time         TIMESTAMPTZ  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'CONFIRMED',
    notes            VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    cancelled_at     TIMESTAMPTZ,
    CONSTRAINT fk_appointment_bay_dealership
        FOREIGN KEY (service_bay_id, dealership_id)
        REFERENCES service_bays(id, dealership_id),
    CONSTRAINT fk_appointment_tech_dealership
        FOREIGN KEY (technician_id, dealership_id)
        REFERENCES technicians(id, dealership_id),
    CONSTRAINT chk_appointment_window
        CHECK (end_time > start_time),
    CONSTRAINT chk_appointment_status
        CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    CONSTRAINT excl_appointments_bay_overlap
        EXCLUDE USING gist (
            service_bay_id WITH =,
            tstzrange(start_time, end_time, '[)') WITH &&
        ) WHERE (status = 'CONFIRMED'),
    CONSTRAINT excl_appointments_tech_overlap
        EXCLUDE USING gist (
            technician_id WITH =,
            tstzrange(start_time, end_time, '[)') WITH &&
        ) WHERE (status = 'CONFIRMED'),
    CONSTRAINT excl_appointments_vehicle_overlap
        EXCLUDE USING gist (
            vehicle_id WITH =,
            tstzrange(start_time, end_time, '[)') WITH &&
        ) WHERE (status = 'CONFIRMED')
);

CREATE INDEX idx_appointments_dealership_start
    ON appointments (dealership_id, start_time);

CREATE INDEX idx_appointments_customer
    ON appointments (customer_id, start_time);
