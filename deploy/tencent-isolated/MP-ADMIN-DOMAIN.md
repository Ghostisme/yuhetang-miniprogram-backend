# 管理后台域名 mp-admin.yuhetangtcm.com

2026-11-05：用户新增管理后台域名。通过公网 DNS 查询确认，`mp-admin.yuhetangtcm.com` 与 `booking.yuhetangtcm.com` 的 A 记录均为 `43.142.125.157`，TTL 600 秒；已有证书的 SAN 包含 `*.yuhetangtcm.com`，覆盖两个域名。这里记录的是本次查询结果，不代表 Nginx、HTTPS 或服务器安装已经完成。

| 用途 | 地址 |
| --- | --- |
| 总部、店长、前台管理后台 | `https://mp-admin.yuhetangtcm.com/admin` |
| 小程序 API 与 `PUBLIC_URL` | `https://booking.yuhetangtcm.com` |
| 微信客服消息回调 | `https://booking.yuhetangtcm.com/api/wechat/customer-service` |
| OSS/CDN 图片 | `https://static.yuhetangtcm.com` |

管理路径沿用 `/admin`；后台中的客户页面预览链接访问同源根路径，继续使用现有 H5 预览。两个入口由同一个 Java 服务处理，管理 API、静态资源和上传路径均通过新域名的反向代理访问，因此不需要浏览器跨域配置或另一套数据库。浏览器登录凭证按域名保存，切换域名后正常重新登录即可。小程序无需增加 mp-admin 为合法域名，API 仍使用 booking。

## 1. 新包已包含管理后台站点配置

本次使用 `yuhetang-tencent-host-isolated-20261105-clean.tar.gz`，其中已包含 `nginx.mp-admin.conf.example`。按 [上传后续操作](CONTINUE-FROM-UPLOAD.md) 校验整个包和解压目录中的 `SHA256SUMS`，不需要单独上传旧补充文件。旧发布包和校验值保留用于历史核对。

## 2. 应用和证书准备好后，新增站点

先完成 [上传后续操作](CONTINUE-FROM-UPLOAD.md) 中的资源预检、独立数据库与应用启动、证书安装。当前可用内存和磁盘不足的问题仍需解决。新域名不改变本次部署的资源需求。

```bash
curl --fail http://127.0.0.1:8790/api/health
openssl x509 -in /etc/yuwatang-reservation/tls/fullchain.pem -noout -dates -checkhost mp-admin.yuhetangtcm.com
nginx -t
nginx -T 2>&1 | awk '/^[ \t]*server_name[ \t]/ && /mp-admin[.]yuhetangtcm[.]com/ {print}'
```

确认健康检查成功、证书匹配且未过期、原 Nginx 校验成功。最后一条应没有输出；若已经存在此域名配置，先核对已有配置，不重复添加。

在确认无同名站点后执行：

```bash
cd /root/yuhetang-upload/yuhetang-tencent-host-isolated-20261105-clean
if test ! -e /www/server/panel/vhost/nginx/mp-admin.yuhetangtcm.com.conf && test ! -L /www/server/panel/vhost/nginx/mp-admin.yuhetangtcm.com.conf; then
    install -o root -g root -m 0644 nginx.mp-admin.conf.example /www/server/panel/vhost/nginx/mp-admin.yuhetangtcm.com.conf && nginx -t && nginx -s reload
else
    echo '目标配置已存在，已停止，未覆盖。'
fi
```

`nginx -t` 失败时不会重载，保留输出，先修正本次新文件。这里只新增此域名配置；证书与 booking 共用，应用和数据库不需要重新安装。

若校验明确提示 `could not build server_names_hash`，检查主配置 `/www/server/nginx/conf/nginx.conf` 的 `http {}`：已有 `server_names_hash_bucket_size` 时调整原值至至少 `64`；没有时在该 `http` 块内添加 `server_names_hash_bucket_size 64;`，不要在多个站点文件中重复添加。备份主配置后再调整，随后重新执行 `nginx -t`，成功后才重载。

## 3. 验收登录和操作

```bash
curl --fail --resolve mp-admin.yuhetangtcm.com:443:127.0.0.1 https://mp-admin.yuhetangtcm.com/api/health
curl --fail --head --resolve mp-admin.yuhetangtcm.com:443:127.0.0.1 https://mp-admin.yuhetangtcm.com/admin
```

本机 HTTPS 检查成功后，在电脑打开 `https://mp-admin.yuhetangtcm.com/admin`。使用管理员 `admin` 和本次 `ADMIN_PASSWORD` 登录，再验收列表读取、门店权限、保存内容、图片上传及客户页面预览。登录请求应为同源的 `https://mp-admin.yuhetangtcm.com/api/admin/login`。

安全组和服务器防火墙按现状核对 80/443；不需要额外公开 8790、8791 或数据库端口。网站备案按此前流程办理。DNS 正常和证书覆盖均不能替代云端 Nginx 校验、实际登录或备案完成。

店长服务号链路按用户约定后续单独验收；目前旧配置生成的服务号通知链接仍使用 `PUBLIC_URL` 加 `/admin`。该地址继续兼容，本次不改变服务号授权回调域名。

本地验证：使用官方 Nginx 1.28.0 Windows 二进制、临时测试证书，在 `http` 配置的 `server_names_hash_bucket_size 64` 下同时加载 booking 与 mp-admin 两个配置，`nginx -t` 通过。未启动 Nginx 服务；腾讯云上的实际版本、证书安装、登录和业务操作仍按上文验收。
