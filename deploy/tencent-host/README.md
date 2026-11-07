# 腾讯云现有宝塔主机部署

> **旧方案已被替代。用户要求不共用原 MySQL，且确认尚未执行旧命令。请使用 [独立数据库部署流程](../tencent-isolated/README.md) 和 `yuhetang-tencent-host-isolated-20261105.tar.gz`；不要执行本页面向现有 MySQL 的步骤。** 本页仅保留旧版参考。

适用主机：`43.142.125.157`，TencentOS Server 3.1 / x86_64，宝塔 Nginx、MySQL **5.7.44**、系统 Java 8。预约系统使用独立数据库、独立 Java 17、独立 systemd 服务；公网入口为 `https://booking.yuhetangtcm.com`，后台路径 `/admin`。

这是在现有主机添加应用的流程。`prepare.sh` 只准备文件和服务定义，不会启动应用或修改 Nginx。真实微信发送、证书、备案与云端容量需要分别验收。

## 1. 先处理现有资源

用户提供的服务器快照：内存可用约 1.9GB；根盘 50GB，已用 95%，剩余约 **2.5GB**。安装脚本要求根盘至少 **5GiB 可用**、可用内存至少 **1.5GiB**，并检查 8790 / 8791 未占用。该阈值用于保留安装和回滚空间，不代表容量验收。

先在腾讯云扩容系统盘并扩展文件系统，或在宝塔确认磁盘占用来源后自行处理已有文件；清理前确认文件用途及备份。不要运行容器 prune、删除现有数据库、替换全局 Java 或修改 `/ai/` 的站点。

检查当前情况：

```bash
df -h /
free -m
sudo ss -lnt '( sport = :8790 or sport = :8791 )'
```

此主机已有多项应用，当前部署预算只用于功能联调和小规模试运行。**没有验证它能支撑 5 万人同时在线**；上线容量需结合实际请求频率、带宽、数据库及云端压测确定。

## 2. 在宝塔创建专用数据库

宝塔「数据库 → MySQL → 添加数据库」：

| 项目 | 填写 |
| --- | --- |
| 数据库名 | `yuwatang_reservation` |
| 用户名 | `yuwatang_app` |
| 密码 | 为此账号新建独立强密码，仅保存在服务器 |
| 字符集 | `utf8mb4`；排序规则如 `utf8mb4_unicode_ci` |
| 访问权限 | 本地服务器；确认允许 `127.0.0.1` 连接 |

只给这个账号本库权限。若同名库已存在，先核对所有者和数据，不直接覆盖或复用。宝塔可正常管理数据库时，不需要在聊天中提供或重置 MySQL root 密码；若宝塔本身也无法创建数据库，再由服务器管理员处理面板连接问题。

应用首次启动自动执行 Flyway V1–V6。正式模式只创建总部 `admin`，不创建本地演示门店账号或示例调理师。初始门店和品牌资料仍需在后台核对并完善。本地 H2 的客户、预约和配置不会自动迁移到 MySQL；完整迁移需要另行备份、导入和核对，大众点评导入不能替代系统迁移。

## 3. 上传、检查和准备文件

将 `yuhetang-tencent-host-20261105.tar.gz` 及同名 `.sha256` 文件上传到服务器新建目录 `/root/yuhetang-upload`。归档包含已构建 JAR、专用 Java 17 运行时、脚本及配置模板，不包含本地数据库、上传图片、密码或 AppSecret。

在该目录执行，目标解压目录应尚不存在：

```bash
cd /root/yuhetang-upload
sha256sum --check yuhetang-tencent-host-20261105.tar.gz.sha256
test ! -e yuhetang-tencent-host-20261105
tar -xzf yuhetang-tencent-host-20261105.tar.gz
cd yuhetang-tencent-host-20261105
sha256sum --check SHA256SUMS
bash preflight.sh
sudo bash prepare.sh
```

如果上一条命令失败，先解决原因，不继续执行后续命令。`prepare.sh` 首次安装会创建：

| 路径 | 用途 |
| --- | --- |
| `/opt/yuhetang-java17` | 本应用专用 Java 17，不改变 `/usr/bin/java` |
| `/srv/yuwatang-reservation/reservation.jar` | 已打包前端、API 的应用 |
| `/var/lib/yuwatang-reservation` | 本应用上传等持久化文件 |
| `/etc/yuwatang-reservation.env` | root 专用环境配置，权限 600 |
| `/etc/systemd/system/yuwatang-reservation.service` | 专用服务，使用 `yuwatang` 系统用户 |

已有同名安装时脚本会停止，升级请看文末。Java 运行时归档会再校验固定 SHA256；若从源码单独制作了不含运行时的归档，脚本才从官方发布地址下载。

## 4. 仅在服务器填写环境配置

使用宝塔终端编辑 `/etc/yuwatang-reservation.env`，使用 `KEY=value` 格式，不写 `export`、命令替换或命令。不要把内容粘贴回聊天。

| 变量 | 内容 |
| --- | --- |
| `DB_PASSWORD` | 上一步专用数据库用户的密码 |
| `JWT_SECRET` | 至少 32 字符随机密钥，例如在本机执行 `openssl rand -hex 32` 生成后填写 |
| `ADMIN_PASSWORD` | 首次建库的总部管理员初始密码，仅初次生效 |
| `WECHAT_APP_SECRET` | 小程序 `wxce054e18303efb8a` 的 AppSecret |
| `WECHAT_MESSAGE_TOKEN` | 自定义 3–32 位字母数字，与微信消息推送配置一致 |
| `WECHAT_MESSAGE_AES_KEY` | 微信消息推送生成的 43 字符 EncodingAESKey |

模板已填写真实 AppID、两个客户模板 ID 及各自字段映射，详见 `wechat-templates.json`。`SERVICE_*` 是门店店长服务号通知的另一条通道，可暂空；短信同样可暂空。客户小程序订阅及原生客服使用 `WECHAT_*`。

`DB_URL` 使用 `jdbc:mariadb://127.0.0.1:3306/yuwatang_reservation?...`，选择兼容 MySQL 5.7 的 MariaDB JDBC 驱动；连接的仍是现有 **MySQL**，不安装 MariaDB 服务。不要改为本机其他业务库。

脚本限制 Java 堆 512MB、进程内存上限 1GB、数据库池 8 条连接、48 个同时执行请求。这些是保护现有共享主机的起步值，不是在线人数承诺。

填好后检查权限并启动：

```bash
sudo chmod 600 /etc/yuwatang-reservation.env
sudo systemctl start yuwatang-reservation
sudo systemctl status yuwatang-reservation --no-pager
curl --fail http://127.0.0.1:8790/api/health
curl --fail http://127.0.0.1:8790/api/runtime
curl --fail http://127.0.0.1:8791/actuator/health
```

健康检查成功，再设置开机启动：

```bash
sudo systemctl enable yuwatang-reservation
```

失败时只查看本服务日志：`sudo journalctl -u yuwatang-reservation -n 100 --no-pager`。对外分享前检查是否含业务资料，环境配置文件和密钥不发送。8790、8791、数据库均不新增公网放行。

## 5. 域名、备案和 HTTPS

阿里云 DNS 的 `booking` A 记录已保存并解析到 `43.142.125.157`。域名注册和 DNS 继续保留在阿里云即可，备案通过实际承载服务的腾讯云办理。当前域名尚未备案，先确认主体首次备案或新增网站流程；小程序自身备案另行核对。

在宝塔为 **booking.yuhetangtcm.com** 新建独立站点，目录 `/www/wwwroot/booking.yuhetangtcm.com`，申请并安装相应 HTTPS 证书。若该站点已存在，先核对配置与证书。不要修改现有 `app/label/qr.hetuonian.cn` 和 IP 下的 `/ai/`。

归档的 `nginx.booking.conf.example` 对应宝塔目录：

```text
/www/server/panel/vhost/nginx/booking.yuhetangtcm.com.conf
/www/server/panel/vhost/cert/booking.yuhetangtcm.com/fullchain.pem
/www/server/panel/vhost/cert/booking.yuhetangtcm.com/privkey.pem
```

证书存在、应用健康后，备份这个新站点的原配置，在宝塔中按模板调整此站点。模板含 `http` 层的独立登录限流区；应整份放在宝塔 vhost 文件中，不要将该指令粘贴进 `server {}` 内。核对实际证书路径，然后：

```bash
sudo nginx -t
# 只有检查成功才执行：
sudo nginx -s reload
curl --fail https://booking.yuhetangtcm.com/api/health
curl --fail https://booking.yuhetangtcm.com/api/runtime
```

本地仅做了配置结构检查，服务器 `nginx -t` 和公网证书验证尚待执行。

## 6. 微信控制台和真机验收

1. 配置服务器域名 request / uploadFile / downloadFile：`https://booking.yuhetangtcm.com`。
2. 按微信后台要求核对服务器出口 IP 白名单、隐私指引及小程序备案。
3. 启用原生客服消息推送：URL 为 `https://booking.yuhetangtcm.com/api/wechat/customer-service`，Token / EncodingAESKey 与环境配置一致，安全模式，XML 或 JSON。
4. 总部后台「扩展与配置」查看两组客户模板字段。初次缺失时由环境变量初始化；已有总部配置不会在重启时被覆盖。实际模板变更后，由总部管理员更新对应字段。
5. 「消息中心」核对各门店客户微信订阅渠道开关；模板授权由客户主动点击取得。确认和到店提醒分别消耗对应模板的一次授权；拒绝授权仍可预约。
6. 域名验证正常后，将 `miniprogram/config.js` 的 `apiBase` 改成正式域名，保留 `developmentLogin: false`，运行 `npm run check:mini`，再用微信开发者工具和真机验收后上传。

真机验证两条链路：预约 → 两模板订阅 → 门店确认 → 收到处理提醒 → 到店前收到提醒；原生客服发消息 → 对应门店后台出现会话 → 回复到微信。消息记录 `ACCEPTED` 仅代表微信受理，不能当作已送达或已读。

## 7. 备份、升级与回退

上线前配置此库的数据库备份和 `/var/lib/yuwatang-reservation/uploads/` 文件备份，并在隔离实例演练恢复。宝塔可设置只针对 `yuwatang_reservation` 的备份任务；备份文件含业务资料，保留异地副本和受控访问。备份数据库时不要把密码放在命令行参数或聊天中。

升级前先保存当前 JAR、环境配置及本应用数据备份；再停止**此服务**、替换已校验的新 JAR、启动并检查健康。回退使用保存的旧 JAR；如果升级含数据库迁移，应预先设计兼容回退方案，不能仅替换 JAR 后假定数据库已还原。

本次增加两个模板不需要新增数据库迁移。安装和回退均不涉及其他服务、数据库或全局 Java。
