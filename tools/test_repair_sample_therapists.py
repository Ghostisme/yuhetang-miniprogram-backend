import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('repair_samples', Path(__file__).with_name('repair-sample-therapists.py'))
repair = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(repair)


class FakeApi:
    def __init__(self):
        self.projects = {sid: [dict(id=sid + '-p' + str(i), storeId=sid, name=name, source='STORE', enabled=True)
                               for i, name in enumerate(['中医推拿', '扶阳悬灸', '香薰足疗', '刮痧', '对症调理', 'A套餐'])]
                         for sid in repair.BRANCHES}
        self.teachers = [dict(id=source, sampleSource=source, storeId=sid, name=name, sample=True, enabled=True,
                              showOnClient=True, resting=False, image='/uploads/teacher.jpg', grade='示例等级',
                              serviceIds=[p['id'] for p in self.projects[sid][:4]])
                         for source, (sid, name) in repair.SOURCES.items()]
        self.writes, self.reads = [], 0
        self.change_on_read = None
        self.fail_write = None

    def get(self, path):
        if path == '/api/admin/me':
            return dict(hq=True, permissions=['therapists:read', 'therapists:write', 'services:read'])
        if path == '/api/admin/therapists':
            self.reads += 1
            if self.change_on_read == self.reads:
                self.teachers[0]['name'] = '运营人员刚刚修改'
            return copy.deepcopy(self.teachers)
        for sid, projects in self.projects.items():
            if path == '/api/admin/stores/' + sid + '/services':
                return copy.deepcopy(projects)
        raise AssertionError('Unexpected API read: ' + path)

    def request(self, method, path, payload):
        assert (method, path) == ('PUT', '/api/admin/therapists')
        if self.fail_write == len(self.writes) + 1:
            raise RuntimeError('Fixture write failure')
        self.writes.append(copy.deepcopy(payload))
        teacher = next(t for t in self.teachers if t['id'] == payload['id'])
        teacher.update({k: v for k, v in payload.items() if k != 'originalStoreId'})
        return {'saved': True}


class RepairTests(unittest.TestCase):
    def run_repair(self, api, folder, apply=True):
        with contextlib.redirect_stdout(io.StringIO()):
            return repair.repair(api, apply, Path(folder))

    def test_preview_covers_all_nine_branches_without_writes_or_backup(self):
        api = FakeApi()
        with tempfile.TemporaryDirectory() as folder:
            result = self.run_repair(api, folder, False)
            self.assertEqual((27, 54), (result['planned'], result['addedLinks']))
            self.assertFalse(api.writes)
            self.assertEqual([], list(Path(folder).iterdir()))

    def test_only_project_links_change_and_repeat_is_idempotent(self):
        api = FakeApi()
        api.teachers[0].update(enabled=False, resting=True, showOnClient=False)
        api.projects['shanghai'].append(dict(id='pending', storeId='shanghai', name='待确认', source='STORE', enabled=False))
        before, projects = copy.deepcopy(api.teachers), copy.deepcopy(api.projects)
        with tempfile.TemporaryDirectory() as folder:
            result = self.run_repair(api, folder)
            self.assertEqual(('verified', 27), (result['status'], len(result['updated'])))
            for old, teacher in zip(before, api.teachers):
                self.assertEqual({k: v for k, v in old.items() if k != 'serviceIds'},
                                 {k: v for k, v in teacher.items() if k != 'serviceIds'})
                self.assertEqual({p['id'] for p in api.projects[teacher['storeId']] if p['enabled']}, set(teacher['serviceIds']))
            self.assertEqual(projects, api.projects)
            saved = json.loads(next(Path(folder).glob('*/before.json')).read_text(encoding='utf-8'))
            self.assertEqual(before, saved['therapists'])
            api.writes.clear()
            self.assertEqual('verified', self.run_repair(api, folder)['status'])
            self.assertFalse(api.writes)

    def test_renamed_real_transferred_and_manually_changed_profiles_are_preserved(self):
        api = FakeApi()
        api.teachers[0]['name'] = '真实员工'
        api.teachers[1]['sample'] = False
        api.teachers[2]['storeId'] = 'yuyao'
        api.teachers[3]['serviceIds'] = api.teachers[3]['serviceIds'][:1]
        unmarked = copy.deepcopy(api.teachers[4])
        unmarked.update(id='real-employee', sample=False, sampleSource='')
        api.teachers.append(unmarked)
        before = copy.deepcopy(api.teachers[:4] + [api.teachers[-1]])
        with tempfile.TemporaryDirectory() as folder:
            result = self.run_repair(api, folder)
        self.assertEqual(23, len(result['updated']))
        self.assertEqual(before, api.teachers[:4] + [api.teachers[-1]])

    def test_cross_store_project_and_duplicate_source_abort_before_any_write(self):
        for bad in ('store', 'source'):
            api = FakeApi()
            if bad == 'store':
                api.projects['shanghai'][0]['storeId'] = 'yuyao'
            else:
                api.teachers.append(copy.deepcopy(api.teachers[0]))
            with tempfile.TemporaryDirectory() as folder, self.assertRaises(ValueError):
                self.run_repair(api, folder)
            self.assertFalse(api.writes)

    def test_concurrent_staff_edit_stops_before_overwriting(self):
        api = FakeApi()
        api.change_on_read = 2
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(RuntimeError, 'changed during repair'):
                self.run_repair(api, folder)
            self.assertFalse(api.writes)
            report = json.loads(next(Path(folder).glob('*/report.json')).read_text(encoding='utf-8'))
            self.assertEqual('incomplete', report['status'])
            self.assertEqual([], report['updated'])

    def test_partial_failure_retains_progress_and_does_not_retry(self):
        api = FakeApi()
        api.fail_write = 2
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(RuntimeError, 'Fixture write failure'):
                self.run_repair(api, folder)
            self.assertEqual(1, len(api.writes))
            report = json.loads(next(Path(folder).glob('*/report.json')).read_text(encoding='utf-8'))
            self.assertEqual('incomplete', report['status'])
            self.assertEqual(1, len(report['updated']))


if __name__ == '__main__':
    unittest.main()
