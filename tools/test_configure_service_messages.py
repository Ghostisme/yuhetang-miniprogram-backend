"""Exercise isolated environment updates; no server, Docker or database access."""
import base64
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('service_messages_config', Path(__file__).with_name('configure-service-messages.py'))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
KEY = base64.b64encode(bytes(range(32))).decode().rstrip('=')
ORIGINAL = ('# current production configuration\nSERVICE_APP_ID=' + MODULE.APP_ID + '\n'
            'DB_PASSWORD=fixture-only-special=$`not-a-shell-command`\n'
            'SERVICE_APP_SECRET=fixture-only\nWECHAT_MESSAGE_TOKEN=fixture-mini-token\n')


class ConfigurationTests(unittest.TestCase):
    def test_appends_three_settings_and_preserves_unrelated_bytes(self):
        result = MODULE.render(ORIGINAL, 'NewToken123', KEY)
        self.assertTrue(result.startswith(ORIGINAL))
        self.assertEqual(set(MODULE.settings(result)) - {'SERVICE_APP_ID'}, MODULE.EDITABLE)
        self.assertEqual(MODULE.settings(result)['SERVICE_MESSAGE_ALLOW_PLAINTEXT'], 'false')

    def test_replaces_existing_settings_in_place_and_is_repeatable(self):
        first = MODULE.render(ORIGINAL, 'Token123', KEY)
        second = MODULE.render(first, 'ChangedToken', KEY)
        self.assertEqual(MODULE.settings(second)['SERVICE_MESSAGE_TOKEN'], 'ChangedToken')
        self.assertEqual(second.count('SERVICE_MESSAGE_TOKEN='), 1)
        self.assertEqual(MODULE.render(second, 'ChangedToken', KEY), second)

    def test_invalid_values_and_other_appid_are_rejected(self):
        for token, key in [('a', KEY), ('bad token', KEY), ('abc', KEY[:-1]), ('abc', '?' * 43), ('abc', KEY + '=')]:
            with self.subTest(token=token, key=key):
                with self.assertRaises(ValueError): MODULE.render(ORIGINAL, token, key)
        with self.assertRaises(ValueError): MODULE.render(ORIGINAL.replace(MODULE.APP_ID, 'wx0000000000000000'), 'Token123', KEY)

    def test_accepts_backend_compatible_keys_without_changing_their_spelling(self):
        # Java Base64.getDecoder() accepts nonzero unused padding bits. A raw
        # 43-character key must not be rejected or rewritten by the config tool.
        alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
        noncanonical = 0
        for final_character in alphabet:
            supplied = KEY[:-1] + final_character
            decoded = base64.b64decode(supplied + '=', validate=True)
            self.assertEqual(len(decoded), 32)
            if base64.b64encode(decoded).decode().rstrip('=') != supplied:
                noncanonical += 1
            result = MODULE.render(ORIGINAL, 'Token123', supplied)
            self.assertEqual(MODULE.settings(result)['SERVICE_MESSAGE_AES_KEY'], supplied)
        self.assertEqual(noncanonical, 48)

    def test_duplicate_settings_are_rejected(self):
        duplicate = ORIGINAL + 'SERVICE_MESSAGE_TOKEN=first\nSERVICE_MESSAGE_TOKEN=second\n'
        with self.assertRaises(ValueError): MODULE.render(duplicate, 'Token123', KEY)

    def test_missing_final_newline_does_not_merge_keys(self):
        result = MODULE.render(ORIGINAL.rstrip('\n'), 'Token123', KEY)
        self.assertIn('WECHAT_MESSAGE_TOKEN=fixture-mini-token\nSERVICE_MESSAGE_', result)

    def test_backup_original_and_write_private_atomic_file(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'docker.env'
            path.write_bytes(ORIGINAL.encode())
            os.chmod(path, 0o600)
            # Windows has no POSIX mode bits; exercise the same save path with a byte-only reader there.
            from unittest.mock import patch
            context = patch.object(MODULE, 'read_private', side_effect=lambda p: Path(p).read_bytes()) if os.name == 'nt' else None
            if context: context.start()
            try:
                backup = MODULE.save(path, ORIGINAL.encode(), 'Token123', KEY)
                self.assertEqual(backup.read_bytes(), ORIGINAL.encode())
                saved = path.read_bytes()
                self.assertEqual(saved, MODULE.render(ORIGINAL, 'Token123', KEY).encode())
                self.assertIsNone(MODULE.save(path, saved, 'Token123', KEY))
                self.assertEqual(len(list(Path(folder).glob('*.before-service-messages-*'))), 1)
                if os.name != 'nt': self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            finally:
                if context: context.stop()

    def test_concurrent_change_does_not_overwrite_original(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'docker.env'
            path.write_bytes(b'changed-by-another-editor')
            with patch.object(MODULE, 'read_private', return_value=path.read_bytes()):
                with self.assertRaises(ValueError): MODULE.save(path, ORIGINAL.encode(), 'Token123', KEY)
            self.assertEqual(path.read_bytes(), b'changed-by-another-editor')
            self.assertEqual(len(list(Path(folder).glob('*.before-service-messages-*'))), 0)


if __name__ == '__main__':
    unittest.main()
