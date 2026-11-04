# 权限与 PMS 接口

## 账号、角色、权限与门店范围

账号关联角色，角色关联权限项，并区分 `GLOBAL` / `STORE` 数据范围。JWT 仅承载登录身份；服务端每次请求重新核对账号是否启用、角色授权和令牌版本。修改账号或角色会使受影响账号旧令牌失效，需要重新登录。

| 默认角色 | 数据范围 | 主要能力 |
| --- | --- | --- |
| 总部管理员 | 全部门店 | 全模块、内容发布、用户角色、资料导入导出、审计及扩展配置 |
| 门店店长 | 本店 | 本店预约处理、门店资料与预约规则、调理师、客户跟进、门店账号和消息绑定 |
| 门店前台 | 本店 | 本店预约查看、代约、人工确认、状态操作、完整电话查看、客户跟进及提醒查看 |

总部可以创建自定义角色和勾选权限。店长不能授予总部权限、给账号切换其他门店、修改总部角色或提升自己的权限。界面按权限显示入口，API 同时执行权限和门店校验，篡改参数不能取得其他门店订单。

总部默认“全部门店”，调理师按所属门店分组汇总；编辑或调整某位老师所属门店，不会将总部查看范围改成该店。`GET /api/admin/therapists` 的 `storeId` 省略或为空时，总部返回全部门店人员，门店账号仅返回本店；指定门店时仍校验数据范围。总部门店配置默认显示全部门店总览，进入某店后可返回总览。全店视图新增调理师必须明确选择所属门店。

拥有 `therapists:write` 权限的总部账号可在调理师编辑表单调整所属门店；门店账号只读所属门店。保存接口 `PUT /api/admin/therapists` 调店时须携带编辑前的 `originalStoreId`，与数据库当前归属不一致返回 409，避免旧表单把人转回。该老师有待确认、已确认或已到店且已实际分配的预约时返回 409，须先重新分配或完成接待。调店与预约分配共用事务锁；操作写入 `therapist.transfer` 审计，原预约仍归原门店，客户提交时的老师意向保留为原始记录。批量导入不直接转店，须使用编辑表单明确操作。

权限定义、内置角色和初始账号见 `src/main/java/com/yuwatang/reservation/Bootstrap.java`；统一请求检查见 `WebSecurity.java`，数据范围见 `AdminService.java`。同一品牌、同一个数据库按 `store_id` 隔离，不属于每店独立数据库的 SaaS 租户系统。

## 客户身份与资料范围

- 原生使用微信 `code` 登录后取得本系统 JWT，服务端保存微信身份。微信密钥不传给客户端。
- “我的预约”只读取当前微信身份提交的记录，输入同一手机号不能获得另一个微信账号的预约历史。
- 会员、是否到过门店属于本次申请的客户自述，与后台核实的会员标签分开保存。
- 门店客户跟进为本店私有；默认关闭跨店精确查询。总部开启共享开关、且当前角色拥有共享查询权限后，才能按完整手机号查询必要的基础白名单字段。
- 完整手机号查看、导出、权限变更、资料变更和预约操作写入审计。客户接口不返回门店私有跟进记录和内部操作历史。

## API 约定

管理和客户接口使用 `Authorization: Bearer TOKEN`。创建预约使用 `Idempotency-Key` 请求头，8–100 位字母、数字、横线或下划线；同身份同标识重试返回同笔记录，修改请求内容时返回 409。预约变更提交当前 `version`，过期版本返回 409，页面刷新后重试。

| 用途 | 接口 |
| --- | --- |
| 健康检查 | `GET /api/health` |
| 品牌发布内容 | `GET /api/site` |
| 管理员登录 | `POST /api/admin/login` |
| 微信客户登录 | `POST /api/auth/wechat` |
| 客户提交预约 | `POST /api/appointments` |
| 客户查看预约 | `GET /api/me/appointments`；`GET /api/me/appointments/{id}` |
| 客户取消/改约 | `PATCH /api/me/appointments/{id}` |
| 管理端预约与动作 | `GET/POST /api/admin/appointments`；`GET/PATCH /api/admin/appointments/{id}` |
| 管理端调理师 | `GET /api/admin/therapists?storeId=`（总部全量、门店账号本店）；`PUT /api/admin/therapists` |
| XLSX 模板与导入 | `GET /api/admin/imports/{kind}/template`；`POST /api/admin/imports/{kind}?commit=false或true` |

`kind` 为 `members`、`therapists`、`stores`、`services`。上传为 multipart 字段 `file`。先预检，再由用户点击提交；每次最多 5000 条，5MB 内，禁止公式单元格。任一行校验失败时不写入整批资料。门店导入更新既有门店，品项导入进入草稿待发布。

## PMS v1 扩展边界

`PmsPort` 作为后续 PMS 适配接口，`pms_outbox` 与预约变更在同一事务内写入。未来系统可以拉取事件、按事件 ID 幂等消费并确认，避免将一期预约代码直接绑定某个收银或财务产品。

默认 `PMS_ENABLED=false`，接口返回 404。启用需设置 `PMS_ENABLED=true`、至少 32 位随机 `PMS_API_KEY`，并通过 HTTPS 调用。密钥只放在服务端 PMS 集成环境，不能放在小程序或管理网页里。

```http
GET /api/integrations/pms/v1/events?limit=50
X-PMS-Key: YOUR_SERVER_SIDE_KEY
```

响应顶层为 `schemaVersion:1` 和 `events`。每次最多 100 条。事件包含 `id`、`aggregateId`、`aggregateVersion`、`eventType`、`schemaVersion`、`storeId`、`payload`、`createdAt`、`acknowledged`；具体列定义见数据库迁移。

`payload` 包含预约 ID、门店/品项 ID、日期、开始时段、人数、状态、版本、实际分配调理师 ID、创建及更新时间。不会附带手机号、客户备注或健康信息。后续如确需交换其他客户字段，应先明确用途和授权后单独扩展协议。

```http
POST /api/integrations/pms/v1/ack
Content-Type: application/json
X-PMS-Key: YOUR_SERVER_SIDE_KEY

{"eventIds":["EVENT_ID"]}
```

重复 ACK 不会重复执行业务；未 ACK 的事件可再次拉取。外部消费者应先可靠处理再 ACK，按 `event id` 去重，并用预约版本防止旧事件覆盖新状态。一期提供事件队列、拉取、确认、后台状态查看；没有实现具体 PMS 的会员、财务、收银、库存或双向同步。

总部“扩展与配置”可以查看是否启用和待确认事件数，并配置微信模板映射。PMS 密钥不在该页面明文展示。
