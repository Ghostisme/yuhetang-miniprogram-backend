# 裕和堂预约 Java 后端

Java 17 / Spring Boot 3.5，提供客户预约 API、管理后台接口、用户角色及 RBAC、同品牌门店隔离、门店独立品项、后台实时客服、内容管理、资料导入、微信消息和 PMS 扩展。

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

各店可在原有“品牌与内容 → 养护项目”内联编辑表单中新增专属品项，或覆盖品牌项目的本店名称、时长、介绍、图片、排序与上下架；客户预约、前台代约和老师可服务项目统一读取本店目录。

“客户沟通”按门店接待小程序在线咨询和微信原生客服消息，每 2 秒同步，支持未读提示、历史、回复及处理状态。小程序各咨询入口支持原生按钮直接打开微信会话，门店及预约入口按本店接待，通用入口进入总部；独立保留在线咨询。微信原生消息需配置真实账号和安全回调；详见 [客服与门店品项接入](docs/客服与门店品项接入.md)。

支持资料 XLSX 预检导入、草稿发布、操作审计、通知队列及失败原因、订阅消息和服务号个人微信通知。PMS 提供白名单事件拉取与幂等 ACK，默认关闭；本期无支付、团购券、余额收银、自动排床或实际 ERP/PMS 业务对接。

## 本地运行

需要 Node.js、Java 17 和 Maven。在本仓库执行：

```powershell
powershell -ExecutionPolicy Bypass -File ./tools/start-local.ps1 -Build
```

脚本构建相邻的管理后台仓库，再打包并启动本仓库 JAR。客户 H5 `http://127.0.0.1:8790/`，后台 `http://127.0.0.1:8790/admin`，API 同域。`-Port 18790` 可使用另一个端口；`-Lan` 仅用于局域网联调。

本项目数据库和上传文件位于 `data/`，日志位于 `logs/`，均不入库。旧工程的业务数据不会自动迁移或连接；当前默认独立使用本仓库的 `data/reservation.mv.db`。本地启动不会修改电脑上其他 MySQL 业务库。

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
# 云端前后端分开部署：生成不含管理页面的 API JAR
mvn -B -ntp -Papi-release verify
# 先在相邻管理后台仓库 npm ci && npm run build
mvn -Prelease package
# 或自定义前端产物目录
mvn -Prelease package '-Dfrontend.dist=D:/deploy/admin-dist'
```

Java 集成测试使用独立 H2 内存库，不连接本地业务库。独立 API 产物为 `target-api/reservation-api-1.0.0.jar`；合并预览产物为 `target/reservation-1.0.0.jar`。API 单独开发可用 `mvn spring-boot:run`，此模式网页由 Vite 提供。

当前云端采用 [1Panel 前端与 Java 分开部署](deploy/1panel/README.md)，专用 MySQL 容器仅映射到 `127.0.0.1:3307`，Java 监听 `127.0.0.1:8790`。源码迁移后，本地构建、打包及更新路径见 [三仓库开发与发布](docs/三仓库开发与发布.md)。安装脚本、发布脚本、环境示例及回调配置都在本仓库中维护。

小程序业务 API 为 `booking.yuhetangtcm.com`，管理后台为 `mp-admin.yuhetangtcm.com/admin`；仅微信消息推送回调单独使用 `https://booking.hetuonian.cn/api/wechat/customer-service`。预约处理与到店提醒使用独立模板及字段映射，分别消费用户同意的一次性订阅。小程序运行配置由 API 下发，后台可调整模板映射。

客户资料、服务器、微信权限与模板、正式验证要求见 [部署和微信接入](docs/部署和微信接入.md)、[权限与 PMS 接口](docs/权限与PMS接口.md)、[功能与验收清单](docs/功能与验收清单.md)。本地验证不代表客户微信真机、真实推送、MySQL 部署或提审已完成。


总部可在「角色与权限」直接配置内置店长、前台角色，或新建角色后在「用户管理」分配给指定门店账号。「品牌与内容」的预览、本店项目查看/维护、总部草稿编辑/发布分别授权；原表单及手机预览保留。角色变更使关联账号旧登录立即失效，重新登录后按新权限显示菜单并校验 API。共享角色影响所有关联账号，单店差异授权请使用自定义门店角色。V3 只调整权限名称和分组，不恢复已撤销授权。

消息中心按 PC、微信个人消息、短信三类渠道管理；按门店开关、配置接收人和筛选记录，继续由总部 RBAC 授权。短信默认关闭，腾讯云短信凭证和正式模板配置见 [三类消息通知接入](docs/三类消息通知接入.md)。升级保留已有角色授权和业务数据。

大众点评第一批支持客户与订单 / 预约记录：总部在“扩展与配置”维护门店及本店项目映射，在“资料导入”下载 XLSX 模板、预检并确认导入；客户进入现有客户档案，订单在“预约管理 → 大众点评订单”按门店隔离查看，确认到店时间后再生成本店预约。支持重复去重、版本更新、导入批次和关联预约核对；自动同步适配入口已预留，尚未连接平台。操作及模板字段见 [大众点评数据导入](docs/dianping-integration.md)，独立测试库浏览器回归命令为 `npm run verify:dianping`。
