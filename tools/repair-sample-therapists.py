#!/usr/bin/env python3
"""Complete the imported sample therapists' branch project links. Preview by default.

Python 3.8+, standard library only. Uses the existing admin API, without redeploying Java.
"""
import argparse
import copy
import datetime as dt
import getpass
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

BRANCHES = ('shanghai', 'yuyao', 'huashan', 'wujiang', 'sichuan', 'taikoo', 'changde', 'meihua', 'bai-le-men')
SOURCES = {'local-content-20261108:%s-t%d' % (sid, i): (sid, name)
           for sid in BRANCHES for i, name in enumerate(('', '林老师（示例）', '陈老师（示例）', '周老师（示例）')) if i}
LEGACY_NAMES = {'中医推拿', '扶阳悬灸', '香薰足疗', '刮痧'}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Api:
    def __init__(self, base):
        url = urllib.parse.urlsplit(base)
        if url.username or url.password or url.query or url.fragment or url.path not in ('', '/'):
            raise ValueError('Use only the API origin without credentials or a path')
        if url.scheme != 'https' and not (url.scheme == 'http' and url.hostname in ('127.0.0.1', 'localhost', '::1')):
            raise ValueError('Use HTTPS or the server loopback address')
        self.base, self.token = base.rstrip('/'), ''
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, method, path, payload=None):
        headers = {'Accept': 'application/json', 'Content-Type': 'application/json; charset=utf-8'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode('utf-8')
        try:
            with self.opener.open(urllib.request.Request(self.base + path, body, headers, method=method), timeout=20) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError('%s %s: HTTP %s' % (method, path, error.code)) from None

    def get(self, path):
        return self.request('GET', path)


def plan_repairs(teachers, services):
    plans, skipped, seen = [], [], set()
    for teacher in teachers:
        identity = SOURCES.get(teacher.get('sampleSource'))
        if not identity:
            continue
        sid, name = identity
        if teacher.get('sample') is not True or teacher.get('storeId') != sid or teacher.get('name') != name:
            skipped.append(dict(id=teacher['id'], reason='Staff identity was edited; preserved'))
            continue
        if teacher['sampleSource'] in seen:
            raise ValueError('Duplicate sample source; review manually: ' + teacher['sampleSource'])
        seen.add(teacher['sampleSource'])
        projects = services[sid]
        if len({p['id'] for p in projects}) != len(projects):
            raise ValueError('Duplicate branch project IDs: ' + sid)
        if any(p.get('storeId') != sid for p in projects):
            raise ValueError('A branch project belongs to another store: ' + sid)
        active = [p['id'] for p in projects if p.get('enabled') is True and p.get('source') == 'STORE']
        current = teacher.get('serviceIds')
        if not isinstance(current, list) or any(not isinstance(x, str) for x in current):
            raise ValueError('Invalid sample project list: ' + teacher['id'])
        missing = [x for x in active if x not in current]
        if not missing:
            continue
        legacy = {p['id'] for p in projects if p.get('name') in LEGACY_NAMES and p.get('source') == 'STORE'}
        if not legacy or set(current) != legacy:
            skipped.append(dict(id=teacher['id'], reason='Project links were edited; preserved'))
            continue
        after = copy.deepcopy(teacher)
        after['serviceIds'] = current + missing
        plans.append(dict(id=teacher['id'], storeId=sid, added=len(missing), before=copy.deepcopy(teacher), after=after))
    return plans, skipped


def write_json(path, value):
    descriptor = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def repair(api, apply=False, backup_dir=Path('/var/backups/yuhetang-therapists')):
    me = api.get('/api/admin/me')
    required = {'therapists:read', 'therapists:write', 'services:read'}
    if not me.get('hq') or not required.issubset(me.get('permissions', [])):
        raise ValueError('A headquarters account with therapist and project permissions is required')
    teachers = api.get('/api/admin/therapists')
    sids = sorted({t['storeId'] for t in teachers if t.get('sampleSource') in SOURCES and t['storeId'] in BRANCHES})
    services = {sid: api.get('/api/admin/stores/' + sid + '/services') for sid in sids}
    plans, skipped = plan_repairs(teachers, services)
    report = dict(status='preview', planned=len(plans), updated=[], skipped=skipped,
                  addedLinks=sum(p['added'] for p in plans))
    print('Samples to repair: %d; project links to add: %d; edited profiles preserved: %d.' %
          (len(plans), report['addedLinks'], len(skipped)))
    for plan in plans:
        print('ADD %s: %d branch project links' % (plan['before']['name'], plan['added']))
    if not apply:
        return report
    if not plans:
        report['status'] = 'verified'
        print('SUCCESS: SAMPLE THERAPIST LINKS VERIFIED (no changes needed)')
        return report
    folder = Path(backup_dir) / (dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex[:8])
    folder.mkdir(parents=True, mode=0o700)
    write_json(folder / 'before.json', dict(therapists=teachers, services=services, plans=plans))
    print('BACKUP=' + str(folder))
    try:
        for plan in plans:
            current = {t['id']: t for t in api.get('/api/admin/therapists')}
            if current.get(plan['id']) != plan['before']:
                raise RuntimeError('A therapist changed during repair; rerun after review: ' + plan['id'])
            if api.get('/api/admin/stores/' + plan['storeId'] + '/services') != services[plan['storeId']]:
                raise RuntimeError('A branch catalog changed during repair; rerun after review')
            api.request('PUT', '/api/admin/therapists', {**plan['after'], 'originalStoreId': plan['storeId']})
            actual = {t['id']: t for t in api.get('/api/admin/therapists')}.get(plan['id'])
            if actual != plan['after']:
                raise RuntimeError('Saved therapist verification failed: ' + plan['id'])
            report['updated'].append(plan['id'])
            write_json(folder / 'report.json', report)
        after = {t['id']: t for t in api.get('/api/admin/therapists')}
        expected = {p['id']: p['after'] for p in plans}
        if len(after) != len(teachers) or any(after.get(t['id']) != expected.get(t['id'], t) for t in teachers):
            raise RuntimeError('Staff records changed during repair; inspect the saved backup')
        report['status'] = 'verified'
    except Exception:
        report['status'] = 'incomplete'
        raise
    finally:
        write_json(folder / 'report.json', report)
    print('SUCCESS: SAMPLE THERAPIST LINKS VERIFIED; updated=%d; addedLinks=%d' %
          (len(report['updated']), report['addedLinks']))
    return report


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8790')
    parser.add_argument('--username', default='admin')
    parser.add_argument('--backup-dir', type=Path, default=Path('/var/backups/yuhetang-therapists'))
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    api = Api(args.base_url)
    password = getpass.getpass('Headquarters administrator password: ')
    api.token = api.request('POST', '/api/admin/login', dict(username=args.username, password=password))['token']
    del password
    repair(api, args.apply, args.backup_dir)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError, KeyError) as error:
        print('Repair stopped: ' + str(error), file=sys.stderr)
        sys.exit(1)
