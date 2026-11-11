-- Public consumption details stay separate from internal reconciliation notes.
CREATE TABLE member_consumption_detail (
    entry_id VARCHAR(64) PRIMARY KEY,
    project_name VARCHAR(120) NOT NULL,
    public_note VARCHAR(300) NOT NULL,
    FOREIGN KEY (entry_id) REFERENCES member_wallet_entry(id) ON DELETE CASCADE
);

-- Legacy orders are historical evidence only; importing one never changes a wallet.
CREATE TABLE customer_consumption_history (
    id VARCHAR(64) PRIMARY KEY,
    customer_profile_id VARCHAR(64) NOT NULL,
    store_id VARCHAR(64) NOT NULL,
    reference_no VARCHAR(100) NOT NULL,
    project_name VARCHAR(120) NOT NULL,
    paid_fen BIGINT NOT NULL,
    business_at VARCHAR(40) NOT NULL,
    public_note VARCHAR(300) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version INTEGER NOT NULL,
    request_key VARCHAR(80) NOT NULL UNIQUE,
    request_hash VARCHAR(64) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    UNIQUE (store_id,reference_no),
    FOREIGN KEY (customer_profile_id) REFERENCES customer_profile(id) ON DELETE CASCADE,
    FOREIGN KEY (store_id) REFERENCES store(id),
    CHECK (paid_fen > 0),
    CHECK (status IN ('RECORDED','VOIDED'))
);
CREATE INDEX ix_consumption_history_customer ON customer_consumption_history(customer_profile_id,store_id,business_at);
