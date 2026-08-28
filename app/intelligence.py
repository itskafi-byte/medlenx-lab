"""
MedLenX Lab — Enterprise intelligence layer.

Pure, deterministic helpers that power the B2B / enterprise-sellable modules:
  * Doctor detailing & brand-substitution engine: when a competitor brand is
    detected on a scan, surface the client company's equivalent brand with
    price / pack / unit difference + a 2-sentence MPO smart pitch note.
  * DGDA price & registration compliance monitor: cross-reference scanned
    brands against the gazette for banned / price-adjusted flags.

These functions receive the already-loaded MedEx catalogue (passed in so they
stay unit-testable and never re-read 25K rows on every call); price/flag data
comes from ``data/dgda_prices.json``.
"""
from __future__ import annotations

import os
import json
from typing import Any, Dict, List, Optional

from .config import settings
from .medicine_matcher import company_key, same_company

# Cache the gazette so we do not re-read the file per request.
_DGDA_CACHE: Dict[str, Any] = {}


def _load_dgda() -> Dict[str, Any]:
    if "data" in _DGDA_CACHE:
        return _DGDA_CACHE["data"]
    path = os.path.join(settings.DATA_DIR, "dgda_prices.json")
    try:
        with open(path, "r", encoding="utf-8") as f:
            _DGDA_CACHE["data"] = json.load(f)
    except Exception as exc:
        print(f"⚠️  could not load {path}: {exc}")
        _DGDA_CACHE["data"] = {"prices": [], "banned": [], "price_adjusted": []}
    return _DGDA_CACHE["data"]


def _normalise_key(text: str) -> str:
    return " ".join((text or "").lower().split())


def _gazette_lookup(brand: str, generic: str = "") -> Optional[dict]:
    """Return the gazette price row for a brand (by brand then generic)."""
    data = _load_dgda()
    brand_k = _normalise_key(brand)
    generic_k = _normalise_key(generic)
    prices = data.get("prices", []) or []
    # 1. exact brand (strip strength/type tokens first too)
    for row in prices:
        if _normalise_key(row.get("brand", "")) == brand_k:
            return row
    # 2. generic match
    for row in prices:
        if generic_k and row.get("generic") and _normalise_key(row.get("generic")) == generic_k:
            return row
    # 3. loose containment on brand
    if len(brand_k) >= 4:
        for row in prices:
            rk = _normalise_key(row.get("brand", ""))
            if brand_k in rk or rk in brand_k:
                return row
    return None


def dgda_check(brand: str = "", generic: str = "", company: str = "") -> dict:
    """Cross-reference a scanned medicine against the DGDA gazette.

    Returns a compliance object:
      status   → 'ok' | 'price_adjusted' | 'banned' | 'unknown'
      concern  → human-readable flag description (or None)
      price    → gazette MRP + pack when known
    """
    data = _load_dgda()
    brand_k = _normalise_key(brand)

    # banned list
    for entry in data.get("banned", []) or []:
        if entry.get("brand") and _normalise_key(entry["brand"]) in brand_k + " " + brand_k:
            return {
                "status": "banned",
                "severity": "critical",
                "concern": f"🚫 {entry.get('brand')} ({entry.get('generic')}): {entry.get('reason')}",
                "flag_type": "banned",
                "price": None,
            }
        if entry.get("generic") and generic and _normalise_key(entry["generic"]) == _normalise_key(generic):
            return {
                "status": "banned",
                "severity": "critical",
                "concern": f"🚫 {entry.get('brand')}: {entry.get('reason')}",
                "flag_type": "banned",
                "price": None,
            }

    # price-adjusted list
    for entry in data.get("price_adjusted", []) or []:
        if entry.get("brand") and _normalise_key(entry["brand"]) == brand_k:
            return {
                "status": "price_adjusted",
                "severity": "warn",
                "concern": (f"⚠️ {entry.get('brand')} MRP {entry.get('old_mrp')} → "
                            f"{entry.get('new_mrp')} BDT. {entry.get('note')}"),
                "flag_type": "price_adjusted",
                "old_mrp": entry.get("old_mrp"),
                "new_mrp": entry.get("new_mrp"),
                "price": {"mrp": entry.get("new_mrp"), "pack": "",
                          "strength": entry.get("generic", "")},
            }

    # known price row → ok
    row = _gazette_lookup(brand, generic)
    if row:
        return {
            "status": "ok",
            "severity": "info",
            "concern": None,
            "flag_type": None,
            "price": {"mrp": row.get("mrp"), "pack": row.get("pack"),
                      "strength": row.get("strength")},
        }
    return {
        "status": "unknown",
        "severity": "info",
        "concern": None,
        "flag_type": None,
        "price": None,
    }


def find_own_brand(own_company: str, generic: str, medex_db: List[dict],
                   prefer_strength: str = "") -> Optional[dict]:
    """Find the client company's catalogue brand for a given generic.

    Generic is matched loosely (substring on the generic / ingredient / category
    field), then a strength-matching variant is preferred.
    """
    if not generic:
        return None
    own_k = company_key(own_company)
    if not own_k:
        return None
    generic_l = _normalise_key(generic)
    candidates = []
    for row in medex_db or []:
        comp = row.get("company", "")
        if not same_company(comp, own_company):
            continue
        blob = _normalise_key(" ".join([
            row.get("generic") or "", row.get("ingredient") or "",
            row.get("category") or "",
        ]))
        if generic_l in blob or blob in generic_l:
            candidates.append(row)
    if not candidates:
        return None
    # Prefer an EXACT generic-name match (e.g. 'omeprazole' should pick Omenix
    # over Esonix, whose generic is 'esomeprazole' — a substring collision),
    # then the variant best matching the detected strength.
    exact = [r for r in candidates
             if _normalise_key(r.get("generic", "")) == generic_l]
    pool = exact or candidates
    want = _normalise_key(prefer_strength)
    if want:
        for row in pool:
            if want in _normalise_key(row.get("strength", "")):
                return row
    pool.sort(key=lambda r: len(str(r.get("strength", ""))))
    return pool[0]


def generic_substitution(detected: dict, own_company: str,
                         medex_db: List[dict]) -> Optional[dict]:
    """Build a competitor → own-brand substitution card for a detected medicine.

    Returns None when the detected medicine is already the officer's own brand,
    or when no own-brand equivalent exists in the catalogue.
    """
    if not own_company or not detected:
        return None
    detected_company = detected.get("company", "") or ""
    if detected_company and same_company(detected_company, own_company):
        return None

    generic = (detected.get("generic") or detected.get("generic_name")
               or detected.get("ingredient") or "").strip()
    if not generic:
        return None

    own = find_own_brand(own_company, generic, medex_db,
                         prefer_strength=detected.get("strength", ""))
    if not own:
        return None

    detected_price = _gazette_lookup(detected.get("brand", ""), generic) or {}
    own_price = _gazette_lookup(own.get("brand_name", ""), generic) or {}
    d_mrp = detected_price.get("mrp")
    o_mrp = own_price.get("mrp")
    unit_diff = None
    unit_diff_lbl = ""
    if d_mrp is not None and o_mrp is not None:
        unit_diff = round(o_mrp - d_mrp, 2)
        unit_diff_lbl = (
            f"{abs(unit_diff):.2f} BDT {'higher' if unit_diff > 0 else 'lower'} per unit"
        )

    return {
        "competitor": {
            "brand": detected.get("brand") or detected.get("brand_name") or "",
            "company": detected_company,
            "generic": generic,
            "strength": detected.get("strength", ""),
            "type": detected.get("type", "Tablet"),
            "image_url": detected.get("image_url") or detected.get("pack_image", ""),
            "mrp": d_mrp,
            "pack": detected_price.get("pack", ""),
        },
        "own_brand": {
            "brand": own.get("brand_name", ""),
            "company": own_company,
            "generic": generic,
            "strength": own.get("strength", ""),
            "type": own.get("type", "Tablet"),
            "image_url": own.get("image_url") or own.get("pack_image", ""),
            "mrp": o_mrp,
            "pack": own_price.get("pack", ""),
        },
        "generic": generic,
        "unit_difference": unit_diff,
        "unit_difference_label": unit_diff_lbl,
        "pitch": smart_pitch_note(detected, own, d_mrp, o_mrp),
    }


def smart_pitch_note(detected: dict, own: dict, d_mrp=None, o_mrp=None) -> str:
    """A 2-sentence promo script for the MPO's next chamber visit."""
    comp_brand = detected.get("brand", "") or "the brand they prescribe"
    own_brand = own.get("brand_name", "") or "our brand"
    generic = (own.get("generic") or detected.get("generic") or "this molecule")
    if d_mrp is not None and o_mrp is not None:
        diff = o_mrp - d_mrp
        if diff > 0:
            price_line = (
                f"While {own_brand} carries a small premium, it keeps the same "
                f"{own.get('strength') or ''} {own.get('type') or 'dose'} as "
                f"{comp_brand}."
            )
        elif diff < 0:
            price_line = (
                f"Switching to {own_brand} saves the patient "
                f"{abs(diff):.2f} BDT per unit with an equivalent "
                f"{own.get('strength') or ''} {own.get('type') or 'dose'}."
            )
        else:
            price_line = (
                f"{own_brand} matches {comp_brand} on price at "
                f"{own.get('strength') or ''} {own.get('type') or 'dose'}."
            )
    else:
        price_line = (
            f"{own_brand} offers the same {generic} at an equivalent "
            f"{own.get('strength') or ''} {own.get('type') or 'dose'}."
        )
    return (
        f"Doctor, {comp_brand} is being written for {generic} at this chamber. "
        f"Our {own_brand} is the same {own.get('strength') or ''} "
        f"{own.get('type') or 'form'} of {generic}. {price_line} May I leave a "
        f"starter pack of {own_brand} to consider for your next patient?"
    )
