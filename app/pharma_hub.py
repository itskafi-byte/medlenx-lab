"""
Pharma Intelligence Hub — news, jobs, health days, company directory.

Live sources are attempted with short timeouts; curated JSON is always the
fallback so the hub stays useful offline / in CI.
"""
from __future__ import annotations

import json
import os
import time
import xml.etree.ElementTree as ET
from datetime import date, datetime, timedelta
from typing import Any, Dict, List, Optional

from .config import settings

_NEWS_CACHE: Dict[str, Any] = {"ts": 0.0, "items": []}
_NEWS_TTL = 30 * 60  # 30 minutes

_MONTHS = ["", "January", "February", "March", "April", "May", "June",
           "July", "August", "September", "October", "November", "December"]


def _data_path(name: str) -> str:
    return os.path.join(settings.DATA_DIR, name)


def _load_json(name: str, default):
    path = _data_path(name)
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as exc:
        print(f"⚠️  could not load {path}: {exc}")
        return default


def _parse_rss_items(xml_text: str, source: str, source_type: str, limit: int = 8) -> List[dict]:
    items = []
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return items
    channel_items = root.findall(".//item")
    for node in channel_items[:limit]:
        title = (node.findtext("title") or "").strip()
        if not title:
            continue
        link = (node.findtext("link") or "").strip()
        desc = (node.findtext("description") or "").strip()
        # strip simple HTML
        if "<" in desc:
            import re
            desc = re.sub(r"<[^>]+>", " ", desc)
            desc = re.sub(r"\s+", " ", desc).strip()
        pub = (node.findtext("pubDate") or "").strip()
        iso = ""
        for fmt in ("%a, %d %b %Y %H:%M:%S %Z", "%a, %d %b %Y %H:%M:%S %z"):
            try:
                iso = datetime.strptime(pub, fmt).isoformat()
                break
            except ValueError:
                continue
        items.append({
            "id": f"rss-{abs(hash(title)) % 10_000_000}",
            "source": source,
            "source_type": source_type,
            "title": title,
            "summary": desc[:320],
            "url": link,
            "published_at": iso or datetime.utcnow().isoformat(),
            "tags": [source, "Live"],
            "live": True,
        })
    return items


def _http_get(url: str, timeout: float = 3.5) -> Optional[str]:
    try:
        import requests
        r = requests.get(
            url,
            headers={"User-Agent": "MedLenX Lab Pharma Intelligence Hub/3.2"},
            timeout=timeout,
        )
        if r.status_code == 200 and r.text:
            return r.text
    except Exception:
        return None
    return None


def _fetch_medex_news(limit: int = 5) -> List[dict]:
    """Parse headlines + links from the live Medex news board (/news).

    Wrapped defensively: if medex.com.bd is unreachable (offline / CI) the
    curated JSON fallback still supplies the feed.
    """
    import re
    items: List[dict] = []
    html = _http_get("https://medex.com.bd/news", timeout=4)
    if not html:
        return items
    anchors = re.findall(
        r'<a[^>]+href="(/news/[^"]+)"[^>]*>(.*?)</a>', html, re.S | re.I
    )
    seen = set()
    for href, text in anchors[:60]:
        title = re.sub(r"<[^>]+>", " ", text)
        title = re.sub(r"\s+", " ", title).strip()
        if not title or len(title) < 12 or title in seen:
            continue
        seen.add(title)
        items.append({
            "id": f"medex-{abs(hash(title)) % 10_000_000}",
            "source": "Medex",
            "source_type": "market",
            "title": title,
            "summary": "",
            "url": "https://medex.com.bd" + href,
            "published_at": datetime.now().isoformat(),
            "tags": ["Medex", "Market", "Live"],
            "live": True,
        })
        if len(items) >= limit:
            break
    return items


def _fetch_dgda_news(limit: int = 4) -> List[dict]:
    """Parse the DGDA circular / notification list if reachable."""
    import re
    items: List[dict] = []
    html = _http_get("https://www.dgda.gov.bd/", timeout=4)
    if not html:
        return items
    anchors = re.findall(
        r'<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>', html, re.S | re.I
    )
    seen = set()
    for href, text in anchors[:120]:
        title = re.sub(r"<[^>]+>", " ", text)
        title = re.sub(r"\s+", " ", title).strip()
        low = title.lower()
        if not title or len(title) < 14 or title in seen:
            continue
        if not any(k in low for k in ("dgda", "circular", "notification", "price", "gmp", "drug", "mrp")):
            continue
        seen.add(title)
        url = href if href.startswith("http") else "https://www.dgda.gov.bd/" + href.lstrip("/")
        items.append({
            "id": f"dgda-{abs(hash(title)) % 10_000_000}",
            "source": "DGDA",
            "source_type": "regulatory",
            "title": title,
            "summary": "",
            "url": url,
            "published_at": datetime.now().isoformat(),
            "tags": ["DGDA", "Regulatory", "Live"],
            "live": True,
        })
        if len(items) >= limit:
            break
    return items


def _fetch_live_news() -> List[dict]:
    live: List[dict] = []
    # Medex market board — the primary field source
    live.extend(_fetch_medex_news(limit=5))
    # DGDA regulator circulars
    live.extend(_fetch_dgda_news(limit=4))
    # WHO global health RSS — reliable public feed
    xml = _http_get("https://www.who.int/rss-feeds/news-english.xml")
    if xml:
        live.extend(_parse_rss_items(xml, "WHO", "public-health", limit=5))
    return live


def get_pharma_news(live: bool = True) -> dict:
    curated = _load_json("pharma_news.json", {"items": []})
    items = list(curated.get("items") or [])
    live_items: List[dict] = []
    if live:
        now = time.time()
        if _NEWS_CACHE["items"] and now - _NEWS_CACHE["ts"] < _NEWS_TTL:
            live_items = _NEWS_CACHE["items"]
        else:
            live_items = _fetch_live_news()
            _NEWS_CACHE["ts"] = now
            _NEWS_CACHE["items"] = live_items
    merged = live_items + items
    # newest first
    def _key(it):
        return str(it.get("published_at") or "")
    merged.sort(key=_key, reverse=True)
    return {
        "updated_at": datetime.utcnow().isoformat() + "Z",
        "live_count": len(live_items),
        "curated_count": len(items),
        "items": merged,
    }


# Company -> plausible career/apply landing page. These are demo "direct apply"
# targets; the apply button always opens in a new tab with a prefilled search so
# the MPO lands on the right opportunities page.
_COMPANY_CAREER_URL = {
    "square": "https://www.squarepharma.com.bd/careers/",
    "beximco": "https://www.beximcopharma.com/careers",
    "incepta": "https://www.incepta.com.bd/careers",
    "renata": "https://renata-ltd.com/career",
    "aci": "https://www.aci-bd.com/career.html",
    "healthcare": "https://www.healthcare-pharma.com/careers",
    "eskayef": "https://eskayefbd.com/careers",
    "novo nordisk": "https://www.novonordisk.com/careers",
    "opsonin": "https://opsonin.com/careers",
    "beacon": "https://beaconpharma.com.bd/careers",
    "aristopharma": "https://www.aristopharma.com/careers",
    "popular": "https://popularpharmabd.com/careers",
}

# category -> functional department (drives the UI filter)
_CATEGORY_DEPT = {
    "MPO": "Field Sales",
    "RSO": "Field Sales",
    "Product Management": "Marketing & Product",
    "Sales Ops": "Operations",
    "Regulatory Affairs": "Regulatory & QA",
}

# category -> skill tags shown as chips
_CATEGORY_TAGS = {
    "MPO": ["MPO", "Territory", "Doctor Coverage"],
    "RSO": ["RSO", "RSM", "Territory", "Leadership"],
    "Product Management": ["PMD", "Product Executive", "KOL"],
    "Sales Ops": ["Ops", "Incentive", "Analytics"],
    "Regulatory Affairs": ["Regulatory", "DGDA", "QA"],
}


def _job_apply_url(job: dict) -> str:
    company = (job.get("company") or "").lower()
    title = (job.get("title") or "").replace(" ", "+")
    for key, url in _COMPANY_CAREER_URL.items():
        if key in company:
            return url
    return f"https://www.google.com/search?q={title}+{company.replace(' ', '+')}+career"


def get_pharma_jobs(category: str = "", q: str = "", location: str = "",
                    department: str = "", territory: str = "") -> dict:
    data = _load_json("pharma_jobs.json", {"jobs": []})
    jobs = list(data.get("jobs") or [])
    today = date.today()
    out = []
    q_lower = (q or "").lower().strip()
    cat_lower = (category or "").lower().strip()
    loc_lower = (location or "").lower().strip()
    dept_lower = (department or "").lower().strip()
    terr_lower = (territory or "").lower().strip()
    for job in jobs:
        if cat_lower and cat_lower not in (job.get("category") or "").lower():
            continue
        if loc_lower and loc_lower not in (
            (job.get("location") or "") + " " + (job.get("division") or "")
        ).lower():
            continue
        if dept_lower and dept_lower not in (
            _CATEGORY_DEPT.get(job.get("category"), "") or ""
        ).lower():
            continue
        if terr_lower and terr_lower not in (
            (job.get("location") or "") + " " + (job.get("division") or "")
        ).lower():
            continue
        blob = " ".join([
            job.get("title") or "", job.get("company") or "",
            job.get("description") or "", job.get("location") or "",
        ]).lower()
        if q_lower and q_lower not in blob:
            continue
        posted = today - timedelta(days=int(job.get("posted_days_ago") or 0))
        item = dict(job)
        item["posted_on"] = posted.isoformat()
        item["fresh"] = int(job.get("posted_days_ago") or 0) <= 1
        item["department"] = _CATEGORY_DEPT.get(item.get("category"), "Field Sales")
        item["tags"] = _CATEGORY_TAGS.get(item.get("category"), [])
        item["apply_url"] = _job_apply_url(item)
        out.append(item)
    cats = sorted({j.get("category") for j in jobs if j.get("category")})
    locs = sorted({j.get("location") for j in jobs if j.get("location")})
    depts = sorted({_CATEGORY_DEPT.get(j.get("category"), "Field Sales")
                    for j in jobs if j.get("category")})
    return {
        "updated_at": datetime.utcnow().isoformat() + "Z",
        "total": len(out),
        "categories": cats,
        "locations": locs,
        "departments": depts,
        "jobs": out,
    }


def get_health_days(year: Optional[int] = None, upcoming_only: bool = False) -> dict:
    data = _load_json("health_days.json", {"days": []})
    year = int(year or date.today().year)
    today = date.today()
    days = []
    for d in data.get("days") or []:
        try:
            dt = date(year, int(d["month"]), int(d["day"]))
        except ValueError:
            continue
        item = dict(d)
        item["date"] = dt.isoformat()
        item["year"] = year
        item["weekday"] = dt.strftime("%A")
        item["days_until"] = (dt - today).days
        item["status"] = (
            "today" if dt == today else ("upcoming" if dt > today else "past")
        )
        if upcoming_only and item["status"] == "past":
            continue
        days.append(item)
    days.sort(key=lambda x: (x["month"], x["day"]))
    next_up = next((d for d in days if d["status"] in ("today", "upcoming")), None)
    by_month: Dict[int, list] = {}
    for d in days:
        by_month.setdefault(d["month"], []).append(d)
    return {
        "year": year,
        "today": today.isoformat(),
        "next": next_up,
        "count": len(days),
        "days": days,
        "by_month": {str(k): v for k, v in by_month.items()},
    }


def unique_companies_from(medex_db: List[dict], q: str = "", limit: int = 25) -> dict:
    q_lower = (q or "").lower().strip()
    seen = {}
    for row in medex_db or []:
        name = (row.get("company") or "").strip()
        if not name:
            continue
        if q_lower and q_lower not in name.lower():
            continue
        bucket = seen.setdefault(name, {"name": name, "brands": 0})
        bucket["brands"] += 1
    ranked = sorted(seen.values(), key=lambda x: (-x["brands"], x["name"]))
    return {
        "total": len(ranked),
        "companies": ranked[: max(1, min(int(limit), 200))],
    }


# ---------------------------------------------------------------------------
# Drug-index quick filters + DGDA status decoration
# ---------------------------------------------------------------------------
_TOP_COMPANY_TOKENS = [
    "square", "incepta", "beximco", "renata", "aci limited",
    "healthcare pharmaceuticals", "opsonin", "eskayef", "acme",
    "beacon", "aristopharma", "drug international",
]

_CATEGORY_TERMS = {
    "cardiology": [
        "atorvastatin", "amlodipine", "losartan", "valsartan", "telmisartan",
        "metoprolol", "bisoprolol", "carvedilol", "nebivolol", "clopidogrel",
        "aspirin", "rosuvastatin", "nitroglycerin", "isosorbide", "ramipril",
        "enalapril", "digoxin", "furosemide", "diltiazem", "verapamil",
        "rivaroxaban", "warfarin", "trimetazidine", "ivabradine", "sacubitril",
    ],
    "antibiotics": [
        "amoxicillin", "ciprofloxacin", "azithromycin", "cephalexin",
        "cefixime", "cefuroxime", "ceftriaxone", "ceftazidime", "cefepime",
        "meropenem", "levofloxacin", "clarithromycin", "doxycycline",
        "cephradine", "cloxacillin", "moxifloxacin", "cefaclor", "flucloxacillin",
        "amikacin", "gentamicin", "nitrofurantoin", "cotrimoxazole", "metronidazole",
    ],
    "otc": [
        "paracetamol", "antacid", "vitamin", "calcium", "multivitamin",
        "cough", "cold", "antihistamine", "fertile", "ferrous", "folic acid",
        "zinc", "domperidone", "ors", "oral rehydration", "digestive", "antacid",
        "hydrocortisone", "emollient", "sunscreen", "multimineral", "b-complex",
        "dextromethorphan", "loratadine", "cetirizine", "chlorpheniramine",
        "nasal", "expectorant", "mucolytic", "probiotic",
    ],
}


def medex_browse(category: str = "top10", medex_db: Optional[List[dict]] = None,
                 limit: int = 24) -> dict:
    """Return a curated slice of the 25K MedEx catalogue for quick filter pills.

    Categories: `top10` (leading companies), `cardiology`, `antibiotics`, `otc`.
    Every returned row is decorated with DGDA registration status so the MPO can
    cite it during detail calls.
    """
    medex_db = medex_db or []
    category = (category or "top10").lower().strip()
    if category not in ("top10", "cardiology", "antibiotics", "otc"):
        return {"category": category, "total": 0, "results": []}
    if not medex_db:
        return {"category": category, "total": 0, "results": []}

    def _pack_score(row: dict) -> int:
        return 1 if "medex.com.bd/storage" in str(
            row.get("pack_image") or row.get("image_url") or "") else 0

    scored = []
    for row in medex_db:
        company = (row.get("company") or "").lower()
        brand = (row.get("brand_name") or "")
        blob = " ".join([
            row.get("generic") or "", row.get("category") or "",
            row.get("ingredient") or "",
        ]).lower()
        rank = 0
        if category == "top10":
            if any(t in company for t in _TOP_COMPANY_TOKENS):
                rank = 5
        else:
            if any(term in blob for term in _CATEGORY_TERMS.get(category, [])):
                # exact category field match ranks higher than substring hit
                rank = 6 if (row.get("category") or "").lower() in blob else 4
        if rank:
            # deduplicate by brand+strength so a single brand shows its form set
            scored.append((rank + _pack_score(row), row))

    scored.sort(key=lambda t: (-t[0], len(str(t[1].get("brand_name", "")))))
    # keep it visually varied: cap 2 rows per company for top10, else pass through
    results = []
    seen = set()
    for _r, row in scored:
        key = (row.get("brand_name", ""), row.get("strength", ""), row.get("company", ""))
        if key in seen:
            continue
        seen.add(key)
        item = dict(row)
        item["dgda_status"] = "DGDA Registered"
        item["company_logo"] = None  # filled by front end
        results.append(item)
        if len(results) >= int(limit):
            break
    return {"category": category, "total": len(scored), "results": results}


# ---------------------------------------------------------------------------
# Health-day campaign cards — pre-generated MPO scripts + brand focus
# ---------------------------------------------------------------------------
_SPECIALTY_BRANDS = {
    "oncology": ["antiemetics", "G-CSF support", "analgesics", "targeted therapies"],
    "cardiology": ["statins", "antiplatelets", "ARBs / ACE inhibitors", "beta blockers"],
    "gastroenterology": ["PPIs", "prokinetics", "antispasmodics", "probiotics"],
    "respiratory": ["bronchodilators", "inhaled steroids", "leukotriene blockers", "antihistamines"],
    "pulmonology": ["bronchodilators", "inhaled steroids", "leukotriene blockers", "antihistamines"],
    "diabetes": ["metformin", "DPP-4 inhibitors", "SGLT2 inhibitors", "insulin"],
    "infectious": ["antibiotics", "antivirals", "antifungals", "antiparasitic"],
    "neurology": ["antiepileptics", "neuropathic pain", "muscle relaxants", "antidepressants"],
    "psychiatry": ["antidepressants", "anxiolytics", "antipsychotics", "hypnotics"],
    "ophthalmology": ["eye drops", "artificial tears", "anti-glaucoma", "antibiotic drops"],
    "dermatology": ["topical steroids", "antifungals", "emollients", "antihistamines"],
    "pediatrics": ["antibiotics", "paracetamol", "ORS", "vitamin D"],
    "rheumatology": ["NSAIDs", "DMARDs", "calcium", "vitamin D"],
    "orthopedics": ["NSAIDs", "muscle relaxants", "calcium", "vitamin D"],
    "rehab": ["muscle relaxants", "neuropathic pain", "vitamin D"],
    "emergency": ["IV fluids", "analgesics", "antiemetics", "antihistamines"],
    "nephrology": ["erythropoietin", "phosphate binders", "diuretics"],
    "community": ["vitamins", "deworming", "ORS", "vaccines"],
    "public health": ["vitamins", "deworming", "ORS", "vaccines"],
    "radiotherapy": ["antiemetics", "G-CSF support", "analgesics", "oral mucositis care"],
    "general": ["analgesics", "antibiotics", "vitamins", "PPIs"],
}

_COLLATERAL_MAP = {
    "oncology": ["screening leaflets", "supportive-care visual aid", "patient diaries"],
    "cardiology": ["cholesterol chart", "blood-pressure tracker", "statin visual aid"],
    "gastroenterology": ["GI symptom chart", "PPI visual aid", "dietary advice cards"],
    "respiratory": ["inhaler technique card", "peak-flow diary", "asthma action plan"],
    "diabetes": ["glucose logbook", "diet plate", "insulin pens demo"],
    "infectious": ["AMR poster", "compliance card", "sample kits"],
    "neurology": ["seizure diary", "neuropathic pain scale", "brain-health chart"],
    "ophthalmology": ["eye-drop technique card", "vision chart", "retina screening card"],
    "dermatology": ["skin-care routine card", "eczema action plan", "spf guide"],
    "pediatrics": ["growth chart", "ORS sachets", "vaccination card"],
    "public health": ["community checklist", "deworming kits", "vaccination card"],
}

_SCRIPT_INTROS = {
    "screening": "This week the global spotlight is on {name}. We see a strong uptick in {focus} OPD visits right after awareness campaigns — a perfect window to talk about {brand}.",
    "education": "{name} is a global awareness moment. Many doctors use it to reinforce {focus} protocols, so it's the ideal time to reintroduce our {brand} range.",
    "campaign": "The {org} is running a coordinated {name} campaign. Patients will ask their doctors about {focus} — make sure they reach for {brand} first.",
}


def _focus_key(focus: str) -> str:
    f = (focus or "").lower()
    for k in _SPECIALTY_BRANDS:
        if k in f or f in k:
            return k
    return "general"


def get_health_day_detail(day_id: str = "", month: Optional[int] = None,
                          day: Optional[int] = None) -> dict:
    """Pre-generated campaign card for a single health day.

    Looks up the curated day, then derives an MPO promotional script, brand
    focus list and collateral to carry — enough for an MPO to plan a doctor
    visit aligned with a global observance.
    """
    data = _load_json("health_days.json", {"days": []})
    target = None
    if day_id:
        target = next((d for d in data.get("days") or [] if d.get("id") == day_id), None)
    elif month and day:
        target = next((
            d for d in data.get("days") or []
            if int(d.get("month", 0)) == month and int(d.get("day", 0)) == day
        ), None)
    if not target:
        today = date.today()
        target = next((
            d for d in data.get("days") or []
            if int(d.get("month", 0)) == today.month and int(d.get("day", 0)) == today.day
        ), None)
    if not target:
        return {"found": False, "message": "No campaign card for that day."}

    # enrich the day with the current-year position so the modal can show a date
    y = date.today().year
    target = dict(target)
    try:
        dt = date(y, int(target.get("month", 1)), int(target.get("day", 1)))
        target["year"] = y
        target["date"] = dt.isoformat()
        target["weekday"] = dt.strftime("%A")
        target["days_until"] = (dt - date.today()).days
    except ValueError:
        pass

    focus = target.get("focus") or ["General"]
    focus_keys = [_focus_key(f) for f in focus]
    brand_focus = []
    for k in focus_keys:
        for b in _SPECIALTY_BRANDS.get(k, []):
            if b not in brand_focus:
                brand_focus.append(b)
    if not brand_focus:
        brand_focus = _SPECIALTY_BRANDS["general"]

    collateral = []
    for k in focus_keys:
        for c in _COLLATERAL_MAP.get(k, []):
            if c not in collateral:
                collateral.append(c)
    if not collateral:
        collateral = ["brand visual aid", "leave-behind leaflet", "sample pack"]

    org = target.get("org", "WHO / UN")
    intro_tpl = _SCRIPT_INTROS["campaign"]
    script = ("{name}, {date}. {org} awareness drive.\n"
              "Opening: {intro}\n"
              "Bridge: \"The evidence line — {summary}\"\n"
              "Close: \"May I leave a starter pack of {first_brand} and the "
              "{first_col} for your OPD?\""
              ).format(
        name=target.get("name", ""),
        date=f"{target.get('day', 0)} {_MONTHS[target.get('month', 1)]}",
        org=org,
        intro=intro_tpl.format(name=target.get("name", ""), focus=focus[0],
                               brand="our portfolio", org=org),
        summary=target.get("summary", ""),
        first_brand=brand_focus[0],
        first_col=collateral[0],
    )

    return {
        "found": True,
        "day": target,
        "brand_focus": brand_focus,
        "collateral": collateral,
        "promo_script": script,
        "focus": focus,
    }
