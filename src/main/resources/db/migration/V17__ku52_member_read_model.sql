-- The source read model never writes to the authoritative wallet or its ledger.
CREATE TABLE ku52_sync_job (
    customer_profile_id VARCHAR(64) PRIMARY KEY,
    state VARCHAR(24) NOT NULL,
    next_due BIGINT NOT NULL,
    lease_until BIGINT NOT NULL,
    lease_token VARCHAR(64) NOT NULL,
    last_attempt VARCHAR(40) NOT NULL,
    last_success VARCHAR(40) NOT NULL,
    last_error VARCHAR(40) NOT NULL,
    source_total_fen BIGINT,
    history_complete BOOLEAN NOT NULL,
    FOREIGN KEY (customer_profile_id) REFERENCES customer_profile(id) ON DELETE CASCADE
);
CREATE INDEX ix_ku52_sync_due ON ku52_sync_job(next_due,lease_until);
CREATE TABLE ku52_member_card (
    id VARCHAR(64) PRIMARY KEY,
    customer_profile_id VARCHAR(64) NOT NULL,
    source_key VARCHAR(100) NOT NULL UNIQUE,
    store_id VARCHAR(64) NOT NULL,
    member_number VARCHAR(100) NOT NULL,
    card_number VARCHAR(100) NOT NULL,
    label VARCHAR(80) NOT NULL,
    balance_fen BIGINT NOT NULL,
    discount INTEGER NOT NULL,
    validity VARCHAR(100) NOT NULL,
    source_updated_at VARCHAR(40) NOT NULL,
    FOREIGN KEY (customer_profile_id) REFERENCES customer_profile(id) ON DELETE CASCADE,
    FOREIGN KEY (store_id) REFERENCES store(id),
    UNIQUE(store_id,card_number),
    CHECK (balance_fen >= 0)
);
CREATE INDEX ix_ku52_card_customer ON ku52_member_card(customer_profile_id,store_id);
CREATE TABLE ku52_consumption_record (
    id VARCHAR(64) PRIMARY KEY,
    customer_profile_id VARCHAR(64) NOT NULL,
    source_key VARCHAR(100) NOT NULL UNIQUE,
    store_id VARCHAR(64) NOT NULL,
    reference_no VARCHAR(100) NOT NULL,
    project_name VARCHAR(120) NOT NULL,
    paid_fen BIGINT NOT NULL,
    business_at VARCHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL,
    source_updated_at VARCHAR(40) NOT NULL,
    FOREIGN KEY (customer_profile_id) REFERENCES customer_profile(id) ON DELETE CASCADE,
    FOREIGN KEY (store_id) REFERENCES store(id),
    UNIQUE(store_id,reference_no),
    CHECK (paid_fen >= 0)
);
CREATE INDEX ix_ku52_consumption_customer ON ku52_consumption_record(customer_profile_id,store_id,business_at);
