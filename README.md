# 裕和堂预约 Java 后端

Java 17 / Spring Boot 3.5，提供客户预约 API、管理后台接口、用户角色及 RBAC、同品牌门店隔离、内容管理、资料导入、微信消息和 PMS 扩展。

## 三个仓库

将以下仓库放在同一父目录：

| 仓库 | 内容 |
| --- | --- |
| [yuhetang-miniprogram](https://github.com/Ghostisme/yuhetang-miniprogram) | 微信原生小程序 |
| [yuhetang-miniprogram-admin](https://github.com/Ghostisme/yuhetang-miniprogram-admin) | 管理后台、客户 H5 预览及品牌素材 |
| [yuhetang-miniprogram-backend](https://github.com/Ghostisme/yuhetang-miniprogram-backend) | 本仓库，Java API、部署及验收文档 |

## 功能与边界

选门店 → 选项目 → 选日期时段和人数 → 联系信息、意向老师、会员自述 → 待人工确认 → 门店实际分配老师并确认。按开始时段人数配额控制，事务与幂等防重复和超额；改约原子释放并重新占用，回到待确认。客户按登录身份查看预约，不能仅凭手机号读取他人历史。

总部看全部门店，各店账号仅看本店；账号、角色、权限分开维护，角色或账号变化使原令牌失效。调理师归属门店，跨店意向和实际分配被拒绝；转店不搬移历史预约，有进行中的实际分配时须先处理。

支持资料 XLSX 预检导入、草稿发布、操作审计、通知队列及失败原因、订阅消息和服务号个人微信通知。PMS 提供白名单事件拉取与幂等 ACK，默认关闭；本期无支付、团购券、余额收银、自动排床或实际 ERP/PMS 业务对接。

## 本地运行

需要 Node.js、Java 17 和 Maven。在本仓库执行：

```powershell
powershell -ExecutionPolicy Bypass -File ./tools/start-local.ps1 -Build
```

脚本构建相邻的管理后台仓库，再打包并启动本仓库 JAR。客户 H5 `http://127.0.0.1:8790/`，后台 `http://127.0.0.1:8790/admin`，API 同域。`-Port 18790` 可使用另一个端口；`-Lan` 仅用于局域网联调。

本项目数据库和上传文件位于 `data/`，日志位于 `logs/`，均不入库。旧工程的 `data/yuhetang.mv.db` 不会自动迁移或连接；当前默认独立使用 `data/reservation.mv.db`。本地启动不会修改电脑上其他 MySQL 业务库。

| 仅 local 模式账号 | 密码 | 范围 |
| --- | --- | --- |
| admin | Yht@9800dev | 总部全部门店 |
| yuyao | Yht@9800dev | 余姚路店店长 |
| bailemen | Yht@9800dev | 百乐门店店长 |
| frontdesk | Yht@9800dev | 余姚路店受限前台 |

正式环境设置 `APP_MODE=production`、独立 `ADMIN_PASSWORD` 和至少 32 位随机 `JWT_SECRET`，不创建演示门店账号。真实凭证只通过后端环境变量传入；格式见 `.env.example`。

```powershell
powershell -ExecutionPolicy Bypass -File ./tools/stop-local.ps1
```

停止前校验 PID 和本仓库 JAR 路径；数据库和上传文件保留。

## 构建和测试

```powershell
mvn test
# 先在相邻管理后台仓库 npm ci && npm run build
mvn -Prelease package
# 或自定义前端产物目录
mvn -Prelease package '-Dfrontend.dist=D:/deploy/admin-dist'
```

Java 集成测试使用独立 H2 内存库，不连接本地业务库。发布 JAR 为 `target/reservation-1.0.0.jar`；API 单独开发可用 `mvn spring-boot:run`，此模式网页由 Vite 提供。

客户资料、服务器、微信权限与模板、正式验证要求见 [部署和微信接入](docs/部署和微信接入.md)、[权限与 PMS 接口](docs/权限与PMS接口.md)、[功能与验收清单](docs/功能与验收清单.md)。本地验证不代表客户微信真机、真实推送、MySQL 部署或提审已完成。
