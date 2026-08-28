"""
DGDA NEML & price-ceiling compliance, prescribing-pattern analytics,
MPO pitch cards, and geofenced off-territory verification.

Run:  .venv/bin/python -m pytest tests/test_compliance_geofence.py -v
"""
import os
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

_TMPDIR = tempfile.mkdtemp(prefix="medlenx-compliance-")
os.environ["MEDLENX_TEST_DB"] = os.path.join(_TMPDIR, "test.db")

from app import database as db  # noqa: E402

db.DB_PATH = os.environ["MEDLENX_TEST_DB"]

from fastapi.testclient import TestClient  # noqa: E402
from app import main as app_main  # noqa: E402
from app.compliance import (  # noqa: E402
    neml_lookup, is_antibiotic, is_broad_spectrum, resolve_therapeutic_class,
    polypharmacy_index, price_ceiling_alert, therapy_breakdown,
    clinical_summary, territory_check, resolve_geo_district,
)

importlib = __import__("importlib")
importlib.reload(app_main)
app_main.init_db()
client = TestClient(app_main.app)

TPL = open(os.path.join(ROOT, "templates", "index.html"), encoding="utf-8").read()

from tests.test_rx_audit_drawer import _png_bytes, _scan  # noqa: E402


class TestNemlLookup(unittest.TestCase):
    """NEML 295 membership drives the blue 'NEML Listed' pill."""

    def test_core_molecules_listed(self):
        for mol in ["Omeprazole", "Metformin", "Amlodipine", "Azithromycin",
                    "Paracetamol", "Cefixime"]:
            hit = neml_lookup(mol)
            self.assertTrue(hit["listed"], mol)
            self.assertTrue(hit["class"], mol)

    def test_ingredient_blob_with_dosage_resolves(self):
        hit = neml_lookup("Omeprazole 20 mg")
        self.assertTrue(hit["listed"])
        self.assertEqual(hit["molecule"], "Omeprazole")

    def test_unknown_molecule_not_listed(self):
        self.assertFalse(neml_lookup("Unobiological Xyzol")["listed"])
        self.assertFalse(neml_lookup("")["listed"])

    def test_dataset_has_295_scale_coverage(self):
        import json
        data = json.load(open(os.path.join(ROOT, "data", "neml_list.json")))
        self.assertGreaterEqual(len(data["molecules"]), 120)
        classes = {m["class"] for m in data["molecules"]}
        self.assertIn("Antibiotics", classes)
        self.assertIn("Cardiology", classes)


class TestAntibioticStewardship(unittest.TestCase):
    def test_common_antibiotics_detected(self):
        for g in ["Azithromycin", "Cefixime", "Ciprofloxacin", "Amoxicillin",
                  "Doxycycline", "Metronidazole"]:
            self.assertTrue(is_antibiotic(g), g)

    def test_broad_spectrum_flagged(self):
        self.assertTrue(is_broad_spectrum("Azithromycin"))
        self.assertTrue(is_broad_spectrum("Amoxicillin + Clavulanic Acid"))
        self.assertFalse(is_broad_spectrum("Amoxicillin"))
        self.assertFalse(is_broad_spectrum("Paracetamol"))

    def test_non_antibiotics_clear(self):
        for g in ["Paracetamol", "Omeprazole", "Amlodipine", "Cetirizine"]:
            self.assertFalse(is_antibiotic(g), g)

    def test_category_fallback(self):
        self.assertTrue(is_antibiotic("Some brand", category="Antibiotic"))
        self.assertFalse(is_antibiotic("Some brand", category="Laxative"))


class TestTherapeuticClass(unittest.TestCase):
    def test_neml_classes_win(self):
        self.assertEqual(resolve_therapeutic_class("Omeprazole"), "Gastroenterology")
        self.assertEqual(resolve_therapeutic_class("Amlodipine"), "Cardiology")
        self.assertEqual(resolve_therapeutic_class("Azithromycin"), "Antibiotics")

    def test_keyword_fallback(self):
        self.assertEqual(resolve_therapeutic_class("", category="Antacid"), "Gastroenterology")
        self.assertEqual(resolve_therapeutic_class("", category="Vitamins"), "Vitamins & Minerals")
        self.assertEqual(resolve_therapeutic_class("Unknownxyzol"), "Other")


class TestPolypharmacy(unittest.TestCase):
    def test_bands(self):
        high = polypharmacy_index(8)
        self.assertEqual(high["level"], "high")
        self.assertIn("High Polypharmacy", high["label"])
        self.assertEqual(polypharmacy_index(6)["level"], "moderate")
        self.assertEqual(polypharmacy_index(3)["level"], "normal")

    def test_breakdown_and_summary(self):
        items = [
            {"brand_name": "Seclo", "therapeutic_class": "Gastroenterology", "is_antibiotic": False},
            {"brand_name": "Zithrin", "therapeutic_class": "Antibiotics", "is_antibiotic": True,
             "broad_spectrum": True},
            {"brand_name": "Napa", "therapeutic_class": "Gastroenterology", "is_antibiotic": False},
        ]
        bd = therapy_breakdown([i["therapeutic_class"] for i in items])
        self.assertEqual(bd[0]["class"], "Gastroenterology")
        self.assertEqual(bd[0]["count"], 2)
        self.assertAlmostEqual(bd[0]["pct"], 66.7)
        summary = clinical_summary(items)
        self.assertEqual(summary["polypharmacy"]["count"], 3)
        self.assertEqual(summary["antibiotics"]["count"], 1)
        self.assertEqual(summary["antibiotics"]["broad_spectrum"], 1)
        self.assertIn("Zithrin", summary["antibiotics"]["brands"])


class TestPriceCeilingAlert(unittest.TestCase):
    def test_price_adjusted_flags(self):
        # Seclo is in the gazette price_adjusted list (8.0 → 7.0 BDT)
        from app.intelligence import dgda_check
        dgda = dgda_check(brand="Seclo", generic="Omeprazole")
        alert = price_ceiling_alert("Seclo", "Omeprazole", dgda=dgda)
        self.assertTrue(alert["flagged"])
        self.assertIn("ceiling", alert["reason"].lower())

    def test_detected_mrp_above_ceiling(self):
        dgda = {"status": "ok", "price": {"mrp": 2.0, "pack": "10 tablets"}}
        alert = price_ceiling_alert("Napa", "Paracetamol", dgda=dgda, detected_mrp=3.5)
        self.assertTrue(alert["flagged"])
        self.assertIn("exceeds", alert["reason"])

    def test_compliant_and_unknown(self):
        ok = price_ceiling_alert("Napa", "Paracetamol",
                                 dgda={"status": "ok", "price": {"mrp": 2.0}},
                                 detected_mrp=2.0)
        self.assertFalse(ok["flagged"])
        unknown = price_ceiling_alert("Mysteryol", "Mystery", dgda={"status": "unknown"})
        self.assertFalse(unknown["flagged"])

    def test_banned_critical(self):
        alert = price_ceiling_alert("Analgin", "Metamizole", dgda={"status": "banned"})
        self.assertTrue(alert["flagged"])
        self.assertEqual(alert["severity"], "critical")


class TestTerritoryGeofence(unittest.TestCase):
    LOCATIONS = {"districts": {
        "Dhaka": {"division": "Dhaka",
                  "territories": ["Dhaka North", "Dhaka South"]},
        "Khulna": {"division": "Khulna",
                   "territories": ["Khulna Regional", "Bagerhat Territory"]},
    }}

    def test_officer_without_territory_never_flags(self):
        r = territory_check("", "Khulna Regional", "Khulna", locations=self.LOCATIONS)
        self.assertFalse(r["off_territory"])

    def test_matching_territory_ok(self):
        r = territory_check("Dhaka South", "Dhaka South", "Dhaka",
                            locations=self.LOCATIONS)
        self.assertFalse(r["off_territory"])
        r2 = territory_check("Dhaka South", "Dhaka South", "", locations=self.LOCATIONS)
        self.assertFalse(r2["off_territory"])

    def test_off_territory_scan_flagged(self):
        r = territory_check("Dhaka South", "Khulna Regional", "Khulna",
                            locations=self.LOCATIONS)
        self.assertTrue(r["off_territory"])
        self.assertIn("Khulna", r["reason"])

    def test_district_matches_implied_territory(self):
        # scan carries no territory but the district implies Dhaka South
        r = territory_check("Dhaka South", "", "Dhaka", locations=self.LOCATIONS)
        self.assertFalse(r["off_territory"])

    def test_gps_resolution(self):
        self.assertEqual(resolve_geo_district(23.8103, 90.4125), "Dhaka")
        self.assertEqual(resolve_geo_district(22.81, 89.55), "Khulna")
        self.assertEqual(resolve_geo_district(None, None), "")
        # far-away coordinates resolve to nothing
        self.assertEqual(resolve_geo_district(51.5, -0.12), "")


class TestDrawerCompliancePayload(unittest.TestCase):
    """The drawer now ships NEML/price/stewardship data per item + clinical block."""

    @classmethod
    def setUpClass(cls):
        client.post("/api/officer-profile", json={
            "employee_id": "MR001", "full_name": "Field Officer", "role": "MPO",
            "company_name": "Healthcare Pharmaceuticals Ltd.",
            "territory": "Dhaka South", "division": "Dhaka",
        })
        app_main.get_current_own_company(force=True)
        # in-territory audit (the mock scan's molecules: Seclo/Napa/Histacin/Zithrin)
        data = _scan(_png_bytes((30, 64, 175), seed=11, style="lines"),
                     mr_id="MR-COMP", district="Dhaka", territory="Dhaka South")
        cls.pid = data["id"]

    def test_items_carry_compliance_fields(self):
        d = client.get(f"/api/prescriptions/{self.pid}").json()
        for item in d["items"]:
            for key in ["neml", "therapeutic_class", "is_antibiotic",
                        "broad_spectrum", "dgda_price_alert"]:
                self.assertIn(key, item)
        seclo = next(m for m in d["items"] if m["brand_name"].lower() == "seclo")
        self.assertTrue(seclo["neml"]["listed"])
        self.assertEqual(seclo["neml"]["molecule"], "Omeprazole")
        self.assertEqual(seclo["therapeutic_class"], "Gastroenterology")
        # Seclo sits on the gazette price_adjusted list → red DGDA flag
        self.assertTrue(seclo["dgda_price_alert"]["flagged"])
        # Zithrin (Azithromycin) → broad-spectrum stewardship tag
        zithrin = next(m for m in d["items"] if m["brand_name"].lower() == "zithrin")
        self.assertTrue(zithrin["is_antibiotic"])
        self.assertTrue(zithrin["broad_spectrum"])

    def test_clinical_block(self):
        d = client.get(f"/api/prescriptions/{self.pid}").json()
        clinical = d["clinical"]
        self.assertEqual(clinical["polypharmacy"]["count"], len(d["items"]))
        self.assertTrue(clinical["therapy_breakdown"])
        total_pct = sum(s["pct"] for s in clinical["therapy_breakdown"])
        self.assertAlmostEqual(total_pct, 100.0, delta=0.2)
        self.assertGreaterEqual(clinical["antibiotics"]["count"], 1)


class TestPitchCardPdf(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = _scan(_png_bytes((109, 40, 217), seed=12, style="waves"),
                     mr_id="MR-PITCH")
        d = client.get(f"/api/prescriptions/{data['id']}").json()
        cls.pid = data["id"]
        cls.seclo_idx = next(i for i, m in enumerate(d["items"])
                             if m["brand_name"].lower() == "seclo"
                             and m.get("portfolio_match"))

    def test_pdf_download(self):
        r = client.get(f"/api/prescriptions/{self.pid}/pitch-card.pdf?idx={self.seclo_idx}")
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.headers["content-type"], "application/pdf")
        self.assertIn("attachment", r.headers.get("content-disposition", ""))
        self.assertTrue(r.content.startswith(b"%PDF-"))
        self.assertGreater(len(r.content), 1500)

    def test_no_match_returns_404(self):
        d = client.get(f"/api/prescriptions/{self.pid}").json()
        own_idx = next((i for i, m in enumerate(d["items"]) if m["is_own"]), None)
        if own_idx is None:
            self.skipTest("no own-brand row in the mock scan")
        r = client.get(f"/api/prescriptions/{self.pid}/pitch-card.pdf?idx={own_idx}")
        self.assertEqual(r.status_code, 404)

    def test_index_out_of_range(self):
        self.assertEqual(client.get(
            "/api/prescriptions/999/pitch-card.pdf?idx=99").status_code, 404)


class TestOffTerritoryFlow(unittest.TestCase):
    """Off-territory scans are stored flagged, listed for the RSM and shown in the drawer."""

    @classmethod
    def setUpClass(cls):
        client.post("/api/officer-profile", json={
            "employee_id": "MR001", "full_name": "Field Officer", "role": "MPO",
            "company_name": "Healthcare Pharmaceuticals Ltd.",
            "territory": "Dhaka South", "division": "Dhaka",
        })
        app_main.get_current_own_company(force=True)

    def _scan_with_location(self, district, territory, mr_id):
        img = open(os.path.join(ROOT, "uploads", "prescriptions",
                                "sample_bd_prescription.jpg"), "rb").read()
        r = client.post("/api/scan",
                        files={"file": ("rx.jpg", img, "image/jpeg")},
                        data={"mr_id": mr_id, "district": district,
                              "territory": territory})
        self.assertEqual(r.status_code, 200)
        return r.json()

    def test_off_territory_scan_flagged_end_to_end(self):
        bad = self._scan_with_location("Khulna", "Khulna Regional", "MR-OFF")
        self.assertTrue(bad["territory_check"]["off_territory"])
        self.assertIn("Khulna", bad["territory_check"]["reason"])

        # visible in the RSM feed
        feed = client.get("/api/rsm/off-territory?days=30").json()
        row = next(x for x in feed if x["id"] == bad["id"])
        self.assertEqual(row["district"], "Khulna")
        self.assertIn("Khulna", row["territory_note"])

        # drawer surfaces the flag
        d = client.get(f"/api/prescriptions/{bad['id']}").json()
        self.assertTrue(d["prescription"]["off_territory"])
        self.assertIn("Khulna", d["prescription"]["territory_note"])

        # list endpoint carries the column for the card tag
        rows = client.get("/api/prescriptions?limit=50").json()["prescriptions"]
        listed = next(x for x in rows if x["id"] == bad["id"])
        self.assertEqual(listed["off_territory"], 1)

    def test_on_territory_scan_not_flagged(self):
        good = self._scan_with_location("Dhaka", "Dhaka South", "MR-OFF2")
        self.assertFalse(good["territory_check"]["off_territory"])
        d = client.get(f"/api/prescriptions/{good['id']}").json()
        self.assertFalse(d["prescription"]["off_territory"])

    def test_scan_points_payload(self):
        pts = client.get("/api/rsm/scan-points?days=30").json()
        self.assertIsInstance(pts, list)
        self.assertTrue(pts, "expected district-centroid fallback points")
        for p in pts:
            self.assertIn("lat", p)
            self.assertIn("lng", p)
            self.assertIn("off_territory", p)
            self.assertIn("items", p)


class TestUpgradeTemplate(unittest.TestCase):
    """Drawer pills, clinical strip, pitch modal, geofence cards + cluster toggle."""

    def test_neml_and_price_pills(self):
        self.assertIn("NEML Listed", TPL)
        self.assertIn("DGDA Price Alert", TPL)
        self.assertIn("rx-pitch-btn", TPL)
        self.assertIn("Generate Doctor Pitch Card", TPL)

    def test_clinical_strip(self):
        for eid in ["rxDrawerClinical", "rxPolyBadge", "rxAbxBadge",
                    "rxTherapyBar", "rxTherapyLegend", "rxOffTerritoryBadge"]:
            self.assertIn(f'id="{eid}"', TPL)
        self.assertIn("Antibiotic Stewardship", TPL)
        self.assertIn("poly.level==='high'", TPL)  # ⚠️ High Polypharmacy badge
        self.assertIn("Therapeutic class breakdown", TPL)

    def test_pitch_modal(self):
        for eid in ["rxPitchModal", "rxPitchBody", "rxPitchPdfBtn",
                    "rxPitchCopyBtn", "openRxPitchCard"]:
            self.assertIn(eid, TPL)

    def test_geofence_ui(self):
        self.assertIn("Off-Territory Audit Verification", TPL)
        self.assertIn('id="otBody"', TPL)
        self.assertIn('id="geoPinBtn"', TPL)
        self.assertIn("loadOffTerritory", TPL)
        self.assertIn("Off-Territory Audit", TPL)

    def test_cluster_toggle(self):
        self.assertIn('id="hmViewClusters"', TPL)
        self.assertIn("loadRsmClusters", TPL)
        self.assertIn("scan-points", TPL)


if __name__ == "__main__":
    unittest.main()
