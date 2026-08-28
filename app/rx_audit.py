"""
MedLenX Lab — Rx Audit Drawer helpers.

Pure, deterministic helpers that power the "Prescription Audit Summary"
slide-over drawer and the Duplicate-Rx fraud alert:

  * ``compute_phash``      — DCT-based perceptual image hash (pure Python +
                             Pillow, no numpy) used to catch the same physical
                             prescription being scanned twice.
  * ``hamming_distance``   — bit distance between two pHash hex digests.
  * ``is_duplicate_hash``  — threshold wrapper around the hamming distance.
  * ``build_market_share`` — own-vs-competitor share summary for one Rx.
  * ``items_to_csv``       — "Export Rx Items as CSV" payload builder.
  * ``items_to_clipboard`` — "Copy list to clipboard" payload builder.

Nothing here touches the DB or network so every function stays unit-testable.
"""
from __future__ import annotations

import csv
import io
from typing import Any, Dict, List, Optional

# Two scans whose pHashes differ by this many bits or fewer are treated as the
# same physical prescription. 64-bit DCT pHash: <=8 bits is the industry
# sweet-spot for "same image, different capture pipeline" (re-compression,
# rotation of a phone shot, etc).
DUPLICATE_THRESHOLD = 8

_HASH_SIZE = 8   # 8x8 hash -> 64 bits
_IMG_SIZE = 32   # 32x32 DCT input plane


# ---------------------------------------------------------------- pHash ----
def _dct_1d(vector: List[float]) -> List[float]:
    """Naive O(n^2) DCT-II of one vector — fine for 32-element inputs."""
    import math
    n = len(vector)
    out = []
    for k in range(n):
        total = 0.0
        for i, v in enumerate(vector):
            total += v * math.cos(math.pi * (2 * i + 1) * k / (2 * n))
        out.append(total * (0.5 if k == 0 else 1.0))
    return out


def compute_phash(image_path: str) -> str:
    """Return a 16-hex-char perceptual hash of the image, or "" on failure.

    Standard pHash pipeline: grayscale -> 32x32 -> separable 2D DCT-II ->
    top-left 8x8 low-frequency block -> median threshold (DC term excluded
    from the median) -> 64-bit hex digest.
    """
    try:
        from PIL import Image
        with Image.open(image_path) as img:
            gray = img.convert("L").resize((_IMG_SIZE, _IMG_SIZE))
        pixels = list(gray.getdata())
        rows = [pixels[y * _IMG_SIZE:(y + 1) * _IMG_SIZE]
                for y in range(_IMG_SIZE)]

        # Separable 2D DCT: DCT every row (index = vertical position,
        # value = horizontal frequency), then DCT every column of that
        # result (index = horizontal frequency, value = vertical frequency).
        row_dct = [_dct_1d(r) for r in rows]
        col_dct = [_dct_1d([row_dct[i][k] for i in range(_IMG_SIZE)])
                   for k in range(_IMG_SIZE)]

        # Top-left 8x8 = lowest frequencies on both axes.
        flat = [col_dct[x][y] for x in range(_HASH_SIZE)
                for y in range(_HASH_SIZE)]
        # Exclude the DC term (flat[0]) from the median, per pHash spec.
        med = sorted(flat[1:])[len(flat[1:]) // 2]
        bits = 0
        for c in flat:
            bits = (bits << 1) | (1 if c > med else 0)
        return f"{bits:016x}"
    except Exception as exc:  # never fail a scan over hashing
        print(f"⚠️  pHash computation failed for {image_path}: {exc}")
        return ""


def hamming_distance(hex_a: str, hex_b: str) -> Optional[int]:
    """Bit distance between two hex digests; None when either is missing."""
    if not hex_a or not hex_b:
        return None
    try:
        a, b = int(hex_a, 16), int(hex_b, 16)
    except ValueError:
        return None
    return bin(a ^ b).count("1")


def is_duplicate_hash(hex_a: str, hex_b: str,
                      threshold: int = DUPLICATE_THRESHOLD) -> bool:
    dist = hamming_distance(hex_a, hex_b)
    return dist is not None and dist <= threshold


# ----------------------------------------------------- market share -------
def _norm(text: Any) -> str:
    return " ".join(str(text or "").lower().split())


def same_company_loose(a: str, b: str) -> bool:
    """Cheap company equality used when the matcher helpers aren't loaded."""
    a_k, b_k = _norm(a), _norm(b)
    if not a_k or not b_k:
        return False
    if a_k == b_k:
        return True
    # "Healthcare Pharmaceuticals Ltd." vs "Healthcare Pharmaceuticals"
    return a_k.startswith(b_k) or b_k.startswith(a_k)


def build_market_share(medicines: List[dict], own_company: str) -> Dict[str, Any]:
    """Own-vs-competitor split for the drawer's footer summary."""
    total = len(medicines or [])
    own_items = []
    competitor_items = []
    for med in medicines or []:
        if own_company and same_company_loose(med.get("company", ""), own_company):
            own_items.append(med.get("brand_name", ""))
        else:
            competitor_items.append(med.get("brand_name", ""))
    own_count = len(own_items)
    competitor_count = len(competitor_items)
    return {
        "own_company": own_company or "",
        "total_medicines": total,
        "own_count": own_count,
        "competitor_count": competitor_count,
        "own_share_pct": round(own_count * 100.0 / total, 1) if total else 0.0,
        "competitor_share_pct": (
            round(competitor_count * 100.0 / total, 1) if total else 0.0
        ),
        "own_brands": own_items,
        "competitor_brands": competitor_items,
    }


# ------------------------------------------------------ export helpers ----
CSV_HEADERS = [
    "Brand Name", "Strength", "Dosage Form", "Generic Composition",
    "Pharmaceutical Company", "Confidence %", "Own/Competitor",
]


def items_to_csv(medicines: List[dict], own_company: str = "") -> str:
    """CSV payload for the drawer's "Export Rx Items as CSV" button."""
    buf = io.StringIO()
    writer = csv.writer(buf, lineterminator="\n")
    writer.writerow(CSV_HEADERS)
    for med in medicines or []:
        own = bool(own_company and same_company_loose(
            med.get("company", ""), own_company))
        conf = med.get("confidence", 0) or 0
        conf_pct = round(conf * 100) if conf <= 1 else round(conf)
        writer.writerow([
            med.get("brand_name", "") or "",
            med.get("strength", "") or "",
            med.get("type", "") or med.get("form", "") or "",
            med.get("generic", "") or med.get("generic_name", "") or "",
            med.get("company", "") or "",
            conf_pct,
            "Own" if own else "Competitor",
        ])
    return buf.getvalue()


def items_to_clipboard(medicines: List[dict], header: str = "") -> str:
    """Plain-text audit list a field rep can paste into a reporting channel."""
    lines = []
    if header:
        lines.append(header)
    for i, med in enumerate(medicines or [], 1):
        brand = " ".join(filter(None, [
            med.get("brand_name", ""), med.get("strength", ""),
            med.get("type", "") or med.get("form", ""),
        ]))
        generic = med.get("generic", "") or med.get("generic_name", "") or ""
        company = med.get("company", "") or "Unknown Brand"
        conf = med.get("confidence", 0) or 0
        conf_pct = round(conf * 100) if conf <= 1 else round(conf)
        lines.append(f"{i}. {brand} — {generic} — {company} ({conf_pct}%)")
    return "\n".join(lines)
