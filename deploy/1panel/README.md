# 裕和堂：Ubuntu + 1Panel，前端与 Java 分开部署

适用本次重装后的 Ubuntu 26.04 LTS / x86_64 / 8GB 服务器。公网 IP 沿用 `43.142.125.157`，请以腾讯云控制台实际公网 IP 为准。此前 TencentOS、旧 Nginx 路径和宝塔流程均不用于本次安装。

这是三个独立部分：1Panel 的 OpenResty 提供管理后台静态页面和 HTTPS；Java 17 API 作为独立 systemd 服务；MySQL 8.4 使用独立 Docker 容器。Java 的启动、重启和日志通过 1Panel「终端」执行 systemctl/journalctl 管理，本方案没有创建 1Panel「Java 运行环境」，不要再启动第二份 Java。前端网站不需要 Node.js、npm 或 Maven。

| 部分 | 位置 / 入口 | 更新方式 |
| --- | --- | --- |
| 管理后台前端 | `https://mp-admin.yuhetangtcm.com/admin`，文件位于 1Panel 创建的网站运行目录 | 单独上传前端 ZIP |
| Java API | `/srv/yuwatang-reservation/reservation.jar`，仅监听 `127.0.0.1:8790` | 单独替换 API JAR，并重启本项目 |
| 小程序接口 | `https://booking.yuhetangtcm.com/api/...` | OpenResty 转发到同一 Java API |
| 数据库 | Docker 容器 `yuwatang-mysql-isolated`，仅映射 `127.0.0.1:3307` | 数据保存在 `/var/lib/yuwatang-mysql-isolated` |
| 上传文件 | `/var/lib/yuwatang-reservation/uploads` | 独立持久保存，不放在前端解压目录 |
| 配置及密钥 | `/etc/yuwatang-reservation.env`，root:root 0600 | 只在服务器修改 |
| 内部监控 | `127.0.0.1:8791` | 仅服务器内访问 |

首次启动只建立系统表、权限、一个总部管理员和空站点配置；不导入本地门店、客户、预约或演示账号。之后在后台逐步导入。项目分别提供管理后台 ZIP 和 Java API TAR，以及各自 `.sha256` 校验文件。新包使用精确到秒的发布编号；下文以 `20261107-180000` 举例，执行时改成实际包名中的编号。证书和真实密钥不在包内。

## 第一步：安装 1Panel

在当前 `ubuntu@...` 终端逐条执行：

```bash
sudo -i
apt-get update
apt-get install -y curl ca-certificates unzip openssl
install -d -m 0700 /root/yuhetang-upload
cd /root/yuhetang-upload
curl --fail --show-error --location https://resource.fit2cloud.com/1panel/package/v2/quick_start.sh --output 1panel-quick-start.sh
bash 1panel-quick-start.sh
```

这是官方交互安装入口。选择中文，安装目录可用默认 `/opt`；未安装 Docker 时同意由安装程序安装 Docker；设置自己的面板用户名、密码和安全入口。面板端口用安装程序实际给出的值，不假定是 10086。安装完成后，在腾讯云安全组开放 **TCP 80、443 和实际面板端口**；面板端口可限制为你的公网 IP。SSH 的 22 保持可用，3307、8790、8791 无需对公网开放。

用安装完成显示的完整地址登录，包括端口和安全入口。遗忘入口时在服务器执行 `1pctl user-info`，输出只自己保存，不必贴到聊天。业务管理员与 1Panel 管理员是两套账号。

本地检查不代替目标服务器验收；安装器或 Docker 报错时先保留具体输出，避免混装其他容器引擎或宿主 Nginx。已经安装的服务器直接继续部署或更新，不重复安装面板。

安装后检查：

```bash
docker version
docker info >/dev/null
1pctl status
```

在 1Panel「应用商店」安装 **OpenResty**，使用面板当前稳定版本，提供 80/443 网站入口。MySQL 由下面项目脚本建立，不要在应用商店重复安装一套本项目 MySQL。暂不需要 Redis、PHP、Node.js 或其他应用。

## 第二步：上传并安装 Java API

使用腾讯云网页终端的上传按钮时，登录用户是 ubuntu，应先传到 `/home/ubuntu/`。切换 root 不会改变上传按钮使用的身份，不能将 `/root` 改成公开可写。也可以用 1Panel 的 root 文件管理入口上传。

以下示例将已上传的后端包复制到 root 专用目录；前端 ZIP 留到第四步。不上传本地 data、环境密钥或旧版合并包。

在 1Panel「终端」进入 root shell，然后执行：

```bash
sudo -i
YHT_RELEASE_ID=20261107-180000
install -d -m 0700 /root/yuhetang-upload
install -o root -g root -m 0600 \
  "/home/ubuntu/yuhetang-java-api-1panel-${YHT_RELEASE_ID}.tar.gz" \
  "/home/ubuntu/yuhetang-java-api-1panel-${YHT_RELEASE_ID}.tar.gz.sha256" \
  /root/yuhetang-upload/
cd /root/yuhetang-upload
sha256sum --check "yuhetang-java-api-1panel-${YHT_RELEASE_ID}.tar.gz.sha256"
tar -xzf "yuhetang-java-api-1panel-${YHT_RELEASE_ID}.tar.gz"
cd "yuhetang-java-api-1panel-${YHT_RELEASE_ID}"
bash prepare.sh
```

脚本先检查剩余资源、端口、目录和容器名，校验离线 Java 17，拉取固定摘要的 MySQL 8.4 镜像。它生成本项目数据库密码和 JWT_SECRET，配置独立库及应用账号，准备容器和两个 systemd 服务，**此时不会启动服务**；MySQL 首次启动时才初始化数据库。发现已有同名安装时脚本会退出，不会覆盖。首次拉取镜像需要服务器能访问 Docker Hub；如下载失败，先处理 Docker 镜像网络后再继续。

通过 1Panel「文件」编辑 `/etc/yuwatang-reservation.env`，填写：

- `ADMIN_PASSWORD`：自己的总部管理员初始密码，至少 12 字符。避免引号、空格和反斜杠，便于直接填写环境文件；例如自行生成的长字母数字密码。不要使用本文或截图里的示例密码。
- 微信 AppSecret 可留到后台与接口上线后再填写。AppID、两组客户订阅模板 ID 和字段映射已预置。
- 接入原生客服时，再填写 `WECHAT_APP_SECRET`、`WECHAT_MESSAGE_TOKEN`、`WECHAT_MESSAGE_AES_KEY`，与微信后台保持一致。

保留自动生成的 `DB_PASSWORD`、`JWT_SECRET` 及固定的 DB_URL/DB_USERNAME。环境文件按 systemd 格式读取，不要执行 `source` 或把整份文件贴到聊天；其中模板映射的单引号属于已有格式，保持不变。

## 第三步：启动数据库和 Java，并验证

```bash
systemctl start yuwatang-mysql-isolated.service
/usr/local/libexec/yuwatang-dbctl status
systemctl start yuwatang-reservation.service
systemctl --no-pager --full status yuwatang-reservation.service
curl --fail http://127.0.0.1:8790/api/health
```

数据库第一次初始化可能需要一两分钟。健康接口返回成功后，再设置开机启动：

```bash
systemctl enable yuwatang-mysql-isolated.service yuwatang-reservation.service
```

启动失败只查看本项目日志：

```bash
journalctl -u yuwatang-mysql-isolated.service -n 80 --no-pager
journalctl -u yuwatang-reservation.service -n 100 --no-pager
```

Java 包不包含后台 `index.html` 和 JS/CSS，只保留 API、迁移和小程序仍使用的内置 `/media/` 图片。直接访问 Java 的 `/admin` 不提供后台页面，这是正常的。

## 第四步：部署管理后台前端

先在阿里云 DNS 确认：`mp-admin` 和 `booking` 都是 A 记录，记录值为腾讯云实际公网 IP；保留 `static` 原来的 OSS/CDN 解析。

在 1Panel「网站」创建 **静态网站**，主域名填写 `mp-admin.yuhetangtcm.com`。在网站设置查看「网站目录 / 运行目录」，从该入口打开实际目录，不凭空填写其他 Nginx 路径。

将前端 ZIP 和 `.zip.sha256` 先传到 `/home/ubuntu/`，使用 root 终端校验：

```bash
cd /home/ubuntu
YHT_RELEASE_ID=20261107-180000
sha256sum --check "yuhetang-admin-${YHT_RELEASE_ID}.zip.sha256"
```

然后使用 1Panel「文件」把 ZIP 解压到刚创建网站的**实际运行目录**，解压后直接看到：

```text
网站运行目录/
  index.html
  favicon.svg
  assets/
  media/
```

不能多套一层 `dist/` 或压缩包名称目录。仅移除或改名面板为这个新网站生成的默认首页，不删除其他网站文件。前端包没有后端密钥或数据库资料。

解压后检查权限。旧版 `yuhetang-admin-20261107.zip` 内文件权限为 `0600`，以 root 解压后 OpenResty 工作进程无法读取；这不是需要重新安装后端的问题。对这个静态网站的实际运行目录，将目录设为 `0755`、普通文件设为 `0644`。本次面板截图确认网站代号为 `mp-admin`，目录如下；其他安装必须以面板显示为准：

```bash
find /opt/1panel/www/sites/mp-admin/index -type d -exec chmod 0755 {} +
find /opt/1panel/www/sites/mp-admin/index -type f -exec chmod 0644 {} +
ls -lah /opt/1panel/www/sites/mp-admin/index
```

这两条 find 命令仅调整该网站的静态页面目录，不修改网站相邻的 ssl、Java 配置或数据库。文件保持 root 所有即可，OpenResty 只需读权限；不需要把网站目录或 `/root` 改为 `0777`。后续打包已显式写入普通文件 `0644` 权限，已发布的旧 ZIP 及校验值保留原样。

在该网站「配置文件」的现有 `server { ... }` 内，加入后端包中的 `admin.locations.conf` 内容。保留面板的 `listen`、`server_name`、`root`、SSL 和已有 include；如果已有 `location /`，用提供的 `location /` 替换它，不要重复添加。若该 server 已有 `client_max_body_size`，在原位置改为 `6m`，不重复声明。不要把此片段当成整个网站配置文件覆盖保存，也不要在「反向代理」再建立第二条 `/api/`。

这个配置让 `/admin` 和前端路由回到静态 index.html，`/api/` 与 `/uploads/` 原路径转发 Java。未知 API 不会错误返回 HTML。哈希资源长期缓存，首页每次检查更新。首页 `/` 和 `/admin` 都进入管理后台。客户 H5 在独立 Taro 工程构建与发布。

**代理连通检查：** 本流程使用 OpenResty 的 host 网络转发宿主机 `127.0.0.1:8790`；用户提供的容器检查结果也已确认是 host。其他安装先查找实际容器名，再检查：

```bash
docker ps --format '{{.Names}}  {{.Image}}'
# 把下一行的 OPENRESTY_CONTAINER 替换为上一条输出里的实际 OpenResty 容器名
docker inspect --format '{{.HostConfig.NetworkMode}}' OPENRESTY_CONTAINER
```

预期输出 `host`。若是 `bridge`，先在面板核对 OpenResty 的网络配置；不要将 Java 改成监听公网来解决 502。本流程按已确认的 host 网络编写。

## 第五步：配置 booking 接口网站及 HTTPS

在 1Panel 再创建一个 **静态网站**，主域名为 `booking.yuhetangtcm.com`，该网站目录保留空白即可。在这个网站现有 `server { ... }` 中加入 `booking.locations.conf`，同样替换已有 `location /`，保留面板其他配置。它实际作为接口代理，只开放 `/api/`、`/uploads/`、`/media/`；根路径 `/` 返回 404 是预期行为。

1Panel「证书」中导入已有泛域名证书：证书内容对应 `fullchain.pem`，私钥内容对应 `privkey.key`。在自己电脑与已登录面板之间操作，不把私钥发送到聊天。然后在两个网站各自的 HTTPS 设置选择这张证书、启用 HTTPS，并在确认可访问后开启 HTTP 跳转 HTTPS。

这张证书覆盖 `*.yuhetangtcm.com`，可同时用于 mp-admin 和 booking；有效期以面板和实际证书为准，续期后需在面板更新。导入现有证书不等于已配置自动续期。

面板保存时进行配置检查；若提示重复 location 或重复参数，处理该新网站的重复项后重新检查，不要覆盖面板全局配置。需要终端检查时，只对前面确认的 OpenResty 容器执行：

```bash
docker exec OPENRESTY_CONTAINER nginx -t
```

检查通过后，在 1Panel 使用重载配置操作。无需在宿主机安装另一份 Nginx。

验收：

```bash
curl --fail https://booking.yuhetangtcm.com/api/health
curl --fail https://mp-admin.yuhetangtcm.com/api/health
curl --fail -I https://mp-admin.yuhetangtcm.com/admin
curl -I https://booking.yuhetangtcm.com/actuator/health
```

前三项应成功，最后一项应为 404。浏览器打开 `https://mp-admin.yuhetangtcm.com/admin`，以 `admin` 和自己设置的 `ADMIN_PASSWORD` 登录，确认初始业务资料为空，再从「资料导入」下载模板、小批量导入和核对。密码以后通过后台账号功能修改；环境变量的初始密码不会在每次重启时重置已有账号。

## 第六步：之后再接小程序和客服

先完成上面的管理后台、接口及少量数据验收，再处理小程序。当前客户端默认连接 `https://booking.yuhetangtcm.com`，包括微信开发者工具；Taro 构建可通过 `TARO_APP_API_BASE` 指定 API。本地 H5 使用代理；模拟登录须显式开启 `TARO_APP_DEV_LOGIN=true`，且后端为 local 模式，正式构建禁用模拟登录。不再维护旧 `config.js`。

小程序服务器域名中，request 填 booking HTTPS；uploadFile 按当前上传路径填 booking；downloadFile 按实际使用加入 booking 与 `https://static.yuhetangtcm.com`。现有后端上传仍保存到本项目 uploads，不能仅配置 static 合法域名就认为已实现 OSS 直传。WebSocket 域名仅在客户端实际使用 WebSocket 后配置。

原生客服回调单独使用 `https://booking.hetuonian.cn/api/wechat/customer-service`，配置见 [回调专用域名](WECHAT-CALLBACK-DOMAIN.md)。业务 API 和管理后台域名保持原配置。需要配置后端 AppSecret、Token、43 位 AESKey，以及微信消息推送安全模式；微信后台的格式与服务端保持一致。结果订阅和到店提醒的模板及映射已随包提供，但仍需真实微信登录、用户主动订阅、触发预约处理并检查平台发送结果。仅启动后端或开关回调不代表已经发到客户手机。

域名此前确认未备案；中国大陆腾讯云的域名访问与小程序正式合法域名配置需完成相应备案。DNS 与证书完成后，也要确认腾讯云与微信平台的实际校验结果。

## 分别更新与备份

只改后台页面：重新构建前端，备份这个网站当前文件，将新 ZIP 解压到同一运行目录；先放好新的 assets，再替换 index.html，保留上一版 assets 一段时间，方便已打开页面继续使用。检查后再清理过期文件。Java 和小程序不需要随这次前端更新重启或发布。

只改 API：构建 `api-release`，先执行下面的一致性备份，再另外保存当前 `/srv/yuwatang-reservation/reservation.jar`，停止本项目 Java 后替换 JAR，再启动并验收 health；旧 JAR 保留至升级验收完成。不要再次运行首次安装的 prepare.sh，不要修改数据库数据目录，也不要用旧的合并 JAR。涉及数据库迁移的升级需同时核对恢复方案，不能仅替换旧 JAR 认为已经回退数据库。

```bash
# 导入真实资料前、每次后端升级前运行；会短暂停本项目 Java 以一致备份
/usr/local/libexec/yuwatang-dbctl backup
# 检查恢复到全新的校验容器；用 backup 输出的实际目录替换 BACKUP_DIRECTORY
/usr/local/libexec/yuwatang-dbctl restore-check BACKUP_DIRECTORY
```

备份位于 `/var/backups/yuwatang-reservation`，含数据库、上传及环境配置，需要另外保存到受控的异地位置；JAR 按前述步骤另行保留。restore-check 使用另一个校验容器，不覆盖在线库。容器与 Java 的开停由本项目 systemd 单元协调；在 1Panel 里查看容器可以，但不要随意删除、重建或重复启用它的其他守护方式。面板更新或 Docker 维护后，再检查两个项目服务和 health。

开发电脑分别进入对应仓库构建，不要求相邻工程存在：

```powershell
# 管理后台仓库
npm ci
npm run build
npm run package:release
# Java 仓库；运行包需先自行准备并匹配脚本固定 SHA256
mvn -B -ntp -Papi-release verify
python tools/package-1panel.py --java-runtime D:/deploy/java17-linux-x64.tar.gz
# 可选：显式指定后台产物进行本机代理联合验收
python tools/verify-1panel-split.py --frontend-dist D:/deploy/admin-dist
```

发布编号默认使用当前时间；也可为两个打包命令传入 `--release-id YYYYMMDD-HHMMSS`。不覆盖旧包。Taro 的微信与 H5 构建在小程序仓库独立执行，不参与后台或 Java 打包。

Java 测试使用隔离 H2，不接本地业务库；云端 MySQL 8.4、1Panel、证书和真实微信投递仍须按上面步骤验收。当前单机资源用于先跑通业务，5 万同时在线需另在目标云环境压测与扩容，不能由安装面板来证明。

官方参考：[1Panel 在线安装](https://docs.fit2cloud.com/1panel/installation/online-installation)、[1Panel Java 运行环境](https://docs.fit2cloud.com/1panel/user_manual/websites/java)、[官方 OpenResty 应用的 host 网络配置](https://github.com/1Panel-dev/appstore/blob/master/apps/openresty/1.27.1.2-5-1-focal/docker-compose.yml)。
