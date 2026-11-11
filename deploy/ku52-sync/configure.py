"""Create private sync configuration without displaying credentials."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import tempfile
from urllib.parse import urlsplit

ROOT = Path('/etc/yuhetang-ku52-sync')
ENV = Path('/etc/yuwatang-reservation.docker.env')


def atomic(path, value):
    descriptor, temporary = tempfile.mkstemp(prefix='.' + path.name, dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', type=Path, help='Private browser-state.json exported from an authorized source login')
    parser.add_argument('--interval-seconds', type=int, default=300)
    args = parser.parse_args()
    if os.geteuid() != 0 or not ENV.is_file():
        raise SystemExit('STOP: run as root on the existing reservation server.')
    if not 60 <= args.interval_seconds <= 86400:
        parser.error('interval-seconds must be 60..86400')
    session = None
    if args.session:
        session = args.session.read_bytes()
        value = json.loads(session)
        if not isinstance(value.get('cookies'), list) or not isinstance(value.get('origins'), list):
            raise SystemExit('STOP: expected a browser storage-state export.')
        domains = [str(row.get('domain', '')).lstrip('.') for row in value['cookies']]
        if not domains or any(domain != 'ku52.cn' and not domain.endswith('.ku52.cn') for domain in domains):
            raise SystemExit('STOP: session contains another website; use the source-only export.')
        if any(urlsplit(str(row.get('origin', ''))).scheme != 'https' or
               (urlsplit(str(row.get('origin', ''))).hostname != 'ku52.cn' and
                not str(urlsplit(str(row.get('origin', ''))).hostname).endswith('.ku52.cn'))
               for row in value['origins']):
            raise SystemExit('STOP: browser storage contains another website.')
    text = ENV.read_text(encoding='utf-8-sig')
    entries = re.findall(r'^KU52_SYNC_TOKEN=(.*)$', text, re.M)
    if len(entries) > 1 or (entries and not re.fullmatch(r'[A-Za-z0-9_-]{32,128}', entries[0])):
        raise SystemExit('STOP: existing KU52_SYNC_TOKEN needs manual verification; no changes made.')
    token = entries[0] if entries else secrets.token_urlsafe(48)
    updated = re.sub(r'^KU52_SYNC_(?:TOKEN|INTERVAL_SECONDS)=.*\n?', '', text, flags=re.M).rstrip('\n')
    updated += '\nKU52_SYNC_TOKEN=' + token + '\nKU52_SYNC_INTERVAL_SECONDS=' + str(args.interval_seconds) + '\n'
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(ROOT, 0o700)
    if updated != text:
        backup = ROOT / ('reservation-env-before-' + dt.datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
        shutil.copy2(ENV, backup); os.chmod(backup, 0o600)
        atomic(ENV, updated.encode('utf-8'))
    config = dict(api='http://127.0.0.1:8790', token=token,
                  state=str(ROOT / 'browser-state.json'), storesFile='/opt/yuhetang-ku52-sync/stores.json')
    atomic(ROOT / 'config.json', (json.dumps(config, indent=2) + '\n').encode('utf-8'))
    if session is not None:
        atomic(ROOT / 'browser-state.json', session)
    print('CONFIGURED: private sync config and Java environment prepared; no secret printed.')
    print('Recreate Java through the full updater to load the environment. A simple docker restart does not reload it.')
    print('SOURCE_SESSION=' + ('present (validity not yet tested)' if (ROOT / 'browser-state.json').exists() else 'missing; upload an authorized browser-state.json privately before starting the timer'))


if __name__ == '__main__':
    main()
