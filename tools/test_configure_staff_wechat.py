import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("staff_config", Path(__file__).with_name("configure-staff-wechat.py"))
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)


class StaffConfigTests(unittest.TestCase):
    def test_edits_only_named_service_settings_and_preserves_unrelated_values(self):
        original = "# existing\nDB_PASSWORD=literal-$()-#secret\nWECHAT_MESSAGE_TOKEN=keep\nSERVICE_APP_ID=old\n"
        updated = module.render(original, {"SERVICE_APP_ID": "wx1234567890abcdef", "SERVICE_TEMPLATE_ID": "fixture-template-id"})
        self.assertIn("DB_PASSWORD=literal-$()-#secret\nWECHAT_MESSAGE_TOKEN=keep\n", updated)
        self.assertEqual(1, updated.count("SERVICE_APP_ID=")); self.assertTrue(updated.endswith("\n"))

    def test_rejects_unrelated_edits_and_multiline_injection(self):
        for changes in ({"DB_URL": "foreign"}, {"SERVICE_APP_ID": "wx\nDB_URL=foreign"}):
            with self.assertRaises(ValueError): module.render("", changes)

    def test_rejects_duplicate_keys_and_wrong_template_fields(self):
        with self.assertRaises(ValueError): module.render("SERVICE_APP_ID=old\nSERVICE_APP_ID=duplicate\n", {"SERVICE_APP_ID": "new"})
        values = {"SERVICE_APP_ID": "wx1234567890abcdef", "SERVICE_APP_SECRET": "a" * 32, "SERVICE_TEMPLATE_ID": "fixture-template-id", "PUBLIC_URL": "https://mp-admin.hetuonian.cn"}
        module.validate(values)
        with self.assertRaises(ValueError): module.validate({**values, "SERVICE_TEMPLATE_MAPPING": '{"thing1":"phone"}'})
        with self.assertRaises(ValueError): module.validate({**values, "PUBLIC_URL": "https://user:secret@fixture.invalid"})

    def test_selected_pending_template_accepts_staff_phone_and_store_notice(self):
        import json
        values = {"SERVICE_APP_ID": module.STAFF_APP_ID, "SERVICE_APP_SECRET": "a" * 32,
                  "SERVICE_TEMPLATE_ID": module.STAFF_TEMPLATE_ID, "PUBLIC_URL": "https://mp-admin.hetuonian.cn",
                  "SERVICE_TEMPLATE_MAPPING": json.dumps(module.STAFF_MAPPING)}
        module.validate(values)
        self.assertEqual("customerPhone", module.STAFF_MAPPING["phone_number3"])


if __name__ == "__main__": unittest.main()
