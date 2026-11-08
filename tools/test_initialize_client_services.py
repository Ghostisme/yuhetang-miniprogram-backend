import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("client_initializer", Path(__file__).with_name("initialize-client-services.py"))
init = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(init)


class ClientServiceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.path = init.ROOT / "initial-data/client-services-20261108/services.json"
        cls.data = init.load_dataset(cls.path)
        cls.templates = {s["id"]:s for s in cls.data["services"]}

    def store(self, sid):
        return next(s for s in self.data["stores"] if s["id"] == sid)

    def item(self, sid, item_id):
        return next(s for s in self.store(sid)["items"] if s["serviceId"] == item_id)

    def test_all_nine_branch_images_checked_and_pending_rows_stay_off(self):
        self.assertEqual(9, len(self.data["stores"]))
        self.assertEqual(20, len(self.templates))
        items = [i for s in self.data["stores"] for i in s["items"]]
        self.assertEqual(126, sum(i["enabled"] for i in items))
        self.assertEqual(9, sum(not i["enabled"] for i in items))
        self.assertTrue(all(not i["enabled"] for i in items if i["source"].startswith("pdf")))

    def test_branch_prices_and_red_package_prices_are_not_homogenized(self):
        self.assertEqual(228, self.item("yuyao", "massage")["price"]["values"]["高级"])
        self.assertEqual(268, self.item("shanghai", "massage")["price"]["values"]["高级"])
        self.assertEqual([706,856,1078], list(self.item("bai-le-men", "yuht-package-a")["price"]["values"].values()))
        self.assertEqual([716,816,1026], list(self.item("yuyao", "yuht-package-a")["price"]["values"].values()))

    def test_meihua_has_its_own_package_and_no_spa_or_foot_offering(self):
        items = {i["serviceId"] for i in self.store("meihua")["items"]}
        self.assertFalse(items & {"yuht-spa", "foot", "yuht-package-c", "yuht-package-d"})
        project = init.project(self.templates["yuht-package-a"], self.item("meihua", "yuht-package-a"))
        self.assertEqual(["经络疏通60分钟", "扶阳悬灸60分钟"], project["steps"])
        self.assertNotIn("精油", project["name"])

    def test_per_item_and_unlisted_expert_prices_are_not_invented(self):
        p = init.project(self.templates["yuht-pedicure"], self.item("bai-le-men", "yuht-pedicure"))
        self.assertEqual("每项", p["duration"])
        self.assertIn("专家价格未标注", p["description"])
        self.assertNotIn("专家128元", p["description"])

    def test_identity_reuses_only_store_owned_rows_and_keeps_existing_ids(self):
        template, item = self.templates["massage"], self.item("yuyao", "massage")
        inherited = dict(id="massage", name="中医推拿", english="TCM Massage", source="BRAND")
        self.assertIsNone(init.match_item(template, item, [inherited]))
        own = dict(inherited, id="existing-used-by-appointments", source="STORE")
        self.assertEqual(own, init.match_item(template, item, [inherited, own]))
        with self.assertRaisesRegex(ValueError, "Ambiguous"):
            init.match_item(template, item, [own, dict(own, id="duplicate")])

    def test_exact_store_name_keeps_the_actual_database_id(self):
        source = self.store("meihua")
        matched = init.resolve_stores([source], [dict(id="old-store-id", name="梅花路中医诊所")])[0]
        self.assertEqual("old-store-id", matched["id"])
        for current in ([dict(id="meihua", name="另一家门店")], [],
                        [dict(id="one", name="梅花路店"), dict(id="two", name="梅花路中医诊所")]):
            with self.assertRaises(ValueError):init.resolve_stores([source], current)

    def test_tampered_image_fails_before_any_api_connection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            data = copy.deepcopy(self.data)
            data["stores"] = [data["stores"][0]]
            menu = data["stores"][0]["menu"]
            image = root / menu["file"]
            image.parent.mkdir()
            image.write_bytes((self.path.parent / menu["file"]).read_bytes()+b"tampered")
            (root/"services.json").write_text(json.dumps(data),encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                init.load_dataset(root/"services.json")

    def test_pending_rows_cannot_accidentally_be_enabled_in_the_dataset(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = copy.deepcopy(self.data)
            data["stores"] = [copy.deepcopy(self.store("shanghai"))]
            data["stores"][0]["items"][-1]["enabled"] = True
            path = Path(tmp)/"services.json"
            path.write_text(json.dumps(data),encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Pending items must remain disabled"):
                init.load_dataset(path)


if __name__ == "__main__":
    unittest.main()
