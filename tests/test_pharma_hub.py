"""
Pharma Intelligence Hub, enterprise profile, RSM, Android file-picker, UI upgrades.
"""
import importlib
import io
import os
import re
import sqlite3
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

_TMPDIR = tempfile.mkdtemp(prefix="medlenx-hub-")
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


def do_scan(**form):
    return client.post(
        "/api/scan",
        files={"file": ("rx.jpg", _png_bytes(), "image/jpeg")},
        data=form or None,
    ).json()


class TestAndroidFilePicker(unittest.TestCase):
    def test_choose_file_input_does_not_force_camera(self):
        m = re.search(r'<input id="fileInput"[^>]*>', TPL)
        self.assertIsNotNone(m)
        tag = m.group(0)
        self.assertNotIn("capture=", tag)
        self.assertNotIn("capture=\"environment\"", tag)

    def test_dedicated_camera_input_exists(self):
        m = re.search(r'<input id="cameraFileInput"[^>]*>', TPL)
        self.assertIsNotNone(m)
        self.assertIn("capture", m.group(0))

    def test_choose_file_button_stops_propagation(self):
        self.assertIn('id="chooseFileBtn"', TPL)
        self.assertIn("e.stopPropagation()", TPL)
        self.assertIn("chooseFileBtn", TPL)

    def test_upload_zone_ignores_button_clicks(self):
        self.assertIn("if(e.target.closest('button, input, video, a')) return", TPL)


class TestNavRename(unittest.TestCase):
    def test_hub_label(self):
        self.assertIn("Pharma Intelligence Hub", TPL)
        self.assertNotIn("> Medicine Database<", TPL.replace("  ", " "))

    def test_hub_subtabs(self):
        for token in ["data-hub-tab=\"index\"", "data-hub-tab=\"news\"",
                      "data-hub-tab=\"jobs\"", "data-hub-tab=\"healthdays\"",
                      "25K+ Drug Index", "Industry News", "Job Board", "Health Days"]:
            self.assertIn(token, TPL, token)

    def test_settings_help_rsm_tabs(self):
        for token in ['data-tab="settings"', 'data-tab="help"', 'data-tab="rsm"',
                      "Officer Profile", "Interactive Scan Guide",
                      "Error Escalation", "BMDC", "RSM Command"]:
            self.assertIn(token, TPL, token)

    def test_company_badge_and_latency(self):
        self.assertIn("companyBadge", TPL)
        self.assertIn("apiLatency", TPL)
        self.assertIn("function pingLatency", TPL)
        self.assertIn("function renderCompanyBadge", TPL)


class TestHubApis(unittest.TestCase):
    def test_news(self):
        d = client.get("/api/pharma/news?live=0").json()
        self.assertIn("items", d)
        self.assertGreater(len(d["items"]), 3)
        sources = {i["source"] for i in d["items"]}
        self.assertTrue({"DGDA", "Medex"} & sources)

    def test_jobs_filter(self):
        allj = client.get("/api/pharma/jobs").json()
        self.assertGreaterEqual(allj["total"], 10)
        mpo = client.get("/api/pharma/jobs?category=MPO").json()
        self.assertTrue(all(j["category"] == "MPO" for j in mpo["jobs"]))
        self.assertIn("MPO", allj["categories"])

    def test_health_days(self):
        d = client.get("/api/pharma/health-days").json()
        self.assertGreaterEqual(d["count"], 20)
        names = [x["name"] for x in d["days"]]
        self.assertTrue(any("Diabetes" in n for n in names))
        self.assertTrue(any("Heart" in n for n in names))
        self.assertIn("next", d)
        self.assertTrue(all("mpo_tip" in x for x in d["days"]))

    def test_companies_search(self):
        d = client.get("/api/companies?q=square&limit=8").json()
        self.assertIn("companies", d)
        self.assertTrue(any("square" in c["name"].lower() for c in d["companies"]))


class TestOfficerProfile(unittest.TestCase):
    def test_default_profile_seeded(self):
        p = client.get("/api/officer-profile").json()
        self.assertEqual(p["employee_id"], "MR001")
        self.assertTrue(p["company_name"])
        self.assertIn("targets", p)

    def test_save_company_and_targets(self):
        r = client.post("/api/officer-profile", json={
            "employee_id": "MR001",
            "full_name": "Ayesha Rahman",
            "role": "MPO",
            "company_name": "Beximco Pharmaceuticals Ltd.",
            "territory": "Dhaka South",
            "zone": "Dhaka South",
            "division": "Dhaka",
            "portfolio": "Cardiology, Gastroenterology",
            "targets": [{"brand_name": "Napa", "monthly_target": 50}],
        }).json()
        self.assertTrue(r["success"])
        self.assertEqual(r["profile"]["company_name"], "Beximco Pharmaceuticals Ltd.")
        self.assertEqual(r["profile"]["full_name"], "Ayesha Rahman")
        self.assertTrue(any(t["brand_name"] == "Napa" for t in r["profile"]["targets"]))

        con = sqlite3.connect(db.DB_PATH)
        own = con.execute(
            "SELECT name FROM pharma_companies WHERE is_own_company=1"
        ).fetchone()[0]
        con.close()
        self.assertIn("Beximco", own)

        prog = client.get("/api/officer-targets").json()
        self.assertEqual(prog["employee_id"], "MR001")
        self.assertTrue(any(b["brand_name"] == "Napa" for b in prog["brands"]))

    def test_target_progress_increments_on_scan(self):
        client.post("/api/officer-profile", json={
            "employee_id": "MR001",
            "company_name": "Square Pharmaceuticals Ltd.",
            "targets": [{"brand_name": "Seclo", "monthly_target": 10}],
        })
        before = {b["brand_name"]: b["captured"]
                  for b in client.get("/api/officer-targets").json()["brands"]}
        do_scan(mr_id="MR001")
        after = {b["brand_name"]: b["captured"]
                 for b in client.get("/api/officer-targets").json()["brands"]}
        self.assertGreaterEqual(after.get("Seclo", 0), before.get("Seclo", 0) + 1)


class TestErrorEscalation(unittest.TestCase):
    def test_queue_report(self):
        r = client.post("/api/error-reports", json={
            "brand_name": "Napa",
            "reported_text": "Nepaa 500",
            "correction": "Napa 500 mg — Beximco",
            "notes": "cursive n looked like m",
        }).json()
        self.assertTrue(r["success"])
        self.assertIn("training", r["message"].lower())
        listed = client.get("/api/error-reports").json()["reports"]
        self.assertTrue(any(x["brand_name"] == "Napa" for x in listed))


class TestRsmAndSov(unittest.TestCase):
    def test_rsm_team_size(self):
        d = client.get("/api/rsm/dashboard").json()
        self.assertGreaterEqual(d["team_size"], 50)
        self.assertIn("totals", d)
        self.assertIn("sov_percent", d["totals"])
        self.assertEqual(len(d["members"]), d["team_size"])

    def test_own_vs_competitor_shape(self):
        do_scan(mr_id="MR001")
        d = client.get("/api/dashboard/own-vs-competitor").json()
        self.assertIn("own_company", d)
        self.assertIn("doctors", d)
        if d["doctors"]:
            row = d["doctors"][0]
            self.assertIn("own_share", row)
            self.assertIn("competitor_share", row)
            self.assertAlmostEqual(row["own"] + row["competitor"], row["total"])


class TestVerificationConfidence(unittest.TestCase):
    def test_badge_thresholds_in_ui(self):
        self.assertIn("pct>85", TPL.replace(" ", ""))
        self.assertIn("pct<70", TPL.replace(" ", ""))
        self.assertIn("manual flag", TPL)
        self.assertIn("report-misid", TPL)

    def test_prescription_source_select(self):
        self.assertIn("verifySource", TPL)
        self.assertIn("Private Chamber", TPL)
        self.assertIn("rs-tag", TPL)

    def test_scan_infers_hospital_source(self):
        s = do_scan()
        # mock doctor has a hospital name containing 'Hospital'
        con = sqlite3.connect(db.DB_PATH)
        row = con.execute(
            "SELECT prescription_source FROM prescriptions WHERE id=?", (s["id"],)
        ).fetchone()
        con.close()
        self.assertEqual(row[0], "Hospital")


class TestOfflineAndPing(unittest.TestCase):
    def test_ping(self):
        r = client.get("/api/ping").json()
        self.assertTrue(r["ok"])

    def test_offline_sync(self):
        r = client.post("/api/offline/sync").json()
        self.assertTrue(r["success"])

    def test_health_still_ok(self):
        r = client.get("/api/health").json()
        self.assertEqual(r["status"], "ok")


class TestLightModeUntouched(unittest.TestCase):
    def test_still_no_dark_utilities(self):
        self.assertEqual(len(re.findall(r"\bdark:", TPL)), 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
