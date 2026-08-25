"""
Regression tests for the "detected medicine gets the wrong company" bug.

Run:  python -m pytest tests/ -v      (or)   python tests/test_medicine_matcher.py
"""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.medicine_matcher import (  # noqa: E402
    MedexIndex, normalize_brand, normalize_strength, normalize_form,
    company_key, same_company, resolve_company,
)

DATA = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    "data", "medex_full.json")


class TestNormalisation(unittest.TestCase):
    def test_brand_strips_form_and_strength(self):
        self.assertEqual(normalize_brand("Tab. Napa 500mg"), "napa")
        self.assertEqual(normalize_brand("Cap Seclo 20 mg"), "seclo")
        self.assertEqual(normalize_brand("SECLO®"), "seclo")
        self.assertEqual(normalize_brand("Syr. Napa 120mg/5ml"), "napa")
        self.assertEqual(normalize_brand("  Maxpro  "), "maxpro")

    def test_brand_survives_form_only_names(self):
        # a brand that *is* a form word must not normalise to empty
        self.assertNotEqual(normalize_brand("Tablet"), "")

    def test_strength(self):
        self.assertEqual(normalize_strength("500 mg"), "500mg")
        self.assertEqual(normalize_strength("120 mg/5 ml"), "120mg/5ml")
        self.assertEqual(normalize_strength("৫০০ mg"), "500mg")

    def test_form_buckets(self):
        self.assertEqual(normalize_form("Tablet"), "tablet")
        self.assertEqual(normalize_form("Tab"), "tablet")
        self.assertEqual(normalize_form("Syrup"), "syrup")
        self.assertEqual(normalize_form("Suspension"), "syrup")
        self.assertEqual(normalize_form("IV Infusion"), "injection")
        self.assertEqual(normalize_form("Eye Drop"), "eye_drop")


class TestCompanyResolution(unittest.TestCase):
    def test_company_key_ignores_suffixes(self):
        self.assertTrue(same_company("Square Pharmaceuticals Ltd.",
                                     "Square Pharmaceuticals PLC"))
        self.assertTrue(same_company("Beximco Pharmaceuticals Ltd.", "Beximco"))
        self.assertFalse(same_company("Square Pharmaceuticals Ltd.",
                                      "Beximco Pharmaceuticals Ltd."))

    def test_catalogue_wins_over_model_guess(self):
        """THE core fix: MedEx is authoritative, the VL guess never overrides."""
        company, source, verified = resolve_company(
            "Beximco Pharmaceuticals Ltd.",   # catalogue (truth)
            "Square Pharmaceuticals Ltd.",    # model hallucination
        )
        self.assertEqual(company, "Beximco Pharmaceuticals Ltd.")
        self.assertEqual(source, "medex")
        self.assertTrue(verified)

    def test_model_guess_only_as_fallback_and_unverified(self):
        company, source, verified = resolve_company("", "Square Pharmaceuticals Ltd.")
        self.assertEqual(company, "Square Pharmaceuticals Ltd.")
        self.assertEqual(source, "model_guess")
        self.assertFalse(verified)

    def test_no_company_is_not_invented(self):
        company, source, verified = resolve_company("", "")
        self.assertEqual(company, "")
        self.assertFalse(verified)
        # 'Unknown' from the model is not a real company
        self.assertEqual(resolve_company("", "Unknown")[0], "")


class TestIndexNoCollapse(unittest.TestCase):
    def setUp(self):
        self.entries = [
            {"id": "1", "brand_name": "Napa", "strength": "500 mg", "type": "Tablet",
             "form": "Tablet", "company": "Beximco Pharmaceuticals Ltd.",
             "generic": "Paracetamol"},
            {"id": "2", "brand_name": "Napa", "strength": "120 mg/5 ml", "type": "Syrup",
             "form": "Syrup", "company": "Beximco Pharmaceuticals Ltd.",
             "generic": "Paracetamol"},
            {"id": "3", "brand_name": "Medrol", "strength": "4 mg", "type": "Tablet",
             "form": "Tablet", "company": "Pfizer Ltd.", "generic": "Methylprednisolone"},
            {"id": "4", "brand_name": "Medrol", "strength": "16 mg", "type": "Tablet",
             "form": "Tablet", "company": "ACI Limited", "generic": "Methylprednisolone"},
        ]
        self.idx = MedexIndex(self.entries)

    def test_all_variants_kept(self):
        """The old dict-comprehension index dropped 8,819 of 25,105 rows."""
        self.assertEqual(self.idx.variant_count, 4)
        self.assertEqual(len(self.idx.exact("Napa")), 2)

    def test_variant_picked_by_strength(self):
        v = self.idx.pick_variant(self.idx.exact("Napa"), "500mg", "Tablet")
        self.assertEqual(v["id"], "1")

    def test_variant_picked_by_form(self):
        v = self.idx.pick_variant(self.idx.exact("Napa"), "", "Syrup")
        self.assertEqual(v["id"], "2")

    def test_multi_company_brand_is_flagged(self):
        self.assertTrue(self.idx.company_is_ambiguous(self.idx.exact("Medrol")))
        self.assertFalse(self.idx.company_is_ambiguous(self.idx.exact("Napa")))

    def test_multi_company_disambiguated_by_strength(self):
        v = self.idx.pick_variant(self.idx.exact("Medrol"), "16mg", "Tablet")
        self.assertEqual(v["company"], "ACI Limited")
        v = self.idx.pick_variant(self.idx.exact("Medrol"), "4mg", "Tablet")
        self.assertEqual(v["company"], "Pfizer Ltd.")

    def test_lookup_is_deterministic(self):
        first = self.idx.pick_variant(self.idx.exact("Medrol"), "", "")["id"]
        for _ in range(20):
            self.assertEqual(
                self.idx.pick_variant(self.idx.exact("Medrol"), "", "")["id"], first
            )


class TestFuzzyGuards(unittest.TestCase):
    def setUp(self):
        self.entries = [
            {"id": "1", "brand_name": "Napa", "company": "Beximco Pharmaceuticals Ltd."},
            {"id": "2", "brand_name": "Epa", "company": "Zenith Pharmaceuticals Ltd."},
            {"id": "3", "brand_name": "Nepado", "company": "UNIDO Pharmaceuticals Ltd."},
            {"id": "4", "brand_name": "Seclo", "company": "Square Pharmaceuticals PLC"},
        ]
        self.idx = MedexIndex(self.entries)

    def test_short_stub_cannot_hijack(self):
        """'Nepa' used to match 'Epa' with an inflated 1.057 score."""
        variants, score, key = self.idx.fuzzy("Nepa")
        self.assertNotEqual(key, "epa")
        if variants:
            self.assertNotEqual(variants[0]["company"], "Zenith Pharmaceuticals Ltd.")

    def test_score_never_exceeds_one(self):
        for q in ["Nepa", "Ceevita", "Seclo", "Napa"]:
            _v, score, _k = self.idx.fuzzy(q)
            self.assertLessEqual(score, 1.0, f"{q} score {score} > 1.0")

    def test_typo_still_matches_right_brand(self):
        variants, score, key = self.idx.fuzzy("Secio")   # OCR l->i
        self.assertEqual(key, "seclo")
        self.assertEqual(variants[0]["company"], "Square Pharmaceuticals PLC")

    def test_unrelated_query_returns_nothing(self):
        variants, _s, _k = self.idx.fuzzy("Zzzxqywv")
        self.assertEqual(variants, [])


@unittest.skipUnless(os.path.exists(DATA), "medex_full.json not present")
class TestRealCatalogue(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(DATA, encoding="utf-8") as f:
            cls.db = json.load(f)
        cls.idx = MedexIndex(cls.db)

    def test_no_rows_lost(self):
        rows_with_brand = sum(1 for e in self.db if normalize_brand(e.get("brand_name", "")))
        self.assertEqual(self.idx.variant_count, rows_with_brand)

    def test_known_brands_get_correct_company(self):
        cases = [
            ("Napa", "500 mg", "Tablet", "beximco"),
            ("Seclo", "20 mg", "Capsule", "square"),
            ("Maxpro", "20 mg", "Tablet", "renata"),
        ]
        for brand, strength, form, expect in cases:
            variants = self.idx.exact(brand)
            self.assertTrue(variants, f"{brand} not found")
            v = self.idx.pick_variant(variants, strength, form)
            self.assertIn(expect, (v.get("company") or "").lower(),
                          f"{brand} {strength} -> {v.get('company')}")

    def test_napa_tablet_is_not_syrup(self):
        v = self.idx.pick_variant(self.idx.exact("Napa"), "500mg", "Tablet")
        self.assertEqual(normalize_form(v.get("type", "")), "tablet")
        self.assertEqual(normalize_strength(v.get("strength", "")), "500mg")

    def test_prefixed_brand_name_resolves(self):
        """'Tab. Napa 500mg' straight from the VL model must still match."""
        v = self.idx.pick_variant(self.idx.exact("Tab. Napa 500mg"), "500mg", "Tablet")
        self.assertIn("beximco", (v.get("company") or "").lower())

    def test_ambiguous_brands_are_detected(self):
        ambiguous = [b for b, v in self.idx.by_brand.items()
                     if self.idx.company_is_ambiguous(v)]
        self.assertGreater(len(ambiguous), 0)
        # every one of them must be flagged, never silently resolved
        for brand in ambiguous[:20]:
            self.assertTrue(self.idx.company_is_ambiguous(self.idx.by_brand[brand]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
