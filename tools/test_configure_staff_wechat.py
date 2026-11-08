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
        values = {"SERVICE_APP_ID": "wx1234567890abcdef", "SERVICE_APP_SECRET": "a" * 32, "SERVICE_TEMPLATE_ID": "fixture-template-id", "PUBLIC_URL": "https://mp-admin.hetuonian.cn", "SERVICE_TEMPLATE_MAPPING": '{"thing1":"storeName"}'}
        module.validate(values)
        with self.assertRaises(ValueError): module.validate({**values, "SERVICE_TEMPLATE_MAPPING": '{"thing1":"phone"}'})
        with self.assertRaises(ValueError): module.validate({**values, "PUBLIC_URL": "https://user:secret@fixture.invalid"})

    def test_actual_template_mapping_accepts_staff_phone_and_store_notice(self):
        import json
        values = {"SERVICE_APP_ID": module.STAFF_APP_ID, "SERVICE_APP_SECRET": "a" * 32,
                  "SERVICE_TEMPLATE_ID": "fixture-formal-message-template", "PUBLIC_URL": "https://mp-admin.hetuonian.cn",
                  "SERVICE_TEMPLATE_MAPPING": json.dumps({"phone_number3": "customerPhone", "thing4": "staffNotice"})}
        module.validate(values)

    def test_requires_explicit_template_and_mapping_instead_of_a_subscription_default(self):
        values = {"SERVICE_APP_ID": module.STAFF_APP_ID, "SERVICE_APP_SECRET": "a" * 32,
                  "SERVICE_TEMPLATE_ID": "fixture-formal-message-template", "PUBLIC_URL": "https://mp-admin.hetuonian.cn",
                  "SERVICE_TEMPLATE_MAPPING": '{"thing1":"storeName"}'}
        for patch in ({"SERVICE_TEMPLATE_ID": ""}, {"SERVICE_TEMPLATE_MAPPING": ""}, {"SERVICE_TEMPLATE_MAPPING": "{}"}):
            with self.assertRaises(ValueError): module.validate({**values, **patch})
        del values["SERVICE_TEMPLATE_MAPPING"]
        with self.assertRaises(ValueError): module.validate(values)


if __name__ == "__main__": unittest.main()
