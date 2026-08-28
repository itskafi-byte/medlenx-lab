"""
Rx Audit Drawer + Duplicate-Rx fraud alert + Doctor Detailing Target Tracker.

Covers the Prescription Audit Summary slide-over drawer (backend payload,
CSV export, clipboard list), the DCT perceptual-hash duplicate scan alert,
and the RSM → MPO doctor target auto visit-log.

Run:  .venv/bin/python -m pytest tests/test_rx_audit_drawer.py -v
"""
import io
import os
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

_TMPDIR = tempfile.mkdtemp(prefix="medlenx-rxaudit-")
os.environ["MEDLENX_TEST_DB"] = os.path.join(_TMPDIR, "test.db")

from app import database as db  # noqa: E402

db.DB_PATH = os.environ["MEDLENX_TEST_DB"]

from fastapi.testclient import TestClient  # noqa: E402
from app import main as app_main  # noqa: E402
from app.rx_audit import (  # noqa: E402
    compute_phash, hamming_distance, is_duplicate_hash,
    build_market_share, items_to_csv, items_to_clipboard,
)

importlib = __import__("importlib")
importlib.reload(app_main)
app_main.init_db()
client = TestClient(app_main.app)

TPL = open(os.path.join(ROOT, "templates", "index.html"), encoding="utf-8").read()

from PIL import Image  # noqa: E402


def _png_bytes(color, seed=1, style="lines", noise=6, size=(280, 200),
               resize=None, jpg_q=None):
    """Structured synthetic 'prescription'. Large layout blocks drive the
    low-frequency DCT window and paper-like noise keeps coefficients
    non-degenerate (flat colour planes all hash identically)."""
    import random
    from PIL import ImageDraw
    rng = random.Random(seed)
    img = Image.new("RGB", size, (246, 244, 240))
    d = ImageDraw.Draw(img)
    if style == "lines":      # dark header band + note lines on the bottom
        d.rectangle([0, 0, size[0], 44], fill=color)
        for i, y in enumerate(range(110, size[1] - 10, 16)):
            d.line([(14, y), (size[0] - 14 - (i % 3) * 46, y + 4)],
                   fill=(60, 60, 70), width=3)
    elif style == "circles":  # dark bottom-left quadrant + rings top-right
        d.rectangle([0, size[1] // 2, size[0] // 2, size[1]], fill=color)
        for i in range(size[0] // 2 + 10, size[0] - 20, 34):
            d.ellipse([i, 20, i + 30, 50], outline=color, width=3)
    elif style == "waves":    # dark right-third band + waves on the left
        d.rectangle([size[0] * 7 // 10, 0, size[0], size[1]], fill=color)
        for y in range(24, size[1] - 10, 20):
            pts = [(12 + (size[0] * 3 // 5) * s / 24, y + 9 * ((-1) ** (s % 2)))
                   for s in range(25)]
            d.line(pts, fill=(70, 70, 80), width=3)
    elif style == "grid":     # dark top-left quadrant + hatch on the bottom
        d.rectangle([0, 0, size[0] // 2 - 10, 90], fill=color)
        for i in range(0, size[0], 22):
            d.line([(i, 100), (i + 13, size[1] - 6)], fill=(70, 70, 80), width=3)
    px = img.load()
    for y in range(size[1]):
        for x in range(size[0]):
            n = rng.randint(-noise, noise)
            r, g, b = px[x, y]
            px[x, y] = (max(0, min(255, r + n)),
                        max(0, min(255, g + n)),
                        max(0, min(255, b + n)))
    if resize:
        img = img.resize(resize)
    if jpg_q:
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=jpg_q)
        buf.seek(0)
        img = Image.open(buf).convert("RGB")
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


BLUE_RX = _png_bytes((30, 64, 175), seed=1, style="lines")
GREEN_RX = _png_bytes((22, 101, 52), seed=2, style="circles")
TEAL_RX = _png_bytes((13, 148, 136), seed=3, style="grid")
PURPLE_RX = _png_bytes((109, 40, 217), seed=4, style="waves")


def _scan(image_bytes, mr_id="MR001", **fields):
    data = {"mr_id": mr_id}
    data.update(fields)
    resp = client.post(
        "/api/scan",
        files={"file": ("rx.png", image_bytes, "image/png")},
        data=data,
    )
    assert resp.status_code == 200, resp.text
    return resp.json()


class TestRxDrawerTemplate(unittest.TestCase):
    """The slide-over drawer, its toolbar/footer controls and the clickable
    Recent Prescriptions cards exist in the template."""

    def test_drawer_scaffold(self):
        for eid in ["rxDrawer", "rxDrawerPanel", "rxDrawerClose", "rxDrawerBackdrop",
                    "rxDrawerRxNo", "rxDrawerDoctor", "rxAuditItems", "rxAuditShare"]:
            self.assertIn(f'id="{eid}"', TPL)

    def test_search_and_filter_pills(self):
        self.assertIn('id="rxAuditSearch"', TPL)
        self.assertIn('Search scanned items...', TPL)
        for pill in ['data-filter="all"', 'data-filter="own"',
                     'data-filter="competitor"', 'data-filter="low"']:
            self.assertIn(pill, TPL)

    def test_table_headers(self):
        for header in ["Medicine Brand", "Generic Composition",
                       "Pharmaceutical", "Conf."]:
            self.assertIn(header, TPL)

    def test_export_and_copy_buttons(self):
        self.assertIn('id="rxExportCsvBtn"', TPL)
        self.assertIn("Export Rx Items as CSV", TPL)
        self.assertIn('id="rxCopyBtn"', TPL)
        self.assertIn("Copy List to Clipboard", TPL)

    def test_duplicate_tag_and_crop_popover(self):
        self.assertIn("Duplicate Rx Detected", TPL)
        self.assertIn('id="rxCropPopover"', TPL)
        self.assertIn("rx-crop-hover", TPL)
        self.assertIn("Own Portfolio Match", TPL)
        self.assertIn("Verify against Medex", TPL)

    def test_history_cards_open_drawer(self):
        self.assertIn("openRxAuditDrawer", TPL)
        self.assertIn("data-rx-id", TPL)
        self.assertIn('class="rx-card', TPL)

    def test_doctor_target_tracker_card(self):
        self.assertIn("Doctor Detailing Target Tracker", TPL)
        for eid in ["doctorTargetForm", "dtMpo", "dtDoctor", "dtTarget",
                    "dtBody", "dtVisits", "loadDoctorTargets"]:
            self.assertIn(eid, TPL)


class TestRxImageHashing(unittest.TestCase):
    """DCT pHash: stable across identical inputs, distinct for different Rx."""

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="medlenx-phash-")
        self.a = os.path.join(self.dir, "a.png")
        self.b = os.path.join(self.dir, "b.png")
        Image.open(io.BytesIO(BLUE_RX)).save(self.a)
        Image.open(io.BytesIO(GREEN_RX)).save(self.b)

    def test_identical_image_same_hash(self):
        h1 = compute_phash(self.a)
        h2 = compute_phash(self.a)
        self.assertTrue(h1)
        self.assertEqual(len(h1), 16)
        self.assertEqual(h1, h2)
        self.assertEqual(hamming_distance(h1, h2), 0)

    def test_different_images_far_apart(self):
        ha, hb = compute_phash(self.a), compute_phash(self.b)
        self.assertNotEqual(ha, hb)
        self.assertGreater(hamming_distance(ha, hb), 8)
        self.assertFalse(is_duplicate_hash(ha, hb))

    def test_noisy_recompression_is_duplicate(self):
        # simulate a phone re-upload: same image, resized + heavily
        # JPEG-compressed before the MPO re-scans it
        variant = _png_bytes((30, 64, 175), seed=1, style="lines",
                             noise=11, resize=(252, 180), jpg_q=55)
        path = os.path.join(self.dir, "variant.png")
        with open(path, "wb") as f:
            f.write(variant)
        h1 = compute_phash(self.a)
        h2 = compute_phash(path)
        self.assertTrue(is_duplicate_hash(h1, h2),
                        f"dist={hamming_distance(h1, h2)}")

    def test_empty_and_invalid_inputs(self):
        self.assertEqual(compute_phash("/nonexistent/path.png"), "")
        self.assertIsNone(hamming_distance("", "abc"))
        self.assertIsNone(hamming_distance("zzz", "abc"))


class TestMarketShareAndExports(unittest.TestCase):
    def test_market_share_split(self):
        meds = [
            {"brand_name": "Seclo", "company": "Square Pharmaceuticals PLC"},
            {"brand_name": "Napa", "company": "Beximco Pharmaceuticals Ltd."},
            {"brand_name": "Opal", "company": "Healthcare Pharmaceuticals Ltd."},
        ]
        share = build_market_share(meds, "Healthcare Pharmaceuticals Ltd.")
        self.assertEqual(share["total_medicines"], 3)
        self.assertEqual(share["own_count"], 1)
        self.assertEqual(share["competitor_count"], 2)
        self.assertAlmostEqual(share["own_share_pct"], 33.3)
        self.assertIn("Opal", share["own_brands"])

    def test_csv_layout(self):
        meds = [{"brand_name": "Seclo", "strength": "20 mg", "type": "EC Capsule",
                 "generic": "Omeprazole", "company": "Square Pharmaceuticals PLC",
                 "confidence": 0.92}]
        text = items_to_csv(meds, "Healthcare Pharmaceuticals Ltd.")
        lines = text.strip().splitlines()
        self.assertIn("Brand Name", lines[0])
        self.assertIn("Pharmaceutical Company", lines[0])
        row = lines[1].split(",")
        self.assertEqual(row[0], "Seclo")
        self.assertEqual(row[5], "92")
        self.assertEqual(row[6], "Competitor")

    def test_clipboard_layout(self):
        meds = [{"brand_name": "Napa", "strength": "500 mg", "type": "Tablet",
                 "generic": "Paracetamol", "company": "Beximco",
                 "confidence": 0.7}]
        text = items_to_clipboard(meds, "Rx #A-1 header")
        lines = text.splitlines()
        self.assertEqual(lines[0], "Rx #A-1 header")
        self.assertIn("1. Napa 500 mg Tablet", lines[1])
        self.assertIn("(70%)", lines[1])


class TestRxDrawerEndpoints(unittest.TestCase):
    """GET /api/prescriptions/{pid} powers the whole drawer in one call."""

    @classmethod
    def setUpClass(cls):
        # other test files re-point the officer profile at their own company;
        # pin ours so the Own-Portfolio-Match expectations are deterministic
        client.post("/api/officer-profile", json={
            "employee_id": "MR001", "full_name": "Field Officer", "role": "MPO",
            "company_name": "Healthcare Pharmaceuticals Ltd.",
            "territory": "Dhaka South", "division": "Dhaka",
        })
        app_main.get_current_own_company(force=True)
        data = _scan(PURPLE_RX, mr_id="MR-DRAWER")
        cls.pid = data["id"]

    def test_detail_payload_shape(self):
        r = client.get(f"/api/prescriptions/{self.pid}")
        self.assertEqual(r.status_code, 200)
        d = r.json()
        self.assertEqual(d["prescription"]["rx_no"], f"A-{self.pid}")
        self.assertIn("Dr.", d["prescription"]["doctor_name"])
        self.assertTrue(d["items"])
        item = d["items"][0]
        for key in ["brand_name", "generic", "company", "confidence_pct",
                    "low_confidence", "is_own", "portfolio_match"]:
            self.assertIn(key, item)
        share = d["market_share"]
        self.assertEqual(share["own_count"] + share["competitor_count"],
                         share["total_medicines"])
        self.assertEqual(share["total_medicines"], len(d["items"]))
        self.assertIn("is_duplicate", d)

    def test_portfolio_match_for_competitor(self):
        d = client.get(f"/api/prescriptions/{self.pid}").json()
        seclo = next(m for m in d["items"]
                     if m["brand_name"].lower() == "seclo")
        self.assertIsNotNone(seclo["portfolio_match"],
                             "Seclo (Square, Omeprazole) must map to an own brand")
        own = seclo["portfolio_match"]["own_brand"]
        self.assertIn("Healthcare", own["company"])
        self.assertEqual(seclo["portfolio_match"]["pitch"], seclo["portfolio_match"]["pitch"])

    def test_csv_export_endpoint(self):
        r = client.get(f"/api/prescriptions/{self.pid}/export.csv")
        self.assertEqual(r.status_code, 200)
        self.assertIn("text/csv", r.headers["content-type"])
        self.assertIn("attachment", r.headers.get("content-disposition", ""))
        lines = r.text.strip().splitlines()
        self.assertIn("Brand Name", lines[0])
        self.assertIn("Pharmaceutical Company", lines[0])
        self.assertTrue(any("Seclo" in ln for ln in lines[1:]))

    def test_clipboard_endpoint(self):
        r = client.get(f"/api/prescriptions/{self.pid}/clipboard")
        self.assertEqual(r.status_code, 200)
        d = r.json()
        self.assertIn(f"Rx #A-{self.pid}", d["header"])
        self.assertGreaterEqual(len(d["text"].splitlines()), 2)

    def test_detail_404(self):
        self.assertEqual(client.get("/api/prescriptions/999999").status_code, 404)
        self.assertEqual(
            client.get("/api/prescriptions/999999/export.csv").status_code, 404)


class TestDuplicateScanAlert(unittest.TestCase):
    """Re-uploading the same physical Rx is flagged; a fresh Rx is not."""

    @classmethod
    def setUpClass(cls):
        cls.first = _scan(BLUE_RX, mr_id="MR-DUP-A")

    def test_same_image_flags_duplicate(self):
        second = _scan(BLUE_RX, mr_id="MR-DUP-B")
        self.assertIn("duplicate", second)
        dup = second["duplicate"]
        self.assertIsInstance(dup["duplicate_of"], int)
        self.assertLess(dup["duplicate_of"], second["id"])
        self.assertEqual(dup["mr_id"], self.first.get("mr_id", "MR-DUP-A"))
        self.assertIn("Duplicate Rx detected", dup["message"])
        # list endpoint exposes the flag for the red card tag
        rows = client.get("/api/prescriptions?limit=50").json()["prescriptions"]
        row = next(r for r in rows if r["id"] == second["id"])
        self.assertEqual(row["duplicate_of"], dup["duplicate_of"])
        # drawer detail carries the fraud banner data
        d = client.get(f"/api/prescriptions/{second['id']}").json()
        self.assertTrue(d["is_duplicate"])
        self.assertEqual(d["duplicate"]["mr_id"], "MR-DUP-A")

    def test_different_image_not_flagged(self):
        other = _scan(GREEN_RX, mr_id="MR-DUP-C")
        self.assertNotIn("duplicate", other)
        d = client.get(f"/api/prescriptions/{other['id']}").json()
        self.assertFalse(d["is_duplicate"])


class TestDoctorTargetTracker(unittest.TestCase):
    """RSM-attached doctor lists auto-log visits from prescription scans."""

    MONTH_DOCTOR = "Dr. A. K. M. Rahman"  # the mock VL scan's doctor

    def test_create_requires_fields(self):
        r = client.post("/api/rsm/doctor-targets", json={"mpo_id": "MR001"})
        self.assertEqual(r.status_code, 400)

    @classmethod
    def setUpClass(cls):
        # ensure at least one prior scan by the mock doctor exists this month
        _scan(TEAL_RX, mr_id="MR-TRACK-PRIOR")

    def test_attach_progress_and_delete(self):
        r = client.post("/api/rsm/doctor-targets", json={
            "mpo_id": "MR-TRACK", "mpo_name": "Tracker MPO",
            "doctor_name": self.MONTH_DOCTOR, "specialty": "Medicine",
            "monthly_target": 4,
        })
        self.assertEqual(r.status_code, 200)
        # this month's existing scans by the same doctor retro-log visits
        targets = client.get("/api/rsm/doctor-targets?mpo_id=MR-TRACK").json()
        rows = [t for t in targets["targets"] if t["mpo_id"] == "MR-TRACK"]
        self.assertTrue(rows)
        row = rows[0]
        self.assertEqual(row["doctor_name"], self.MONTH_DOCTOR)
        self.assertGreaterEqual(row["visits"], 1)
        self.assertGreater(row["percent"], 0)
        self.assertEqual(row["remaining"], max(4 - row["visits"], 0))

        # re-attaching the same doctor must not double-count visits
        client.post("/api/rsm/doctor-targets", json={
            "mpo_id": "MR-TRACK", "doctor_name": self.MONTH_DOCTOR,
            "monthly_target": 4,
        })
        targets2 = client.get("/api/rsm/doctor-targets?mpo_id=MR-TRACK").json()
        row2 = next(t for t in targets2["targets"] if t["id"] == row["id"])
        self.assertEqual(row2["visits"], row["visits"])

        # auto visit log has the Rx links
        visits = client.get("/api/rsm/doctor-targets/visits?mpo_id=MR-TRACK").json()
        self.assertTrue(visits)
        self.assertIn("prescription_id", visits[0])

        # delete cleans up target + its visit log
        self.assertEqual(
            client.delete(f"/api/rsm/doctor-targets/{row['id']}").json()["ok"], True)
        visits_after = client.get(
            "/api/rsm/doctor-targets/visits?mpo_id=MR-TRACK").json()
        self.assertEqual(visits_after, [])

    def test_fuzzy_name_matching(self):
        from app.database import _doctor_names_match, normalize_doctor_name
        self.assertTrue(_doctor_names_match(
            "Dr. A. K. M. Rahman", "A.K.M. Rahman"))
        self.assertTrue(_doctor_names_match(
            "dr. rahman", "Prof. Rahman"))
        self.assertFalse(_doctor_names_match(
            "Dr. A. K. M. Rahman", "Dr. Sabrina Chowdhury"))
        self.assertEqual(normalize_doctor_name("Dr. A. K. M. Rahman"),
                         "a k m rahman")


if __name__ == "__main__":
    unittest.main()
