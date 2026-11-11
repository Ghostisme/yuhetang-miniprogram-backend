-- Keep module visibility and configuration maintenance independently assignable.
INSERT INTO permission(code,name,module)
SELECT 'integrations:read','查看扩展与配置（总部）','扩展与配置'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code='integrations:read');
UPDATE permission SET name='查看扩展与配置（总部）',module='扩展与配置'
WHERE code='integrations:read';

INSERT INTO permission(code,name,module)
SELECT 'integrations:write','维护扩展与配置（总部）','扩展与配置'
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code='integrations:write');

-- Upgrade the built-in recovery role only. Custom and branch roles must opt in;
-- an explicitly revoked read permission must not be restored during upgrade.
INSERT INTO role_permission(role_id,permission_code)
SELECT r.id,'integrations:write' FROM app_role r
WHERE r.id='hq' AND r.builtin=TRUE AND r.data_scope='GLOBAL'
AND EXISTS (SELECT 1 FROM role_permission p WHERE p.role_id=r.id AND p.permission_code='integrations:read')
AND NOT EXISTS (SELECT 1 FROM role_permission p WHERE p.role_id=r.id AND p.permission_code='integrations:write');
