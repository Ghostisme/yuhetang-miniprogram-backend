# 仅切换小程序客服消息推送回调域名

本次仅配置 `https://booking.hetuonian.cn/api/wechat/customer-service`。管理后台、现有小程序业务接口、Java 服务及数据库继续使用现有配置，无需重新上传前端、重装后端或重新发布小程序。

## 已确认

- 腾讯云公网 IP：`43.142.125.157`。
- `booking.hetuonian.cn` 已有指向该 IP 的 A 记录。
- OpenResty 容器 `1Panel-openresty-Iiuu` 使用 `host` 网络；MySQL 容器使用 `bridge`，符合当前部署方式。
- Java 监听 `127.0.0.1:8790`，客服回调路径为 `/api/wechat/customer-service`，支持 GET 验证及 POST 消息。
- 2026-11-07 外部检查时，新域名 HTTP 返回 404，HTTPS 握手失败，尚未证明新回调可用。

## 1Panel 配置

1. 在「网站」创建一个独立的静态网站，域名填写 `booking.hetuonian.cn`，网站代号可用 `wechat-callback`。如果该域名已经绑定了网站，直接配置对应网站，避免重复绑定。这个网站仅作为 HTTPS 回调入口，运行目录不用上传任何前端文件。
2. 在「证书」申请或导入覆盖 `booking.hetuonian.cn` 的有效证书，也可使用覆盖 `*.hetuonian.cn` 的证书。原来的 `*.yuhetangtcm.com` 不覆盖新域名。在新回调网站的「HTTPS」中选择对应证书并启用，保留证书校验；导入证书本身并不等于网站已经启用 HTTPS。
3. 在这个新网站的「配置文件」内保留现有 `server_name`、`listen`、`root`、证书路径及面板 include。把同目录的 `wechat-callback.locations.conf` 片段放入该网站现有 `server { ... }` 内，并替换原有 `location /`，避免重复 location。不要用片段覆盖整个文件，也不要修改管理后台或原业务接口网站。
4. 保存并重载 OpenResty。新网站应包含 `server_name booking.hetuonian.cn;` 和 `listen 443 ssl;`；`proxy_pass http://127.0.0.1:8790;` 后不要附加路径，以保留完整回调路径及微信签名查询参数。

新回调站点只代理客服回调路径；访问其根目录或 `/api/health` 返回 404 属于预期，不是 Java 故障。

## 验证 HTTPS 与回调路由

在服务器终端执行：

```bash
docker exec 1Panel-openresty-Iiuu nginx -t
curl --noproxy '*' --resolve booking.hetuonian.cn:443:127.0.0.1 --connect-timeout 3 --max-time 10 -i https://booking.hetuonian.cn/api/wechat/customer-service
```

这条请求未携带微信的 `signature`、`timestamp`、`nonce`、`echostr`。如果 TLS 成功，响应为 Java 的 JSON 错误且 HTTP 400，说明域名、证书及到 Java 的路由已通；它尚不证明 Token 验签通过。不要把 400 改成固定返回 success，否则会破坏微信验证要求。

- TLS 握手失败或证书域名不匹配：检查新回调网站的域名绑定及证书。
- OpenResty HTML 404：检查是否匹配了该网站，以及精确回调 location 是否保存。
- 502：检查 Java 服务及 OpenResty 到 `127.0.0.1:8790` 的连接；当前已确认的 host 网络无需调整。
- 备案拦截或跳转页面：在腾讯云核对 `hetuonian.cn` 的备案和接入状态。

之后可从外部电脑访问同一 HTTPS 回调地址，确认公网访问同样到达 Java。不要使用 `-k` 跳过证书校验。

## 微信后台填写

| 项目 | 内容 |
| --- | --- |
| URL（服务器地址） | `https://booking.hetuonian.cn/api/wechat/customer-service` |
| Token | 与服务器 `/etc/yuwatang-reservation.env` 中 `WECHAT_MESSAGE_TOKEN` 完全相同 |
| EncodingAESKey | 与服务器 `WECHAT_MESSAGE_AES_KEY` 完全相同，43 位 |
| 消息加密方式 | 安全模式 |
| 数据格式 | XML |

本次仅变更回调 URL 时，保留原 Token、AESKey 与 AppID。如果此前已经修改环境文件但 Java 尚未重新读取配置，只需 `systemctl restart yuwatang-reservation.service`；仅修改域名和 OpenResty 不需要重启 Java。

微信提交时会携带签名等参数发起 GET，Java 验签后必须原样返回 `echostr`。实际发送客户消息还需要正确的 AppSecret、用户客服交互场景及后续收发验证，URL 验证通过不能替代真实客服测试。

官方依据：[微信消息推送配置与 URL 验证](https://developers.weixin.qq.com/miniprogram/dev/framework/server-ability/message-push.html)。
