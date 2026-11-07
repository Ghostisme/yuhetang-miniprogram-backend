# 腾讯云纯终端部署：裕和堂预约系统

> **旧方案：共用现有 MySQL 实例。用户确认尚未执行，现已改为独立容器数据库。请改用 [独立数据库部署流程](../tencent-isolated/README.md) 和 `yuhetang-tencent-host-isolated-20261105.tar.gz`，不要执行本页建库、授权或启动步骤。** 本页保留用于核对旧说明。

本流程不依赖宝塔。目标是 `43.142.125.157` / TencentOS Server 3.1 / x86_64，域名 `booking.yuhetangtcm.com`，数据库按此前确认的 MySQL 5.7.44 编写。先检查服务器现状；如果当前主机不是此前那台，不能套用旧的磁盘和端口结论。

使用 `releases/yuhetang-tencent-host-terminal-20261105.tar.gz` 及其 `.sha256` 文件。归档已包含前端、Java 后端 JAR、独立 Java 17 运行时和配置模板，不需要安装 Node、Maven、Docker 或宝塔。不会自动迁移本地 H2 业务数据、上传图片或微信密钥。

## 1. 当前证书

用户提供的 `fullchain.pem` 和 `privkey.key` 已在本地检查：

- SAN 包含 `*.yuhetangtcm.com` 和 `yuhetangtcm.com`，覆盖 `booking.yuhetangtcm.com`。
- RSA 2048 位私钥与叶证书匹配；文件中包含的相邻证书签名校验通过。
- 有效期为 2026-10-24 07:16:03 UTC 至 **2027-01-22 07:16:02 UTC**。
- 这里只验证了本地文件；服务器安装、公网证书链及真实微信发送尚未执行。

证书和私钥单独上传，不包含在源码、发布包或校验报告中。证书到期前需要续期并重新安装服务器证书，CDN 上的证书也需单独核对；本流程没有配置自动续期。证书不能代替域名备案。

## 2. 在服务器检查资源、准备上传目录

以下 Linux 命令使用腾讯云控制台登录的 root 终端执行；非 root 用户先使用 `sudo -i`。

```bash
cat /etc/os-release
uname -m
df -h /
free -m
ss -lnt '( sport = :8790 or sport = :8791 )'
command -v nginx mysql mysqld
```

安装脚本要求根盘至少 5GiB 可用、MemAvailable 至少 1.5GiB、8790/8791 未占用。此前快照根盘只剩约 2.5GB，必须以本次输出为准。空间不足时先扩容并扩展文件系统，保留现有业务数据。这里只是安装资源门槛，不是 5 万人同时在线的容量验收。

```bash
install -d -o root -g root -m 0700 /root/yuhetang-upload
install -d -o root -g root -m 0700 /root/yuhetang-upload/tls
```

通过腾讯云终端文件上传、SFTP 或 WinSCP 上传：

| 本地文件 | 服务器目标 |
| --- | --- |
| `yuhetang-tencent-host-terminal-20261105.tar.gz` | `/root/yuhetang-upload/` |
| 同名 `.tar.gz.sha256` | `/root/yuhetang-upload/` |
| `fullchain.pem` | `/root/yuhetang-upload/tls/fullchain.pem` |
| `privkey.key` | `/root/yuhetang-upload/tls/privkey.key` |

若本机已能 SSH 登录，可在 Windows PowerShell 执行以下命令；登录凭据只在 SSH 提示处输入：

```powershell
$projectDir = 'D:/BaiduNetdiskDownload/照片/yuwatang-reservation'
$certDir = 'D:/桌面资料/裕和堂/_.yuhetangtcm.com_5f9c63d23183c356b4bd152d3caedf9a/Nginx'
scp "$projectDir/releases/yuhetang-tencent-host-terminal-20261105.tar.gz" "$projectDir/releases/yuhetang-tencent-host-terminal-20261105.tar.gz.sha256" root@43.142.125.157:/root/yuhetang-upload/
scp "$certDir/fullchain.pem" "$certDir/privkey.key" root@43.142.125.157:/root/yuhetang-upload/tls/
```

## 3. 校验并安装独立 Java 服务文件

回到服务器终端，按顺序执行；任何一步失败都先处理原因。

```bash
cd /root/yuhetang-upload
sha256sum --check yuhetang-tencent-host-terminal-20261105.tar.gz.sha256
```

确认目标目录不存在后再解压，避免覆盖以前解压的文件：

```bash
test ! -e /root/yuhetang-upload/yuhetang-tencent-host-terminal-20261105
```

上条成功才继续：

```bash
tar -xzf yuhetang-tencent-host-terminal-20261105.tar.gz
cd yuhetang-tencent-host-terminal-20261105
sha256sum --check SHA256SUMS
bash preflight.sh
bash prepare.sh
```

脚本创建以下本应用专用路径，不启动服务、不修改 Nginx，也不改变系统 `/usr/bin/java`：

| 路径 | 用途 |
| --- | --- |
| `/opt/yuhetang-java17` | 独立 Java 17 |
| `/srv/yuwatang-reservation/reservation.jar` | 后端和已构建后台页面 |
| `/var/lib/yuwatang-reservation` | 持久化数据与上传文件 |
| `/etc/yuwatang-reservation.env` | root:root，权限 600 的环境配置 |
| `/etc/systemd/system/yuwatang-reservation.service` | systemd 服务，使用专用 `yuwatang` 用户 |

如果已有同名 JAR 或 systemd 服务，脚本会停止；升级不能重复执行首次安装流程。

## 4. 创建独立数据库和本地账号

使用已有具备建库、建用户和授权权限的 MySQL 管理账号。操作系统 root 与 MySQL root 是两个账号，服务器 root 权限不意味着自动知道 MySQL 密码。此前你说不知道 MySQL 管理员密码；没有宝塔时，需要由已有数据库管理员提供可用管理账号或代建以下库和用户。这一步不要求重置数据库密码。

例如已知 MySQL root 密码时执行；密码只在交互提示中输入：

```bash
MYSQL_HISTFILE=/dev/null mysql -uroot -p
```

若 `mysql` 不在 PATH，但此前安装目录仍存在，用 `/www/server/mysql/bin/mysql` 替换命令中的 `mysql`，无需安装面板。先查询现状：

```sql
SELECT VERSION();
SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='yuwatang_reservation';
SELECT User,Host FROM mysql.user WHERE User='yuwatang_app';
```

如果同名库或账号已存在，先核对用途和数据，不覆盖。确认没有冲突后，将下面的 `YOUR_NEW_APP_DB_PASSWORD` 替换为这个应用新建的独立强密码，再执行。建议使用随机十六进制密码，避免 SQL 引号和环境文件的转义问题。

```sql
CREATE DATABASE yuwatang_reservation CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'yuwatang_app'@'127.0.0.1' IDENTIFIED BY 'YOUR_NEW_APP_DB_PASSWORD';
GRANT ALL PRIVILEGES ON yuwatang_reservation.* TO 'yuwatang_app'@'127.0.0.1';
EXIT;
```

验证专用账号可以连接，输入刚创建的密码：

```bash
mysql -h127.0.0.1 -P3306 -uyuwatang_app -p yuwatang_reservation -e 'SELECT VERSION(), DATABASE();'
```

应用连接的是 MySQL，`jdbc:mariadb` 只用于选择兼容 MySQL 5.7 的 JDBC 驱动，不需要安装 MariaDB。首次启动自动执行 Flyway V1–V6；生产模式初始化总部 `admin`，不创建演示门店账号。

## 5. 填写环境配置并验证应用

```bash
openssl rand -hex 32
vi /etc/yuwatang-reservation.env
```

第一条生成 JWT 密钥，把生成值填进文件；不要把命令本身写入文件。此文件采用 systemd 的 `KEY=value` 语法，不写 `export`，也不要用 `source` 执行。

必须填写：`DB_PASSWORD`、`JWT_SECRET`、`ADMIN_PASSWORD`；客户微信链路还需填写真实小程序的 `WECHAT_APP_SECRET`。`ADMIN_PASSWORD` 只用于首次初始化总部管理员。

以下公开配置已写入，不需要重新查找：

| 项目 | 配置 |
| --- | --- |
| 小程序 AppID | `wxce054e18303efb8a` |
| 预约处理提醒 | `JVNwa7kEPHJUORhAJKvqdnYbazd7uNmkJPJ4ImucDlQ` |
| 预约提醒 | `VaYcGAZpoIhzLoojppTX8LVbcS1Ftq33WBPTYwsfkFY` |
| 正式地址 | `https://booking.yuhetangtcm.com` |
| 数据库 | `127.0.0.1:3306/yuwatang_reservation`，用户 `yuwatang_app` |

模板的各字段映射也已填写。客户订阅消息由后端主动调用微信 API 发送，不依赖微信后台“消息推送”接收回调开关。接原生客服回调时再填写 `WECHAT_MESSAGE_TOKEN` 和 `WECHAT_MESSAGE_AES_KEY`，并保持与微信后台完全一致。`SERVICE_*` 和短信参数可暂空。

```bash
chmod 600 /etc/yuwatang-reservation.env
systemctl start yuwatang-reservation
journalctl -u yuwatang-reservation -n 80 --no-pager
systemctl status yuwatang-reservation --no-pager
```

等待日志出现应用启动成功，再检查：

```bash
curl --fail --show-error http://127.0.0.1:8790/api/health
curl --fail --show-error http://127.0.0.1:8790/api/runtime
curl --fail --show-error http://127.0.0.1:8791/actuator/health
```

健康检查及数据库连接成功后执行 `systemctl enable yuwatang-reservation`。应用内存上限约 1GB、数据库池 8 个连接，是共享主机联调起点，不能当作 5 万同时在线的容量承诺。

## 6. 安装泛域名证书、配置独立 Nginx 站点

如果已安装 Nginx，复用现有实例，先只查看配置来源和 include 位置：

```bash
nginx -v
nginx -T 2>&1 | awk '/^# configuration file |^[ \t]*include[ \t]/ {print}'
```

以下操作以 **http 块内**已有 `include /etc/nginx/conf.d/*.conf;` 为例。如果实际是其他目录，配置文件应放进那个 http include 目录。旧路径即使仍叫 `/www/server/panel/vhost/nginx/`，也可以直接管理文件，不需要面板。不要重装已有 Nginx，不覆盖主配置和其他站点。

仅当服务器没有 Nginx 时，TencentOS 3.1 可执行：

```bash
dnf install -y nginx openssl
```

安装前确认 80/443 没有被另一套入口服务占用。`openssl` 若已安装不需要重复安装。

安装证书：

```bash
install -d -o root -g root -m 0700 /etc/yuwatang-reservation/tls
install -o root -g root -m 0644 /root/yuhetang-upload/tls/fullchain.pem /etc/yuwatang-reservation/tls/fullchain.pem
install -o root -g root -m 0600 /root/yuhetang-upload/tls/privkey.key /etc/yuwatang-reservation/tls/privkey.key
```

以下命令只显示证书公开信息，不显示私钥：

```bash
openssl x509 -in /etc/yuwatang-reservation/tls/fullchain.pem -noout -subject -issuer -dates -ext subjectAltName
```

确认 `/etc/nginx/conf.d/` 是实际的 http include 目录、该域名没有重复站点后，新增本应用配置：

```bash
test ! -e /etc/nginx/conf.d/booking.yuhetangtcm.com.conf
```

上条成功才执行，已有文件时先核对和备份，不直接覆盖：

```bash
install -o root -g root -m 0644 /root/yuhetang-upload/yuhetang-tencent-host-terminal-20261105/nginx.booking.conf.example /etc/nginx/conf.d/booking.yuhetangtcm.com.conf
nginx -t
```

`nginx -t` 成功后，已有运行实例只执行：

```bash
nginx -s reload
```

首次新安装且尚未启动 Nginx 时执行 `systemctl enable --now nginx`。配置模板整份放在 http include 文件中；`limit_req_zone` 不能放到 `server {}` 内。

腾讯云安全组按实际入口放行 TCP 80/443，若系统防火墙启用，也应按现有 zone 规则放行 HTTP/HTTPS。8790、8791、3306 保持本机访问。不要关闭整个防火墙或修改其他业务的放行规则。

如果 Nginx 启动成功但代理返回 502，先检查应用健康和 Nginx 错误日志。SELinux Enforcing 且日志证明阻止代理连接时，可使用 `setsebool -P httpd_can_network_connect 1` 允许 Web 服务连接后端；不要通过关闭 SELinux 处理。

## 7. 验收域名和 HTTPS

阿里云 DNS 保持 `booking` 的 A 记录指向 `43.142.125.157`；现有 `static.yuhetangtcm.com` 的 OSS/CDN CNAME 保持其 CDN 目标。

先在服务器验证本机 Nginx 和证书域名匹配：

```bash
curl --fail --show-error --resolve booking.yuhetangtcm.com:443:127.0.0.1 https://booking.yuhetangtcm.com/api/health
```

再从自己的电脑或其他外部网络验证真实 DNS、安全组和公网访问：

```bash
curl --fail --show-error https://booking.yuhetangtcm.com/api/health
curl --fail --show-error https://booking.yuhetangtcm.com/api/runtime
```

不能用 `-k` 跳过证书错误。成功后浏览器访问 `https://booking.yuhetangtcm.com/admin`，账号 `admin`，密码为首次初始化时填写的 `ADMIN_PASSWORD`。

域名此前尚未备案，应通过实际服务器接入商腾讯云办理；可以先准备服务和做本机检查，正式对外提供服务、配置微信合法域名和发布前完成相应备案条件。证书安装不代表备案或小程序发布完成。

## 8. 接通客户小程序链路

- request 合法域名：`https://booking.yuhetangtcm.com`。
- uploadFile 合法域名：`https://booking.yuhetangtcm.com`，仅实际调用 wx.uploadFile 时使用。
- downloadFile 合法域名：按实际下载来源添加 `https://booking.yuhetangtcm.com` 和 `https://static.yuhetangtcm.com`。CDN 有域名不代表文件已上传，需验证真实对象地址和 HTTPS。
- socket 暂不填写，当前链路没有 WebSocket 服务。
- 本地 `miniprogram/config.js` 的 `apiBase` 改为 `https://booking.yuhetangtcm.com`，保持 `developmentLogin: false`；运行 `npm run check:mini` 后用微信开发者工具和真机验收。
- 按微信后台要求配置服务器出口 IP 白名单；没有另设 NAT 时核对是否为 `43.142.125.157`。

客户订阅验收：客户主动点击同意两个模板 → 提交预约 → 门店确认 → 客户收到预约处理提醒；到店前再验证预约提醒。两个模板分别消耗各自的授权次数，后台建好模板不等于客户已经授权。总部已有模板配置不会被环境变量强制覆盖，后台「扩展与配置」及各店「消息中心」应核对客户通道开关和字段映射。

客服回调：在微信后台生成 EncodingAESKey，自定义 3–32 位字母数字 Token，把它们填入服务器环境文件并重启本应用，再提交以下配置：

```text
URL: https://booking.yuhetangtcm.com/api/wechat/customer-service
Token: 与 WECHAT_MESSAGE_TOKEN 一致
EncodingAESKey: 与 WECHAT_MESSAGE_AES_KEY 一致
模式: 安全模式
数据格式: XML
```

`systemctl restart yuwatang-reservation` 后先确认服务健康，再在微信后台提交验证。浏览器直接打开无签名的回调 URL 返回 400 不等于配置错误；微信会带签名验证。真机发送客服消息 → 对应门店后台出现会话 → 回复到微信。发送记录 ACCEPTED 仅表示微信受理，不能当作已送达或已读。服务号和店长通知留在下一阶段。

## 9. 备份和更新

正式承接客户前，配置仅针对 `yuwatang_reservation` 的数据库备份，以及 `/var/lib/yuwatang-reservation` 文件备份，保留异地副本并演练隔离恢复。不能把本机 H2 文件直接挂给 MySQL。

升级前备份本应用 JAR、配置和业务数据，停止本服务，替换已校验 JAR，启动并复核健康；包含数据库迁移时需单独设计兼容回退。证书续期后更新 `/etc/yuwatang-reservation/tls/` 的证书和匹配私钥，保持权限，`nginx -t` 成功后 reload。全程不需要重装面板或替换系统 Java。
