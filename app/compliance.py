"""
MedLenX Lab — Clinical & regulatory compliance layer.

Pure, deterministic helpers powering the drawer's DGDA/NEML badges and the
prescribing-pattern analytics:

  * NEML (National Essential Medicines List, ~295 molecules) lookup → the
    blue "NEML Listed" pill in the Rx Audit drawer.
  * DGDA price-ceiling alerts → the red "DGDA Price Alert" flag when a brand
    is banned, its MRP was ceiling-adjusted, or a detected price exceeds the
    gazette ceiling.
  * Therapeutic-class resolution + antibiotic stewardship detection
    (broad-spectrum tagging for brand-manager compliance tracking).
  * Polypharmacy risk index (>=8 items high, 5-7 moderate).
  * Off-territory geofence check (scan location vs the officer's assigned
    territory) using ``data/bd_geo.json`` district centroids.

Nothing here touches the DB or network; datasets are cached per process.
"""
from __future__ import annotations

import json
import math
import os
import re
from typing import Any, Dict, List, Optional

from .config import settings

# ---------------------------------------------------------------- NEML ----
_NEM_CACHE: Dict[str, Any] = {}


def _norm(text: Any) -> str:
    return " ".join(re.sub(r"[^a-z0-9+ ]", " ",
                           str(text or "").lower()).split())


def _load_neml() -> Dict[str, Any]:
    if "data" in _NEM_CACHE:
        return _NEM_CACHE
    path = os.path.join(settings.DATA_DIR, "neml_list.json")
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as exc:
        print(f"⚠️  could not load {path}: {exc}")
        data = {"version": "unavailable", "molecules": []}
    # index: normalised molecule -> entry
    index: Dict[str, dict] = {}
    for m in data.get("molecules", []):
        index[_norm(m.get("molecule", ""))] = m
    data["_index"] = index
    _NEM_CACHE.update(data)
    return data


def neml_lookup(generic: str = "", ingredient: str = "") -> Dict[str, Any]:
    """NEML membership for a molecule (the drawer's blue pill).

    Matches the generic/ingredient text against the NEML molecule index —
    exact first, then containment in either direction so 'Omeprazole 20 mg'
    still resolves to the 'Omeprazole' entry.
    """
    data = _load_neml()
    index: Dict[str, dict] = data.get("_index", {})
    for raw in (generic, ingredient):
        key = _norm(raw)
        if not key:
            continue
        hit = index.get(key)
        if hit:
            return {"listed": True, "molecule": hit.get("molecule", raw),
                    "class": hit.get("class", ""), "category": hit.get("category", "")}
        # containment both ways (strip dosage tails from ingredient blobs)
        for mk, m in index.items():
            if not mk:
                continue
            if mk in key or key in mk:
                # guard against 3-letter fragments matching everything
                if len(mk) >= 5 or len(key) >= 5:
                    return {"listed": True, "molecule": m.get("molecule", ""),
                            "class": m.get("class", ""),
                            "category": m.get("category", "")}
    return {"listed": False, "molecule": "", "class": "", "category": ""}


# ------------------------------------------------- therapeutic classes ----
# Keyword → class fallbacks for molecules not in the NEML index (or for
# catalogue rows that only carry a MedEx category string).
_CLASS_KEYWORDS: List[tuple] = [
    ("antibiot", "Antibiotics"), ("macrolide", "Antibiotics"),
    ("cephalosporin", "Antibiotics"), ("quinolone", "Antibiotics"),
    ("penicillin", "Antibiotics"), ("antifungal", "Antibiotics"),
    ("anthelmint", "Antibiotics"), ("antiviral", "Antibiotics"),
    ("antiprotozoal", "Antibiotics"), ("nitroimidaz", "Antibiotics"),
    ("ppi", "Gastroenterology"), ("proton pump", "Gastroenterology"),
    ("antiulcer", "Gastroenterology"), ("antacid", "Gastroenterology"),
    ("laxative", "Gastroenterology"), ("antiemetic", "Gastroenterology"),
    ("antidiarrhoe", "Gastroenterology"), ("antispasmod", "Gastroenterology"),
    ("digestive", "Gastroenterology"), ("carminative", "Gastroenterology"),
    ("h2 receptor", "Gastroenterology"),
    ("cardiac", "Cardiology"), ("antihypertens", "Cardiology"),
    ("statin", "Cardiology"), ("antiplatelet", "Cardiology"),
    ("anticoagulant", "Cardiology"), ("antiarrhyth", "Cardiology"),
    ("diuretic", "Cardiology"), ("beta blocker", "Cardiology"),
    ("ace inhibitor", "Cardiology"), ("nitrate", "Cardiology"),
    ("antidiabet", "Endocrinology"), ("insulin", "Endocrinology"),
    ("thyroid", "Endocrinology"), ("corticosteroid", "Endocrinology"),
    ("steroid", "Endocrinology"), ("hormone", "Endocrinology"),
    ("bronchodilat", "Respiratory"), ("asthma", "Respiratory"),
    ("expectorant", "Respiratory"), ("cough", "Respiratory"),
    ("antihistamine", "Antihistamines"), ("antiallerg", "Antihistamines"),
    ("antidepress", "Neurology & Psychiatry"), ("antipsychot", "Neurology & Psychiatry"),
    ("antiepilept", "Neurology & Psychiatry"), ("benzodiazep", "Neurology & Psychiatry"),
    ("neuropath", "Neurology & Psychiatry"), ("sedative", "Neurology & Psychiatry"),
    ("analgesic", "Analgesics & Antipyretics"), ("antipyret", "Analgesics & Antipyretics"),
    ("nsaid", "Analgesics & Antipyretics"), ("opioid", "Analgesics & Antipyretics"),
    ("nsaids", "Analgesics & Antipyretics"),
    ("vitamin", "Vitamins & Minerals"), ("mineral", "Vitamins & Minerals"),
    ("haematin", "Vitamins & Minerals"), ("supplement", "Vitamins & Minerals"),
    ("antioxidant", "Vitamins & Minerals"),
    ("antigout", "Other"), ("muscle relax", "Other"),
    ("ophthalmic", "Other"), ("eye", "Other"), ("derma", "Other"),
    ("topical", "Other"), ("contracept", "Other"), ("oncology", "Other"),
    ("anticancer", "Other"), ("immunosuppress", "Other"),
]

# Broad-spectrum / higher-priority stewardship molecules (AWaRe "Watch").
BROAD_SPECTRUM_KEYWORDS = (
    "azithromycin", "clarithromycin", "cefixime", "ceftriaxone", "cefuroxime",
    "cefotaxime", "ceftazidime", "cefepime", "ciprofloxacin", "levofloxacin",
    "moxifloxacin", "ofloxacin", "gemifloxacin", "meropenem", "imipenem",
    "clavulanic", "pipemidic", "norfloxacin", "cefpodoxime", "faropenem",
    "teicoplanin", "vancomycin", "colistin", "linezolid", "tigecycline",
)

_ANTIBIOTIC_KEYWORDS = BROAD_SPECTRUM_KEYWORDS + (
    "amoxicillin", "ampicillin", "cloxacillin", "penicillin", "erythromycin",
    "cephalexin", "cephradine", "doxycycline", "tetracycline", "metronidazole",
    "secnidazole", "tinidazole", "clindamycin", "trimethoprim", "sulphameth",
    "co-trimoxazole", "nitrofurantoin", "chloramphenicol", "gentamicin",
    "amikacin", "neomycin", "fluconazole", "ketoconazole", "itraconazole",
    "acyclovir", "albendazole", "mebendazole", "ivermectin", "antibiot",
    "antifungal", "antiviral", "anthelmint", "antiprotozoal",
)


def is_antibiotic(generic: str = "", category: str = "",
                  ingredient: str = "") -> bool:
    """Stewardship detection on the molecule text (generic/ingredient/category)."""
    blob = _norm(" ".join([generic, category, ingredient]))
    return any(k in blob for k in _ANTIBIOTIC_KEYWORDS)


def is_broad_spectrum(generic: str = "", ingredient: str = "") -> bool:
    blob = _norm(" ".join([generic, ingredient]))
    return any(k in blob for k in BROAD_SPECTRUM_KEYWORDS)


def resolve_therapeutic_class(generic: str = "", category: str = "",
                              ingredient: str = "") -> str:
    """One human label for the therapy-breakdown bar.

    Order: NEML index class → MedEx category keywords → molecule keywords →
    'Other'.
    """
    neml = neml_lookup(generic, ingredient)
    if neml.get("listed") and neml.get("class"):
        return neml["class"]
    blob = _norm(" ".join([category, generic, ingredient]))
    for kw, label in _CLASS_KEYWORDS:
        if kw in blob:
            return label
    return "Other"


# ------------------------------------------------------ polypharmacy ------
POLYPHARMACY_HIGH = 8     # >=8 concurrent items — high risk
POLYPHARMACY_MODERATE = 5  # 5-7 items — watch


def polypharmacy_index(item_count: int) -> Dict[str, Any]:
    """WHO-style polypharmacy banding for the drawer's top-level badge."""
    n = int(item_count or 0)
    if n >= POLYPHARMACY_HIGH:
        return {"count": n, "level": "high",
                "label": f"⚠️ {n}+ Meds Prescribed — High Polypharmacy"}
    if n >= POLYPHARMACY_MODERATE:
        return {"count": n, "level": "moderate",
                "label": f"{n} Meds Prescribed — Moderate Polypharmacy"}
    return {"count": n, "level": "normal",
            "label": f"{n} Meds Prescribed"}


# -------------------------------------------- DGDA price ceiling ----------
def price_ceiling_alert(brand: str = "", generic: str = "",
                        dgda: Optional[dict] = None,
                        detected_mrp: Optional[float] = None) -> Dict[str, Any]:
    """Red "DGDA Price Alert" evaluation for one drawer row.

    Flagged when the brand is banned, its MRP was ceiling-adjusted, or a
    price captured in the scan exceeds the gazette ceiling. Brands absent
    from the gazette get a mild "unverified" note, not a red flag (gazette
    coverage is partial).
    """
    dgda = dgda or {}
    status = dgda.get("status")
    if status == "banned":
        return {"flagged": True, "severity": "critical",
                "reason": dgda.get("concern") or "Banned per DGDA notification"}
    if status == "price_adjusted":
        return {"flagged": True, "severity": "warn",
                "reason": dgda.get("concern")
                or "MRP revised under DGDA ceiling price notification"}
    price = dgda.get("price") or {}
    ceiling = price.get("mrp")
    if detected_mrp is not None and ceiling is not None:
        try:
            if float(detected_mrp) > float(ceiling) + 1e-9:
                return {
                    "flagged": True, "severity": "critical",
                    "reason": (f"Detected MRP {detected_mrp} BDT exceeds the "
                               f"DGDA ceiling {ceiling} BDT"),
                }
        except (TypeError, ValueError):
            pass
    if status == "unknown":
        return {"flagged": False, "severity": "info",
                "reason": "Not found in the DGDA MRP gazette — pricing unverified"}
    return {"flagged": False, "severity": "info",
            "reason": f"Compliant with gazette MRP ({ceiling} BDT)"
            if ceiling else "Compliant"}


# -------------------------------------------------- therapy breakdown -----
THERAPY_COLORS = {
    "Antibiotics": "#DC2626",
    "Cardiology": "#1E40AF",
    "Gastroenterology": "#0D9488",
    "Endocrinology": "#7C3AED",
    "Respiratory": "#0284C7",
    "Antihistamines": "#D97706",
    "Neurology & Psychiatry": "#DB2777",
    "Analgesics & Antipyretics": "#65A30D",
    "Vitamins & Minerals": "#CA8A04",
    "Other": "#64748B",
}


def therapy_breakdown(classes: List[str]) -> List[Dict[str, Any]]:
    """Percent split across therapeutic classes for the stacked bar."""
    total = len(classes or [])
    if not total:
        return []
    counts: Dict[str, int] = {}
    for c in classes:
        counts[c] = counts.get(c, 0) + 1
    out = []
    for cls, n in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0])):
        out.append({
            "class": cls,
            "count": n,
            "pct": round(n * 100.0 / total, 1),
            "color": THERAPY_COLORS.get(cls, "#64748B"),
        })
    return out


def clinical_summary(items: List[dict]) -> Dict[str, Any]:
    """Assemble the drawer's prescribing-pattern analytics block."""
    classes = [it.get("therapeutic_class") or "Other" for it in items or []]
    abx = [it for it in items or [] if it.get("is_antibiotic")]
    broad = [a for a in abx if a.get("broad_spectrum")]
    return {
        "polypharmacy": polypharmacy_index(len(items or [])),
        "therapy_breakdown": therapy_breakdown(classes),
        "antibiotics": {
            "count": len(abx),
            "broad_spectrum": len(broad),
            "brands": [a.get("brand_name") for a in abx if a.get("brand_name")],
        },
    }


# ------------------------------------------------------- geofencing -------
_GEO_CACHE: Dict[str, Any] = {}


def _load_geo() -> Dict[str, Any]:
    if "districts" in _GEO_CACHE:
        return _GEO_CACHE
    path = os.path.join(settings.DATA_DIR, "bd_geo.json")
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as exc:
        print(f"⚠️  could not load {path}: {exc}")
        data = {"districts": {}}
    _GEO_CACHE.update(data.get("districts", {}) or {})
    return _GEO_CACHE


def haversine_km(lat1, lng1, lat2, lng2) -> float:
    import math as _m
    try:
        lat1, lng1, lat2, lng2 = map(float, (lat1, lng1, lat2, lng2))
    except (TypeError, ValueError):
        return float("inf")
    r = 6371.0
    p1, p2 = _m.radians(lat1), _m.radians(lat2)
    dp = _m.radians(lat2 - lat1)
    dl = _m.radians(lng2 - lng1)
    a = _m.sin(dp / 2) ** 2 + _m.cos(p1) * _m.cos(p2) * _m.sin(dl / 2) ** 2
    return 2 * r * _m.asin(_m.sqrt(a))


def resolve_geo_district(lat: Optional[float], lng: Optional[float],
                         max_km: float = 90.0) -> str:
    """Nearest BD district centroid to a GPS point (within max_km), else ''."""
    districts = _load_geo()
    if lat is None or lng is None:
        return ""
    best, best_d = "", None
    for district, c in districts.items():
        d = haversine_km(lat, lng, c.get("lat"), c.get("lng"))
        if best_d is None or d < best_d:
            best, best_d = district, d
    return best if best_d is not None and best_d <= max_km else ""


def _territory_base(name: str) -> str:
    """'Dhaka South' / 'Bagerhat Territory' → comparable base tokens."""
    text = _norm(name)
    for suffix in ("territory", "regional", "region", "zone"):
        text = re.sub(rf"\b{suffix}\b", " ", text)
    return _norm(text)


def territory_check(officer_territory: str = "",
                    scan_territory: str = "",
                    scan_district: str = "",
                    gps_lat: Optional[float] = None,
                    gps_lng: Optional[float] = None,
                    locations: Optional[dict] = None) -> Dict[str, Any]:
    """Off-territory geofence verdict for a scan.

    Flags an audit only when we hold BOTH a positive assigned territory and a
    positive scan location signal that contradict each other. Never flags on
    missing data.
    """
    assigned = _territory_base(officer_territory)
    result = {"off_territory": False, "reason": "",
              "gps_district": resolve_geo_district(gps_lat, gps_lng)}
    if not assigned:
        result["reason"] = "No assigned territory on the officer profile"
        return result

    # Districts implied by the officer's territory: name containment or the
    # district's own territory list (bd_locations.json).
    implied = set()
    if locations:
        for district, info in (locations.get("districts") or {}).items():
            if district.lower() in assigned or assigned in district.lower():
                implied.add(district.lower())
            for t in (info or {}).get("territories", []) or []:
                if _territory_base(t) == assigned:
                    implied.add(district.lower())

    evidences = []   # (matches, description)
    if scan_territory:
        st = _territory_base(scan_territory)
        matches = bool(st) and (st == assigned or st in assigned
                                or assigned in st)
        evidences.append(("territory", matches,
                          f"scanned in {scan_territory} (assigned: "
                          f"{officer_territory})"))
    if scan_district:
        d = scan_district.lower()
        matches = d in implied if implied else (d in assigned
                                                or assigned in d)
        evidences.append(("district", matches,
                          f"scanned in {scan_district} district (assigned: "
                          f"{officer_territory})"))
    if result["gps_district"]:
        d = result["gps_district"].lower()
        matches = d in implied if implied else (d in assigned
                                                or assigned in d)
        evidences.append(("gps", matches,
                          f"GPS pin resolves to {result['gps_district']} "
                          f"(assigned: {officer_territory})"))

    if not evidences:
        result["reason"] = "No scan location captured — cannot verify territory"
        return result

    mismatches = [e for e in evidences if not e[1]]
    if mismatches:
        result["off_territory"] = True
        result["reason"] = "; ".join(m[2] for m in mismatches)
    else:
        result["reason"] = "Within assigned territory"
    return result
