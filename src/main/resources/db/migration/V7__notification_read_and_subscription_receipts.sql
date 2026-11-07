-- Personal read state never changes another account's inbox.
CREATE TABLE notification_read (
    admin_user_id VARCHAR(64) NOT NULL,
    notification_id VARCHAR(64) NOT NULL,
    read_at VARCHAR(40) NOT NULL,
    PRIMARY KEY(admin_user_id,notification_id)
);
CREATE INDEX idx_outbox_inbox ON notification_outbox(channel,delivery_status,created_at,id);
CREATE INDEX idx_outbox_store_inbox ON notification_outbox(store_id,channel,delivery_status,created_at,id);
CREATE INDEX idx_outbox_customer_messages ON notification_outbox(recipient_id,channel,created_at,id);

-- A retried authorization acknowledgement must not create another allowance.
CREATE TABLE subscription_receipt (
    customer_id VARCHAR(64) NOT NULL,
    template_id VARCHAR(160) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    PRIMARY KEY(customer_id,template_id,request_id)
);
