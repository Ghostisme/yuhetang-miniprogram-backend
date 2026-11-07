CREATE TABLE store_service (
    store_id VARCHAR(64) NOT NULL,
    service_id VARCHAR(64) NOT NULL,
    custom BOOLEAN NOT NULL,
    profile_json LONGTEXT NOT NULL,
    revision INTEGER NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    PRIMARY KEY(store_id,service_id),
    FOREIGN KEY(store_id) REFERENCES store(id)
);
CREATE TABLE chat_conversation (
    id VARCHAR(64) PRIMARY KEY,
    openid VARCHAR(160) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    store_id VARCHAR(64) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    status VARCHAR(16) NOT NULL,
    last_preview VARCHAR(200) NOT NULL,
    last_customer_at BIGINT NOT NULL DEFAULT 0,
    reply_deadline BIGINT NOT NULL DEFAULT 0,
    reply_remaining INTEGER NOT NULL DEFAULT 0,
    last_seq BIGINT NOT NULL DEFAULT 0,
    read_seq BIGINT NOT NULL DEFAULT 0,
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    UNIQUE(openid,channel,store_id)
);
CREATE INDEX ix_chat_store ON chat_conversation(store_id,updated_at);
CREATE TABLE chat_message (
    seq BIGINT AUTO_INCREMENT PRIMARY KEY,
    id VARCHAR(64) NOT NULL UNIQUE,
    conversation_id VARCHAR(64) NOT NULL,
    direction VARCHAR(12) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    content LONGTEXT NOT NULL,
    media_id VARCHAR(512),
    delivery_status VARCHAR(20) NOT NULL,
    error_message VARCHAR(500),
    sender_name VARCHAR(80) NOT NULL,
    client_key VARCHAR(100),
    created_at VARCHAR(40) NOT NULL,
    FOREIGN KEY(conversation_id) REFERENCES chat_conversation(id),
    UNIQUE(conversation_id,client_key)
);
CREATE INDEX ix_chat_messages ON chat_message(conversation_id,seq);
CREATE TABLE chat_entry (
    token VARCHAR(80) PRIMARY KEY,
    openid VARCHAR(160) NOT NULL,
    store_id VARCHAR(64) NOT NULL,
    expires_at BIGINT NOT NULL
);
CREATE TABLE chat_route (
    openid VARCHAR(160) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    event_time BIGINT NOT NULL,
    FOREIGN KEY(conversation_id) REFERENCES chat_conversation(id)
);
CREATE TABLE chat_receipt (event_id VARCHAR(160) PRIMARY KEY, received_at VARCHAR(40) NOT NULL);
INSERT INTO app_setting(setting_key,setting_value) VALUES('chatLock','1');
INSERT INTO permission VALUES('services:read','查看本店项目','项目');
INSERT INTO permission VALUES('services:write','维护本店项目','项目');
INSERT INTO permission VALUES('chat:read','查看客户会话','客服');
INSERT INTO permission VALUES('chat:reply','回复客户消息','客服');
INSERT INTO permission VALUES('chat:route','总部客服分流','客服');
INSERT INTO role_permission SELECT id,'services:read' FROM app_role WHERE id IN ('hq','manager','frontdesk') AND builtin=TRUE;
INSERT INTO role_permission SELECT id,'services:write' FROM app_role WHERE id IN ('hq','manager','frontdesk') AND builtin=TRUE;
INSERT INTO role_permission SELECT id,'chat:read' FROM app_role WHERE id IN ('hq','manager','frontdesk') AND builtin=TRUE;
INSERT INTO role_permission SELECT id,'chat:reply' FROM app_role WHERE id IN ('hq','manager','frontdesk') AND builtin=TRUE;
INSERT INTO role_permission SELECT id,'chat:route' FROM app_role WHERE id='hq' AND builtin=TRUE;
