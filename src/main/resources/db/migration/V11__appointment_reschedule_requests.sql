CREATE TABLE appointment_reschedule (
    id VARCHAR(64) PRIMARY KEY,
    appointment_id VARCHAR(64) NOT NULL,
    request_version INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    original_date VARCHAR(10) NOT NULL,
    original_time VARCHAR(5) NOT NULL,
    original_headcount INTEGER NOT NULL,
    original_status VARCHAR(24) NOT NULL,
    requested_date VARCHAR(10) NOT NULL,
    requested_time VARCHAR(5) NOT NULL,
    requested_headcount INTEGER NOT NULL,
    request_note VARCHAR(500) NOT NULL,
    resolution_note VARCHAR(500) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    expires_at VARCHAR(40) NOT NULL,
    resolved_at VARCHAR(40),
    UNIQUE(appointment_id,request_version),
    FOREIGN KEY(appointment_id) REFERENCES appointment(id) ON DELETE CASCADE
);
CREATE INDEX ix_reschedule_pending ON appointment_reschedule(status,expires_at);
