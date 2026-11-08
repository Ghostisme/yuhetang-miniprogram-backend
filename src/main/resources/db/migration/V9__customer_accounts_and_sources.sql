CREATE TABLE customer_account (
    identity_id VARCHAR(64) PRIMARY KEY,
    customer_profile_id VARCHAR(64),
    display_name VARCHAR(80) NOT NULL DEFAULT '',
    profile_consent_at VARCHAR(40),
    phone_verified_at VARCHAR(40),
    first_login_at VARCHAR(40) NOT NULL,
    last_login_at VARCHAR(40) NOT NULL,
    FOREIGN KEY (identity_id) REFERENCES customer_identity(id) ON DELETE CASCADE,
    FOREIGN KEY (customer_profile_id) REFERENCES customer_profile(id) ON DELETE SET NULL
);
CREATE INDEX idx_customer_account_profile ON customer_account(customer_profile_id);
INSERT INTO customer_account(identity_id,display_name,first_login_at,last_login_at)
SELECT id,'',created_at,created_at FROM customer_identity;

CREATE TABLE customer_source (
    customer_profile_id VARCHAR(64) NOT NULL,
    source VARCHAR(24) NOT NULL,
    first_seen_at VARCHAR(40) NOT NULL,
    last_seen_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (customer_profile_id,source),
    FOREIGN KEY (customer_profile_id) REFERENCES customer_profile(id) ON DELETE CASCADE
);
INSERT INTO customer_source(customer_profile_id,source,first_seen_at,last_seen_at)
SELECT cp.id,sc.source,MIN(cp.created_at),MAX(cp.created_at)
FROM customer_profile cp JOIN store_customer sc ON sc.customer_profile_id=cp.id
WHERE sc.source IN ('MINIPROGRAM','DIANPING','WECHAT','PHONE','WALKIN','IMPORT')
GROUP BY cp.id,sc.source;
INSERT INTO customer_source(customer_profile_id,source,first_seen_at,last_seen_at)
SELECT DISTINCT cp.id,'DIANPING',cp.created_at,cp.created_at
FROM customer_profile cp JOIN dianping_customer_link dl ON dl.customer_profile_id=cp.id
WHERE NOT EXISTS (SELECT 1 FROM customer_source s WHERE s.customer_profile_id=cp.id AND s.source='DIANPING');
INSERT INTO customer_source(customer_profile_id,source,first_seen_at,last_seen_at)
SELECT cp.id,a.source,MIN(a.created_at),MAX(a.created_at)
FROM customer_profile cp JOIN appointment a ON a.phone=cp.phone
WHERE a.source IN ('MINIPROGRAM','DIANPING','WECHAT','PHONE','WALKIN')
AND NOT EXISTS (SELECT 1 FROM customer_source s WHERE s.customer_profile_id=cp.id AND s.source=a.source)
GROUP BY cp.id,a.source;
INSERT INTO customer_source(customer_profile_id,source,first_seen_at,last_seen_at)
SELECT cp.id,'DIANPING',MIN(o.created_at),MAX(o.updated_at)
FROM customer_profile cp JOIN dianping_order o ON o.phone=cp.phone
WHERE NOT EXISTS (SELECT 1 FROM customer_source s WHERE s.customer_profile_id=cp.id AND s.source='DIANPING')
GROUP BY cp.id;

CREATE TABLE customer_phone_receipt (
    code_hash VARCHAR(64) PRIMARY KEY,
    identity_id VARCHAR(64) NOT NULL,
    customer_profile_id VARCHAR(64) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    FOREIGN KEY (identity_id) REFERENCES customer_identity(id) ON DELETE CASCADE,
    FOREIGN KEY (customer_profile_id) REFERENCES customer_profile(id) ON DELETE CASCADE
);
