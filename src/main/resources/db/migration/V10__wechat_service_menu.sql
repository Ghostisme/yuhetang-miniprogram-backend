CREATE TABLE wechat_service_menu (
    id VARCHAR(32) PRIMARY KEY,
    revision INTEGER NOT NULL,
    draft_json TEXT NOT NULL,
    saved_at VARCHAR(40) NOT NULL,
    published_json TEXT,
    published_at VARCHAR(40),
    backup_json TEXT,
    backup_at VARCHAR(40)
);
INSERT INTO wechat_service_menu(id,revision,draft_json,saved_at)
VALUES('default',0,'{"button":[]}','');

INSERT INTO permission(code,name,module) VALUES
('wechat-menu:read','查看服务号菜单（总部）','服务号菜单'),
('wechat-menu:write','编辑服务号菜单草稿（总部）','服务号菜单'),
('wechat-menu:publish','发布服务号菜单（总部）','服务号菜单');
INSERT INTO role_permission(role_id,permission_code)
SELECT r.id,p.code FROM app_role r CROSS JOIN permission p
WHERE r.builtin=TRUE AND r.data_scope='GLOBAL'
AND p.code IN ('wechat-menu:read','wechat-menu:write','wechat-menu:publish');
