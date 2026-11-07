"""Local fixture checks. Never reads real server/local credentials."""
import contextlib
import hashlib
import importlib.util
import io
from pathlib import Path
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock


spec = importlib.util.spec_from_file_location('credential_export', Path(__file__).with_name('export-tencent-credentials.py'))
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)


class CredentialExportTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='yuhetang-credential-fixture-')
        self.directory = Path(self.temporary.name)
        self.environment = self.directory / 'application.env'
        self.secrets = self.directory / 'secrets'
        self.secrets.mkdir()
        self.output = self.directory / 'record.md'
        self.password = 'a1' * 32
        self.root_password = 'b2' * 32
        self.configuration = (
            'DB_URL={}\nDB_USERNAME=yuwatang_app\nDB_PASSWORD={}\n'
            'ADMIN_PASSWORD="fixture admin 密码"\nJWT_SECRET=fixture-only-jwt\n'
            'WECHAT_APP_SECRET=fixture-only-secret\nWECHAT_MESSAGE_TOKEN=\n'
        ).format(exporter.EXPECTED_URL, self.password)
        self.environment.write_text(self.configuration, encoding='utf-8')
        (self.secrets / 'app-password').write_text(self.password + '\n', encoding='utf-8')
        (self.secrets / 'root-password').write_text(self.root_password + '\n', encoding='utf-8')

    def tearDown(self):
        self.temporary.cleanup()

    def export(self, output=None):
        # Fixtures on Windows cannot model Unix ownership; permission checks
        # are tested separately. The server CLI always enforces them.
        exporter.export_record(self.environment, self.secrets, output or self.output, check_permissions=False)

    def test_records_real_fixture_values_without_stdout_or_input_changes(self):
        paths = [self.environment, self.secrets / 'app-password', self.secrets / 'root-password']
        before = {path: hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}
        captured = io.StringIO()
        with contextlib.redirect_stdout(captured), contextlib.redirect_stderr(captured):
            self.export()
        document = self.output.read_text(encoding='utf-8')
        self.assertIn(self.password, document)
        self.assertIn(self.root_password, document)
        self.assertIn(self.configuration, document)
        self.assertIn('没有连接数据库或验证登录', document)
        self.assertIn('不会自动跟随后台改密', document)
        self.assertEqual(captured.getvalue(), '')
        self.assertEqual(before, {path: hashlib.sha256(path.read_bytes()).hexdigest() for path in paths})

    def test_refuses_to_overwrite_an_existing_record(self):
        self.output.write_text('keep existing record', encoding='utf-8')
        with self.assertRaisesRegex(exporter.ExportError, 'already exists'):
            self.export()
        self.assertEqual(self.output.read_text(encoding='utf-8'), 'keep existing record')

    def test_rejects_application_password_mismatch_before_writing(self):
        (self.secrets / 'app-password').write_text('different-fixture-password\n', encoding='utf-8')
        with self.assertRaisesRegex(exporter.ExportError, 'differs'):
            self.export()
        self.assertFalse(self.output.exists())

    def test_rejects_old_mysql_target_before_writing(self):
        self.environment.write_text(self.configuration.replace(':3307/', ':3306/'), encoding='utf-8')
        with self.assertRaisesRegex(exporter.ExportError, '3307 target'):
            self.export()
        self.assertFalse(self.output.exists())

    def test_rejects_unexpected_user_before_writing(self):
        self.environment.write_text(self.configuration.replace('DB_USERNAME=yuwatang_app', 'DB_USERNAME=root'), encoding='utf-8')
        with self.assertRaisesRegex(exporter.ExportError, 'dedicated application user'):
            self.export()
        self.assertFalse(self.output.exists())

    def test_missing_installation_does_not_create_an_empty_record(self):
        self.environment.unlink()
        with self.assertRaisesRegex(exporter.ExportError, 'finish prepare.sh'):
            self.export()
        self.assertFalse(self.output.exists())

    def test_preserves_shell_and_markdown_characters_without_execution(self):
        marker = self.directory / 'must-not-be-created'
        payload = 'WECHAT_MESSAGE_TOKEN=$(touch {})\n# literal fence ````\n'.format(marker)
        self.environment.write_text(self.configuration + payload, encoding='utf-8')
        self.export()
        document = self.output.read_text(encoding='utf-8')
        self.assertIn(payload, document)
        self.assertIn('`````ini', document)
        self.assertFalse(marker.exists())

    def test_relative_output_path_is_rejected(self):
        with self.assertRaisesRegex(exporter.ExportError, 'absolute output path'):
            self.export(Path('relative-record.md'))

    def test_empty_root_password_does_not_create_a_record(self):
        (self.secrets / 'root-password').write_text('\n', encoding='utf-8')
        with self.assertRaisesRegex(exporter.ExportError, 'empty'):
            self.export()
        self.assertFalse(self.output.exists())

    def test_server_rejects_nonprivate_or_nonroot_credential_inputs(self):
        for mode, owner in ((0o644, 0), (0o600, 1000)):
            with self.subTest(mode=mode, owner=owner):
                info = SimpleNamespace(st_mode=stat.S_IFREG | mode, st_uid=owner, st_size=10)
                with mock.patch.object(Path, 'lstat', return_value=info), mock.patch.object(Path, 'read_text') as reader:
                    with self.assertRaisesRegex(exporter.ExportError, 'owned by root'):
                        exporter.read_private_file(self.environment)
                    reader.assert_not_called()


if __name__ == '__main__':
    unittest.main()
