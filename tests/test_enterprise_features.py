"""
Enterprise feature + regression tests.

Covers the four reported issues and the new dashboard/API surface.
Uses FastAPI's TestClient against a temporary database, so it is safe to run
repeatedly and does not need a live server.

Run:  .venv/bin/python -m pytest tests/test_enterprise_features.py -v
"""
import importlib
import io
import json
import os
import re
import sqlite3
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

_TMPDIR = tempfile.mkdtemp(prefix="medlenx-test-")
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


# ======================================================================
# ISSUE 1 - Specialty / District / Upazila / Territory
# ======================================================================
class TestIssue1LocationSync(unittest.TestCase):
    def test_locations_endpoint_has_full_cascade(self):
        d = client.get("/api/locations").json()
        self.assertEqual(len(d["districts"]), 64, "Bangladesh has 64 districts")
        self.assertGreater(len(d["specialties"]), 20)
        total_upazilas = sum(len(v["upazilas"]) for v in d["districts"].values())
        self.assertGreater(total_upazilas, 450)

    def test_every_district_has_upazilas_and_territories(self):
        d = client.get("/api/locations").json()
        for name, entry in d["districts"].items():
            self.assertTrue(entry["upazilas"], f"{name} has no upazilas")
            self.assertTrue(entry["territories"], f"{name} has no territories")
            self.assertTrue(entry["division"], f"{name} has no division")

    def test_resolve_location_rejects_mismatched_upazila(self):
        """An upazila that does not belong to the district must be dropped."""
        out = app_main.resolve_location(district="Dhaka", upazila="Teknaf")
        self.assertEqual(out["district"], "Dhaka")
        self.assertEqual(out["upazila"], "", "Teknaf is in Cox's Bazar, not Dhaka")

    def test_resolve_location_accepts_valid_pair(self):
        out = app_main.resolve_location(district="Dhaka", upazila="Savar",
                                        territory="Dhaka North")
        self.assertEqual((out["district"], out["upazila"], out["territory"]),
                         ("Dhaka", "Savar", "Dhaka North"))
        self.assertEqual(out["division"], "Dhaka")

    def test_resolve_location_is_case_insensitive(self):
        out = app_main.resolve_location(district="dhaka", upazila="savar")
        self.assertEqual(out["district"], "Dhaka")
        self.assertEqual(out["upazila"], "Savar")

    def test_resolve_location_defaults_territory(self):
        out = app_main.resolve_location(district="Bagerhat")
        self.assertTrue(out["territory"], "a territory should be auto-selected")

    def test_verify_persists_specialty_and_location(self):
        s = do_scan()
        pid = s["id"]
        payload = {
            "doctor": {
                "name": "Dr. Location Sync", "bmdc_no": "A-11111",
                "specialty": "Neurology", "chamber": "C1",
                "qualifications": "MBBS", "hospital": "H1",
                "district": "Chattogram", "upazila": "Patiya",
                "territory": "Chattogram North",
            },
            "medicines": s["medicines"],
        }
        r = client.post(f"/api/prescriptions/{pid}/verify", json=payload).json()
        self.assertTrue(r["success"])

        con = sqlite3.connect(db.DB_PATH)
        con.row_factory = sqlite3.Row
        row = con.execute(
            "SELECT doctor_specialty, district, upazila, territory, is_verified "
            "FROM prescriptions WHERE id=?", (pid,)).fetchone()
        self.assertEqual(row["doctor_specialty"], "Neurology")
        self.assertEqual(row["district"], "Chattogram")
        self.assertEqual(row["upazila"], "Patiya")
        self.assertEqual(row["territory"], "Chattogram North")
        self.assertEqual(row["is_verified"], 1)

        # the doctors master record must be synced too - it drives analytics
        doc = con.execute(
            "SELECT specialty, district, upazila, territory FROM doctors "
            "WHERE bmdc_no='A-11111'").fetchone()
        self.assertIsNotNone(doc, "doctor record not created")
        self.assertEqual(doc["specialty"], "Neurology")
        self.assertEqual(doc["district"], "Chattogram")
        self.assertEqual(doc["upazila"], "Patiya")
        con.close()

    def test_reverify_updates_doctor_without_inflating_count(self):
        s = do_scan()
        pid = s["id"]
        base = {"name": "Dr. Recount", "bmdc_no": "A-22222",
                "specialty": "Medicine", "district": "Dhaka", "upazila": "Savar",
                "territory": "Dhaka North", "qualifications": "", "chamber": "",
                "hospital": ""}
        client.post(f"/api/prescriptions/{pid}/verify",
                    json={"doctor": base, "medicines": s["medicines"]})
        con = sqlite3.connect(db.DB_PATH)
        con.row_factory = sqlite3.Row
        first = con.execute(
            "SELECT prescription_count FROM doctors WHERE bmdc_no='A-22222'"
        ).fetchone()["prescription_count"]

        upd = {**base, "specialty": "Cardiology"}
        client.post(f"/api/prescriptions/{pid}/verify",
                    json={"doctor": upd, "medicines": s["medicines"]})
        row = con.execute(
            "SELECT specialty, prescription_count FROM doctors "
            "WHERE bmdc_no='A-22222'").fetchone()
        self.assertEqual(row["specialty"], "Cardiology", "correction must stick")
        self.assertEqual(row["prescription_count"], first,
                         "re-verify must not inflate prescription_count")
        con.close()

    def test_form_uses_bound_selects_not_free_text(self):
        self.assertIn("handleLocationChange", TPL)
        self.assertIn("syncLocationSelects", TPL)
        self.assertRegex(TPL, r'selectRow\(\s*[\'"]District[\'"]')
        self.assertRegex(TPL, r'selectRow\(\s*[\'"]Specialty[\'"]')

    def test_changing_district_clears_dependents(self):
        """The cascade must invalidate upazila when district changes."""
        m = re.search(r"function handleLocationChange[\s\S]{0,400}?\n\}", TPL)
        self.assertIsNotNone(m)
        self.assertIn("formData.location.upazila=''", m.group(0).replace(" ", ""))


# ======================================================================
# ISSUE 2 - Save feedback
# ======================================================================
class TestIssue2SaveFeedback(unittest.TestCase):
    def test_verify_returns_message(self):
        s = do_scan()
        r = client.post(f"/api/prescriptions/{s['id']}/verify",
                        json={"doctor": {"name": "Dr. Msg"},
                              "medicines": s["medicines"]}).json()
        self.assertIn("message", r)
        self.assertTrue(r["message"])
        self.assertEqual(r["medicines_saved"], len(s["medicines"]))

    def test_verify_unknown_id_returns_404(self):
        r = client.post("/api/prescriptions/999999/verify",
                        json={"doctor": {"name": "x"}, "medicines": []})
        self.assertEqual(r.status_code, 404)

    def test_toast_system_present(self):
        for token in ["function toast", "toast.loading", "toast.success",
                      "toast.error", "toastRoot"]:
            self.assertIn(token, TPL, f"missing {token}")

    def test_no_blocking_alerts_on_save_path(self):
        m = re.search(r"async function verifyAndSave\(\)[\s\S]+?\n\}", TPL)
        self.assertIsNotNone(m)
        self.assertNotIn("alert(", m.group(0))

    def test_button_disabled_and_spinner_during_save(self):
        m = re.search(r"async function verifyAndSave\(\)[\s\S]+?\n\}", TPL).group(0)
        self.assertIn("btn.disabled=true", m.replace(" ", ""))
        self.assertIn("fa-circle-notch fa-spin", m)
        self.assertIn("isSaving", m)

    def test_workspace_resets_and_refreshes(self):
        m = re.search(r"async function verifyAndSave\(\)[\s\S]+?\n\}", TPL).group(0)
        self.assertIn("resetWorkspaceState()", m)
        self.assertIn("refreshDashboard()", m)

    def test_double_submit_guard(self):
        m = re.search(r"async function verifyAndSave\(\)[\s\S]+?\n\}", TPL).group(0)
        self.assertRegex(m.replace(" ", ""), r"if\(isSaving\)return")


# ======================================================================
# ISSUE 3 - Itemized recent scans
# ======================================================================
class TestIssue3RecentScans(unittest.TestCase):
    def test_table_created(self):
        con = sqlite3.connect(db.DB_PATH)
        names = {r[0] for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertIn("recent_scanned_medicines", names)
        cols = {r[1] for r in con.execute(
            "PRAGMA table_info(recent_scanned_medicines)")}
        for c in ["prescription_id", "mr_id", "brand_name", "generic_name",
                  "company_name", "dosage_form", "confidence_score", "created_at"]:
            self.assertIn(c, cols)
        con.close()

    def test_scan_writes_itemized_rows(self):
        before = client.get("/api/recent-medicines?limit=1").json()["total"]
        s = do_scan(mr_id="MR-ITEM")
        after = client.get("/api/recent-medicines?limit=1").json()["total"]
        self.assertEqual(after - before, len(s["medicines"]),
                         "each detected medicine must create one feed row")

    def test_items_carry_mr_id(self):
        do_scan(mr_id="MR-XYZ")
        d = client.get("/api/recent-medicines?mr_id=MR-XYZ&limit=50").json()
        self.assertGreater(d["total"], 0)
        self.assertTrue(all(i["mr_id"] == "MR-XYZ" for i in d["items"]))

    def test_search_filter(self):
        do_scan()
        d = client.get("/api/recent-medicines?q=Napa&limit=50").json()
        self.assertGreater(d["total"], 0)
        self.assertTrue(all("napa" in (i["brand_name"] or "").lower()
                            or "napa" in (i["generic_name"] or "").lower()
                            or "napa" in (i["company_name"] or "").lower()
                            or "napa" in (i["doctor_name"] or "").lower()
                            for i in d["items"]))

    def test_pagination_is_stable(self):
        for _ in range(3):
            do_scan()
        p1 = client.get("/api/recent-medicines?limit=5&offset=0").json()
        p2 = client.get("/api/recent-medicines?limit=5&offset=5").json()
        self.assertEqual(p1["total"], p2["total"])
        ids1 = {i["id"] for i in p1["items"]}
        ids2 = {i["id"] for i in p2["items"]}
        self.assertFalse(ids1 & ids2, "pages must not overlap")

    def test_sorting(self):
        do_scan()
        asc = client.get("/api/recent-medicines?order=brand_name&direction=asc&limit=20").json()
        names = [i["brand_name"] for i in asc["items"]]
        self.assertEqual(names, sorted(names))

    def test_reverify_does_not_duplicate_feed_rows(self):
        s = do_scan()
        pid = s["id"]
        payload = {"doctor": {"name": "Dr. Dup", "bmdc_no": "A-33333"},
                   "medicines": s["medicines"]}
        client.post(f"/api/prescriptions/{pid}/verify", json=payload)
        con = sqlite3.connect(db.DB_PATH)
        n1 = con.execute("SELECT COUNT(*) FROM recent_scanned_medicines "
                         "WHERE prescription_id=?", (pid,)).fetchone()[0]
        client.post(f"/api/prescriptions/{pid}/verify", json=payload)
        n2 = con.execute("SELECT COUNT(*) FROM recent_scanned_medicines "
                         "WHERE prescription_id=?", (pid,)).fetchone()[0]
        self.assertEqual(n1, n2, "re-verify must replace, not duplicate")
        con.close()

    def test_csv_export(self):
        do_scan()
        r = client.get("/api/export/recent-medicines.csv")
        self.assertEqual(r.status_code, 200)
        self.assertIn("text/csv", r.headers["content-type"])
        head = r.text.splitlines()[0]
        for col in ["brand_name", "company_name", "mr_id", "created_at"]:
            self.assertIn(col, head)

    def test_ui_grid_present(self):
        for token in ["rsBody", "loadRecentMedicines", "rsSearch", "rsPager",
                      "Live Recent Scans"]:
            self.assertIn(token, TPL, f"missing {token}")


# ======================================================================
# ISSUE 4 - Chart colours
# ======================================================================
class TestIssue4ChartColors(unittest.TestCase):
    def test_palette_and_mapper_exist(self):
        self.assertIn("COMPANY_COLOR_PALETTE", TPL)
        self.assertIn("function getCompanyColor", TPL)
        self.assertIn("function companyColorScale", TPL)

    def test_known_companies_have_distinct_brand_colors(self):
        block = re.search(r"const COMPANY_COLOR_PALETTE=\{[\s\S]+?\};", TPL).group(0)
        hexes = re.findall(r"#([0-9A-Fa-f]{6})", block)
        self.assertGreaterEqual(len(hexes), 10)
        self.assertEqual(len(hexes), len(set(h.lower() for h in hexes)),
                         "palette colours must be unique")

    def test_no_grey_fallback_for_all_competitors(self):
        self.assertNotIn("'#e5e7eb'", TPL,
                         "the old single-grey competitor fallback must be gone")

    def test_no_bright_violet(self):
        self.assertNotIn("#8b5cf6", TPL)

    def test_donut_uses_color_scale(self):
        m = re.search(r"chartCompanyShare=new Chart\([\s\S]+?\}\);", TPL).group(0)
        self.assertIn("backgroundColor:colors", m.replace(" ", ""))
        self.assertIn("companyColorScale", TPL)


# ======================================================================
# Design system - light only
# ======================================================================
class TestLightOnlyDesign(unittest.TestCase):
    def test_no_dark_utilities(self):
        self.assertEqual(len(re.findall(r"\bdark:", TPL)), 0)

    def test_no_dark_css_rules(self):
        self.assertEqual(len(re.findall(r"^\.dark ", TPL, flags=re.M)), 0)

    def test_no_theme_toggle(self):
        self.assertNotIn("themeToggle", TPL)
        self.assertNotIn("applyTheme", TPL)

    def test_html_locked_to_light(self):
        self.assertIn('<html lang="en" class="light">', TPL)

    def test_enterprise_palette_tokens(self):
        for color in ["#0F172A", "#2563EB", "#059669", "#D97706"]:
            self.assertIn(color, TPL, f"missing palette colour {color}")


# ======================================================================
# Dashboard: filters, drill-down, leaderboard
# ======================================================================
class TestDashboard(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        s = do_scan(mr_id="MR-DASH", district="Dhaka",
                    upazila="Savar", territory="Dhaka North")
        client.post(f"/api/prescriptions/{s['id']}/verify", json={
            "doctor": {"name": "Dr. Dash", "bmdc_no": "A-44444",
                       "specialty": "Cardiology", "district": "Dhaka",
                       "upazila": "Savar", "territory": "Dhaka North"},
            "medicines": s["medicines"]})
        cls.scan = s

    def test_filters_endpoint(self):
        d = client.get("/api/filters").json()
        for key in ["districts", "territories", "specialties", "companies", "mr_ids"]:
            self.assertIn(key, d)
        self.assertIn("Dhaka", d["districts"])
        self.assertIn("Cardiology", d["specialties"])

    def test_kpis_have_new_fields(self):
        d = client.get("/api/dashboard/kpis").json()
        self.assertIn("identified_items", d)
        self.assertIn("delta_percent", d["total_prescriptions"])
        self.assertIn("delta_percent", d["market_share"])

    def test_kpis_respect_district_filter(self):
        match = client.get("/api/dashboard/kpis?district=Dhaka").json()
        nomatch = client.get("/api/dashboard/kpis?district=Bandarban").json()
        self.assertGreater(match["total_prescriptions"]["all"],
                           nomatch["total_prescriptions"]["all"])

    def test_company_share_respects_filter(self):
        allc = client.get("/api/dashboard/company-share").json()["companies"]
        none = client.get("/api/dashboard/company-share?district=Bandarban").json()["companies"]
        self.assertTrue(allc)
        self.assertEqual(none, [])

    def test_company_share_percentages_sum_to_100(self):
        cs = client.get("/api/dashboard/company-share").json()["companies"]
        if cs:
            self.assertAlmostEqual(sum(c["percentage"] for c in cs), 100.0, delta=1.5)

    def test_most_prescribed_respects_filter(self):
        d = client.get("/api/dashboard/most-prescribed?district=Bandarban").json()
        self.assertEqual(d["medicines"], [])

    def test_top_doctors_shape_and_pagination(self):
        d = client.get("/api/dashboard/top-doctors?limit=2&offset=0").json()
        self.assertIn("doctors", d)
        self.assertIn("total", d)
        self.assertLessEqual(len(d["doctors"]), 2)
        for doc in d["doctors"]:
            self.assertIn("conversion_rate", doc)
            self.assertGreaterEqual(doc["conversion_rate"], 0)
            self.assertLessEqual(doc["conversion_rate"], 100)

    def test_top_doctors_search(self):
        d = client.get("/api/dashboard/top-doctors?q=Dash").json()
        self.assertTrue(any("Dash" in x["doctor_name"] for x in d["doctors"]))

    def test_company_drilldown(self):
        cs = client.get("/api/dashboard/company-share").json()["companies"]
        target = next((c for c in cs if not c.get("is_others")), None)
        self.assertIsNotNone(target)
        d = client.get("/api/dashboard/company-drilldown",
                       params={"company": target["company"]}).json()
        self.assertEqual(d["company"], target["company"])
        for key in ["generics", "brands", "doctors"]:
            self.assertIn(key, d)
        self.assertTrue(d["brands"], "drill-down should list brands")

    def test_brand_doctors_drilldown(self):
        brand = self.scan["medicines"][0]["brand_name"]
        d = client.get("/api/dashboard/brand-doctors", params={"brand": brand}).json()
        self.assertEqual(d["brand_name"], brand)
        self.assertTrue(d["doctors"])

    def test_ui_has_filter_bar_and_drilldown(self):
        for token in ["fTerritory", "fDistrict", "fSpecialty", "fDays", "fExport",
                      "drillModal", "openCompanyDrilldown", "openBrandDrilldown",
                      "filterQuery", "loadDoctorLeaderboard"]:
            self.assertIn(token, TPL, f"missing {token}")


class TestTimestampConsistency(unittest.TestCase):
    """Regression: 'YYYY-MM-DD HH:MM:SS' broke every string date comparison."""

    def test_stored_timestamps_are_iso(self):
        do_scan()
        con = sqlite3.connect(db.DB_PATH)
        rows = [r[0] for r in con.execute("SELECT timestamp FROM prescriptions")]
        con.close()
        self.assertTrue(rows)
        for ts in rows:
            self.assertRegex(ts, r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}",
                             f"non-ISO timestamp stored: {ts!r}")

    def test_normalizer_handles_both_separators(self):
        a = db._normalize_timestamp("2026-08-26 14:30:00")
        b = db._normalize_timestamp("2026-08-26T14:30:00")
        self.assertEqual(a, b)
        self.assertEqual(a, "2026-08-26T14:30:00")

    def test_today_kpi_counts_todays_scans(self):
        before = client.get("/api/dashboard/kpis").json()["total_prescriptions"]["today"]
        do_scan()
        after = client.get("/api/dashboard/kpis").json()["total_prescriptions"]["today"]
        self.assertEqual(after, before + 1,
                         "a scan made now must appear in the Today KPI")

    def test_today_never_exceeds_week_or_month(self):
        k = client.get("/api/dashboard/kpis").json()["total_prescriptions"]
        self.assertLessEqual(k["today"], k["week"])
        self.assertLessEqual(k["week"], k["month"])
        self.assertLessEqual(k["month"], k["all"])


class TestNoRegressions(unittest.TestCase):
    def test_core_endpoints_ok(self):
        for path in ["/", "/api/health", "/api/medex?q=napa&limit=3",
                     "/api/prescriptions", "/api/locations", "/api/filters",
                     "/api/recent-medicines", "/api/dashboard/kpis",
                     "/api/dashboard/company-share",
                     "/api/dashboard/most-prescribed",
                     "/api/dashboard/top-doctors",
                     "/api/dashboard/generic-brand-matrix",
                     "/manifest.json"]:
            self.assertEqual(client.get(path).status_code, 200, path)

    def test_company_still_resolved_from_catalogue(self):
        """Guard the previous turn's fix: model guess must not win."""
        s = do_scan()
        for med in s["medicines"]:
            if med.get("match_type") in ("exact", "fuzzy"):
                self.assertEqual(med["company_source"], "medex")
                self.assertTrue(med["company_verified"])

    def test_scan_normalises_location(self):
        s = do_scan(district="dhaka", upazila="Teknaf", territory="")
        d = s["doctor"]
        self.assertEqual(d["district"], "Dhaka")
        self.assertEqual(d["upazila"], "", "mismatched upazila must be dropped")
        self.assertTrue(d["territory"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
