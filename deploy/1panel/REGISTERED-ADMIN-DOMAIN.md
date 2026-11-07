# 管理后台公网连接被关闭：增加已备案域名入口

## 2026-11-07 20:03 至 20:05 的诊断证据

- 用户服务器连续三轮检查：Java `127.0.0.1:8790/api/health` 返回 200，本机 HTTPS 返回 200，服务器访问自身公网域名也返回 200。
- 外部 HTTP 请求 `http://mp-admin.yuhetangtcm.com/api/health` 返回 302，Location 指向 `https://dnspod.qcloud.com/static/webblock.html?d=mp-admin.yuhetangtcm.com`；外部 HTTPS 在握手时被关闭。
- 同一服务器的 `booking.hetuonian.cn` 能完成 HTTPS，客服回调在没有微信签名参数时返回 Java 的 400，说明请求可到达应用。
- 公共 DNS 查询中 `mp-admin.hetuonian.cn` 已指向 `43.142.125.157`；该新域名当时 HTTP 返回 OpenResty 404，HTTPS 握手失败，仍需要配置对应网站和证书。

这确认了原管理域名存在公网入口拦截。服务器访问自身返回 200 不能代替外部客户网络验证。拦截的具体原因和解除状态应以腾讯云控制台或工单为准；用户此前说明 `yuhetangtcm.com` 未备案。

原域名继续使用时，应先让腾讯云解除该域名拦截。以下是增加 `mp-admin.hetuonian.cn` 入口的配置方案，原网站、文件、Java、MySQL 与微信回调均保留。

## 1. 在 1Panel 创建独立静态网站

进入「网站 → 创建 → 静态网站」，填写：

| 字段 | 值 |
| --- | --- |
| 域名 | `mp-admin.hetuonian.cn` |
| 代号 | `mp-admin-hetuonian` |
| HTTP 端口 | `80` |
| HTTPS | 开启 |
| 证书 | 已申请的 `*.hetuonian.cn` 证书 |

必须选覆盖 `mp-admin.hetuonian.cn` 的证书；只有 `app.hetuonian.cn` 的单域名证书不覆盖这个地址。

DNS 已有解析结果，先核对当前 DNS 管理台，不要重复添加冲突的 A/CNAME 记录。

## 2. 新入口复用原管理后台文件

进入新网站「配置文件」，保留 1Panel 创建的 server、listen、证书、日志及 include 配置。将新网站 server 内现有 root 行替换为：

```nginx
root /www/sites/mp-admin/index;
```

这是 OpenResty 容器内路径，对应宿主机 `/opt/1panel/www/sites/mp-admin/index`，即原后台文件。不要改成 `/opt/1panel/...`，不要复制第二套前端。

在新站点同一个 server 块内按 [admin.locations.conf](admin.locations.conf) 添加后台路由和 API 反向代理。这个文件是 server 内片段，不能代替整个站点配置。需要注意：

- 原来的 `location /` 用片段中的 `location /` 替换，不能重复。
- 若已有 `client_max_body_size`，修改原值为 `6m`，不能再添加同名配置。
- API 上游仍然是 `http://127.0.0.1:8790`；OpenResty 使用已确认的 host 网络。
- 保留 `server_name mp-admin.hetuonian.cn;` 和新站点的 `*.hetuonian.cn` 证书路径。

保存后通过 1Panel 校验并重载配置。此次无需重启 Java、MySQL，也不用重新安装 OpenResty。

## 3. 本机与外部验证

服务器终端执行：

```bash
curl --noproxy '*' --resolve mp-admin.hetuonian.cn:443:127.0.0.1 \
  --connect-timeout 3 --max-time 10 -I \
  https://mp-admin.hetuonian.cn/admin
curl --noproxy '*' --resolve mp-admin.hetuonian.cn:443:127.0.0.1 \
  --connect-timeout 3 --max-time 10 -i \
  https://mp-admin.hetuonian.cn/api/health
```

前端应返回 200，API 应返回 200 且 `status=ok`、`database=connected`。

然后在自己电脑或手机网络中打开 `https://mp-admin.hetuonian.cn/admin` 并重新登录。登录令牌按网站分别保存，换入口后需要登录一次。前端 API 使用相对地址，新入口会请求自己的 `/api/...`，不需要修改前端代码或跨域设置。

仅服务器自测通过不算公网恢复；还需在外部浏览器的 Network 中确认 `/api/admin/chats`、`/api/admin/inbox` 返回正常。

## 4. 再更新客服修复包

当前用户确认 `20261107-195806` 的前后端都尚未部署。公网入口通后，再按照 [客服修复更新步骤](../../docs/客服修复更新步骤.md) 更新 Java 和原 `mp-admin/index` 目录。新入口共用这份文件，因此一次上传即可更新两个入口。

`booking.hetuonian.cn` 继续仅用于原客服回调。本轮不需要改小程序代码或重新提交审核。
