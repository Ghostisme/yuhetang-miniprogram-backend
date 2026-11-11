# 会员卡余额与消费定时同步

Java 负责客户归属、同步队列及数据库事务；独立 Python 定时任务使用已授权的酷鼠登录会话，按正常会员查询界面读取数据。查询是只读的，不充值、不扣款，不重放历史消费为余额流水。

客户完成微信手机号授权并打开会员余额或消费记录后，系统自动登记同步任务。默认每个已登记客户约 5 分钟进入一次更新队列，任务按队列顺序执行；小程序可见页面每 15 秒读取一次后台最新结果。客户选择日期和门店只过滤数据库，不向酷鼠发起采集。

逐卡核对会员详情中的完整手机号、卡数量和总余额。搜索列表可以显示掩码手机号，身份以详情的完整手机号为准。每张卡按卡号和所属门店展示；同门店同卡号已录入本地期初账户时，本地账户优先。原系统同步卡只读。请核对完整迁移截止时间和卡号后再开始本地维护，避免两个系统同时维护同一余额。

酷鼠当前正常客户查询界面返回近期消费，本次已用实际界面验证可获得 20 条。此程序不宣称获得全部历史，已同步的旧消费会保留；之后有单独导出的历史明细可继续补入，不重复扣款。未匹配、登录过期、部分卡缺失或总额不符时保留上次成功数据，页面显示同步状态与时间。

## 首次安装

本目录由整体发布包补齐 `ku52_export.py`、`ku52_data.py` 和 `stores.json`。本包不包含登录 Cookie、密码和生产数据。

1. 将本机已经登录酷鼠后的源系统专用 `browser-state.json` 私下上传至 `/home/ubuntu/yuhetang-ku52-browser-state.json`。文件包含登录会话，不要贴到聊天里，也不要放在网站目录。当前可用的本机原文件为 `D:\github_code\裕和堂相关项目\ku52-member-exporter\data\browser-state.json`。
2. 在整体包目录执行：

```bash
sudo python3 -B member-sync/configure.py --session /home/ubuntu/yuhetang-ku52-browser-state.json
sudo bash update.sh --check
sudo bash update.sh --reload-env
sudo bash member-sync/install.sh
```

配置脚本只增加 `KU52_SYNC_TOKEN` 和 `KU52_SYNC_INTERVAL_SECONDS`，自动生成内部凭证，原配置先备份到权限为 0700 的 `/etc/yuhetang-ku52-sync`。Java 必须通过更新入口重新创建容器加载配置，`docker restart` 不会重新读取 env 文件。安装脚本下载独立 Python 虚拟环境和 Playwright Chromium，不修改 OpenResty 站点和 MySQL 安装。

3. 用已授权手机号的客户微信打开一次「我的预约 → 会员余额」，再执行：

```bash
sudo systemctl start yuhetang-ku52-sync.service
sudo journalctl -u yuhetang-ku52-sync.service -n 30 --no-pager
```

成功日志形如 `SYNC state=READY cards=4 orders=20`。`IDLE` 表示当前没有到期任务。确认后启用定时任务：

```bash
sudo systemctl enable --now yuhetang-ku52-sync.timer
sudo systemctl list-timers --all yuhetang-ku52-sync.timer
```

定时器每批结束后约 1 分钟检查队列，每批最多 2 个客户，避免多个采集进程重叠。实际更新速度受队列长度与源系统响应影响，页面展示成功采集时间和延迟状态。默认配置可通过 `configure.py --interval-seconds 300` 修改，之后用整体更新入口 `--reload-env` 生效。

## 登录会话过期

看到 `LOGIN_REQUIRED` 时，在原系统重新正常登录，更新专用浏览器状态文件，上传后执行：

```bash
sudo python3 -B member-sync/configure.py --session /home/ubuntu/yuhetang-ku52-browser-state.json
sudo systemctl start yuhetang-ku52-sync.service
```

若任务未到下一次更新时间会暂时显示 `IDLE`，等待定时器即可。仅替换登录状态而内部凭证未变化时，无需重新发布小程序或重建 Java。源系统限制访问时停止该批读取并保留数据，不绕过登录或验证。

## 数据与服务位置

- 新表：`ku52_sync_job`、`ku52_member_card`、`ku52_consumption_record`，位于现有独立 `yuwatang_reservation` 库。
- 采集代码：`/opt/yuhetang-ku52-sync`；私有配置和会话：`/etc/yuhetang-ku52-sync`。
- 查看状态：`systemctl status yuhetang-ku52-sync.timer`，`journalctl -u yuhetang-ku52-sync.service`。
- 暂停采集：`sudo systemctl disable --now yuhetang-ku52-sync.timer`。上次成功数据仍可查询。
- 部署前完整备份由整体更新脚本执行；原会员归属门店、本地余额流水及现有消费记录保留。
