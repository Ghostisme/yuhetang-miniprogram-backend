# 从 /root/yuhetang-upload 继续部署

> 历史进度已被服务器重装替代。当前 Ubuntu + 1Panel、前端与 Java 分开部署，请使用 [新的安装说明](../1panel/README.md)；本页旧 TencentOS、Podman 与 `/www/server/` 命令不适用。

适用于用户 2026-11-05 最新终端输出：TencentOS 3.1、`/dev/vda1` 为 ext4、Nginx 主配置 `/www/server/nginx/conf/nginx.conf`、HTTP 站点目录 `/www/server/panel/vhost/nginx/`、已有 Podman。使用终端即可，不需要宝塔界面。

2026-11-05 用户再次确认：目前仅创建了上传目录，尚未安装。此次使用空业务库的新包，正式环境不预置门店、品项、调理师或客户，实际资料由用户逐步导入。以下只使用 **isolated** 包，数据库为新建的 MySQL 8.4 容器，监听 `127.0.0.1:3307`；原 MySQL 5.7 的 3306、原有业务容器及其数据目录不在本次安装范围内。

2026-11-05 补充：用户暂不能确定能否扩容。最新资源是 4 核、可用内存 1774 MiB、根盘剩余约 2.4 GiB；`/www/server` 的 24 GiB 中，`label-studio` 占 19 GiB，`data` 目录为 612 MiB（MySQL 实际数据目录仍需以运行配置为准）。原 `mes-prod-mysql` 容器内存已达自身上限的约 92%。先看 [现有资源与部署判断](../../docs/现有服务器资源与部署判断.md)，核对 Label Studio 文件用途及内存余量后再决定，不直接跳过现有资源门槛。服务器账号与密钥记录已单独保存在本机 `D:\BaiduNetdiskDownload\照片\裕和堂-私密运维记录\`，未放入发布包。

## 1. 现在可以上传和校验

本地 `releases/` 中上传以下两项到 `/root/yuhetang-upload/`：

- `yuhetang-tencent-host-isolated-20261105-clean.tar.gz`（全新空业务库版本，约 101 MiB）。
- `yuhetang-tencent-host-isolated-20261105-clean.tar.gz.sha256`。

将已有 `fullchain.pem`、`privkey.key` 单独上传到 `/root/yuhetang-upload/tls/`。私钥不包含在发布包中，不发到聊天或公开网盘。

本次新包已经包含 `nginx.mp-admin.conf.example`，无需单独上传旧补充文件。新增站点的完整命令见 [管理后台域名配置](MP-ADMIN-DOMAIN.md)。小程序接口与 `PUBLIC_URL` 继续使用 booking。旧发布包保留用于历史核对，本次安装不要使用旧包。

在服务器执行：

```bash
cd /root/yuhetang-upload
sha256sum --check yuhetang-tencent-host-isolated-20261105-clean.tar.gz.sha256
```

必须出现 `OK`。校验值以新包同名 `.sha256` 文件为准，不使用旧包的校验值。

校验成功后解压到一个尚不存在的新目录：

```bash
test ! -e yuhetang-tencent-host-isolated-20261105-clean && test ! -L yuhetang-tencent-host-isolated-20261105-clean && tar -xzf yuhetang-tencent-host-isolated-20261105-clean.tar.gz
cd /root/yuhetang-upload/yuhetang-tencent-host-isolated-20261105-clean
bash preflight.sh
```

目录已存在时不重复解压覆盖，先核对是否为上次解压结果。预检只检查，不安装、不建库、不启动服务。按最新快照，可用内存约 1.5 GiB、根盘剩余约 2.5 GiB，预检应拒绝继续；这是预期保护。

## 2. 补足资源后才执行安装

本包要求根盘至少 **15 GiB 可用**、`MemAvailable` 至少 **3 GiB**，且 3307、8790、8791 空闲。这是本部署的安装余量门槛，不是 MySQL 官方最低配置，也不代表已具备 5 万人同时在线的容量。

可先规划系统盘从 50 GiB 扩至 100 GiB；内存可考虑从 8 GiB 增至 16 GiB。实际能否安装仍以预检为准。共享主机扩容不代替后续容量规划；实例规格调整可能需要停机，应结合原有业务安排维护窗口。

先在腾讯云为该系统盘创建快照，再通过控制台扩容这块盘。控制台完成后检查：

```bash
lsblk -o NAME,SIZE,TYPE,FSTYPE,MOUNTPOINT
findmnt -n -o SOURCE,FSTYPE /
command -v growpart resize2fs
```

当前已确认设备关系为 `/dev/vda` → `/dev/vda1` → ext4 `/`。**只有 `vda` 已显示扩容后的容量、`vda1` 仍较小、两个工具均存在时**，依次执行：

```bash
growpart /dev/vda 1
resize2fs /dev/vda1
df -h /
free -m
```

如果 `vda1` 已扩大而文件系统尚未扩大，只需要 `resize2fs /dev/vda1`；如果 `df -h /` 已反映新容量，则不重复扩展。工具不存在、设备关系变化、扩展报错时保留输出再处理，不执行格式化或重建分区命令。

在腾讯云补足内存后重新检查 `free -m`，然后：

```bash
cd /root/yuhetang-upload/yuhetang-tencent-host-isolated-20261105-clean
bash preflight.sh
```

必须出现 `Preflight passed` 才继续。不要降低脚本门槛、关闭原业务或删除未知数据库/容器文件来绕过检查。

腾讯云官方操作：[扩容云硬盘](https://cloud.tencent.com/document/product/362/5747)、[扩容 Linux 分区和文件系统](https://cloud.tencent.com/document/product/362/54127)、[调整实例配置](https://cloud.tencent.com/document/product/213/2178)。

## 3. 准备新容器和独立 Java 服务

```bash
bash prepare.sh
```

脚本使用现有 Podman，下载固定摘要的官方 MySQL 8.4 镜像，准备一个新的数据库容器、私有 Java 17 和本项目的 systemd 单元。数据库和应用尚未启动；遇到已有同名目录、容器或配置会退出，不自动覆盖。下载失败或准备中断时保留现场，先处理错误，不清空目录重装。

不运行宿主机 `mysql -uroot -p`，不需要原数据库 root 密码。新数据库密码和 JWT 密钥由脚本生成，保存在 root 专用配置文件中。

```bash
vi /etc/yuwatang-reservation.env
```

先填写：

- `ADMIN_PASSWORD`：本系统独立初始管理员密码，至少 12 字符。
- `WECHAT_APP_SECRET`：AppID `wxce054e18303efb8a` 对应的真实 AppSecret。
- `WECHAT_MESSAGE_TOKEN`、`WECHAT_MESSAGE_AES_KEY`：小程序客服回调将使用的 Token 与 43 位 EncodingAESKey，与微信后台保持一致。

AppID、两个客户订阅模板及字段映射已经写入默认配置。`DB_URL` 固定连接 3307，不修改自动生成的 `DB_PASSWORD`、`JWT_SECRET`。`SERVICE_*` 店长服务号和短信配置可以暂空，先验收客户小程序链路。订阅消息发送不依赖客服消息推送开关。

配置填好后，将项目 `tools/export-tencent-credentials.py` 单独上传到 `/root/yuhetang-upload/`，再执行：

```bash
python3 /root/yuhetang-upload/export-tencent-credentials.py --output /root/yuhetang-upload/yuhetang-credentials-20261105.md
```

下载生成的 Markdown 到本机私密运维记录目录。导出只读本项目的专用配置文件，不连接数据库、改密码或在终端回显密钥；已有记录不会覆盖。文档中的 `ADMIN_PASSWORD` 是首次初始化配置值，之后如在后台改密，要另外更新当前管理员密码记录。导出工具独立上传，不修改部署包或其校验值。

## 4. 启动和本机验收

```bash
systemctl start yuwatang-mysql-isolated.service
/usr/local/libexec/yuwatang-dbctl status
systemctl start yuwatang-reservation.service
systemctl --no-pager --full status yuwatang-reservation.service
curl --fail http://127.0.0.1:8790/api/health
curl --fail http://127.0.0.1:8791/actuator/health
```

每一步成功再执行下一步；首次新库初始化需要等待。失败时只检查本项目日志：

```bash
journalctl -u yuwatang-mysql-isolated.service -n 80 --no-pager
journalctl -u yuwatang-reservation.service -n 100 --no-pager
```

不粘贴环境文件或密钥。首次正式启动只创建数据库表、角色与权限、总部管理员、空白站点配置、订阅模板字段映射及内部锁/任务配置。门店、品项、产品、调理师、客户、订单/预约、会话和素材记录均为空，本地 H2 数据与上传文件不迁移。重启保留之后导入的资料，不会重新清空。

## 5. 安装证书，新增 booking 和管理后台站点

```bash
install -d -o root -g root -m 0700 /etc/yuwatang-reservation/tls
install -o root -g root -m 0644 /root/yuhetang-upload/tls/fullchain.pem /etc/yuwatang-reservation/tls/fullchain.pem
install -o root -g root -m 0600 /root/yuhetang-upload/tls/privkey.key /etc/yuwatang-reservation/tls/privkey.key
openssl x509 -in /etc/yuwatang-reservation/tls/fullchain.pem -noout -dates -checkhost booking.yuhetangtcm.com
nginx -t
```

确认域名匹配、证书未过期、现有配置有效。当前已证实 HTTP include 目录为 `/www/server/panel/vhost/nginx/`，不是 `/etc/nginx/conf.d/`。先检查是否已经配置过相同域名：

```bash
nginx -T 2>&1 | awk '/^[ \t]*server_name[ \t]/ && /booking[.]yuhetangtcm[.]com/ {print}'
```

若没有输出，且以下目标文件不存在，才从当前包目录执行新增站点：

```bash
cd /root/yuhetang-upload/yuhetang-tencent-host-isolated-20261105-clean
if test ! -e /www/server/panel/vhost/nginx/booking.yuhetangtcm.com.conf && test ! -L /www/server/panel/vhost/nginx/booking.yuhetangtcm.com.conf; then
    install -o root -g root -m 0644 nginx.booking.conf.example /www/server/panel/vhost/nginx/booking.yuhetangtcm.com.conf && nginx -t && nginx -s reload
else
    echo '目标文件已存在，已停止，未覆盖。'
fi
```

`nginx -t` 失败时不会重载；保留错误输出，先修正本次新增文件。这里平滑重载同一个已确认的 Nginx 实例，不重写原站点和 `/ai/` 路由。

```bash
curl --fail --resolve booking.yuhetangtcm.com:443:127.0.0.1 https://booking.yuhetangtcm.com/api/health
```

本机 HTTPS 成功后，按 [管理后台域名配置第 2、3 步](MP-ADMIN-DOMAIN.md#2-应用和证书准备好后新增站点) 新增 `mp-admin` 站点，并从电脑浏览器检查 `https://mp-admin.yuhetangtcm.com/admin`，初始账号为 `admin`，密码为前面填写的 `ADMIN_PASSWORD`。两个站点共用当前泛域名证书与 Java 服务。腾讯云安全组和本机防火墙按现状核对 80/443 入站；不公开数据库和 8790/8791 端口。

域名未备案的问题仍需通过腾讯云备案流程处理；证书和 DNS 解析不替代备案。HTTP/HTTPS 本机成功也不等于公网或微信真机验收完成。

## 6. 登录后由用户逐步导入真实资料

账号 `admin` 的密码为安装前填写的 `ADMIN_PASSWORD`。首次看到空门店、空列表是预期状态。按现有“资料导入”入口操作，每份文件先预检，再确认导入：

1. **门店资料**：按模板填写实际门店。`id` 使用稳定的字母、数字、横线或下划线；新编号创建门店，同编号更新，空白可选字段不会覆盖已有值。新门店默认关闭在线预约。
2. **养护品项资料**：导入后保存为品牌草稿；在“品牌与内容”维护实际文案、图片并发布。未导入品项时可保存品牌草稿，发布前至少配置一个项目。
3. **调理师资料**：使用已存在的 `storeId` 和已发布的项目编号；品项编号以英文逗号分隔。
4. **会员基础资料**：使用已存在的 `storeId`，按完整手机号匹配更新客户。
5. **门店与时段、用户管理**：核对营业时段、名额、本店品项和调理师配置；创建店长/前台账号，确认无误再开启在线预约。

以上导入不会自动创建预约或向客户发送通知。大众点评客户及订单/预约以后需要时，先完成对应门店与项目映射，再使用现有点评导入入口。

## 7. 备份、开机启动和客户微信链路

后台读写确认正常后：

```bash
/usr/local/libexec/yuwatang-dbctl backup
systemctl enable yuwatang-mysql-isolated.service yuwatang-reservation.service
```

备份只会短暂停止本项目 Java 服务，导出新容器内业务库、应用文件和受保护配置，随后恢复本项目原运行状态。成功备份再保存一份加密异机副本，不放到公开 OSS。恢复演练使用单独新容器 3308，操作及额外资源要求见 [独立部署说明](README.md#5-备份和恢复演练)。

备案、HTTPS 和 AppSecret 齐备后，在小程序后台配置 request 合法域名 `https://booking.yuhetangtcm.com`，downloadFile 加入实际使用的 `https://booking.yuhetangtcm.com` 与 `https://static.yuhetangtcm.com`。uploadFile 仅填写真实上传目标；当前通过本后端上传时填 booking，OSS/CDN 图片域名不是自动具备直传接口。当前项目未使用 WebSocket，不必为了这个部署增加 socket 域名。

客服消息推送地址为 `https://booking.yuhetangtcm.com/api/wechat/customer-service`，安全模式、XML 或 JSON，Token/AESKey 与后端相同。先启动服务再向微信提交验签。客户真实微信登录、主动订阅、门店确认、收到处理提醒、到店前提醒、原生客服双向消息必须逐项真机验收。

日常经营内容和订阅模板配置通过后台/API 更新；小程序代码统一验收后发布。说明见 [小程序接口更新约定](../../docs/小程序接口更新约定.md)。本次空业务库包重新构建了 JAR，并使用新的包名和校验值；旧包未覆盖。小程序源码需要单独通过开发者工具提交，不包含在服务端 JAR 中。
