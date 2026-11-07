import copy
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile


SPEC = importlib.util.spec_from_file_location("initializer", Path(__file__).with_name("initialize-official-stores.py"))
init = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(init)


class OfficialStoreTests(unittest.TestCase):
    def setUp(self):
        self.dataset = init.ROOT / "initial-data/official-stores/stores.json"
        self.manifest = init.load_dataset(self.dataset)
        self.source = self.manifest["stores"][0]

    def test_same_name_preserves_existing_store_id(self):
        old = {"id": "already-used-by-orders", "name": self.source["name"]}
        plan = init.plan_stores([self.source], [old])[0]
        self.assertEqual("already-used-by-orders", plan["id"])
        self.assertEqual(old, plan["before"])

    def test_ambiguous_or_conflicting_identity_is_rejected(self):
        with self.assertRaises(ValueError):
            init.plan_stores([self.source], [{"id": self.source["id"], "name": "另一家店"}])
        with self.assertRaises(ValueError):
            init.plan_stores([self.source], [{"id": "one", "name": self.source["name"]},
                                             {"id": "two", "name": self.source["name"]}])

    def test_public_update_preserves_booking_flags_associations_and_coordinates(self):
        old = {"id": "existing-id", "name": self.source["name"], "enabled": False,
               "onlineBooking": False, "disabledServiceIds": ["private-service"],
               "serviceOverrides": {"massage": {"name": "本店项目"}},
               "latitude": "31.1", "longitude": "121.1", "gallery": ["/media/placeholder.jpg"]}
        before = copy.deepcopy(old)
        merged = init.merge_profile(old, self.source, "/uploads/photo.jpg")
        self.assertEqual(before, old)
        for key in ("id", "enabled", "onlineBooking", "disabledServiceIds", "serviceOverrides", "latitude", "longitude"):
            self.assertEqual(old[key], merged[key])
        self.assertEqual(["/uploads/photo.jpg"], merged["gallery"])

    def test_checksum_failure_happens_before_any_api_is_created(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manifest = copy.deepcopy(self.manifest)
            manifest["stores"] = [manifest["stores"][0]]
            source = manifest["stores"][0]
            photo = root / source["photo"]["file"]
            photo.parent.mkdir()
            photo.write_bytes((self.dataset.parent / source["photo"]["file"]).read_bytes() + b"changed")
            init.write_json(root / "stores.json", manifest)
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                init.load_dataset(root / "stores.json")

    def test_xlsx_encodes_plain_text_and_preserves_other_template_files(self):
        output = io.BytesIO()
        sheet = '<worksheet xmlns="%s"><dimension ref="A1:G1"/><sheetData><row r="1"/></sheetData></worksheet>' % init.NS
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr("xl/worksheets/sheet1.xml", sheet)
            archive.writestr("unchanged", "server styles and headers")
        row = {**self.source, "name": "名字<&>不是公式", "image": ""}
        with zipfile.ZipFile(io.BytesIO(init.workbook(output.getvalue(), [row]))) as archive:
            self.assertEqual(b"server styles and headers", archive.read("unchanged"))
            document = ET.fromstring(archive.read("xl/worksheets/sheet1.xml"))
            ns = {"s": init.NS}
            cells = document.findall("s:sheetData/s:row", ns)[1].findall("s:c", ns)
            self.assertEqual("名字<&>不是公式", cells[1].find("s:is/s:t", ns).text)
            self.assertTrue(all(cell.get("t") == "inlineStr" for cell in cells))

    def test_remote_cleartext_and_credential_urls_are_rejected(self):
        for url in ("http://example.com", "https://user:secret@example.com", "https://example.com/api"):
            with self.assertRaises(ValueError):
                init.Api(url)
        self.assertEqual("http://127.0.0.1:8790", init.Api("http://127.0.0.1:8790").base)


if __name__ == "__main__":
    unittest.main()
