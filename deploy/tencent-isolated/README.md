# 腾讯云部署：独立 MySQL 容器，保留原数据库

> 历史流程：适用于重装前的 TencentOS 共享主机。当前已重装 Ubuntu，前端与 Java 分开，使用 [1Panel 安装说明](../1panel/README.md) 和 20261107 两个新包；不要在新服务器执行本页的旧 Nginx 路径或合并部署步骤。

用户已经建立 `/root/yuhetang-upload/tls`，并提供 ext4 分区及实际 Nginx include 路径。**从当前进度继续请使用 [上传后续操作](CONTINUE-FROM-UPLOAD.md)**，命令已与现有目录匹配。

如暂不能扩容，先看 [现有服务器资源与部署判断](../../docs/现有服务器资源与部署判断.md)；最新只读快照已定位到 `/www/server/label-studio` 占 19 GiB，可用内存为 1774 MiB，尚未确认可回收空间及旧业务高峰余量。不要通过降低预检数值直接安装。部署后可单独使用 [凭据导出工具](../../tools/export-tencent-credentials.py)，将实际生成的账号与密钥下载到本地私密记录目录；该工具未改动原发布包。

管理后台使用 `https://mp-admin.yuhetangtcm.com/admin`，小程序 API 与客服回调继续使用 booking。当前包同时包含这两个域名的 Nginx 配置，管理后台和接口由同一个 Java 服务提供；具体操作见 [管理后台域名配置](MP-ADMIN-DOMAIN.md)。

**本版替代之前“在现有 MySQL 5.7 中建库”的步骤。用户确认旧命令尚未执行；无需回滚原数据库，也不需要原数据库的 root 密码。** 不执行旧版 `terminal` / 宝塔包中的建库、授权或启动步骤。

适用：TencentOS Server 3.1 / x86_64，`43.142.125.157`，域名 `booking.yuhetangtcm.com`。后端和后台仍作为独立 Java 17 服务运行，新增数据库使用官方 MySQL 8.4 LTS 容器；兼容现有 Docker 或 Podman。脚本不安装或重启容器引擎，不连接现有宿主 MySQL。

| 项目 | 本次新建的资源 |
| --- | --- |
| 数据库容器 | `yuwatang-mysql-isolated` |
| 数据库地址 | `127.0.0.1:3307`，仅本机开放 |
| 数据库及专用账号 | `yuwatang_reservation` / `yuwatang_app` |
| 数据文件 | `/var/lib/yuwatang-mysql-isolated` |
| 数据库密码与运行配置 | `/etc/yuwatang-reservation-db`，root 专用 |
| 后端服务 | `yuwatang-reservation.service`，仅 `127.0.0.1:8790` |
| 监控 | 仅 `127.0.0.1:8791` |
| 后端环境配置 | `/etc/yuwatang-reservation.env`，root 专用 |
| 上传等应用文件 | `/var/lib/yuwatang-reservation` |
| 备份 | `/var/backups/yuwatang-reservation` |
| 恢复演练 | 新容器 `yuwatang-mysql-restorecheck`，仅本机 `3308`，新目录 `/var/lib/yuwatang-mysql-restorecheck` |

原来的 MySQL 3306、原账号、原数据目录和原服务配置不作为本部署的目标。首次安装遇到同名容器、已有目录或端口占用会退出，不能反复执行安装脚本来覆盖已有安装。

## 1. 先检查资源，再安装

同机容器共享 CPU、磁盘和内存；隔离数据库不能消除资源耗尽或整机故障的影响。数据库限制 1 CPU、1 GiB 内存，Java 服务限制 1 GiB 内存；这不是 5 万人同时在线的容量承诺。

最新快照为可用内存 1774 MiB、根盘仅剩约 2.4 GiB，**不足以稳定部署本版**。如果不能扩容，先按资源判断文档核对可回收文件及运行余量；若不足，再采用扩容或独立资源。不要通过停止现有业务或删除未知文件腾资源。短时快照不能代替旧业务高峰和新服务实测。

本版预检要求根盘至少 **15 GiB 可用**、可用内存至少 **3 GiB**，且 `3307 / 8790 / 8791` 未占用。这只是安装余量门槛，后续仍要监控磁盘增长和真实负载。

在腾讯云控制台 root 终端创建上传目录：

```bash
install -d -o root -g root -m 0700 /root/yuhetang-install
```

将以下两个文件上传到这个目录，证书私钥单独上传，不包含在包中：

- `yuhetang-tencent-host-isolated-20261105-clean.tar.gz`
- `yuhetang-tencent-host-isolated-20261105-clean.tar.gz.sha256`

```bash
cd /root/yuhetang-install
sha256sum --check yuhetang-tencent-host-isolated-20261105-clean.tar.gz.sha256
tar -xzf yuhetang-tencent-host-isolated-20261105-clean.tar.gz
cd yuhetang-tencent-host-isolated-20261105-clean
bash preflight.sh
```

`preflight.sh` 只读检查，不会建库、安装软件、启动容器或修改现有数据库。若提示没有容器引擎或引擎不可用，先按实际主机情况处理；不要同时安装 Docker 和 Podman，或重启正在承载其他业务的引擎。

## 2. 首次准备文件与独立数据库

预检通过后才执行：

```bash
bash prepare.sh
```

此命令校验完整发布包，下载固定摘要的官方 MySQL 8.4 镜像，安装本项目私有 Java 17，创建专用容器、目录、账号密码和 systemd 服务文件。**准备结束时，数据库和 Java 服务都没有启动，也没有设置开机启动；现有 Nginx 未修改。** Docker Hub 下载失败会在安装应用文件前中止；先解决镜像可达性，不要换成来源不明的镜像。

数据库密码和 JWT 密钥由服务器随机生成，保存到 root 专用文件，不需要手动创建数据库或执行宿主机 `mysql -uroot -p`。新库由新容器初始化；新表由应用的 Flyway 迁移创建。正式环境首次启动不预置门店、品项、产品、调理师、客户、预约、会话或素材记录；仅初始化系统表、权限、总部管理员和空站点配置。本地业务库及上传文件不导入。后续由用户在现有“资料导入”入口逐步填写真实资料，顺序见 [上传后续操作](CONTINUE-FROM-UPLOAD.md#6-登录后由用户逐步导入真实资料)。重启保留后续录入的数据。

编辑新配置：

```bash
vi /etc/yuwatang-reservation.env
```

只需先填：

- `ADMIN_PASSWORD`：本系统独立的管理员密码，至少 12 字符；不要使用原数据库密码。
- `WECHAT_APP_SECRET`：小程序 AppSecret，保留在服务器，不要发到聊天。
- `WECHAT_MESSAGE_TOKEN` / `WECHAT_MESSAGE_AES_KEY`：接入原生客服回调时与微信后台保持一致；订阅消息发送不依赖回调开关。

两个订阅消息模板 ID、字段映射和 AppID 已在模板中配置。不修改自动生成的 `DB_PASSWORD`、`JWT_SECRET` 或既定的 `DB_URL`。该 URL 固定连接本机 3307；启动前检查会拒绝 3306、其他库名、其他账号以及绕过检查的 Spring/JVM 配置变量。应用使用 MariaDB JDBC 驱动连接 MySQL，这是驱动名称，不会安装 MariaDB 服务。数据库访问仅走本机回环；以后改为远程数据库时应重新配置 TLS 与专用网络。

## 3. 启动并验证本项目

```bash
systemctl start yuwatang-mysql-isolated.service
/usr/local/libexec/yuwatang-dbctl status
systemctl start yuwatang-reservation.service
systemctl --no-pager --full status yuwatang-reservation.service
curl --fail http://127.0.0.1:8790/api/health
```

数据库服务会等待初始化和就绪检查，失败时不继续启动应用。只检查本项目日志，不粘贴环境文件或密钥到聊天：

```bash
journalctl -u yuwatang-mysql-isolated.service -n 80 --no-pager
journalctl -u yuwatang-reservation.service -n 100 --no-pager
```

登录后台检查门店、预约和权限后，再设置本项目开机启动：

```bash
systemctl enable yuwatang-mysql-isolated.service yuwatang-reservation.service
```

若需要停止本次新增业务，执行下面两条即可；这会保留新库的数据文件：

```bash
systemctl stop yuwatang-reservation.service
systemctl stop yuwatang-mysql-isolated.service
```

不要使用清理所有容器/卷的命令，不要删除任何 MySQL 数据目录。首次安装中途失败时保留现场、先查日志，不通过清空目录重装。

## 4. 证书和 Nginx

将提供的 `fullchain.pem`、`privkey.key` 上传到 `/root/yuhetang-install/` 后执行：

```bash
install -d -o root -g root -m 0700 /etc/yuwatang-reservation/tls
install -o root -g root -m 0644 /root/yuhetang-install/fullchain.pem /etc/yuwatang-reservation/tls/fullchain.pem
install -o root -g root -m 0600 /root/yuhetang-install/privkey.key /etc/yuwatang-reservation/tls/privkey.key
nginx -T 2>&1 | awk '/^# configuration file / {print}'
```

按最后一条命令显示的**实际 Nginx http 配置 include 目录**添加一个新站点；不能假设一定是 `/etc/nginx/conf.d/`。若确实使用该目录，先检查目标不存在，再安装：

```bash
test ! -e /etc/nginx/conf.d/booking.yuhetangtcm.com.conf && test ! -L /etc/nginx/conf.d/booking.yuhetangtcm.com.conf
```

只有上一条成功、并且已确认该目录被 http 配置加载时，才执行：

```bash
if test ! -e /etc/nginx/conf.d/booking.yuhetangtcm.com.conf && test ! -L /etc/nginx/conf.d/booking.yuhetangtcm.com.conf; then
    install -o root -g root -m 0644 nginx.booking.conf.example /etc/nginx/conf.d/booking.yuhetangtcm.com.conf && nginx -t && systemctl reload nginx && curl --fail --resolve booking.yuhetangtcm.com:443:127.0.0.1 https://booking.yuhetangtcm.com/api/health
else
    echo '目标配置已存在，已停止，未覆盖。'
fi
```

如果实际目录不同，将上面**目标配置路径**替换为实际目录的新文件名，并先确认没有同名站点。保留原站点配置和 `/ai/` 路由。若 Nginx 没有 systemd 单元，用该实例原有的 reload 方式。脚本不自动安装 Nginx，不重写全局配置，不停止旧站点。

安全组只需为网站开放 80/443；不开放 3307、3308、8790、8791。证书本地验证覆盖 `booking.yuhetangtcm.com`，到期时间为 **2027-01-22 15:16:02 北京时间**，需提前续期。域名备案、实际公网 HTTPS、小程序合法域名和真机消息推送仍需逐项验收。

## 5. 备份和恢复演练

第一次检查正常后立即备份：

```bash
/usr/local/libexec/yuwatang-dbctl backup
```

备份时会短暂停止**本项目** Java 服务，导出容器内 `yuwatang_reservation` 的 SQL、上传等应用文件、环境与数据库密码配置，并记录各表行数及 SHA256；完成或失败后尝试恢复本项目 Java 服务原来的运行状态。不会停止原 MySQL 或其他应用。备份失败会保留现场并返回非零状态，不能当作有效备份使用。

成功输出的目录类似 `/var/backups/yuwatang-reservation/20261105T120000Z-XXXXXXXX`。其中包含数据库密码、JWT、微信密钥等，**只用受保护通道保存一份加密异机备份**，不要当普通附件发送，也不要上传到公开 OSS。仅存同一块系统盘的备份不能防止整盘故障。

将实际成功输出的目录代入：

```bash
/usr/local/libexec/yuwatang-dbctl restore-check /var/backups/yuwatang-reservation/实际备份目录
```

恢复演练先检查固定备份文件的校验值与压缩包，再创建**全新的** `yuwatang-mysql-restorecheck` 容器、本机 `3308` 端口和新的数据目录。它会导入 SQL、逐表核对记录数，结束时停止测试容器并保留现场。它不会把 SQL 导入原 MySQL，也不会覆盖正式裕和堂库。演练额外要求至少 2 GiB 可用内存和 10 GiB 可用根盘空间；同名测试资源已存在时退出，不自动删除。

这一步验证 SQL 可导入及表行数一致；应用文件和配置归档只校验完整性与可读取性，尚不等于完整业务恢复验收。正式故障恢复应保留故障现场，在新目录/新实例恢复、验证应用读写与文件后再切换；不要直接向已有数据库导入备份。当前包不提供覆盖正式库的一键恢复操作。

备份周期、异机备份和告警需要按运营安排，本安装不会自动启动定时备份、删除历史备份或清理原数据库。

## 6. 本次交付的验证边界

本版重新构建 JAR，完成全量后端测试，并新增 7 项空库初始化及手动导入验证（独立内存 H2）。此前 MySQL 5.7.44 隔离验证与后台浏览器验证是历史证据，不是本次新包的 MySQL 8.4 运行验证。新增脚本执行了 Bash 语法检查、错误目标拒绝和隔离边界的本地模拟检查，详情见 `isolation-validation.json`。当前电脑的 Docker 引擎不可用，因此**本版尚未实际启动 MySQL 8.4 容器，也未在腾讯云执行安装、备份、恢复或真实消息发送**。上线前必须完成上述服务器验收。
