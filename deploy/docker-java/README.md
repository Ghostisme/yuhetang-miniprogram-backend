# 纯 Java 后端：独立 JAR / Docker

本包仅含 Spring Boot JAR、Java 镜像构建文件和本后端的运行工具。镜像不安装 OpenResty、Nginx、1Panel、MySQL、Node.js 或 Maven，不包含管理后台、小程序、证书、数据库数据和真实密钥。服务器用已有 Docker 构建镜像，容器内有 Java 17，无需宿主机再安装 Java。

本次针对已运行的服务器迁移：现有 `yuwatang-mysql-isolated` 容器和 `127.0.0.1:3307/yuwatang_reservation` 继续使用；上传目录 `/var/lib/yuwatang-reservation` 原地挂载；现有网站继续代理 `127.0.0.1:8790`。Docker 使用 Linux host 网络，Java API 和监控端口均绑定回环地址。包里没有数据库首次安装、清理、建库或重置脚本；应用启动时仍会通过 Flyway 执行本项目的必要表结构迁移。

当前后台与小程序 API 使用 `https://mp-admin.hetuonian.cn`；客服回调仍是 `https://booking.hetuonian.cn/api/wechat/customer-service`。本包不配置域名、HTTPS 或公网网关。

## 当前 Docker 部署的直接更新入口

已经运行 `yuhetang-java-api` 容器时，使用与本版本配套的 `yuhetang-activate-时分秒.sh`。将 Java `.tar.gz`、它的 `.sha256`、激活脚本及其 `.sha256` 上传至 `/home/ubuntu`，校验后 `sudo bash` 运行激活脚本即可。脚本会核对发行文件、镜像中和运行中 JAR 的 SHA256、独立库目标，并在健康检查失败时恢复原 Java 容器；旧容器另名保留。

本次增加 V7 迁移：通知个人已读表、订阅确认去重表及通知索引。启动时仅迁移现有裕和堂业务库，保留门店、预约和客户记录。更新前先使用现有备份工具保存本项目数据。镜像构建完成不等于运行版本已切换，末尾必须看到新镜像、正确 JAR 校验值和 `Health=healthy`。

后续只调整服务号环境变量时，使用同一版本激活脚本的 `--reload-env` 参数重新创建 Java 容器。普通 `docker restart` 不会重新加载编辑后的 `--env-file`。服务号配置工具 `configure-staff-wechat.py` 会保留原环境文件的私密备份，且仅编辑服务号通知字段。

下面的 systemd 配置导出和首次迁移章节适用于首次安装；已迁移至 Docker 的服务器直接使用上述更新入口。

## 一、上传与校验

腾讯云网页终端以 ubuntu 登录时，上传压缩包及同名 `.sha256` 到 `/home/ubuntu/`；不要给 `/root` 放开权限。进入 root 后执行。以下编号由打包脚本替换成当前版本，仓库内的 `@RELEASE_ID@` 仅为占位符：

```bash
sudo -i
cd /home/ubuntu
sha256sum --check yuhetang-java-docker-@RELEASE_ID@.tar.gz.sha256
install -d -o root -g root -m 0700 /root/yuhetang-upload
tar -xzf yuhetang-java-docker-@RELEASE_ID@.tar.gz -C /root/yuhetang-upload
cd /root/yuhetang-upload/yuhetang-java-docker-@RELEASE_ID@
sha256sum --check SHA256SUMS
```

校验必须全部显示 OK。也会单独提供 `yuhetang-java-api-@RELEASE_ID@.jar` 及其校验文件；只使用已有 Java 17 环境时可直接使用 JAR，配置、数据目录和数据库仍由部署方提供。

## 二、先准备镜像和配置，旧 Java 暂时继续运行

```bash
docker version
systemctl is-active yuwatang-mysql-isolated.service yuwatang-reservation.service
curl --fail --silent --show-error --max-time 8 http://127.0.0.1:8790/api/health
docker build --pull -t yuhetang-java-api:@RELEASE_ID@ .
```

如果拉取基础镜像失败，先解决服务器的 Docker 镜像访问；此时不停止旧 Java。构建只复制包里的 JAR，不在服务器上编译源码。

首次迁移执行一次配置导出。systemd 负责按原有规则读取 `/etc/yuwatang-reservation.env`，Python 将解码后的值写成 Docker `--env-file` 格式，保留已有密码、微信凭证和 JSON 字段映射。命令不输出密钥、不连接数据库，也不修改原配置：

```bash
systemd-run --quiet --wait --pipe --collect \
  --property=EnvironmentFile=/etc/yuwatang-reservation.env \
  /usr/bin/python3 "$PWD/export-env.py" \
  --output /etc/yuwatang-reservation.docker.env
```

新文件必须为 root 所有、权限 0600；已存在时脚本会拒绝覆盖。以后修改 Docker 配置就编辑这个新文件，值不要额外套 shell 引号；JSON 写成 `CUSTOMER_TEMPLATE_MAPPING={"name1":"customerName",...}`。不要 `source` 或 `eval` 私密环境文件，也不要把 `docker inspect` 的完整输出发到聊天。

先校验配置，不启动 Java、不访问数据库：

```bash
docker run --rm --network none \
  --env-file /etc/yuwatang-reservation.docker.env \
  --entrypoint /bin/bash \
  yuhetang-java-api:@RELEASE_ID@ /app/check-db-target.sh
```

应显示 `Isolated database target check passed.`。导出器和启动检查会拒绝其他库、3306、root 数据库账号、公开 API 监听地址或丢失上传目录的配置。

## 三、备份后切换 Java

先备份本项目数据库和上传文件。已有备份工具会短暂停止并恢复旧 systemd Java，等待其成功退出后继续；它只处理裕和堂独立数据库：

```bash
/usr/local/libexec/yuwatang-dbctl backup
install -d -o root -g root -m 0700 /var/backups/yuhetang-java-migration-@RELEASE_ID@
install -o root -g root -m 0600 \
  /srv/yuwatang-reservation/reservation.jar \
  /etc/yuwatang-reservation.env \
  /etc/yuwatang-reservation.docker.env \
  /var/backups/yuhetang-java-migration-@RELEASE_ID@/
```

备份失败就停在这一步。备份目录是私密文件，不放到网站目录，不对公网开放。切换会有短暂停机：

```bash
systemctl stop yuwatang-reservation.service
bash run.sh yuhetang-java-api:@RELEASE_ID@
docker logs --tail 80 yuhetang-java-api
curl --fail --silent --show-error --max-time 8 http://127.0.0.1:8790/api/health
docker inspect --format '{{.State.Health.Status}}' yuhetang-java-api
```

Java 刚启动时可先显示 starting，约 30～90 秒后再检查；应得到 `database=connected`、`status=ok` 和 `healthy`。启动脚本复用宿主机 `yuwatang` 用户的 UID/GID，不递归修改数据目录所有权，也不重启数据库或网站。容器限制为 1GB 内存，保留可写上传目录和临时目录，日志每份最多 10MB、保留三份。健康状态异常会被标记，进程退出由 Docker 重启策略处理；健康检查本身不自动重建容器。

确认 API、后台登录和客服回调正常后，禁用旧 Java 的开机启动，保留其文件供回退：

```bash
systemctl disable yuwatang-reservation.service
systemctl is-enabled yuwatang-mysql-isolated.service
```

数据库服务应保持 enabled。不要让 systemd Java 和 Docker Java 同时占用 8790。Docker 的 `unless-stopped` 策略负责新 Java 的开机恢复。

## 四、首次切换失败时回退

只停止这次新建的 Java 容器，再恢复旧服务；上传目录、MySQL 和网站都保留：

```bash
docker stop --time 50 yuhetang-java-api
docker rename yuhetang-java-api yuhetang-java-api-failed-@RELEASE_ID@
systemctl enable --now yuwatang-reservation.service
curl --fail --silent --show-error --max-time 8 http://127.0.0.1:8790/api/health
```

如果容器创建前就失败，不需要执行 stop/rename，只恢复旧服务即可。本次迁移不删除旧 JAR 和原环境文件。后续版本如果有不兼容的数据库迁移，旧 JAR 可能无法直接运行；需按版本说明恢复本项目的已验证备份，不恢复或清空整个 MySQL 实例。

## 五、后续只更新后端

上传新的 Java Docker 包并校验，在旧容器运行期间先构建新版本镜像。以后不执行 `prepare.sh`，不重新安装数据库，不上传后台静态文件。

旧数据库备份工具只认识旧 systemd Java，因此迁移后先主动暂停 Docker Java，备份后通过 trap 恢复。数据库配置和上传备份由原工具保存，新 Docker 环境文件额外私密备份：

```bash
bash -e <<'YHT_BACKUP'
docker stop --time 50 yuhetang-java-api
trap 'docker start yuhetang-java-api >/dev/null' EXIT
/usr/local/libexec/yuwatang-dbctl backup
install -o root -g root -m 0600 /etc/yuwatang-reservation.docker.env \
  "/var/backups/yuwatang-reservation/docker-env-$(date +%Y%m%d-%H%M%S).backup"
YHT_BACKUP
```

备份成功后，记录旧镜像，停止并保留旧容器，再启动新版本（这些命令里的编号应是新包的编号）：

```bash
docker inspect --format '{{.Config.Image}}' yuhetang-java-api
docker stop --time 50 yuhetang-java-api
docker rename yuhetang-java-api yuhetang-java-api-before-@RELEASE_ID@
bash run.sh yuhetang-java-api:@RELEASE_ID@
curl --fail --silent --show-error --max-time 8 http://127.0.0.1:8790/api/health
docker inspect --format '{{.State.Health.Status}}' yuhetang-java-api
```

新容器验收失败时，停止并另名保存新容器，再将 `yuhetang-java-api-before-@RELEASE_ID@` 改回 `yuhetang-java-api` 并 `docker start`。不要运行 `docker compose down -v`、`docker system prune` 或删除 MySQL 数据目录。确认版本和备份可恢复后再单独处理历史 Java 镜像。

## 验证范围

发布报告记录 Java 测试、纯 API JAR 内容、发布文件和校验值。本地未运行 Docker 引擎时会明确记录镜像构建、容器启动、实际 MySQL 和微信送达仍需服务器验收。此包不代表已部署到云端，也不构成 5 万同时在线容量验证。
