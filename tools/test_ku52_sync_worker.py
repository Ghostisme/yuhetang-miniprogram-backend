"""Isolated sync worker contract tests; no source website or customer messages."""
import asyncio
from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
import unittest
from unittest.mock import patch, AsyncMock
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('ku52_sync_worker', ROOT / 'deploy/ku52-sync/worker.py')
W = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(W)
STORES = {'1': {'source_name': '测试甲店', 'storeId': 'a'}, '2': {'source_name': '测试乙店', 'storeId': 'b'}}
PHONE = '13900006101'


def source():
    return dict(memberId='10', profile={'id': 10, 'mobile': PHONE, 'money': '75.51'},
        summary={'store_card_count': 2}, cards=[
            {'id': 11, 'number': 'CARD-A', 'user_name': '测试甲店', 'money': '60.18', 'discount': 70, 'name': '七折卡'},
            {'id': 12, 'number': 'CARD-B', 'user_name': '测试乙店', 'money': '15.33', 'discount': 50, 'name': '五折卡'}],
        orders=[{'order_id': 20, 'order_number': 'ORDER-A', 'user_name': '测试乙店',
            'finish_at': '2026-11-10 10:20:00', 'total_fee': '8.50', 'status_str': '正常',
            'consume_list': [{'project_name': '测试养护'}]}])


class SnapshotTests(unittest.TestCase):
    def test_multi_branch_cards_are_integer_fen_and_history_not_claimed_complete(self):
        result = W.snapshot(PHONE, [source()], STORES)
        self.assertEqual(result['sourceTotalFen'], 7551)
        self.assertEqual([row['balanceFen'] for row in result['cards']], [6018, 1533])
        self.assertEqual([row['storeId'] for row in result['cards']], ['a', 'b'])
        self.assertEqual(result['orders'][0]['paidFen'], 850)
        self.assertTrue(result['orders'][0]['businessAt'].endswith('+08:00'))
        self.assertFalse(result['historyComplete'])

    def test_bad_amounts_are_not_rounded_or_converted_to_zero(self):
        for value in ('1.001', '-1', 'NaN', 'Infinity', None, '', '10000000'):
            with self.subTest(value=value), self.assertRaises(W.SnapshotError):
                W.money(value)
        self.assertEqual(W.money('0.29'), 29)

    def test_partial_card_response_never_publishes_smaller_balance(self):
        data = source(); data['cards'].pop()
        with self.assertRaisesRegex(W.SnapshotError, 'INCOMPLETE'):
            W.snapshot(PHONE, [data], STORES)
        data['summary']['store_card_count'] = 1
        with self.assertRaisesRegex(W.SnapshotError, 'TOTAL_MISMATCH'):
            W.snapshot(PHONE, [data], STORES)

    def test_phone_identity_and_store_mapping_must_match_exactly(self):
        data = source(); data['profile']['mobile'] = '139****6101'
        with self.assertRaisesRegex(W.SnapshotError, 'IDENTITY'):
            W.snapshot(PHONE, [data], STORES)
        data = source(); data['cards'][0]['user_name'] = '未映射店'
        with self.assertRaisesRegex(W.SnapshotError, 'UNMAPPED'):
            W.snapshot(PHONE, [data], STORES)

    def test_same_card_seen_through_two_member_entries_not_double_counted(self):
        data = source(); other = deepcopy(data)
        other['memberId'] = other['profile']['id'] = 13
        result = W.snapshot(PHONE, [data, other], STORES)
        self.assertEqual(result['sourceTotalFen'], 7551)
        self.assertEqual(result['cardCount'], 2)
        self.assertEqual(len(result['orders']), 1)

    def test_inconsistent_snapshots_and_unknown_order_status_are_rejected(self):
        a = source(); b = deepcopy(a)
        b['cards'][0]['money'] = '61.18'; b['profile']['money'] = '76.51'
        with self.assertRaisesRegex(W.SnapshotError, 'CONFLICT'):
            W.snapshot(PHONE, [a, b], STORES)
        a['orders'][0]['status_str'] = '未识别状态'
        with self.assertRaisesRegex(W.SnapshotError, 'STATUS_UNKNOWN'):
            W.snapshot(PHONE, [a], STORES)

    def test_unmatched_customer_has_no_invented_zero_balance(self):
        self.assertEqual(W.snapshot(PHONE, [], STORES), {'state': 'NO_MATCH'})

    def test_api_posts_fixed_length_and_keeps_sync_key_in_header(self):
        seen = []
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                seen.append((self.path, dict(self.headers), json.loads(self.rfile.read(int(self.headers['Content-Length'])))))
                self.send_response(200); self.send_header('Content-Type', 'application/json'); self.end_headers()
                self.wfile.write(b'{"job":{}}')
            def log_message(self, *args): pass
        server = HTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            W.api({'api': 'http://127.0.0.1:' + str(server.server_port), 'token': 'x' * 48}, 'claim', {})
        finally:
            server.shutdown(); thread.join(); server.server_close()
        path, headers, body = seen[0]
        self.assertEqual(path, '/api/internal/ku52-sync/claim')
        self.assertEqual(headers['X-Yht-Sync-Key'], 'x' * 48)
        self.assertNotIn('Transfer-Encoding', headers)
        self.assertEqual(body, {})

    def test_sync_key_is_not_sent_to_unsafe_origins(self):
        for origin in ('http://example.com', 'https://example.com/path', 'https://user:pw@example.com', 'https://example.com?key=1'):
            with self.subTest(origin=origin), self.assertRaises(W.SnapshotError):
                W.api({'api': origin, 'token': 'x' * 48}, 'claim', {})

    def test_expired_source_login_only_marks_failure_preserving_previous_snapshot(self):
        class NeedsLogin(Exception): pass
        class AccessStopped(Exception): pass
        requests = []
        def fake_api(config, endpoint, body):
            requests.append((endpoint, body))
            return {'job': {'customerProfileId': 'test-customer', 'leaseToken': 'lease', 'phone': PHONE}} if endpoint == 'claim' else {'state': body['state']}
        with patch.dict(sys.modules, {'ku52_export': SimpleNamespace(NeedsLogin=NeedsLogin, AccessStopped=AccessStopped)}), \
             patch.object(W, 'api', side_effect=fake_api), patch.object(W, 'collect', new=AsyncMock(side_effect=NeedsLogin())):
            asyncio.run(W.run({}, 2))
        self.assertEqual(len(requests), 2)
        self.assertEqual(requests[1], ('complete', {'customerProfileId': 'test-customer', 'leaseToken': 'lease', 'state': 'LOGIN_REQUIRED'}))


if __name__ == '__main__':
    unittest.main(verbosity=2)
