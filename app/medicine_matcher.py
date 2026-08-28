"""
MedLenX Lab - Medicine <-> MedEx catalogue matcher.

Fixes the "detected medicine shows the wrong company" bug.

Root causes this module addresses
---------------------------------
1. The old index was built as ``{brand.lower(): entry}`` which silently kept only
   the LAST row for every brand name. In the shipped MedEx dump that threw away
   8,819 of 25,105 rows, so "Napa 500 mg Tablet" resolved to the *Napa Syrup*
   row, and for the 29 brand names that are sold by more than one company it
   returned a completely different manufacturer.

2. The company was taken as ``med.get('company') or entry.get('company')`` i.e.
   the vision model's *guess* outranked the authoritative MedEx catalogue. The
   VL prompt even nudges it to guess ("Square, Incepta, Beximco, Renata etc"),
   so a hallucinated company overwrote the correct one.

3. The fuzzy fallback added a flat ``+0.2`` substring bonus (scores could exceed
   1.0) with no length sanity check, so a 3-letter catalogue brand such as "Epa"
   beat the real "Napa" (which scored 0.75 and was excluded by a ``> 0.75``
   threshold).

Design
------
* one brand -> **all** of its catalogue variants (never collapsed),
* variants disambiguated by strength, then dosage form/type,
* the company ALWAYS comes from the matched catalogue row - the model's guess is
  only a last resort and is flagged as unverified.
"""

from __future__ import annotations

import re
import unicodedata
from difflib import SequenceMatcher
from typing import Any, Dict, Iterable, List, Optional, Tuple

# --------------------------------------------------------------------------
# Normalisation helpers
# --------------------------------------------------------------------------

# Dosage-form words that are frequently glued onto the brand name by the OCR /
# vision model ("Tab. Napa", "Napa Syrup", "Cap Seclo 20mg").
_FORM_WORDS = {
    "tab", "tabs", "tablet", "tablets", "cap", "caps", "capsule", "capsules",
    "syr", "syrup", "susp", "suspension", "inj", "injection", "iv", "infusion",
    "cream", "oint", "ointment", "gel", "lotion", "drop", "drops", "eye",
    "ear", "nasal", "spray", "inhaler", "inhalation", "puff", "sachet",
    "powder", "vial", "ampoule", "amp", "suppository", "supp", "solution",
    "soln", "sol", "nebuliser", "nebulizer", "chewable", "er", "sr", "xr",
    "cr", "la", "plus", "forte",
}

# Strength tokens such as 500mg, 20 mg, 120mg/5ml, 10ml, 2.5%, 40 mg/vial
_STRENGTH_RE = re.compile(
    r"\d+(?:\.\d+)?\s*(?:%|mcg|microgram|mg|gm?|g|ml|l|iu|unit|units)"
    r"(?:\s*/\s*\d*(?:\.\d+)?\s*(?:ml|l|vial|amp|dose|puff|gm?|g))?",
    re.IGNORECASE,
)

_BN_DIGITS = str.maketrans("০১২৩৪৫৬৭৮৯", "0123456789")


def _hamming(a: str, b: str) -> int:
    """Character differences between two equal-length strings."""
    if len(a) != len(b):
        return max(len(a), len(b))
    return sum(1 for x, y in zip(a, b) if x != y)


def _ascii_fold(text: str) -> str:
    """Strip accents/diacritics so 'Rosuvas' == 'Rosuvás'."""
    return "".join(
        ch for ch in unicodedata.normalize("NFKD", text)
        if not unicodedata.combining(ch)
    )


def normalize_brand(raw: str) -> str:
    """
    Canonical form of a brand name for matching.

    'Tab. Napa 500mg'  -> 'napa'
    'Cap  Seclo-20 mg' -> 'seclo'
    'SECLO®'           -> 'seclo'
    """
    if not raw:
        return ""
    text = _ascii_fold(str(raw)).translate(_BN_DIGITS).lower()
    text = text.replace("®", " ").replace("™", " ")
    text = _STRENGTH_RE.sub(" ", text)
    # keep alphanumerics and spaces only
    text = re.sub(r"[^a-z0-9]+", " ", text)
    tokens = [t for t in text.split() if t]
    # drop leading/trailing dosage-form noise, but never drop everything
    while tokens and tokens[0] in _FORM_WORDS:
        tokens.pop(0)
    while tokens and tokens[-1] in _FORM_WORDS:
        tokens.pop()
    if not tokens:  # brand *was* the form word (rare) - fall back to raw tokens
        tokens = [t for t in re.sub(r"[^a-z0-9]+", " ", text).split() if t]
    # drop bare numeric tokens ("napa 500" -> "napa")
    trimmed = [t for t in tokens if not t.isdigit()]
    return " ".join(trimmed or tokens).strip()


def normalize_strength(raw: str) -> str:
    """'500 mg' / '500mg' / '৫০০ mg' -> '500mg'; '120 mg/5 ml' -> '120mg/5ml'."""
    if not raw:
        return ""
    text = str(raw).translate(_BN_DIGITS).lower()
    text = re.sub(r"\s+", "", text)
    text = text.replace("microgram", "mcg").replace("µg", "mcg")
    return text


def normalize_form(raw: str) -> str:
    """Map every spelling of a dosage form onto one canonical bucket."""
    text = (raw or "").lower()
    # order matters: check the most specific buckets first
    buckets: List[Tuple[str, Tuple[str, ...]]] = [
        ("eye_drop", ("eye drop", "eye/ear", "ophthalmic", "eye ")),
        ("nasal", ("nasal", "nose")),
        ("inhaler", ("inhaler", "inhalation", "puff", "hfa", "nebul")),
        ("injection", ("injection", "inj", " iv", "iv ", "infusion", "vial",
                       "ampoule", "amp")),
        ("suppository", ("suppository", "supp")),
        ("syrup", ("syrup", "syr", "suspension", "susp", "elixir", "oral solution")),
        ("drop", ("drop", "paediatric drop", "pediatric drop")),
        ("cream", ("cream",)),
        ("ointment", ("ointment", "oint")),
        ("gel", ("gel",)),
        ("lotion", ("lotion",)),
        ("spray", ("spray",)),
        ("powder", ("powder", "sachet", "granule")),
        ("capsule", ("capsule", "cap")),
        ("tablet", ("tablet", "tab")),
    ]
    for name, needles in buckets:
        for needle in needles:
            if needle in text:
                return name
    return ""


# --------------------------------------------------------------------------
# Index
# --------------------------------------------------------------------------

class MedexIndex:
    """
    Brand -> every catalogue variant of that brand.

    Never collapses duplicates: that collapse was the primary source of the
    wrong-company bug.
    """

    def __init__(self, entries: Optional[Iterable[Dict[str, Any]]] = None):
        self.by_brand: Dict[str, List[Dict[str, Any]]] = {}
        self._brand_keys: List[str] = []
        if entries:
            self.build(entries)

    def build(self, entries: Iterable[Dict[str, Any]]) -> "MedexIndex":
        self.by_brand = {}
        for entry in entries:
            key = normalize_brand(entry.get("brand_name", ""))
            if not key:
                continue
            self.by_brand.setdefault(key, []).append(entry)
        self._brand_keys = list(self.by_brand.keys())
        return self

    def __len__(self) -> int:
        return len(self._brand_keys)

    @property
    def variant_count(self) -> int:
        return sum(len(v) for v in self.by_brand.values())

    # -- variant selection -------------------------------------------------

    @staticmethod
    def _variant_score(entry: Dict[str, Any], strength: str, form: str) -> float:
        """Rank catalogue variants of one brand against the detected med."""
        score = 0.0
        want_strength = normalize_strength(strength)
        if want_strength:
            have = normalize_strength(entry.get("strength", ""))
            if have and have == want_strength:
                score += 10.0
            elif have and want_strength in have:
                score += 6.0
            else:
                # compare the leading number only: '500mg' vs '500 mg tablet'
                a = re.match(r"[\d.]+", want_strength)
                b = re.match(r"[\d.]+", have or "")
                if a and b and a.group() == b.group():
                    score += 4.0
        want_form = normalize_form(form)
        if want_form:
            have_form = normalize_form(
                f"{entry.get('type', '')} {entry.get('form', '')}"
            )
            if have_form and have_form == want_form:
                score += 5.0
            elif have_form:
                score -= 1.0
        # tie-breakers: prefer rows with a real pack image and a company
        if entry.get("company"):
            score += 0.5
        pack = entry.get("pack_image") or entry.get("image_url") or ""
        if "medex.com.bd/storage" in pack:
            score += 0.3
        # deterministic final tie-break so results never flip between runs
        return score

    def pick_variant(
        self,
        entries: List[Dict[str, Any]],
        strength: str = "",
        form: str = "",
    ) -> Dict[str, Any]:
        """Choose the catalogue row that best fits strength + dosage form."""
        if not entries:
            return {}
        if len(entries) == 1:
            return entries[0]
        best = max(
            entries,
            key=lambda e: (
                self._variant_score(e, strength, form),
                # stable, deterministic tie-break
                -len(str(e.get("strength", ""))),
                str(e.get("id", "")),
            ),
        )
        return best

    def company_is_ambiguous(self, entries: List[Dict[str, Any]]) -> bool:
        """True when one brand name is marketed by more than one company."""
        companies = {
            (e.get("company") or "").strip().lower()
            for e in entries
            if (e.get("company") or "").strip()
        }
        return len(companies) > 1

    # -- lookup ------------------------------------------------------------

    def exact(self, brand: str) -> List[Dict[str, Any]]:
        return self.by_brand.get(normalize_brand(brand), [])

    def fuzzy(
        self,
        brand: str,
        min_score: float = 0.80,
    ) -> Tuple[List[Dict[str, Any]], float, str]:
        """
        Fuzzy brand lookup that cannot be hijacked by tiny catalogue names.

        Returns (variants, score, matched_key).
        """
        query = normalize_brand(brand)
        if len(query) < 3:
            return [], 0.0, ""

        best_key, best_score = "", 0.0
        q_len = len(query)
        for key in self._brand_keys:
            # length sanity: 'nepa' must never match 'epa'-style stubs or very
            # long unrelated names. Ratio can't exceed 2*min/(a+b) anyway.
            k_len = len(key)
            if abs(k_len - q_len) > max(3, q_len * 0.5):
                continue
            if (2.0 * min(k_len, q_len)) / (k_len + q_len) < min_score:
                continue
            # A different first letter almost always means a different drug
            # ('Nepa' must not collapse onto the 3-letter stub 'Epa'). Only
            # tolerate it for an equal-length single-character substitution.
            if key[0] != query[0] and not (
                k_len == q_len and _hamming(query, key) == 1
            ):
                continue

            score = SequenceMatcher(None, query, key).ratio()

            # Equal-length single-character substitution is the classic OCR
            # error ('Secio' -> 'Seclo'); trust it above a longer partial name.
            if k_len == q_len and _hamming(query, key) == 1:
                score = min(1.0, score + 0.15)

            # containment bonus, bounded so the score stays <= 1.0 and short
            # stubs cannot outrank a genuinely closer name
            elif q_len >= 4 and k_len >= 4 and (query in key or key in query):
                score = min(1.0, score + 0.08)

            if score > best_score:
                best_key, best_score = key, score

        if best_key and best_score >= min_score:
            return self.by_brand[best_key], round(best_score, 3), best_key
        return [], round(best_score, 3), ""


# --------------------------------------------------------------------------
# Company resolution
# --------------------------------------------------------------------------

_COMPANY_NOISE = re.compile(
    r"\b(ltd|limited|plc|inc|co|company|pharmaceuticals?|pharma|laboratories|"
    r"labs?|industries|healthcare|health\s*care|bd|bangladesh)\b",
    re.IGNORECASE,
)


def company_key(name: str) -> str:
    """Loose key so 'Square Pharmaceuticals Ltd.' == 'Square Pharmaceuticals PLC'."""
    if not name:
        return ""
    text = _ascii_fold(str(name)).lower()
    text = _COMPANY_NOISE.sub(" ", text)
    text = re.sub(r"[^a-z0-9]+", " ", text)
    key = " ".join(text.split())
    if not key:
        # Corporate-noise-only name (e.g. 'Healthcare Pharmaceuticals Ltd.'
        # is healthcare + pharmaceuticals + ltd) — strip-noise left nothing.
        # Fall back to the normalised raw name so the key stays stable and
        # comparable instead of silently disabling own-company matching.
        raw = _ascii_fold(str(name)).lower()
        key = " ".join(re.sub(r"[^a-z0-9]+", " ", raw).split())
    return key


def same_company(a: str, b: str) -> bool:
    ka, kb = company_key(a), company_key(b)
    if not ka or not kb:
        return False
    return ka == kb or ka.startswith(kb) or kb.startswith(ka)


def resolve_company(
    catalogue_company: str,
    model_company: str,
) -> Tuple[str, str, bool]:
    """
    Decide which company name to trust.

    The MedEx catalogue is authoritative. The vision model's company is a guess
    and is only used when the catalogue has nothing.

    Returns (company, source, verified).
    """
    catalogue_company = (catalogue_company or "").strip()
    model_company = (model_company or "").strip()

    if catalogue_company:
        return catalogue_company, "medex", True
    if model_company and model_company.lower() not in {"unknown", "n/a", "none"}:
        return model_company, "model_guess", False
    return "", "none", False
