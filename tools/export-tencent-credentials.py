#!/usr/bin/env python3
"""Export only this deployment's credentials to a new, root-only Markdown file.

Run on TencentOS after prepare.sh. Python 3.6+; no third-party dependencies.
Environment configuration is preserved verbatim, never sourced or evaluated.
"""
import argparse
import datetime
import os
from pathlib import Path
import re
import stat
import sys


APP_ENV = Path('/etc/yuwatang-reservation.env')
DB_SECRETS = Path('/etc/yuwatang-reservation-db/secrets')
EXPECTED_URL = ('jdbc:mariadb://127.0.0.1:3307/yuwatang_reservation?'
                'useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai&'
                'allowPublicKeyRetrieval=true&sslMode=disable&connectTimeout=3000&socketTimeout=10000')


class ExportError(Exception):
    pass


def read_private_file(path, check_permissions=True):
    path = Path(path)
    try:
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or path.is_symlink():
            raise ExportError('Required credential input is not a regular file: {}'.format(path))
        if check_permissions and (info.st_uid != 0 or stat.S_IMODE(info.st_mode) & 0o077):
            raise ExportError('Credential input must be owned by root and inaccessible to group/others: {}'.format(path))
        if info.st_size > 131072:
            raise ExportError('Credential input exceeds the expected size: {}'.format(path))
        return path.read_text(encoding='utf-8-sig')
    except OSError:
        raise ExportError('Cannot read the dedicated credential file; finish prepare.sh first: {}'.format(path))
    except UnicodeError:
        raise ExportError('Credential input is not valid UTF-8: {}'.format(path))


def config_value(configuration, key):
    # Required generated values are single-line literals; no shell expansion.
    found = None
    for line in configuration.splitlines():
        candidate, separator, value = line.partition('=')
        if separator and candidate.strip() == key:
            found = value.strip()
    if found is None:
        raise ExportError('Required configuration key is missing: {}'.format(key))
    if len(found) >= 2 and found[0] == found[-1] and found[0] in "'\"":
        found = found[1:-1]
    return found


def fenced(value, language='text'):
    runs = [len(match.group(0)) for match in re.finditer(r'`+', value)]
    fence = '`' * max(3, max(runs, default=0) + 1)
    return '{}{}\n{}\n{}\n'.format(fence, language, value.rstrip('\n'), fence)


def render_record(configuration, app_password, root_password, timestamp):
    if config_value(configuration, 'DB_URL') != EXPECTED_URL:
        raise ExportError('Database URL is not the dedicated 3307 target; no record was written.')
    if config_value(configuration, 'DB_USERNAME') != 'yuwatang_app':
        raise ExportError('Database user is not the dedicated application user; no record was written.')
    if not app_password or not root_password or '\n' in app_password or '\n' in root_password:
        raise ExportError('A dedicated database password is empty or has unexpected line breaks.')
    if config_value(configuration, 'DB_PASSWORD') != app_password:
        raise ExportError('Application DB_PASSWORD differs from the dedicated password file; reconcile them first.')
    return (
        '# 裕和堂部署账号与密钥记录（服务器实值）\n\n'
        '导出时间：{}（北京时间）。本文件含真实凭据，仅供私密运维保管。\n\n'
        '这是本项目磁盘配置快照，没有连接数据库或验证登录。后台修改后的当前管理员密码需另外更新；'
        '`ADMIN_PASSWORD` 是首次初始化配置值，不会自动跟随后台改密。\n\n'
        '## 入口与账号\n\n'
        '| 项目 | 值 |\n| --- | --- |\n'
        '| 腾讯云服务器 | `43.142.125.157` |\n'
        '| 管理后台 | `https://mp-admin.yuhetangtcm.com/admin` |\n'
        '| 小程序 API | `https://booking.yuhetangtcm.com` |\n'
        '| 独立数据库 | `127.0.0.1:3307 / yuwatang_reservation` |\n'
        '| 应用数据库账号 | `yuwatang_app` |\n'
        '| 本次数据库容器管理员 | `root`，仅本实例内部 |\n'
        '| 后台初始账号 | `admin` |\n'
        '| 后台当前密码（改密后人工记录） | 待填写 |\n\n'
        '## 应用数据库密码\n\n'
        '来源：`/etc/yuwatang-reservation-db/secrets/app-password`，与应用环境配置一致。\n\n'
        '{}\n'
        '## 独立数据库 root 密码\n\n'
        '来源：`/etc/yuwatang-reservation-db/secrets/root-password`。不是原 MySQL 3306 或服务器 root 的密码。\n\n'
        '{}\n'
        '## 原始应用配置：管理员初始密码、JWT、微信与通知配置\n\n'
        '以下逐字保存 `/etc/yuwatang-reservation.env`，引号及转义保持原样，未执行其中内容。'
        '空值表示尚未配置；数据库中的通知设置仍需通过业务库备份保存。\n\n'
        '{}\n'
        '## 恢复定位\n\n'
        '- 数据：`/var/lib/yuwatang-mysql-isolated`；应用文件：`/var/lib/yuwatang-reservation`。\n'
        '- 数据库维护配置：`/etc/yuwatang-reservation-db`；应用配置：`/etc/yuwatang-reservation.env`。\n'
        '- 备份：`/var/backups/yuwatang-reservation`；维护工具：`/usr/local/libexec/yuwatang-dbctl`。\n'
        '- TLS 私钥：`/etc/yuwatang-reservation/tls/privkey.key`（本导出不展开证书私钥）。\n'
        '- 本文件不包含业务数据、现有数据库凭据或云控制台密码，不能代替异机数据备份。\n'
    ).format(timestamp, fenced(app_password), fenced(root_password), fenced(configuration, 'ini'))


def export_record(environment_file, secrets_dir, output, check_permissions=True):
    configuration = read_private_file(environment_file, check_permissions)
    app_password = read_private_file(Path(secrets_dir) / 'app-password', check_permissions).rstrip('\n')
    root_password = read_private_file(Path(secrets_dir) / 'root-password', check_permissions).rstrip('\n')
    timestamp = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(timespec='seconds')
    document = render_record(configuration, app_password, root_password, timestamp)
    output = Path(output)
    if not output.is_absolute():
        raise ExportError('Specify an absolute output path inside your private root directory.')
    try:
        parent_info = output.parent.lstat()
        if not stat.S_ISDIR(parent_info.st_mode) or output.parent.is_symlink():
            raise ExportError('Output parent must be an existing private directory, not a link.')
        if check_permissions and (parent_info.st_uid != 0 or stat.S_IMODE(parent_info.st_mode) & 0o077):
            raise ExportError('Output directory must be root-owned with mode 0700.')
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0)
        descriptor = os.open(str(output), flags, 0o600)
        with os.fdopen(descriptor, 'w', encoding='utf-8', newline='\n') as stream:
            stream.write(document)
    except FileExistsError:
        raise ExportError('Output already exists; use a new filename. Existing record was not changed.')
    except OSError:
        raise ExportError('Could not write the new private record; check path, permissions and free disk space.')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, help='New absolute Markdown path, for example /root/yuhetang-upload/yuhetang-credentials-20261105.md')
    args = parser.parse_args(argv)
    try:
        if os.name != 'posix' or os.geteuid() != 0:
            raise ExportError('Run as root on the TencentOS server after preparing this deployment.')
        export_record(APP_ENV, DB_SECRETS, args.output)
    except ExportError as error:
        print('STOP: {}'.format(error), file=sys.stderr)
        return 1
    print('Credential record saved (mode 0600); download privately: {}'.format(args.output))
    return 0


if __name__ == '__main__':
    sys.exit(main())
