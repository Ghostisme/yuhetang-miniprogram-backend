CREATE TABLE dianping_store_mapping (
    external_shop_id VARCHAR(100) PRIMARY KEY,
    external_shop_name VARCHAR(100) NOT NULL,
    store_id VARCHAR(64) NOT NULL,
    revision INT NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    FOREIGN KEY (store_id) REFERENCES store(id)
);
CREATE TABLE dianping_service_mapping (
    external_shop_id VARCHAR(100) NOT NULL,
    external_product_id VARCHAR(100) NOT NULL,
    external_product_name VARCHAR(100) NOT NULL,
    service_id VARCHAR(64) NOT NULL,
    revision INT NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (external_shop_id, external_product_id),
    FOREIGN KEY (external_shop_id) REFERENCES dianping_store_mapping(external_shop_id)
);
CREATE TABLE dianping_import_batch (
    id VARCHAR(64) PRIMARY KEY,
    kind VARCHAR(24) NOT NULL,
    source_name VARCHAR(200) NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    total_count INT NOT NULL,
    created_count INT NOT NULL,
    updated_count INT NOT NULL,
    skipped_count INT NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    actor_name VARCHAR(100) NOT NULL,
    created_at VARCHAR(40) NOT NULL
);
CREATE TABLE dianping_customer_link (
    external_shop_id VARCHAR(100) NOT NULL,
    external_customer_id VARCHAR(100) NOT NULL,
    customer_profile_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (external_shop_id, external_customer_id),
    FOREIGN KEY (external_shop_id) REFERENCES dianping_store_mapping(external_shop_id),
    FOREIGN KEY (customer_profile_id) REFERENCES customer_profile(id)
);
CREATE TABLE dianping_order (
    id VARCHAR(64) PRIMARY KEY,
    external_shop_id VARCHAR(100) NOT NULL,
    external_order_id VARCHAR(100) NOT NULL,
    external_line_id VARCHAR(100) NOT NULL,
    external_product_id VARCHAR(100) NOT NULL,
    store_id VARCHAR(64) NOT NULL,
    service_id VARCHAR(64) NOT NULL,
    service_name VARCHAR(100) NOT NULL,
    customer_name VARCHAR(40) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    order_status VARCHAR(24) NOT NULL,
    amount_fen BIGINT NOT NULL,
    headcount INT NOT NULL,
    booking_date VARCHAR(10) NOT NULL,
    start_time VARCHAR(5) NOT NULL,
    external_created_at VARCHAR(40) NOT NULL,
    external_updated_at VARCHAR(40) NOT NULL,
    payload_json TEXT NOT NULL,
    data_hash VARCHAR(64) NOT NULL,
    appointment_id VARCHAR(64),
    needs_review BOOLEAN NOT NULL DEFAULT FALSE,
    revision INT NOT NULL,
    import_batch_id VARCHAR(64) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    UNIQUE (external_shop_id, external_order_id, external_line_id),
    FOREIGN KEY (external_shop_id) REFERENCES dianping_store_mapping(external_shop_id),
    FOREIGN KEY (store_id) REFERENCES store(id),
    FOREIGN KEY (appointment_id) REFERENCES appointment(id),
    FOREIGN KEY (import_batch_id) REFERENCES dianping_import_batch(id)
);
CREATE INDEX idx_dianping_order_store ON dianping_order(store_id, updated_at);
