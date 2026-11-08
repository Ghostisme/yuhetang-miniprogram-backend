-- Preserve historical appointments without guessing their membership at booking time.
CREATE TABLE appointment_membership (
    appointment_id VARCHAR(64) PRIMARY KEY,
    customer_profile_id VARCHAR(64),
    member_verified BOOLEAN NOT NULL,
    member_grade VARCHAR(60) NOT NULL,
    member_store_id VARCHAR(64),
    service_store_id VARCHAR(64) NOT NULL,
    cross_store BOOLEAN NOT NULL,
    verification_method VARCHAR(24) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    FOREIGN KEY (appointment_id) REFERENCES appointment(id) ON DELETE CASCADE,
    FOREIGN KEY (customer_profile_id) REFERENCES customer_profile(id) ON DELETE SET NULL
);
INSERT INTO appointment_membership(appointment_id,member_verified,member_grade,service_store_id,cross_store,verification_method,created_at)
SELECT id,FALSE,'',store_id,FALSE,'LEGACY_UNKNOWN',created_at FROM appointment;

-- An intent/quote is never a payment or a stored-value ledger entry.
CREATE TABLE reservation_payment (
    id VARCHAR(64) PRIMARY KEY,
    appointment_id VARCHAR(64) NOT NULL UNIQUE,
    service_store_id VARCHAR(64) NOT NULL,
    member_store_id VARCHAR(64),
    amount_fen BIGINT,
    currency VARCHAR(3) NOT NULL,
    channel VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    settlement_status VARCHAR(24) NOT NULL,
    version INTEGER NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    FOREIGN KEY (appointment_id) REFERENCES appointment(id) ON DELETE CASCADE
);
INSERT INTO reservation_payment(id,appointment_id,service_store_id,currency,channel,status,settlement_status,version,created_at,updated_at)
SELECT id,id,store_id,'CNY','UNSELECTED',CASE WHEN status IN ('CANCELLED','NO_SHOW') THEN 'CLOSED' ELSE 'UNPRICED' END,'NOT_STARTED',1,created_at,updated_at FROM appointment;

CREATE TABLE reservation_payment_event (
    id VARCHAR(64) PRIMARY KEY,
    payment_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    version INTEGER NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(100),
    request_hash VARCHAR(64),
    detail_json LONGTEXT NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    UNIQUE(payment_id,actor_id,idempotency_key),
    FOREIGN KEY (payment_id) REFERENCES reservation_payment(id) ON DELETE CASCADE
);
CREATE INDEX ix_reservation_payment_store ON reservation_payment(service_store_id,status);

INSERT INTO permission(code,name,module) VALUES('payments:read','查看本店结算预留','预约管理');
INSERT INTO permission(code,name,module) VALUES('payments:write','维护本店结算预留','预约管理');
INSERT INTO role_permission(role_id,permission_code) SELECT id,'payments:read' FROM app_role WHERE id IN ('hq','manager','frontdesk');
INSERT INTO role_permission(role_id,permission_code) SELECT id,'payments:write' FROM app_role WHERE id IN ('hq','manager');
