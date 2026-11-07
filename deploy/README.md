# 生产部署

当前服务器已清空并重装 Ubuntu 26.04 LTS。按用户最新要求，采用 [1Panel：前端与 Java 分开部署](1panel/README.md)：两个独立发布包、静态网站、独立 Java 17 API 和专用 Docker MySQL。当前本地使用的 `data/reservation.mv.db` 不会由这些配置迁移、覆盖或导入，服务器业务资料由用户之后逐步导入。

本次 AppID 为 `wxce054e18303efb8a`，公网 IP 沿用 `43.142.125.157`，以腾讯云控制台实际值为准。后台为 `mp-admin.yuhetangtcm.com/admin`，API 为 `booking.yuhetangtcm.com`。使用 `yuhetang-admin-20261107.zip` 和 `yuhetang-java-api-1panel-20261107.tar.gz`。API 绑定 127.0.0.1:8790，监控 127.0.0.1:8791，MySQL 127.0.0.1:3307；面板 OpenResty 提供公网 HTTPS。旧 TencentOS、宝塔路径、合并部署包和下列 Compose 模板不用于本次安装。云端 1Panel、MySQL 容器、证书和真实微信发送仍待安装后验收。

## 容量规划口径

需求口径是 **5 万人同时在线**。当前实现加入了预约并发保护、请求限流、数据库连接池及通知队列协调，但本地正确性验证不能证明达到这个容量。当前 8GB 单机及有限带宽作为功能联调起点；应用实例、数据库和 COS/CDN 等资源应按在线用户的请求频率、读写比例和静态资源流量规划，再在目标云环境逐级压测。具体证据和扩容条件见 [并发容量与上线说明](../docs/并发容量与上线说明.md)。

下列 Compose 是应用与 MySQL 同机运行的起步模板，不会自动连接云数据库，也不会自动接入 COS/CDN。采用独立云数据库时，需改为真实内网 JDBC 地址和专用账号，移除对本机 MySQL 服务的启动依赖；备份方式相应改为云数据库备份与单独的文件备份。公网连接使用正式 HTTPS。云资源接入和本地业务数据迁移应另行验证。

## 起步部署

需要 Linux、Docker Engine、支持 `!reset` 的较新 Docker Compose，以及已配置域名和证书的 HTTPS 入口。已离线校验的 Compose 版本为 2.40.2。项目目录执行：

```bash
cd deploy
cp .env.example .env
chmod 600 .env
# 编辑 .env：填写域名、独立数据库密码、独立管理员密码和至少 32 字符的随机 JWT_SECRET。
# 微信和短信参数只有在正式渠道接入后才填写。
docker compose config --quiet
docker compose build
docker compose up -d
docker compose ps
curl --fail http://127.0.0.1:8080/api/health
```

默认只在本机 `127.0.0.1:8080` 提供 HTTP，由主机 HTTPS 反向代理转发。直接接腾讯云 CLB 时，将端口绑定改为该主机实际内网 IP，并用安全组只允许 CLB 源访问。小程序的 `apiBase` 使用已备案、配置合法域名的正式 HTTPS 地址。8790、9001 和 MySQL 3306 不映射公网。

必须按真实网络设置 `trusted-proxies.conf` 的 `set_real_ip_from`，仅填写实际 HTTPS 代理或 CLB 的源 IP/CIDR；上游代理必须覆盖客户端提交的 `X-Forwarded-For`。同机 HTTPS 代理经过 Docker 时，Nginx 看到的源地址可能是网桥网关，应按实际网络核对。缺省不信任任何转发源，避免伪造来源；未配置真实来源时，所有访问可能共用代理 IP 的限流额度。不能填 `0.0.0.0/0` 或 `::/0`。Nginx 随后覆盖转发给 Java 的来源头。Java 仅信任私网代理，部署到不同网络时用 `TRUSTED_PROXY_REGEX` 精确指定，API 端口始终仅限私网代理访问。

默认 MySQL 只创建独立的 `yuwatang_reservation` 库和专用账号，不引用宿主机其他 MySQL 数据目录。数据库卷 `mysql-data` 与上传卷 `uploads` 持久保存；不要执行 `docker compose down -v`。第一次启动自动运行 Flyway V1–V6；有业务数据的升级应先备份，并为建索引、迁移和回滚安排维护窗口。

镜像以非 root 用户运行，JVM 内存受容器限制，MySQL 设置连接和缓存上限。健康检查同时验证数据库连通；Nginx 等待 API 健康后启动。单机默认预算为 API 1.6GB、MySQL 3GB、Nginx 256MB，另需为系统、文件缓存和备份留内存。它是启动配置，实际容量应按访问峰值、业务比例和公网带宽验证。

## 第二个后端实例

```bash
docker compose -f compose.yml -f compose.scale.yml config --quiet
docker compose -f compose.yml -f compose.scale.yml up -d --build
```

两个 API 使用同一数据库、相同 JWT_SECRET 和同一上传卷，Nginx 按连接数分配请求。预约保护和任务领取消费均通过数据库协调，登录与限流不依赖会话粘滞。同机双实例仍然共享 CPU、磁盘和主机故障风险，也会将数据库连接池总上限从 24 提到 48；它不等于主机高可用。4 核 8G 同时跑双 API 和数据库需要实测内存与 CPU 余量。

跨主机扩容前，将 MySQL 移至独立的高可用云数据库，改用内网连接和专用账号，所有实例共享 JWT_SECRET；上传文件需改为 COS/共享文件存储，静态资源经 CDN 分发。当前 `uploads` 卷只在同一 Docker 主机共享，尚未实现 COS 上传适配。多台主机的环境变量、数据库地址、网络、安全组及挂载需要按实际基础设施配置；本目录没有自动创建腾讯云资源。

## 健康、监控与备份

- `/api/health/live` 验证进程存活，不访问数据库；`/api/health` 验证数据库连通。
- 生产 Actuator 在私网 9001 端口暴露 `/actuator/health` 与 `/actuator/prometheus`，不经公网 Nginx 转发。通过私网监控采集 HTTP 延迟、状态码、Hikari 使用率、JVM 内存、GC、线程和 `app_admission_active` / `app_admission_rejected_total`。
- 持续观察 429/503 比例、预约冲突率、通知积压、MySQL CPU/慢查询/锁等待、磁盘空间、上传量和公网带宽。503 是保护性拒绝，应保留原预约提交标识后重试，不能视作容量验收成功。
- 微信、短信外部调用由有上限的后台线程发送；发送结果不明确时记录 UNKNOWN，先核实平台状态，避免重复扣费或重复通知。后台任务使用数据库租约、发送领取和订阅次数原子预留协调多实例。

Linux 上执行备份：

```bash
bash backup.sh /srv/backups/yuwatang
```

脚本只导出 `yuwatang_reservation`，使用一致性事务快照，并备份上传目录、验证归档、生成 SHA256。备份文件可能包含业务资料，应保存在权限受控位置并另存异地。此脚本需 Docker 正常运行；本轮没有实际执行容器备份或恢复演练。正式上线前必须在隔离实例验证恢复；不要直接在现有业务库试恢复。数据库备份与文件归档并非同一跨资源事务，要求严格一致时应暂停上传和相关写入后备份。

本地 H2 数据迁移到 MySQL 需另做一次有记录的数据迁移、数量及业务核对；不能把 H2 文件直接挂到 MySQL，也不能把“大众点评客户及订单导入”当作完整系统数据迁移。

## 验收边界

详见 [并发容量与上线说明](../docs/并发容量与上线说明.md)。本轮已验证 Java 回归、独立 MySQL 并发及两个 API 共用数据库的 HTTP 正确性；容器启动、Nginx 运行、云端压测、证书、真实微信/短信发送、故障切换和完整备份恢复仍需在正式基础设施上验收。
