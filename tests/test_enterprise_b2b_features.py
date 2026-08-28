"""
Enterprise-sellable B2B feature tests: field intelligence (substitution + DGDA),
vision retraining queue, RSM heatmap + doctor tiering, offline PWA markers and
the sample receipt (PDF/WhatsApp) generator.

Run:  .venv/bin/python -m pytest tests/test_enterprise_b2b_features.py -v
"""
import importlib
import io
import json
import os
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

_TMPDIR = tempfile.mkdtemp(prefix="medlenx-ent-")
os.environ["MEDLENX_TEST_DB"] = os.path.join(_TMPDIR, "test.db")

from app import database as db  # noqa: E402

db.DB_PATH = os.environ["MEDLENX_TEST_DB"]

from fastapi.testclient import TestClient  # noqa: E402
from app import main as app_main  # noqa: E402

importlib.reload(app_main)
app_main.init_db()
client = TestClient(app_main.app)

TPL = open(os.path.join(ROOT, "templates", "index.html"), encoding="utf-8").read()


def _png_bytes():
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", (300, 400), "white").save(buf, format="JPEG")
    return buf.getvalue()


def _set_company(name):
    r = client.post("/api/officer-profile", json={
        "employee_id": "MR001", "full_name": "Field Officer", "role": "MPO",
        "company_name": name, "territory": "Dhaka South", "division": "Dhaka",
    }).json()
    # force-refresh the cached own-company used during scan enrichment
    app_main.get_current_own_company(force=True)
    return r


class TestSubstitutionEngine(unittest.TestCase):
    def setUp(self):
        _set_company("Incepta Pharmaceuticals Ltd.")

    def test_seclo_maps_to_incepta_omeprazole(self):
        d = client.get(
            "/api/medex/substitution?brand=Seclo&own_company=Incepta%20Pharmaceuticals%20Ltd."
        ).json()
        sub = d.get("substitution")
        self.assertIsNotNone(sub)
        self.assertEqual(sub["competitor"]["company"], "Square Pharmaceuticals PLC")
        self.assertIn("Omenix", sub["own_brand"]["brand"])
        self.assertEqual(sub["generic"], "Omeprazole")
        self.assertTrue(sub["pitch"])

    def test_pitch_references_brand_and_generic(self):
        d = client.get(
            "/api/medex/substitution?brand=Seclo&own_company=Incepta%20Pharmaceuticals%20Ltd."
        ).json()
        pitch = d["substitution"]["pitch"]
        self.assertIn("Omenix", pitch)
        self.assertIn("Omeprazole", pitch)

    def test_scan_attaches_substitution(self):
        d = client.post("/api/scan",
                        files={"file": ("rx.jpg", _png_bytes(), "image/jpeg")},
                        data={"district": "Dhaka", "upazila": "Dhanmondi"}).json()
        self.assertGreaterEqual(len(d.get("medicines", [])), 1)
        seclo = next((m for m in d["medicines"] if m.get("brand_name") == "Seclo"), None)
        self.assertIsNotNone(seclo)
        self.assertIn("substitution", seclo)

    def test_own_brand_has_no_substitution(self):
        _set_company("Square Pharmaceuticals PLC")
        d = client.post("/api/scan",
                        files={"file": ("rx.jpg", _png_bytes(), "image/jpeg")},
                        data={"district": "Dhaka"}).json()
        seclo = next((m for m in d["medicines"] if m.get("brand_name") == "Seclo"), None)
        if seclo:
            self.assertNotIn("substitution", seclo)


class TestDGDACompliance(unittest.TestCase):
    def test_monitor_returns_gazette(self):
        d = client.get("/api/dgda/monitor").json()
        self.assertGreater(len(d["banned"]), 0)
        self.assertGreater(len(d["price_adjusted"]), 0)
        self.assertGreater(len(d["prices"]), 0)
        self.assertTrue(d["gazette"])

    def test_banned_flag(self):
        d = client.get("/api/dgda/check?brand=Analgin").json()
        self.assertEqual(d["status"], "banned")
        self.assertEqual(d["severity"], "critical")
        self.assertIn("Banned", d["concern"])

    def test_price_adjusted_flag(self):
        d = client.get("/api/dgda/check?brand=Seclo").json()
        self.assertEqual(d["status"], "price_adjusted")
        self.assertEqual(d["severity"], "warn")
        self.assertIn("new_mrp", d)
        self.assertIn("old_mrp", d)
        self.assertGreaterEqual(d["old_mrp"], d["new_mrp"])


class TestTrainingQueue(unittest.TestCase):
    def test_post_and_list(self):
        r = client.post("/api/training/queue", json={
            "mr_id": "MR001",
            "brand_name": "Seclo",
            "corrected_brand": "Provel",
            "corrected_company": "Incepta Pharmaceuticals Ltd.",
            "raw_text": "Cap. Seclo 20mg",
            "confidence": 0.62,
            "notes": "handwritten cursive",
        })
        self.assertTrue(r.json()["success"])
        lst = client.get("/api/training/queue").json()
        self.assertEqual(lst["total"], 1)
        self.assertEqual(lst["items"][0]["corrected_brand"], "Provel")

    def test_stats(self):
        s = client.get("/api/training/queue/stats").json()
        self.assertIn("queued", s)


class TestRsmHeatmapAndTiers(unittest.TestCase):
    def test_heatmap_endpoint(self):
        d = client.get("/api/rsm/heatmap?days=30").json()
        self.assertIn("districts", d)
        self.assertIn("regions", d)
        self.assertTrue(d["own_company"])

    def test_doctor_tiers(self):
        d = client.get("/api/rsm/doctors?days=30&min_rx=1").json()
        self.assertIn("doctors", d)
        self.assertIn("total", d)
        for doc in d["doctors"]:
            self.assertIn(doc["tier"], ("A", "B", "C"))
            self.assertIn("at_risk", doc)
            self.assertIn("sov", doc)

    def test_tier_filter(self):
        d = client.get("/api/rsm/doctors?tier=A&days=30&min_rx=1").json()
        for doc in d["doctors"]:
            self.assertEqual(doc["tier"], "A")


class TestSampleReceipt(unittest.TestCase):
    def test_pdf(self):
        r = client.get(
            "/api/receipt.pdf?doctor=Dr.%20Rahman"
            "&medicines=%5B%7B%22brand_name%22%3A%22Omenix%22%7D%5D"
        )
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.headers["content-type"], "application/pdf")
        self.assertTrue(r.content.startswith(b"%PDF"))

    def test_whatsapp(self):
        r = client.get("/api/receipt/whatsapp?doctor=Dr.%20Rahman").json()
        self.assertIn("share_url", r)
        self.assertTrue(r["share_url"].startswith("https://wa.me/"))
        self.assertIn("Thank you", r["message"])


class TestEnterpriseUI(unittest.TestCase):
    def test_field_intelligence_ui(self):
        for token in ["substitutionCard", "dgdaFlag", "queueTrainingItem",
                      "cropMedicineSlice", "Smart pitch note", "AI Guess"]:
            self.assertIn(token, TPL, token)

    def test_rsm_heatmap_and_tiering_ui(self):
        for token in ["rsmHeatmap", "heatmapColor", "loadRsmHeatmap",
                      "tierBody", "tier-filter", "loadDoctorTiers",
                      "Territory Penetration Heatmap",
                      "Doctor Prescribing Tiering Matrix"]:
            self.assertIn(token, TPL, token)

    def test_offline_sync_ui(self):
        for token in ["syncBadge", "refreshPendingCount", "flushOfflineQueue",
                      "renderOfflineBar", "OFFLINE_SYNC"]:
            self.assertIn(token, TPL, token)

    def test_receipt_share_ui(self):
        for token in ["hdSharePdf", "hdShareWa", "shareReceipt"]:
            self.assertIn(token, TPL, token)


if __name__ == "__main__":
    unittest.main()
