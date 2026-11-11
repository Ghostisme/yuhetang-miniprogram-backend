"""Read normal KU52 member screens into the reservation read model.

No source balance writes. No dates from customer filters. Credentials stay in
the private config/browser session; logs contain counts and states only.
"""
from __future__ import annotations
import argparse
import asyncio
from contextlib import suppress
from datetime import datetime, timezone, timedelta
from decimal import Decimal, InvalidOperation
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler

CN = timezone(timedelta(hours=8))


class SnapshotError(RuntimeError):
    pass


def money(value):
    try:
        value = Decimal(str(value)) * 100
        if not value.is_finite() or value != value.to_integral_value() or not 0 <= value <= 999_999_999:
            raise ValueError()
        return int(value)
    except (InvalidOperation, ValueError, TypeError):
        raise SnapshotError('SOURCE_AMOUNT_INVALID') from None


def store_id(name, stores):
    matches = {str(v['storeId']) for v in stores.values() if v.get('source_name') == name and v.get('storeId')}
    if len(matches) != 1:
        raise SnapshotError('SOURCE_STORE_UNMAPPED')
    return matches.pop()


def snapshot(phone, sources, stores):
    cards, orders = {}, {}
    if not sources:
        return {'state': 'NO_MATCH'}
    for source in sources:
        profile, summary = source['profile'], source['summary']
        if str(profile.get('mobile', '')).strip() != phone or str(profile.get('id')) != str(source['memberId']):
            raise SnapshotError('SOURCE_IDENTITY_MISMATCH')
        current = source['cards']
        if not isinstance(current, list) or summary.get('store_card_count') != len(current):
            raise SnapshotError('SOURCE_CARDS_INCOMPLETE')
        if sum(money(card.get('money')) for card in current) != money(profile.get('money')):
            raise SnapshotError('SOURCE_TOTAL_MISMATCH')
        for card in current:
            key = 'ku52-card:' + str(card['id'])
            item = dict(sourceKey=key, storeId=store_id(card.get('user_name'), stores),
                        memberNumber=str(source['memberId']), cardNumber=str(card['number']).strip(),
                        label=str(card.get('name') or '储值卡')[:80], balanceFen=money(card.get('money')),
                        discount=int(card.get('discount', 100)), validity=str(card.get('valid_str') or '')[:100])
            if not item['cardNumber'] or not 0 <= item['discount'] <= 100:
                raise SnapshotError('SOURCE_CARD_INVALID')
            previous = cards.get(key)
            if previous and {k: v for k, v in previous.items() if k != 'memberNumber'} != {k: v for k, v in item.items() if k != 'memberNumber'}:
                raise SnapshotError('SOURCE_CARD_CONFLICT')
            cards.setdefault(key, item)
        if not isinstance(source['orders'], list):
            raise SnapshotError('SOURCE_ORDERS_INCOMPLETE')
        for order in source['orders']:
            status = {'正常': 'RECORDED', '已撤销': 'VOIDED', '已作废': 'VOIDED', '已退款': 'REVERSED'}.get(order.get('status_str'))
            if not status:
                raise SnapshotError('SOURCE_ORDER_STATUS_UNKNOWN')
            key = 'ku52-order:' + str(order['order_id'])
            stamp = datetime.fromisoformat(str(order['finish_at'])).replace(tzinfo=CN).isoformat()
            projects = '、'.join(str(row['project_name']) for row in order['consume_list'] if row.get('project_name'))
            if not projects:
                raise SnapshotError('SOURCE_PROJECT_MISSING')
            item = dict(sourceKey=key, storeId=store_id(order.get('user_name'), stores),
                        referenceNo=str(order['order_number']), projectName=projects[:120],
                        paidFen=money(order['total_fee']), businessAt=stamp, status=status)
            if key in orders and orders[key] != item:
                raise SnapshotError('SOURCE_ORDER_CONFLICT')
            orders[key] = item
    if len(cards) > 200 or len(orders) > 5000:
        raise SnapshotError('SOURCE_COLLECTION_LIMIT')
    # The visible counter screen currently returns recent orders. Do not claim
    # complete lifetime history; older synchronized records are retained by Java.
    return dict(state='READY', phone=phone, observedAt=datetime.now(CN).isoformat(),
                cards=list(cards.values()), orders=list(orders.values()), cardCount=len(cards),
                cardsComplete=True, sourceTotalFen=sum(card['balanceFen'] for card in cards.values()),
                historyComplete=False)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def api(config, endpoint, body):
    origin = config['api'].rstrip('/')
    parsed = urlsplit(origin)
    if parsed.path not in ('', '/') or parsed.query or parsed.fragment or parsed.username or parsed.password:
        raise SnapshotError('API_ORIGIN_INVALID')
    if parsed.scheme != 'https' and not (parsed.scheme == 'http' and parsed.hostname in ('127.0.0.1', 'localhost')):
        raise SnapshotError('API_HTTPS_REQUIRED')
    token = config['token']
    if len(token) < 32 or '\n' in token or '\r' in token:
        raise SnapshotError('SYNC_TOKEN_INVALID')
    request = Request(origin + '/api/internal/ku52-sync/' + endpoint,
                      data=json.dumps(body, ensure_ascii=False).encode('utf-8'),
                      headers={'Content-Type': 'application/json', 'X-YHT-Sync-Key': token}, method='POST')
    # Fixed Content-Length and no cross-origin redirects/proxy credential leaks.
    try:
        with build_opener(ProxyHandler({}), NoRedirect()).open(request, timeout=30) as response:
            return json.load(response)
    except HTTPError as exc:
        raise SnapshotError('SYNC_API_HTTP_' + str(exc.code)) from None


async def collect(config, phone):
    from playwright.async_api import async_playwright
    from ku52_export import Collector, NeedsLogin
    from ku52_data import Store
    state = Path(config['state'])
    if not state.is_file():
        raise NeedsLogin('Browser session unavailable')
    with tempfile.TemporaryDirectory(prefix='yuhetang-ku52-') as temporary:
        # A fresh private checkpoint prevents a previous poll from being reused.
        db = Store(Path(temporary) / 'snapshot.sqlite3')
        collector = Collector(SimpleNamespace(delay_min=1.1, delay_max=1.8), db)
        try:
            async with async_playwright() as playwright:
                browser = await playwright.chromium.launch(headless=True)
                try:
                    collector.context = await browser.new_context(storage_state=str(state), locale='zh-CN',
                        timezone_id='Asia/Shanghai', viewport={'width': 1920, 'height': 1080})
                    page = await collector.context.new_page()
                    await collector.goto(page, 'https://www.ku52.cn/v3/index.html')
                    await page.frame_locator('iframe[title="Ks"]').get_by_text('会员查询', exact=True).click(timeout=30000)
                    await page.get_by_placeholder('手机号', exact=True).fill(phone)
                    result = await collector.capture(page, {'find': '/app.php/member-api/member/find'},
                        lambda: page.get_by_text('搜索', exact=True).click(timeout=15000))
                    found, _, error = result['find']
                    if error:
                        raise error
                    if not isinstance(found, list) or len(found) > 10:
                        raise SnapshotError('SOURCE_MEMBER_COUNT_INVALID')
                    sources = []
                    seen = set()
                    for match in found:
                        shop, member = str(match['user_id']), str(match['id'])
                        if not shop.isdecimal() or not member.isdecimal():
                            raise SnapshotError('SOURCE_MEMBER_ID_INVALID')
                        if (shop, member) in seen:
                            continue
                        seen.add((shop, member))
                        # Search rows may mask the phone. The member info response
                        # must still contain the exact verified phone in snapshot().
                        # Reuse normal, read-only UI navigation, never submit a balance change.
                        await collector.counter_details({'shop_id': shop, 'id': member,
                            'list_json': json.dumps({'手机': phone}, ensure_ascii=False)})
                        payload = {key: db.get_payload(shop, member, key) for key in
                                   ('counter_profile', 'card_summary', 'cards_储值卡', 'orders')}
                        if any(value is None for value in payload.values()):
                            raise SnapshotError('SOURCE_PARTIAL_RESPONSE')
                        sources.append(dict(memberId=member, profile=payload['counter_profile'],
                            summary=payload['card_summary'], cards=payload['cards_储值卡'], orders=payload['orders']))
                    return snapshot(phone, sources, config['stores'])
                finally:
                    # Never overwrite a session owned by the separate full-member import.
                    await browser.close()
        finally:
            db.conn.close()


def load_config(path):
    config = json.loads(path.read_text(encoding='utf-8-sig'))
    for key in ('state', 'storesFile'):
        value = Path(config[key])
        config[key] = str(value if value.is_absolute() else (path.parent / value).resolve())
    config['stores'] = json.loads(Path(config['storesFile']).read_text(encoding='utf-8-sig'))['stores']
    return config


async def run(config, max_jobs):
    from ku52_export import NeedsLogin, AccessStopped
    completed = 0
    for _ in range(max_jobs):
        job = api(config, 'claim', {}).get('job')
        if not job:
            print('IDLE' if not completed else 'DONE jobs=' + str(completed), flush=True)
            break
        result = {key: job[key] for key in ('customerProfileId', 'leaseToken')}
        try:
            data = await asyncio.wait_for(collect(config, str(job['phone'])), timeout=720)
        except NeedsLogin:
            data = {'state': 'LOGIN_REQUIRED'}
        except AccessStopped:
            data = {'state': 'ERROR'}
        except Exception as exc:
            # Exception text can contain source URLs, names and browser cookies.
            print('COLLECT_FAILED type=' + type(exc).__name__, flush=True)
            data = {'state': 'ERROR'}
        result.update(data)
        try:
            outcome = api(config, 'complete', result)
        except Exception:
            if data['state'] == 'READY':
                with suppress(Exception):
                    api(config, 'complete', {**{key: job[key] for key in ('customerProfileId', 'leaseToken')}, 'state': 'ERROR'})
            raise
        completed += 1
        print('SYNC state=' + outcome['state'] + ' cards=' + str(outcome.get('cards', 0)) + ' orders=' + str(outcome.get('orders', 0)), flush=True)
        if data['state'] in ('LOGIN_REQUIRED', 'ERROR'):
            break


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--max-jobs', type=int, default=2)
    args = parser.parse_args()
    if not 1 <= args.max_jobs <= 10:
        parser.error('max-jobs must be 1..10')
    # A systemd oneshot already excludes overlap. This lock also protects manual/Windows runs.
    lock = args.config.resolve().with_suffix('.lock')
    try:
        stream = lock.open('a+b')
        if os.name == 'nt':
            import msvcrt
            stream.seek(0); stream.write(b'0'); stream.flush(); stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        print('ALREADY_RUNNING', flush=True)
        return
    try:
        asyncio.run(run(load_config(args.config.resolve()), args.max_jobs))
    except Exception as exc:
        print('STOP type=' + type(exc).__name__ + ((' code=' + str(exc)) if isinstance(exc, SnapshotError) else ''), flush=True)
        raise SystemExit(1) from None
    finally:
        stream.close()


if __name__ == '__main__':
    main()
