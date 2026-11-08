import contextlib
import copy
from email import policy
from email.parser import BytesParser
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("local_content", Path(__file__).with_name("initialize-local-content.py"))
init = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(init)


class FakeApi:
    """Implements the existing API contracts, with an initially empty cloud editor."""
    base = "http://127.0.0.1:8790"

    def __init__(self, data):
        self.data = data
        self.draft = dict(brandName="裕和堂", brandEnglish="YU HE TANG", tagline="", brandStory="", theme="sage",
                          hero={k: "" for k in data["content"]["hero"]}, contact={k: "" for k in data["content"]["contact"]},
                          sections=[], services=[], products=[], galleryGroups=[], customerServiceEnabled=True, version=1)
        self.published = copy.deepcopy(self.draft)
        self.stores = [dict(id=s["id"], name=s["name"], catalogMode="INDEPENDENT", address="真实地址", image="/uploads/official.jpg")
                       for s in data["stores"]]
        self.services = {s["id"]: [dict(id="svc-" + s["id"] + "-" + key, name=value["name"], english=value["english"],
                                       source="STORE", enabled=True) for key, value in data["skillDefinitions"].items()
                                      if s["id"] != "meihua" or key != "foot"] for s in data["stores"]}
        self.rules = {s["id"]: dict(opens="09:00", capacity=6) for s in data["stores"]}
        self.teachers, self.assets, self.bodies, self.writes = [], [], {}, []

    def get(self, path, binary=False):
        if path in self.bodies:
            return self.bodies[path]
        if path == "/api/admin/me":
            return dict(hq=True, permissions=["content:read", "content:write", "content:publish", "assets:read", "assets:write",
                                               "therapists:read", "therapists:write", "services:read", "rules:write"])
        if path == "/api/runtime":
            return dict(independentStoreServices=True)
        values = {"/api/admin/content": self.draft, "/api/site": self.published, "/api/admin/stores": self.stores,
                  "/api/admin/assets": self.assets, "/api/admin/therapists": self.teachers}
        if path in values:
            return copy.deepcopy(values[path])
        if path.startswith("/api/admin/stores/"):
            _, _, _, _, sid, kind = path.split("/")
            return copy.deepcopy((self.rules if kind == "rules" else self.services)[sid])
        raise AssertionError("Unexpected API read: " + path)

    def request(self, method, path, payload, content_type=None):
        self.writes.append((method, path))
        if path == "/api/admin/assets":
            msg = BytesParser(policy=policy.default).parsebytes(("Content-Type: " + content_type + "\r\n\r\n").encode() + payload)
            part = next(msg.iter_parts())
            key = "asset-" + str(len(self.assets))
            asset = dict(id=key, url="/uploads/" + key + ".webp", title=part.get_filename())
            self.bodies[asset["url"]] = part.get_payload(decode=True)
            self.assets.append(asset)
            return copy.deepcopy(asset)
        if path.startswith("/api/admin/content/"):
            assert payload["version"] == self.draft["version"]
            self.draft = dict(copy.deepcopy(payload), version=payload["version"] + 1)
            if path.endswith("/publish"):
                self.published = copy.deepcopy(self.draft)
            return copy.deepcopy(self.draft)
        if path == "/api/admin/therapists":
            assert payload["sample"] is True and "示例" in payload["name"]
            self.teachers.append(dict(copy.deepcopy(payload), id="teacher-" + str(len(self.teachers)), storeName="门店"))
            return dict(saved=True)
        raise AssertionError("Unexpected API write: " + path)


class LocalContentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = init.load_dataset(init.DEFAULT_DATASET)

    def run_import(self, api, apply=True):
        with tempfile.TemporaryDirectory() as temp, contextlib.redirect_stdout(io.StringIO()):
            return init.initialize(api, self.data, init.DEFAULT_DATASET.parent, apply, temp)

    def test_fixture_contains_only_the_approved_sample_content(self):
        self.assertEqual((4, 2, 2, 33, 27), (len(self.data["content"]["services"]), len(self.data["content"]["products"]),
                         len(self.data["content"]["galleryGroups"]), len(self.data["assets"]), len(self.data["therapists"])))
        self.assertNotIn("stores", self.data["content"])
        self.assertNotIn("appointments", self.data)
        self.assertTrue(all(t["sample"] and "示例" in t["name"] for t in self.data["therapists"]))

    def test_preview_never_writes(self):
        api = FakeApi(self.data)
        report = self.run_import(api, False)
        self.assertEqual("preview", report["status"])
        self.assertFalse(api.writes)

    def test_full_import_is_additive_and_repeat_makes_no_writes(self):
        api = FakeApi(self.data)
        before = copy.deepcopy((api.stores, api.services, api.rules))
        report = self.run_import(api)
        self.assertEqual("verified", report["status"])
        self.assertEqual(33, len(report["uploaded"]))
        self.assertEqual(27, len(report["createdTherapists"]))
        self.assertEqual("publish", report["contentStatus"])
        self.assertEqual(before, (api.stores, api.services, api.rules))
        self.assertTrue(all(a["title"].startswith("示例素材-") for a in api.assets))
        api.writes.clear()
        again = self.run_import(api)
        self.assertEqual("unchanged", again["contentStatus"])
        self.assertFalse(api.writes)

    def test_live_edits_and_existing_staff_survive_including_disabled_values(self):
        api = FakeApi(self.data)
        api.draft.update(tagline="客户已改的口号", theme="ink", customerServiceEnabled=False)
        api.draft["hero"]["title"] = "客户已改的标题"
        api.draft["products"] = [dict(id="aged-moxa", name="客户改名", enabled=False)]
        api.published = copy.deepcopy(api.draft)
        existing = dict(self.data["therapists"][0], name="正式员工", sample=False)
        api.teachers.append(existing)
        self.run_import(api)
        self.assertEqual("客户已改的口号", api.published["tagline"])
        self.assertEqual("客户已改的标题", api.published["hero"]["title"])
        self.assertFalse(api.published["customerServiceEnabled"])
        self.assertEqual(dict(id="aged-moxa", name="客户改名", enabled=False), api.published["products"][0])
        self.assertEqual(existing, api.teachers[0])
        self.assertEqual(27, len(api.teachers))

    def test_unpublished_edits_remain_unpublished(self):
        api = FakeApi(self.data)
        api.draft["brandStory"] = "尚未发布的内部草稿"
        public_before = copy.deepcopy(api.published)
        report = self.run_import(api)
        self.assertEqual("draft", report["contentStatus"])
        self.assertEqual(public_before, api.published)
        self.assertEqual("尚未发布的内部草稿", api.draft["brandStory"])
        self.assertEqual(2, len(api.draft["products"]))
        self.assertNotIn(("PUT", "/api/admin/content/publish"), api.writes)

    def test_sample_skills_use_branch_ids_and_skip_absent_or_disabled_foot_care(self):
        api = FakeApi(self.data)
        for s in api.services["shanghai"]:
            if s["english"] == self.data["skillDefinitions"]["foot"]["english"]:
                s["enabled"] = False
        self.run_import(api)
        for teacher in api.teachers:
            self.assertTrue(all(sid.startswith("svc-" + teacher["storeId"]) for sid in teacher["serviceIds"]))
            if teacher["storeId"] in {"meihua", "shanghai"}:
                self.assertFalse(any(sid.endswith("-foot") for sid in teacher["serviceIds"]))

    def test_unready_catalog_aborts_before_any_write(self):
        api = FakeApi(self.data)
        api.stores[0]["catalogMode"] = "INHERIT"
        with self.assertRaisesRegex(ValueError, "independent branch"):
            self.run_import(api)
        self.assertFalse(api.writes)

    def test_previously_imported_staff_renamed_or_promoted_are_not_overwritten(self):
        api = FakeApi(self.data)
        self.run_import(api)
        api.teachers[0].update(name="已改名的老师", sample=False, grade="人工确认", enabled=False)
        before = copy.deepcopy(api.teachers)
        api.writes.clear()
        self.run_import(api)
        self.assertEqual(before, api.teachers)
        self.assertFalse(api.writes)

    def test_ambiguous_branch_skills_abort_before_upload(self):
        api = FakeApi(self.data)
        api.services["yuyao"].append(dict(api.services["yuyao"][0], id="duplicate"))
        with self.assertRaisesRegex(ValueError, "Ambiguous branch skill"):
            self.run_import(api)
        self.assertFalse(api.writes)

    def test_tampered_asset_aborts_before_server_connection(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "content.json"
            data = copy.deepcopy(self.data)
            data["assets"][0]["sha256"] = "0" * 64
            path.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                init.load_dataset(path)


if __name__ == "__main__":
    unittest.main()
