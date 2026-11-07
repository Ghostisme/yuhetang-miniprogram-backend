#!/usr/bin/env python3
"""Probe the running Docker API's WeChat callback without writing business data.

Uses Python 3 and openssl. Credentials remain in process memory. The encrypted
POST carries an unsupported event; ChatService ignores it before any DB writes.
This does not send a customer message or request a new WeChat access_token.
"""
import argparse
import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


CALLBACK_PATH = '/api/wechat/customer-service'
EXPECTED_APP_ID = 'wxce054e18303efb8a'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def running_config(container):
    result = subprocess.run(['docker', 'inspect', container], capture_output=True, timeout=10)
    if result.returncode:
        raise ValueError('Cannot inspect the running API container. Run with sudo and check its name.')
    item = json.loads(result.stdout)[0]
    if not item['State']['Running']:
        raise ValueError('API container is stopped.')
    env = dict(line.split('=', 1) for line in item['Config'].get('Env', []) if '=' in line)
    return env, item['Config']['Image']


def signature(token, timestamp, nonce, encrypted=None):
    parts = [token, timestamp, nonce]
    if encrypted is not None:
        parts.append(encrypted)
    return hashlib.sha1(''.join(sorted(parts)).encode('utf-8')).hexdigest()


def envelope(key_text, app_id):
    key = base64.b64decode(key_text + '=', validate=True)
    if len(key) != 32:
        raise ValueError('WECHAT_MESSAGE_AES_KEY must decode to 32 bytes.')
    payload = json.dumps({
        'ToUserName': 'yht-diagnostic', 'FromUserName': 'yht-diagnostic',
        'CreateTime': int(time.time()), 'MsgType': 'event',
        'Event': 'yht_diagnostic_probe',
    }, separators=(',', ':')).encode('utf-8')
    plain = os.urandom(16) + struct.pack('!I', len(payload)) + payload + app_id.encode('utf-8')
    padding = 32 - len(plain) % 32
    plain += bytes([padding]) * padding
    openssl = shutil.which('openssl')
    if not openssl:
        raise ValueError('openssl is required for the encrypted POST probe.')
    result = subprocess.run([
        openssl, 'enc', '-aes-256-cbc', '-nopad', '-K', key.hex(), '-iv', key[:16].hex(),
    ], input=plain, capture_output=True, timeout=5)
    if result.returncode:
        raise ValueError('Cannot encrypt the diagnostic event with openssl.')
    return base64.b64encode(result.stdout).decode('ascii')


def request(label, base, token, encrypted=None, trace=None):
    timestamp, nonce = str(int(time.time())), os.urandom(12).hex()
    if trace is not None:
        trace['nonce'] = nonce
    params = {'timestamp': timestamp, 'nonce': nonce}
    params['signature'] = signature(token, timestamp, nonce)
    expected = 'YHT_CALLBACK_CHECK_' + os.urandom(8).hex()
    body = None
    if encrypted is None:
        params['echostr'] = expected
    else:
        params['encrypt_type'] = 'aes'
        params['msg_signature'] = signature(token, timestamp, nonce, encrypted)
        body = json.dumps({'Encrypt': encrypted}).encode('utf-8')
        expected = 'success'
    url = base + CALLBACK_PATH + '?' + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, data=body, headers={'Content-Type': 'application/json; charset=utf-8'})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(req, timeout=10) as res:
            code, content = res.status, res.read(4096).decode('utf-8', 'replace')
    except urllib.error.HTTPError as exc:
        code, content = exc.code, exc.read(4096).decode('utf-8', 'replace')
    except Exception as exc:
        # Never print a URL-bearing exception: its query includes authentication signatures.
        print(label + ': FAIL network/TLS (' + type(exc).__name__ + ')')
        return False
    passed = code == 200 and content.strip() == expected
    print(label + ': ' + ('PASS' if passed else 'FAIL') + ' HTTP=' + str(code))
    if not passed:
        if code == 200:
            print('  Response body is not the expected callback acknowledgement.')
        elif code == 403:
            print('  Check signature/decryption configuration or reverse-proxy interception.')
        elif code in (301, 302, 307, 308, 404):
            print('  Check the exact callback host/path and reverse-proxy routing; redirects are not followed.')
        elif code >= 500:
            print('  Check Java and reverse-proxy errors at this timestamp.')
        # No raw response, keys, query parameters, or customer data are printed.
    return passed


def proxy_container(requested):
    if requested:
        return requested
    result = subprocess.run(['docker', 'ps', '--format', '{{.Names}} {{.Image}}'], capture_output=True, timeout=10)
    names = [line.split()[0] for line in result.stdout.decode('utf-8', 'replace').splitlines()
             if 'openresty' in line.lower()]
    return names[0] if result.returncode == 0 and len(names) == 1 else None


def proxy_log(container):
    result = subprocess.run(['docker', 'exec', container, 'tail', '-c', '262144',
                             '/www/sites/wechat-callback/log/access.log'], capture_output=True, timeout=5)
    return result.stdout.decode('utf-8', 'replace') if result.returncode == 0 else None


def recent_posts(expected_nonce, path=Path('/opt/1panel/www/sites/wechat-callback/log/access.log'), wait_seconds=6, reader=None):
    if reader is None and not path.is_file():
        print('Access log: expected file not found; use the active site log path in 1Panel.')
        return False
    # Read a bounded tail; output only timestamps, method, path and status.
    deadline = time.monotonic() + wait_seconds
    while True:
        if reader is not None:
            content = reader()
            if content is None:
                print('Access log could not be read inside the selected OpenResty container.')
                return False
        else:
            with path.open('rb') as stream:
                stream.seek(0, 2)
                stream.seek(max(0, stream.tell() - 262144))
                content = stream.read().decode('utf-8', 'replace')
        observed = bool(expected_nonce) and ('nonce=' + expected_nonce) in content
        if observed or time.monotonic() >= deadline:
            break
        time.sleep(0.25)
    pattern = r'\[([^\]]+)\]\s+"POST\s+(/api/wechat/customer-service)(?:\?[^\s]*)?\s+HTTP/[^\"]+"\s+(\d{3})'
    matches = [match.groups() for line in content.splitlines() if (match := re.search(pattern, line))]
    print('Selected access-log snapshot:')
    for stamp, path_text, status in matches[-8:]:
        print('  ' + stamp + ' POST ' + path_text + ' HTTP=' + status)
    print('Current public POST found in this log: ' + str(observed))
    if not observed:
        print('  This file has not been confirmed as the current request log.')
        print('  Check the active access_log path, logging settings, format and buffering.')
        print('  Missing entries in this file do NOT establish that WeChat sent no message.')
    return observed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container', default='yuhetang-java-api')
    parser.add_argument('--proxy-container', help='OpenResty container; auto-detected when exactly one is running')
    parser.add_argument('--public-base', default='https://booking.hetuonian.cn')
    args = parser.parse_args()
    if args.public_base.rstrip('/') != 'https://booking.hetuonian.cn':
        raise ValueError('This deployment diagnostic only permits https://booking.hetuonian.cn.')
    env, image = running_config(args.container)
    app_id, token, key = (env.get(name, '') for name in ('WECHAT_APP_ID', 'WECHAT_MESSAGE_TOKEN', 'WECHAT_MESSAGE_AES_KEY'))
    print('UTC: ' + dt.datetime.now(dt.timezone.utc).isoformat())
    print('China time: ' + dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).isoformat())
    print('Image: ' + image)
    print('AppID matches target: ' + str(app_id == EXPECTED_APP_ID))
    print('AppSecret configured: ' + str(bool(env.get('WECHAT_APP_SECRET', '').strip())))
    print('Callback token configured: ' + str(bool(token)))
    print('AES key length valid: ' + str(len(key) == 43))
    print('Plaintext mode allowed: ' + env.get('WECHAT_MESSAGE_ALLOW_PLAINTEXT', 'false'))
    if app_id != EXPECTED_APP_ID or not token or len(key) != 43:
        raise ValueError('The running container configuration does not match this mini program. No probe was sent.')
    encrypted = envelope(key, app_id)
    port = int(env.get('PORT', '8790'))
    results, public_post_trace = [], {}
    for name, base in [('Local', 'http://127.0.0.1:' + str(port)), ('Public', args.public_base)]:
        results.append(request(name + ' signed GET', base, token))
        results.append(request(name + ' encrypted POST', base, token, encrypted,
                               public_post_trace if name == 'Public' else None))
    proxy = proxy_container(args.proxy_container)
    if proxy:
        print('Access log reader: OpenResty container ' + proxy)
        observed = recent_posts(public_post_trace.get('nonce'), reader=lambda: proxy_log(proxy))
    else:
        print('OpenResty container is ambiguous or unavailable; specify --proxy-container to check its log.')
        observed = False
    print('Probe event is ignored before database writes. No customer message or access_token request was sent.')
    if all(results):
        print('All callback probes passed.')
        if observed:
            print('Capture a NEW phone message while watching this confirmed access log.')
        else:
            print('Locate and confirm the active request log before checking NEW phone messages.')
        print('This validates transport/signature/decryption, not real WeChat delivery or storage of customer text.')
    return 0 if all(results) else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, KeyError, OSError, subprocess.SubprocessError):
        # Deliberately omit exception details: upstream errors can embed credentials.
        print('Diagnostic could not finish. Check Docker access, running container name, required WeChat configuration and openssl.')
        sys.exit(2)
