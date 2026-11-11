CREATE TABLE member_wallet (
    id VARCHAR(64) PRIMARY KEY,
    customer_profile_id VARCHAR(64) NOT NULL,
    store_id VARCHAR(64) NOT NULL,
    external_account VARCHAR(100) NOT NULL,
    label VARCHAR(80) NOT NULL,
    balance_fen BIGINT NOT NULL,
    version INTEGER NOT NULL,
    opening_as_of VARCHAR(40) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    UNIQUE (store_id, external_account),
    FOREIGN KEY (customer_profile_id) REFERENCES customer_profile(id),
    FOREIGN KEY (store_id) REFERENCES store(id),
    CHECK (balance_fen >= 0)
);
CREATE INDEX ix_member_wallet_customer ON member_wallet(customer_profile_id,store_id);
CREATE TABLE member_wallet_entry (
    id VARCHAR(64) PRIMARY KEY,
    wallet_id VARCHAR(64) NOT NULL,
    entry_version INTEGER NOT NULL,
    kind VARCHAR(16) NOT NULL,
    delta_fen BIGINT NOT NULL,
    balance_after_fen BIGINT NOT NULL,
    business_at VARCHAR(40) NOT NULL,
    reference_no VARCHAR(100) NOT NULL,
    note VARCHAR(300) NOT NULL,
    reversal_of VARCHAR(64) UNIQUE,
    request_key VARCHAR(80) NOT NULL UNIQUE,
    request_hash VARCHAR(64) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    operator_name VARCHAR(80) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    UNIQUE (wallet_id,entry_version),
    FOREIGN KEY (wallet_id) REFERENCES member_wallet(id),
    FOREIGN KEY (reversal_of) REFERENCES member_wallet_entry(id),
    CHECK (balance_after_fen >= 0)
);
CREATE TABLE member_service_binding (
    app_id VARCHAR(64) NOT NULL,
    openid VARCHAR(160) NOT NULL,
    customer_profile_id VARCHAR(64) NOT NULL,
    identity_id VARCHAR(64) NOT NULL,
    phone_snapshot VARCHAR(20) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (app_id,openid),
    UNIQUE (app_id,customer_profile_id),
    FOREIGN KEY (customer_profile_id) REFERENCES customer_profile(id) ON DELETE CASCADE,
    FOREIGN KEY (identity_id) REFERENCES customer_identity(id) ON DELETE CASCADE
);
CREATE TABLE member_service_code (
    code_hash VARCHAR(64) PRIMARY KEY,
    app_id VARCHAR(64) NOT NULL,
    identity_id VARCHAR(64) NOT NULL,
    customer_profile_id VARCHAR(64) NOT NULL,
    phone_snapshot VARCHAR(20) NOT NULL,
    expires_epoch BIGINT NOT NULL,
    used BOOLEAN NOT NULL,
    FOREIGN KEY (identity_id) REFERENCES customer_identity(id) ON DELETE CASCADE,
    FOREIGN KEY (customer_profile_id) REFERENCES customer_profile(id) ON DELETE CASCADE
);
CREATE TABLE member_service_receipt (
    receipt_key VARCHAR(64) PRIMARY KEY,
    reply_text LONGTEXT NOT NULL,
    expires_epoch BIGINT NOT NULL
);
INSERT INTO permission VALUES('wallets:read','查看本店会员余额与流水','会员余额');
INSERT INTO permission VALUES('wallets:write','登记本店会员充值与消费','会员余额');
INSERT INTO permission VALUES('wallets:opening','录入期初余额与冲正（总部）','会员余额');
INSERT INTO role_permission(role_id,permission_code)
SELECT id,'wallets:read' FROM app_role WHERE id IN ('hq','manager','frontdesk');
INSERT INTO role_permission(role_id,permission_code)
SELECT id,'wallets:write' FROM app_role WHERE id IN ('hq','manager');
INSERT INTO role_permission(role_id,permission_code) SELECT id,'wallets:opening' FROM app_role WHERE id='hq';
