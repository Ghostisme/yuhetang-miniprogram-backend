"""Set the three official-account message settings without exposing the AES key.

Run interactively as root on the Linux server. Keep every other Docker setting.
The tool backs up the environment file, but does not restart Java or access MySQL.
"""
import base64
import datetime as dt
import getpass
import os
from pathlib import Path
import re
import secrets
import stat
import sys
import tempfile

ENVIRONMENT = Path('/etc/yuwatang-reservation.docker.env')
APP_ID = 'wxf9ad4dfd7684097a'
EDITABLE = frozenset({'SERVICE_MESSAGE_TOKEN', 'SERVICE_MESSAGE_AES_KEY', 'SERVICE_MESSAGE_ALLOW_PLAINTEXT'})


def settings(original):
    values = {}
    for line in original.splitlines():
        if line.lstrip().startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        if key in EDITABLE or key == 'SERVICE_APP_ID':
            if key in values:
                raise ValueError('Duplicate configuration key: ' + key)
            values[key] = value
    return values


def render(original, token, aes_key):
    current = settings(original)
    if current.get('SERVICE_APP_ID') != APP_ID:
        raise ValueError('SERVICE_APP_ID must already be ' + APP_ID + '; no settings were changed.')
    if not re.fullmatch(r'[A-Za-z0-9]{3,32}', token):
        raise ValueError('Token must contain 3-32 English letters or digits.')
    if not re.fullmatch(r'[A-Za-z0-9+/]{43}', aes_key):
        raise ValueError('Paste the exact 43-character EncodingAESKey from the official-account settings.')
    try:
        raw = base64.b64decode(aes_key + '=', validate=True)
        if len(raw) != 32:
            raise ValueError()
    except ValueError:
        raise ValueError('EncodingAESKey must encode a valid 32-byte AES key.') from None
    changes = {'SERVICE_MESSAGE_TOKEN': token, 'SERVICE_MESSAGE_AES_KEY': aes_key,
               'SERVICE_MESSAGE_ALLOW_PLAINTEXT': 'false'}
    output, seen = [], set()
    for line in original.splitlines(keepends=True):
        key = line.partition('=')[0]
        if key in changes:
            output.append(key + '=' + changes[key] + '\n')
            seen.add(key)
        else:
            output.append(line)
    missing = changes.keys() - seen
    if missing and output and not output[-1].endswith(('\n', '\r')):
        output.append('\n')
    output.extend(key + '=' + changes[key] + '\n' for key in sorted(missing))
    return ''.join(output)


def read_private(path):
    descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    with os.fdopen(descriptor, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
            raise ValueError('Keep the existing environment file as a regular 0600 file.')
        if hasattr(os, 'geteuid') and info.st_uid != os.geteuid():
            raise ValueError('The environment file must belong to the current root user.')
        return stream.read()


def save(path, original, token, aes_key):
    content = render(original.decode('utf-8'), token, aes_key).encode('utf-8')
    if read_private(path) != original:
        raise ValueError('Environment file changed during input; rerun to avoid overwriting another edit.')
    if content == original:
        return None
    backup = path.with_name(path.name + '.before-service-messages-' + dt.datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    descriptor = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(original)
        stream.flush()
        os.fsync(stream.fileno())
    descriptor, temporary = tempfile.mkstemp(prefix='.yht-service-messages-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        if read_private(path) != original:
            raise ValueError('Concurrent environment edit detected; original kept and backup retained.')
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return backup


def main():
    if not hasattr(os, 'geteuid') or os.geteuid() != 0 or not sys.stdin.isatty():
        print('Run interactively: sudo python3 -B yuhetang-configure-service-messages-20261109.py', file=sys.stderr)
        return 1
    try:
        original = read_private(ENVIRONMENT)
        current = settings(original.decode('utf-8'))
        if current.get('SERVICE_APP_ID') != APP_ID:
            raise ValueError('Check SERVICE_APP_ID: expected ' + APP_ID + '.')
        token = getpass.getpass('SERVICE_MESSAGE_TOKEN (hidden; Enter keeps current or generates a new one): ').strip()
        token = token or current.get('SERVICE_MESSAGE_TOKEN') or secrets.token_hex(16)
        aes_key = getpass.getpass('SERVICE_MESSAGE_AES_KEY (hidden; paste the 43-char WeChat key, Enter keeps current): ').strip()
        aes_key = aes_key or current.get('SERVICE_MESSAGE_AES_KEY', '')
        backup = save(ENVIRONMENT, original, token, aes_key)
    except (ValueError, OSError, UnicodeError, EOFError) as error:
        message = str(error) if isinstance(error, ValueError) else type(error).__name__
        print('STOP: ' + message, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print('\nCancelled. No changes were saved.', file=sys.stderr)
        return 130
    print('Saved: ' + str(ENVIRONMENT) if backup else 'Settings already match; no file changed.')
    if backup:
        print('Private configuration backup: ' + str(backup))
    print('Copy the following Token into the OFFICIAL ACCOUNT message-push settings:')
    print('SERVICE_MESSAGE_TOKEN=' + token)
    print('SERVICE_MESSAGE_AES_KEY=configured (43 characters; not displayed)')
    print('SERVICE_MESSAGE_ALLOW_PLAINTEXT=false')
    print('URL=https://mp-admin.hetuonian.cn/api/wechat/service/messages')
    print('Mode=safe; format=XML; AppID=' + APP_ID)
    print('NEXT: recreate Java with the release activator --reload-env, then save the WeChat settings.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
