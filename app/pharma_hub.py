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


def _fetch_live_news() -> List[dict]:
    live: List[dict] = []
    # WHO health news RSS — reliable public feed
    xml = _http_get("https://www.who.int/rss-feeds/news-english.xml")
    if xml:
        live.extend(_parse_rss_items(xml, "WHO", "public-health", limit=6))
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


def get_pharma_jobs(category: str = "", q: str = "", location: str = "") -> dict:
    data = _load_json("pharma_jobs.json", {"jobs": []})
    jobs = list(data.get("jobs") or [])
    today = date.today()
    out = []
    q_lower = (q or "").lower().strip()
    cat_lower = (category or "").lower().strip()
    loc_lower = (location or "").lower().strip()
    for job in jobs:
        if cat_lower and cat_lower not in (job.get("category") or "").lower():
            continue
        if loc_lower and loc_lower not in (
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
        out.append(item)
    cats = sorted({j.get("category") for j in jobs if j.get("category")})
    locs = sorted({j.get("location") for j in jobs if j.get("location")})
    return {
        "updated_at": datetime.utcnow().isoformat() + "Z",
        "total": len(out),
        "categories": cats,
        "locations": locs,
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
