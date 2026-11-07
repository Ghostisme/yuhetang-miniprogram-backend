"""Fixture-only checks for preserving real settings during the Docker migration."""
import contextlib
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parent.parent / "deploy/docker-java/export-env.py"
SPEC = importlib.util.spec_from_file_location("docker_java_env", SCRIPT)
exporter = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(exporter)


class DockerEnvironmentTests(unittest.TestCase):
    def setUp(self):
        self.environment = dict(exporter.EXPECTED)
        self.environment.update(DB_PASSWORD="fixture-database-password-" * 2,
                                JWT_SECRET="fixture-jwt-secret-" * 3,
                                ADMIN_PASSWORD="fixture-admin-password")
        self.temporary = tempfile.TemporaryDirectory(prefix="yuhetang-docker-fixture-")
        self.output = Path(self.temporary.name) / "docker.env"

    def tearDown(self):
        self.temporary.cleanup()

    def test_preserves_systemd_decoded_json_and_literal_shell_characters(self):
        marker = Path(self.temporary.name) / "must-not-exist"
        values = {
            "CUSTOMER_TEMPLATE_MAPPING": '{"name1":"customerName","date2":"time"}',
            "WECHAT_MESSAGE_TOKEN": "literal$token#value",
            "WECHAT_APP_SECRET": '$(touch ' + str(marker) + ')`command`\\literal"quoted',
            "SMS_SIGN_NAME": "裕和堂 测试",
        }
        self.environment.update(values)
        before = dict(self.environment)
        captured = io.StringIO()
        with contextlib.redirect_stdout(captured), contextlib.redirect_stderr(captured):
            exporter.export(self.environment, self.output)
        decoded = dict(line.split("=", 1) for line in self.output.read_text(encoding="utf-8").splitlines())
        for key, value in values.items():
            self.assertEqual(decoded[key], value)
        self.assertEqual(self.environment, before)
        self.assertFalse(marker.exists())
        self.assertEqual(captured.getvalue(), "")

    def test_refuses_existing_output_and_preserves_its_contents(self):
        self.output.write_bytes(b"existing private configuration\n")
        before = hashlib.sha256(self.output.read_bytes()).hexdigest()
        with self.assertRaises(FileExistsError):
            exporter.export(self.environment, self.output)
        self.assertEqual(hashlib.sha256(self.output.read_bytes()).hexdigest(), before)

    def test_rejects_any_other_database_without_creating_output(self):
        for key, value in (("DB_URL", exporter.EXPECTED_URL.replace(":3307/", ":3306/")),
                           ("DB_URL", exporter.EXPECTED_URL.replace("/yuwatang_reservation?", "/other_database?")),
                           ("DB_USERNAME", "root")):
            with self.subTest(key=key, value=value):
                environment = dict(self.environment, **{key: value})
                with self.assertRaises(exporter.ExportError):
                    exporter.export(environment, self.output)
                self.assertFalse(self.output.exists())

    def test_rejects_missing_credentials_without_exposing_them(self):
        for key in ("ADMIN_PASSWORD", "DB_PASSWORD", "JWT_SECRET"):
            environment = dict(self.environment, **{key: "fixture-too-short" if key != "ADMIN_PASSWORD" else "fixture123"})
            with self.subTest(key=key), self.assertRaises(exporter.ExportError) as error:
                exporter.export(environment, self.output)
            self.assertNotIn(environment[key], str(error.exception))
            self.assertFalse(self.output.exists())

    def test_rejects_spring_and_jvm_overrides(self):
        for key in exporter.OVERRIDES:
            with self.subTest(key=key), self.assertRaises(exporter.ExportError):
                exporter.export(dict(self.environment, **{key: "fixture-override"}), self.output)
            self.assertFalse(self.output.exists())

    def test_rejects_configuration_that_would_expose_ports_or_lose_uploads(self):
        for key, value in (("BIND_ADDRESS", "0.0.0.0"), ("MANAGEMENT_BIND_ADDRESS", "0.0.0.0"),
                           ("PORT", "8080"), ("MANAGEMENT_PORT", "9001"),
                           ("APP_DATA_DIR", "/tmp/empty-data"), ("APP_MODE", "local")):
            with self.subTest(key=key), self.assertRaises(exporter.ExportError):
                exporter.export(dict(self.environment, **{key: value}), self.output)
            self.assertFalse(self.output.exists())

    def test_rejects_new_configuration_keys_instead_of_silently_discarding_them(self):
        with self.assertRaisesRegex(exporter.ExportError, "Unsupported application configuration key"):
            exporter.export(dict(self.environment, WECHAT_FUTURE_SETTING="fixture"), self.output)
        self.assertFalse(self.output.exists())

    def test_ignores_systemd_metadata_and_keeps_empty_optional_settings(self):
        self.environment.update(INVOCATION_ID="fixture-only", PATH="fixture-only", WECHAT_MESSAGE_AES_KEY="")
        content = exporter.render(self.environment)
        self.assertNotIn("INVOCATION_ID=", content)
        self.assertNotIn("PATH=", content)
        self.assertIn("WECHAT_MESSAGE_AES_KEY=\n", content)
        self.assertIn("TZ=Asia/Shanghai\n", content)

    def test_rejects_multiline_null_and_edge_whitespace_values(self):
        for value in ("first\nsecond", "first\rsecond", "first\0second", " leading", "trailing "):
            with self.subTest(value=repr(value)), self.assertRaises(exporter.ExportError):
                exporter.export(dict(self.environment, WECHAT_MESSAGE_TOKEN=value), self.output)
            self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
