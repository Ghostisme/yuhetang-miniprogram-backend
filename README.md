# 裕和堂预约 Java 后端

Java 17 / Spring Boot 3.5，独立提供客户预约 API、管理后台接口、RBAC、门店品项、客服、资料导入与消息通知。

## 三个独立工程

| 仓库 | 技术与职责 | 构建产物 |
| --- | --- | --- |
| [yuhetang-miniprogram](https://github.com/Ghostisme/yuhetang-miniprogram) | Taro 4 + React + TypeScript，客户小程序及 H5 | dist/weapp、dist/h5 |
| [yuhetang-miniprogram-admin](https://github.com/Ghostisme/yuhetang-miniprogram-admin) | React + TypeScript + Vite + Ant Design，管理后台 | dist |
| [yuhetang-miniprogram-backend](https://github.com/Ghostisme/yuhetang-miniprogram-backend) | 本仓库，Java API、数据库迁移、部署工具 | target-api/reservation-api-1.0.0.jar |

每个工程独立安装、启动、构建和发布，不需要相邻仓库存在。后端自带公开媒体素材，不读取后台的 public/media，也不将管理页面或客户 H5 打入 JAR。

## 功能

预约按“门店 → 本店项目 → 日期时段与人数 → 联系资料”提交，等待门店确认。时段配额通过事务和幂等控制；改约原子释放并重新占用，重新进入待确认。客户按登录身份查询预约。

总部与门店按账号、角色、权限和门店范围隔离。门店在原“品牌与内容 → 养护项目”表单维护本店专属品项或品牌品项的本店覆盖；预约、前台代约和调理师可服务项目读取同一本店目录。角色或账号变更使旧令牌失效。

客户价目资料支持按店独立初始化：9 家门店分别建档 135 条品项（126 条上架、9 条待确认下架），价格以各店中文图片为准。切换独立目录后，总部品牌模板的增删和上下架不再改变本店品项，详见 [客户品项独立初始化](docs/客户品项独立初始化.md)。

完成独立品项后，可通过纯数据包补充本地剩余的首页与品牌文案、4 个品牌养护介绍、2 个产品、2 组相册、33 张素材和 27 位示例调理师。只补缺失内容、保留已有修改和未发布草稿，支持预览、备份与重复执行，详见 [本地示例内容补充](docs/本地示例内容补充.md)。

“客户沟通”接待在线咨询和微信原生客服消息；客户端可见时约 5 秒同步并加随机间隔。小程序保留原生 contact 入口与在线咨询入口。原生消息需完成真实账号和安全回调配置，见 [客服接入](docs/客服与门店品项接入.md)。

支持 XLSX 预检导入、草稿发布、审计、通知队列和失败记录。消息中心按 PC、微信个人消息、短信管理；短信默认关闭。客户订阅消息需要真实微信身份和用户主动授权。大众点评第一批导入客户及订单/预约，含门店和品项映射、去重、批次、关联预约；平台自动同步接口已预留，尚未连接大众点评。PMS 扩展默认关闭。

## 本地运行

需要 Java 17 和 Maven。本仓库无需 Node.js：

```powershell
powershell -ExecutionPolicy Bypass -File ./tools/start-local.ps1 -Build
# http://127.0.0.1:8790/api/health
powershell -ExecutionPolicy Bypass -File ./tools/stop-local.ps1
```

脚本只构建和启动当前 Java 工程，默认只监听本机。可用 -Port 18790 更换端口；-Lan 用于局域网联调。停止时校验 PID 与本仓库 JAR，保留数据和上传文件。

数据库默认独立使用本仓库 data/reservation.mv.db，不连接旧工程或其他 MySQL 业务库。本地配置读取本仓库 .env.local；数据库、上传、日志均不提交。

| 仅 local 模式账号 | 密码 | 范围 |
| --- | --- | --- |
| admin | Yht@9800dev | 总部 |
| yuyao | Yht@9800dev | 余姚路店店长 |
| bailemen | Yht@9800dev | 百乐门店店长 |
| frontdesk | Yht@9800dev | 余姚路店受限前台 |

管理后台另在 admin 仓库运行 npm run dev，访问 http://127.0.0.1:5181/admin。客户 H5 另在 Taro 仓库运行 npm run dev:h5，访问 http://127.0.0.1:5182。两个开发代理通过 API_PROXY_TARGET 指向 Java 地址。H5 模拟登录须显式开启 TARO_APP_DEV_LOGIN=true，且后端为 local 模式；生产构建禁用模拟登录。

## 测试与独立发布

```powershell
mvn -B -ntp -Papi-release verify
python tools/package-java.py
```

测试使用隔离 H2 内存库。发布脚本检查至少 100 项 Java 测试、空生产初始化、API 包内容及 SHA256，分别生成独立 JAR 和纯 Java Docker 压缩包。Docker 包只包含 JAR、Java 镜像构建及运行工具，不包含 OpenResty、Nginx、数据库安装器、管理后台或证书。发布编号默认精确到秒，也可传 --release-id YYYYMMDD-HHMMSS，不覆盖已有同名包。产物写入忽略的 releases/，不携带业务数据、证书私钥或真实密钥。后台 ZIP 在后台仓库独立生成。

可选的跨工程代理验收须显式传入已构建的静态文件路径；该检查不参与普通构建或打包：

```powershell
python tools/verify-1panel-split.py --frontend-dist D:/deploy/admin-dist
```

当前推荐 [纯 Java Docker 部署与迁移](deploy/docker-java/README.md)：复用已有独立 MySQL 和上传目录，只把 Java 应用切换为 Docker。管理后台和现有网关另行管理，后端发布不操作网站、TLS 或数据库容器。根 Dockerfile 供开发者从本仓库源码构建；发布包的 Dockerfile 直接复制已验证的 JAR，不需要服务器安装 Maven。

此前 [1Panel 首次安装流程](deploy/1panel/README.md) 和 tools/package-1panel.py 保留供历史部署参考，已经运行的服务器不再执行 prepare.sh。升级前备份本项目数据库、上传、环境配置及旧版本，并检查备份可恢复，详见 [三仓库说明](docs/三仓库开发与发布.md)。Docker 化不解决未备案域名被云厂商拦截的问题，公网域名需单独验收。

业务 API 使用 booking.yuhetangtcm.com；管理后台使用 mp-admin.yuhetangtcm.com/admin；仅微信客服回调使用 https://booking.hetuonian.cn/api/wechat/customer-service。正式环境设置 APP_MODE=production、独立 ADMIN_PASSWORD 和 JWT_SECRET，真实凭证仅放服务器环境文件。

模板、品牌、门店、品项和现有规则通过后台/API 调整，详见 [接口更新约定](docs/小程序接口更新约定.md)。客户端页面和原生能力变更仍须编译及发布小程序。

若示例调理师在预约的“对症调理”等项目下无法选择，使用[调理师意向修复](docs/调理师意向修复.md)补齐本店品项关联；纯数据修复无需重新部署 Java 或发布小程序。真实员工的可服务项目由后台维护。

## 验收范围

本地构建、隔离库联调和代理验证不能替代云端 MySQL、1Panel、HTTPS、微信真机登录、客服/订阅送达及审核。5 万同时在线尚需目标云环境压测，当前检查未验证该容量。

其他资料：[消息通知](docs/三类消息通知接入.md)、[大众点评导入](docs/dianping-integration.md)、[权限与 PMS](docs/权限与PMS接口.md)、[功能清单](docs/功能与验收清单.md)。历史文档涉及合并工程路径时，以本页和三仓库说明为准。
