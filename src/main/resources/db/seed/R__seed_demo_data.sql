-- Idempotent reference & demo fixtures for local development and Docker Compose evaluation

INSERT INTO dealerships (id, code, name, timezone, open_time, close_time)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'KL-LON-01', 'Keyloop Flagship London West', 'Europe/London', '08:00:00', '18:00:00'),
    ('11111111-1111-1111-1111-111111111112', 'KL-MAN-02', 'Keyloop Manchester EV Hub', 'UTC', '08:00:00', '18:00:00')
ON CONFLICT (id) DO NOTHING;

INSERT INTO service_types (id, code, name, description, duration_minutes, required_qualification)
VALUES
    ('22222222-2222-2222-2222-222222222221', 'OIL_CHANGE', 'Full Synthetic Oil & Filter Service', 'Engine oil drain, OEM filter replacement, and multi-point fluid inspection', 60, 'GENERAL_MAINT'),
    ('22222222-2222-2222-2222-222222222222', 'BRAKE_OVERHAUL', 'Front & Rear Brake Pad and Disc Overhaul', 'Full axle brake disc and pad replacement with hydraulic bleed', 120, 'BRAKE_SPEC'),
    ('22222222-2222-2222-2222-222222222223', 'EV_BATTERY_DIAG', 'High-Voltage EV Battery & Thermal Diagnostic', 'Cell balance telemetry, isolation resistance check, and coolant loop test', 90, 'EV_HIGH_VOLTAGE'),
    ('22222222-2222-2222-2222-222222222224', 'MOT_INSPECTION', 'Statutory Class 4 MOT Inspection', 'DVSA-compliant roadworthiness and emissions inspection', 45, 'MOT_TESTER')
ON CONFLICT (id) DO NOTHING;

INSERT INTO service_bays (id, dealership_id, bay_number, name, is_active)
VALUES
    -- London Flagship: 3 active bays + 1 bay under maintenance
    ('33333333-3333-3333-3333-333333333331', '11111111-1111-1111-1111-111111111111', 'BAY-01', 'Hydraulic Lift 1', TRUE),
    ('33333333-3333-3333-3333-333333333332', '11111111-1111-1111-1111-111111111111', 'BAY-02', 'Hydraulic Lift 2', TRUE),
    ('33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', 'BAY-03', 'EV Insulated Bay 3', TRUE),
    ('33333333-3333-3333-3333-333333333334', '11111111-1111-1111-1111-111111111111', 'BAY-04', 'Calibration Bay 4 (Maintenance)', FALSE),
    -- Manchester EV Hub: 2 active bays
    ('33333333-3333-3333-3333-333333333335', '11111111-1111-1111-1111-111111111112', 'BAY-01', 'Manchester EV Bay 1', TRUE),
    ('33333333-3333-3333-3333-333333333336', '11111111-1111-1111-1111-111111111112', 'BAY-02', 'Manchester EV Bay 2', TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO technicians (id, dealership_id, employee_code, full_name, is_active)
VALUES
    -- London Flagship Technicians
    ('44444444-4444-4444-4444-444444444441', '11111111-1111-1111-1111-111111111111', 'LON-TECH-01', 'Oliver Bennett', TRUE),
    ('44444444-4444-4444-4444-444444444442', '11111111-1111-1111-1111-111111111111', 'LON-TECH-02', 'Amelia Clarke', TRUE),
    ('44444444-4444-4444-4444-444444444443', '11111111-1111-1111-1111-111111111111', 'LON-TECH-03', 'Priya Patel', TRUE),
    ('44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111', 'LON-TECH-04', 'Liam O''Connor', TRUE),
    -- Manchester EV Hub Technicians
    ('44444444-4444-4444-4444-444444444445', '11111111-1111-1111-1111-111111111112', 'MAN-TECH-01', 'Hannah Hughes', TRUE),
    ('44444444-4444-4444-4444-444444444446', '11111111-1111-1111-1111-111111111112', 'MAN-TECH-02', 'Daniel Evans', TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO technician_skills (technician_id, qualification_code)
VALUES
    -- Oliver: General Maintenance specialist
    ('44444444-4444-4444-4444-444444444441', 'GENERAL_MAINT'),
    -- Amelia: General + Brakes
    ('44444444-4444-4444-4444-444444444442', 'GENERAL_MAINT'),
    ('44444444-4444-4444-4444-444444444442', 'BRAKE_SPEC'),
    -- Priya: EV High Voltage + General
    ('44444444-4444-4444-4444-444444444443', 'EV_HIGH_VOLTAGE'),
    ('44444444-4444-4444-4444-444444444443', 'GENERAL_MAINT'),
    -- Liam: MOT Tester + Brakes + EV High Voltage
    ('44444444-4444-4444-4444-444444444444', 'MOT_TESTER'),
    ('44444444-4444-4444-4444-444444444444', 'BRAKE_SPEC'),
    ('44444444-4444-4444-4444-444444444444', 'EV_HIGH_VOLTAGE'),
    -- Manchester EV Hub
    ('44444444-4444-4444-4444-444444444445', 'EV_HIGH_VOLTAGE'),
    ('44444444-4444-4444-4444-444444444445', 'GENERAL_MAINT'),
    ('44444444-4444-4444-4444-444444444446', 'GENERAL_MAINT'),
    ('44444444-4444-4444-4444-444444444446', 'BRAKE_SPEC')
ON CONFLICT (technician_id, qualification_code) DO NOTHING;
