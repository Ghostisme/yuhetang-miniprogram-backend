CREATE TABLE notification_channel_config (
    store_id VARCHAR(64) PRIMARY KEY,
    pc_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    wechat_staff_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    wechat_customer_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sms_staff_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    sms_customer_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    sms_phone VARCHAR(20) NOT NULL DEFAULT '',
    revision INTEGER NOT NULL DEFAULT 1,
    updated_at VARCHAR(40) NOT NULL,
    FOREIGN KEY(store_id) REFERENCES store(id)
);
ALTER TABLE notification_outbox ADD COLUMN provider_message_id VARCHAR(160) NOT NULL DEFAULT '';

UPDATE permission SET name='配置通知渠道与接收人' WHERE code='notifications:write';
