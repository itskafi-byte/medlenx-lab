"""
New Pharma Intelligence Hub + enterprise feature tests.

Covers the tabbed navigation scaffold, quick-filter drug browse, live news,
department/territory job filters + apply links, WHO health-day campaign cards,
RSM SoV sparkline trends and the DGDA compliance PDF report.

Run:  .venv/bin/python -m pytest tests/test_intelligence_hub_features.py -v
"""
import importlib
import io
import os
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

_TMPDIR = tempfile.mkdtemp(prefix="medlenx-intel-")
os.environ["MEDLENX_TEST_DB"] = os.path.join(_TMPDIR, "test.db")

from app import database as db  # noqa: E402

db.DB_PATH = os.environ["MEDLENX_TEST_DB"]

from fastapi.testclient import TestClient  # noqa: E402
from app import main as app_main  # noqa: E402

importlib.reload(app_main)
app_main.init_db()
client = TestClient(app_main.app)

TPL = open(os.path.join(ROOT, "templates", "index.html"), encoding="utf-8").read()


class TestHubNavigationScaffold(unittest.TestCase):
    """The generic right-aligned sub-pills became an active tabbed nav under the
    section title, with the four named tabs."""

    def test_tabbed_nav_under_title(self):
        # title + subtitle block
        self.assertIn("Real-time Medex market intelligence, DGDA notifications",
                      TPL)
        # the nav uses the dedicated tabbed bar id and emoji labels
        self.assertIn('id="hubTabBar"', TPL)
        for label in ["💊 25K+ Drug Index", "📰 Industry News",
                      "💼 Health &amp; Pharma Jobs", "🗓️ Health Days"]:
            self.assertIn(label, TPL)

    def test_quick_filter_pills_exist(self):
        self.assertIn('id="medexFilterBar"', TPL)
        for cat in ["cardiology", "antibiotics", "otc"]:
            self.assertIn(f'data-cat="{cat}"', TPL)

    def test_news_two_column_layout(self):
        self.assertIn('id="newsFeatured"', TPL)
        self.assertIn('id="newsTimeline"', TPL)

    def test_job_filters_and_apply(self):
        for _id in ["jobDept", "jobTerritory", "jobCount", "jobFreshCount"]:
            self.assertIn(f'id="{_id}"', TPL)
        self.assertIn("Apply now", TPL)

    def test_health_day_campaign_modal(self):
        self.assertIn('id="healthDayModal"', TPL)
        self.assertIn('id="hdModalBody"', TPL)

    def test_rsm_pdf_button_and_sparkline(self):
        self.assertIn('id="rsmReportPdf"', TPL)
        self.assertIn("sparklineSvg", TPL)

    def test_ai_guess_confidence_detected(self):
        self.assertIn("AI Guess", TPL)
        self.assertIn("ai-verify", TPL)
        self.assertIn("bboxOverlay", TPL)


class TestMedexBrowse(unittest.TestCase):
    def test_top10_returns_products(self):
        d = client.get("/api/medex/browse?category=top10&limit=5").json()
        self.assertGreater(d["total"], 0)
        self.assertEqual(d["category"], "top10")
        for item in d["results"]:
            self.assertEqual(item["dgda_status"], "DGDA Registered")

    def test_cardiology_returns_dgda_registered(self):
        d = client.get("/api/medex/browse?category=cardiology&limit=8").json()
        self.assertGreater(d["total"], 0)
        self.assertTrue(all(r.get("dgda_status") == "DGDA Registered"
                            for r in d["results"]))

    def test_invalid_category_empty(self):
        d = client.get("/api/medex/browse?category=nope").json()
        self.assertEqual(d["results"], [])

    def test_medex_search_has_dgda_status(self):
        d = client.get("/api/medex?q=Seclo&limit=5").json()
        self.assertGreater(len(d["results"]), 0)
        for r in d["results"]:
            self.assertEqual(r["dgda_status"], "DGDA Registered")


class TestJobsFilters(unittest.TestCase):
    def test_apply_urls(self):
        d = client.get("/api/pharma/jobs").json()
        self.assertGreater(d["total"], 0)
        for j in d["jobs"]:
            self.assertTrue(j.get("apply_url"))
            self.assertTrue(j.get("department"))

    def test_department_filter(self):
        d = client.get("/api/pharma/jobs?department=Field%20Sales").json()
        self.assertTrue(d["departments"])
        for j in d["jobs"]:
            self.assertEqual(j["department"], "Field Sales")

    def test_territory_filter(self):
        d = client.get("/api/pharma/jobs?territory=Dhaka").json()
        self.assertGreater(d["total"], 0)
        for j in d["jobs"]:
            blob = (j.get("location") or "") + " " + (j.get("division") or "")
            self.assertIn("Dhaka", blob)


class TestHealthDayCampaign(unittest.TestCase):
    def test_campaign_card(self):
        d = client.get("/api/pharma/health-days/cancer").json()
        self.assertTrue(d["found"])
        self.assertIn("promo_script", d)
        self.assertGreater(len(d["brand_focus"]), 0)
        self.assertIn("World Cancer Day", d["day"]["name"])

    def test_unknown_day(self):
        d = client.get("/api/pharma/health-days/nope").json()
        self.assertFalse(d["found"])


class TestRsmTrendsAndReport(unittest.TestCase):
    def test_trends_series(self):
        d = client.get("/api/rsm/trends?days=30").json()
        self.assertGreaterEqual(d["bucket_count"], 1)
        self.assertTrue("trends" in d)
        for t in d["trends"]:
            self.assertIn("series", t)
            self.assertIn("own_growth", t)

    def test_pdf_report(self):
        r = client.get("/api/rsm/report.pdf?days=30")
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.headers["content-type"], "application/pdf")
        self.assertGreater(len(r.content), 500)
        self.assertTrue(r.content.startswith(b"%PDF"))


if __name__ == "__main__":
    unittest.main()
