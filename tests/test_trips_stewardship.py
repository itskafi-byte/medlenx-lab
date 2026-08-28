"""
TRIPS Waiver Portfolio Tracker (PMD), Antibiotic Stewardship Monitor
(per doctor chamber), and pitch-card bioequivalence/dosage evidence.

Run:  .venv/bin/python -m pytest tests/test_trips_stewardship.py -v
"""
import os
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

_TMPDIR = tempfile.mkdtemp(prefix="medlenx-trips-")
os.environ["MEDLENX_TEST_DB"] = os.path.join(_TMPDIR, "test.db")

from app import database as db  # noqa: E402

db.DB_PATH = os.environ["MEDLENX_TEST_DB"]

from fastapi.testclient import TestClient  # noqa: E402
from app import main as app_main  # noqa: E402
from app.compliance import (  # noqa: E402
    trips_lookup, trips_expiry, load_trips, substitution_evidence_notes,
    is_antibiotic, is_broad_spectrum,
)

importlib = __import__("importlib")
importlib.reload(app_main)
app_main.init_db()
client = TestClient(app_main.app)

TPL = open(os.path.join(ROOT, "templates", "index.html"), encoding="utf-8").read()


class TestTripsDataset(unittest.TestCase):
    """data/trips_waiver.json: LDC pharma waiver context + watch molecules."""

    def setUp(self):
        self.data = load_trips()

    def test_dataset_shape(self):
        mols = self.data["molecules"]
        self.assertGreaterEqual(len(mols), 20)
        for m in mols:
            for key in ["molecule", "class", "originator", "watch_level", "note"]:
                self.assertTrue(m.get(key), m)
        self.assertIn(self.data["waiver_expiry"], ("2033-01-01", "2033"))

    def test_watch_levels_present(self):
        levels = {m["watch_level"] for m in self.data["molecules"]}
        self.assertIn("critical", levels)
        self.assertIn("high", levels)

    def test_expiry_accessor(self):
        self.assertIn("2033", trips_expiry())


class TestTripsLookup(unittest.TestCase):
    def test_watch_molecules_hit(self):
        for mol in ["Dapagliflozin", "Empagliflozin", "Rivaroxaban",
                    "Adalimumab", "Insulin Glargine"]:
            hit = trips_lookup(mol)
            self.assertTrue(hit["watch"], mol)
            self.assertTrue(hit["originator"], mol)

    def test_ingredient_blob_hits(self):
        hit = trips_lookup("Dapagliflozin 10 mg")
        self.assertTrue(hit["watch"])
        self.assertEqual(hit["molecule"], "Dapagliflozin")

    def test_non_watch_molecules_miss(self):
        for mol in ["Paracetamol", "Omeprazole", "Cetirizine", ""]:
            self.assertFalse(trips_lookup(mol)["watch"], mol)


class TestSubstitutionEvidenceNotes(unittest.TestCase):
    """Pitch-card bioequivalence + dosage-advantage lines (factual only)."""

    def test_identical_strength_and_form(self):
        notes = substitution_evidence_notes({
            "generic": "Omeprazole",
            "competitor": {"brand": "Seclo", "strength": "20 mg", "type": "EC Capsule"},
            "own_brand": {"brand": "Opal", "strength": "20 mg", "type": "EC Capsule"},
        })
        self.assertIn("same molecule", notes["bioequiv"])
        self.assertIn("Omeprazole", notes["bioequiv"])
        self.assertIn("Identical strength (20 mg)", notes["dosage_advantage"])
        self.assertIn("no dose titration", notes["dosage_advantage"])

    def test_differing_strengths_advise_titration(self):
        notes = substitution_evidence_notes({
            "generic": "Metformin",
            "competitor": {"strength": "500 mg", "type": "Tablet"},
            "own_brand": {"strength": "850 mg", "type": "Tablet"},
        })
        self.assertIn("differs", notes["dosage_advantage"])
        self.assertIn("titrate", notes["dosage_advantage"])

    def test_missing_strength_falls_back(self):
        notes = substitution_evidence_notes({
            "generic": "Napa", "competitor": {}, "own_brand": {}})
        self.assertIn("Confirm pack-strength", notes["dosage_advantage"])


class TestTripsPortfolioEndpoint(unittest.TestCase):
    """/api/trips/portfolio aggregates field volume per watch molecule."""

    @classmethod
    def setUpClass(cls):
        # pin own company for deterministic substitution behaviour
        client.post("/api/officer-profile", json={
            "employee_id": "MR001", "full_name": "Field Officer", "role": "MPO",
            "company_name": "Healthcare Pharmaceuticals Ltd.",
            "territory": "Dhaka South", "division": "Dhaka",
        })
        app_main.get_current_own_company(force=True)
        # seed scans in two territories containing a watch molecule
        # (mock scan includes Zithrin → Azithromycin? no — Azithromycin is not
        # watch-listed; instead inject a watch molecule directly via DB)
        img = open(os.path.join(ROOT, "uploads", "prescriptions",
                                "sample_bd_prescription.jpg"), "rb").read()
        for mr, terr, dist in [("MR001", "Dhaka South", "Dhaka"),
                               ("MR007", "Khulna Regional", "Khulna")]:
            r = client.post("/api/scan",
                            files={"file": ("rx.png", img, "image/png")},
                            data={"mr_id": mr, "territory": terr,
                                  "district": dist})
            assert r.status_code == 200

    def _inject(self, doctor, generic, brand, territory, district=""):
        """Directly save a prescription carrying a watch molecule."""
        import json
        conn = db.get_db()
        cur = conn.cursor()
        doctor_id = db.get_or_create_doctor(name=doctor, district=district,
                                            territory=territory)
        medicines = [{"brand_name": brand, "generic_name": generic,
                      "company": "Healthcare Pharmaceuticals Ltd.",
                      "type": "Tablet", "strength": "10 mg",
                      "confidence": 0.93, "line_number": 1,
                      "dosage_normalized": "1+0+1"}]
        pid = db.save_prescription(
            f"uploads/prescriptions/trips_{abs(hash((doctor, brand))) % 99999}.jpg",
            {"doctor": {"name": doctor}, "medicines": medicines,
             "meta": {"avg_confidence": 0.93, "total_medicines": 1}},
            mr_id="MR001", territory=territory, district=district)
        conn.close()
        return pid

    def test_portfolio_counts_watch_molecules(self):
        self._inject("Dr. TRIPS Test", "Dapagliflozin", "Forxiga",
                     "Dhaka South", "Dhaka")
        self._inject("Dr. TRIPS Test", "Dapagliflozin", "Forxiga",
                     "Dhaka South", "Dhaka")
        self._inject("Dr. TRIPS Two", "Rivaroxaban", "Xarelto",
                     "Khulna Regional", "Khulna")
        d = client.get("/api/trips/portfolio?days=90").json()
        self.assertIn("waiver_expiry", d)
        by_mol = {m["molecule"]: m for m in d["molecules"]}
        # every dataset molecule appears (full watch list for PMD)
        self.assertGreaterEqual(len(by_mol), 20)
        dap = by_mol["Dapagliflozin"]
        self.assertGreaterEqual(dap["current_volume"], 2)
        self.assertEqual(dap["watch_level"], "critical")
        names = [t["name"] for t in dap["top_territories"]]
        self.assertIn("Dhaka South", names)
        riv = by_mol["Rivaroxaban"]
        self.assertGreaterEqual(riv["current_volume"], 1)
        self.assertIn("Khulna Regional", [t["name"] for t in riv["top_territories"]])
        # totals sanity
        self.assertEqual(d["totals"]["with_field_volume"],
                         sum(1 for m in d["molecules"] if m["current_volume"]))

    def test_no_volume_molecules_still_listed(self):
        d = client.get("/api/trips/portfolio?days=90").json()
        mol = next(m for m in d["molecules"]
                   if m["molecule"] == "Adalimumab")
        self.assertEqual(mol["current_volume"], 0)
        self.assertEqual(mol["watch_level"], "critical")

    def test_drawer_items_carry_trips_field(self):
        d = client.get("/api/prescriptions/1").json()
        for item in d["items"]:
            self.assertIn("trips", item)
            self.assertIn("watch", item["trips"])


class TestStewardshipMonitor(unittest.TestCase):
    """Per-chamber ABX aggregation + endpoint."""

    @classmethod
    def setUpClass(cls):
        cls._inject = TestTripsPortfolioEndpoint._inject

    def test_summary_shape_and_counts(self):
        # Dr. TRIPS Test's chamber has Dapagliflozin (not ABX); inject an ABX one
        self._inject("Dr. Steward Test", "Azithromycin", "Zithrin",
                     "Dhaka South", "Dhaka")
        self._inject("Dr. Steward Test", "Cefixime", "Cef-3",
                     "Dhaka South", "Dhaka")
        d = client.get("/api/rsm/stewardship?days=30").json()
        t = d["totals"]
        for key in ["doctors_audited", "doctors_with_abx", "items",
                    "abx_items", "broad_items", "abx_share_pct"]:
            self.assertIn(key, t)
        self.assertGreaterEqual(t["broad_items"], 2)  # both are broad-spectrum
        row = next(x for x in d["doctors"] if x["doctor_name"] == "Dr. Steward Test")
        self.assertGreaterEqual(row["abx_items"], 2)
        self.assertGreaterEqual(row["broad_items"], 2)
        self.assertGreater(row["abx_share_pct"], 0)
        self.assertIn("Zithrin", " ".join(row["abx_brands"]))
        # ordering: chambers with ABX first
        self.assertEqual(d["doctors"][0]["abx_items"],
                         max(x["abx_items"] for x in d["doctors"]))

    def test_non_abx_chamber_zero_share(self):
        self._inject("Dr. Clean Test", "Omeprazole", "Opal",
                     "Dhaka South", "Dhaka")
        d = client.get("/api/rsm/stewardship?days=30").json()
        row = next(x for x in d["doctors"]
                   if x["doctor_name"] == "Dr. Clean Test")
        self.assertEqual(row["abx_items"], 0)
        self.assertEqual(row["abx_share_pct"], 0.0)


class TestUpgradeTemplates(unittest.TestCase):
    def test_trips_hub_card(self):
        self.assertIn("TRIPS Waiver Portfolio Tracker", TPL)
        for eid in ["tripsBody", "tripsTotals", "tripsWindow", "tripsDays",
                    "loadTripsPortfolio"]:
            self.assertIn(eid, TPL)
        self.assertIn("api/trips/portfolio", TPL)
        self.assertIn("TRIPS Watch", TPL)  # drawer pill

    def test_stewardship_card(self):
        self.assertIn("Antibiotic Stewardship Monitor", TPL)
        for eid in ["abxBody", "abxTotals", "abxShareChip",
                    "loadStewardship"]:
            self.assertIn(eid, TPL)
        self.assertIn("api/rsm/stewardship", TPL)

    def test_pitch_evidence_in_modal_and_pdf_path(self):
        self.assertIn("Bioequivalence & dosage evidence", TPL)
        self.assertIn("_evidence", TPL)
        app_src = open(os.path.join(ROOT, "app", "main.py"), encoding="utf-8").read()
        self.assertIn("Bioequivalence & dosage", app_src)
        self.assertIn("substitution_evidence_notes", app_src)


if __name__ == "__main__":
    unittest.main()
