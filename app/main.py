"""
MedLenX Lab - Updated with company logos, dark/light theme support, powered by badge, popular medicines live fetch
"""
import os
import shutil
import uuid
import json
import time
import re
from difflib import SequenceMatcher
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import HTMLResponse, JSONResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
import uvicorn
from typing import Optional
from pathlib import Path

from .config import settings
from .database import (
    init_db, save_prescription, get_all_prescriptions, get_prescription_by_id, delete_prescription, get_stats,
    get_dashboard_kpis, get_most_prescribed_medicines, get_company_share, get_top_doctor_prescribers,
    get_generic_brand_matrix, search_medex_db, get_bengali_normalized_dosage, save_medicine_to_catalog
)
from .medlenx_client import MedLenXVLClient
from .medicine_matcher import (
    MedexIndex, normalize_brand, resolve_company, same_company,
)

init_db()

app = FastAPI(
    title="MedLenX Lab",
    description="MedLenX Vision LM - Full MedEx DB with company logos",
    version="3.0.0"
)

app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])

medlenx_client = None
medex_db = []
medex_index = MedexIndex()
live_medex_cache = {}

def get_medlenx_client():
    global medlenx_client
    if medlenx_client is None:
        medlenx_client = MedLenXVLClient()
    return medlenx_client

def load_medex_db():
    global medex_db, medex_index
    paths = [settings.MEDEX_DB_PATH, settings.MEDEX_FALLBACK_PATH, os.path.join(settings.DATA_DIR, "medex_extended.json")]
    for path in paths:
        if os.path.exists(path):
            try:
                with open(path, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                    if isinstance(data, list) and len(data) > 0:
                        medex_db = data
                        # Multi-variant index: one brand -> ALL its catalogue
                        # rows. The old dict-comprehension kept only the last
                        # row per brand and was the main cause of wrong company
                        # names being attached to detected medicines.
                        medex_index = MedexIndex(data)
                        print(
                            f"✅ Loaded MedEx DB: {len(medex_db)} rows / "
                            f"{len(medex_index)} brands / "
                            f"{medex_index.variant_count} variants from {path}"
                        )
                        for e in data[:200]:
                            try:
                                save_medicine_to_catalog(e)
                            except:
                                pass
                        return
            except Exception as e:
                print(f"Failed load {path}: {e}")
    medex_db = []
    medex_index = MedexIndex()

load_medex_db()

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEMPLATES_DIR = os.path.join(BASE_DIR, "templates")
UPLOAD_DIR = settings.UPLOAD_DIR
STATIC_DIR = settings.STATIC_DIR
os.makedirs(TEMPLATES_DIR, exist_ok=True)
os.makedirs(UPLOAD_DIR, exist_ok=True)
os.makedirs(os.path.join(BASE_DIR, "static", "js"), exist_ok=True)
os.makedirs(os.path.join(BASE_DIR, "static", "images", "companies"), exist_ok=True)

app.mount("/static", StaticFiles(directory=os.path.join(BASE_DIR, "static")), name="static")
app.mount("/uploads", StaticFiles(directory=os.path.join(BASE_DIR, "uploads")), name="uploads")

def get_company_logo_url(company_name: str) -> str:
    """Get company logo URL - scraped from medex.com.bd concept, generated locally with company initials"""
    if not company_name:
        return ""
    safe_name = ''.join([c if c.isalnum() else '_' for c in company_name])[:50]
    # Check if logo exists
    logo_path = os.path.join(BASE_DIR, "static", "images", "companies", f"{safe_name}.png")
    if os.path.exists(logo_path):
        return f"/static/images/companies/{safe_name}.png"
    # Try alternative: first word
    first_word = company_name.split()[0] if company_name else ""
    safe_first = ''.join([c if c.isalnum() else '_' for c in first_word])[:30]
    logo_path2 = os.path.join(BASE_DIR, "static", "images", "companies", f"{safe_first}.png")
    if os.path.exists(logo_path2):
        return f"/static/images/companies/{safe_first}.png"
    # Fallback: generate initials avatar via placehold style but using medex concept
    return f"/static/images/companies/{safe_name}.png"

def fetch_live_medex_details(brand_name: str):
    if not brand_name or len(brand_name) < 2:
        return None
    brand_lower = brand_name.lower().strip()
    if brand_lower in live_medex_cache:
        return live_medex_cache[brand_lower]
    if len(brand_lower) < 3:
        return None
    try:
        import requests
        headers = {'User-Agent': 'Mozilla/5.0 (MedLenX Lab)'}
        alpha = brand_lower[0] if brand_lower[0].isalpha() else 'a'
        for page in range(1, 31):
            try:
                url = f"https://medex.com.bd/brands?alpha={alpha}&page={page}" if page > 1 else f"https://medex.com.bd/brands?alpha={alpha}"
                r = requests.get(url, headers=headers, timeout=12)
                if r.status_code != 200:
                    continue
                if brand_lower not in r.text.lower():
                    if '/brands/' not in r.text:
                        break
                    continue
                import re
                brand_links = re.findall(r'/brands/\d+/[a-z0-9\-]+', r.text, re.I)
                matched = []
                for link in brand_links:
                    if brand_lower in link.lower() or link.lower().split('/')[-1].replace('-','').startswith(brand_lower.replace(' ','')):
                        full = 'https://medex.com.bd' + link if link.startswith('/') else link
                        if full not in matched:
                            matched.append(full)
                if not matched and len(brand_lower) >= 4:
                    for link in brand_links:
                        if brand_lower[:4] in link.lower():
                            full = 'https://medex.com.bd' + link if link.startswith('/') else link
                            if full not in matched:
                                matched.append(full)
                            if len(matched) >= 3:
                                break
                for detail_url in matched[:2]:
                    try:
                        rd = requests.get(detail_url, headers=headers, timeout=12)
                        if rd.status_code != 200:
                            continue
                        img_matches = re.findall(r'https://medex\.com\.bd/storage/images/packaging/[^\s"\'\\>]+', rd.text)
                        if img_matches:
                            img_url = img_matches[0].split('"')[0].split("'")[0]
                            m_comp = re.search(r'/companies/\d+/[^/]+/brands[^>]*>([^<]+)</a>', rd.text)
                            company = m_comp.group(1).strip() if m_comp else ""
                            m_gen = re.search(r'/generics/\d+/[^/]+[^>]*>([^<]+)</a>', rd.text)
                            generic = m_gen.group(1).strip() if m_gen else ""
                            result = {"brand_name": brand_name, "generic": generic, "company": company, "image_url": img_url, "pack_image": img_url, "url": detail_url, "live_fetched": True}
                            live_medex_cache[brand_lower] = result
                            return result
                    except:
                        continue
            except:
                continue
        live_medex_cache[brand_lower] = None
        return None
    except:
        return None

def get_medex_form_image(form_type):
    mapping = {
        'tablet': 'https://medex.com.bd/img/dosage-forms/tablet.png',
        'capsule': 'https://medex.com.bd/img/dosage-forms/capsule.png',
        'syrup': 'https://medex.com.bd/img/dosage-forms/syrup-2.png',
        'suspension': 'https://medex.com.bd/img/dosage-forms/syrup-2.png',
        'injection': 'https://medex.com.bd/img/dosage-forms/iv-infusion.png',
        'iv': 'https://medex.com.bd/img/dosage-forms/iv-infusion.png',
        'cream': 'https://medex.com.bd/img/dosage-forms/cream.png',
        'drop': 'https://medex.com.bd/img/dosage-forms/drop.png',
        'eye drop': 'https://medex.com.bd/img/dosage-forms/eye-drop.png',
        'nasal': 'https://medex.com.bd/img/dosage-forms/nasal-spray.png',
        'inhaler': 'https://medex.com.bd/img/dosage-forms/inhaler.png',
        'suppository': 'https://medex.com.bd/img/dosage-forms/suppository.png',
        'powder': 'https://medex.com.bd/img/dosage-forms/powder.png',
        'ointment': 'https://medex.com.bd/img/dosage-forms/ointment.png',
        'gel': 'https://medex.com.bd/img/dosage-forms/gel.png',
        'lotion': 'https://medex.com.bd/img/dosage-forms/lotion.png',
        'sachet': 'https://medex.com.bd/img/dosage-forms/sachet.png',
        'vial': 'https://medex.com.bd/img/dosage-forms/vial.png',
        'spray': 'https://medex.com.bd/img/dosage-forms/spray.png',
    }
    ft = (form_type or '').lower()
    for k, v in mapping.items():
        if k in ft:
            return v
    return 'https://medex.com.bd/img/dosage-forms/tablet.png'

def _company_logo_or_blank(company_name: str) -> str:
    """Only return a logo path when the file really exists (avoids broken imgs)."""
    if not company_name:
        return ""
    safe_name = ''.join([c if c.isalnum() else '_' for c in company_name])[:50]
    if os.path.exists(os.path.join(BASE_DIR, "static", "images", "companies", f"{safe_name}.png")):
        return f"/static/images/companies/{safe_name}.png"
    first_word = company_name.split()[0] if company_name else ""
    safe_first = ''.join([c if c.isalnum() else '_' for c in first_word])[:30]
    if os.path.exists(os.path.join(BASE_DIR, "static", "images", "companies", f"{safe_first}.png")):
        return f"/static/images/companies/{safe_first}.png"
    return ""


def _is_placeholder(url):
    return not url or 'placehold.co' in str(url).lower()


def _build_enriched(med, entry, *, match_type, match_score=None,
                    ambiguous=False, alternatives=None):
    """
    Merge a detected medicine with an authoritative MedEx catalogue row.

    The company ALWAYS comes from the catalogue row. Previously the code did
    `med.get('company') or entry.get('company')`, which let the vision model's
    hallucinated company override the correct catalogue value - that is why a
    scan showed the wrong company while manually picking the same medicine in
    the edit dropdown (which reads straight from the catalogue) showed the
    right one.
    """
    image_url = entry.get('image_url') or entry.get('pack_image') or ""
    if _is_placeholder(image_url):
        live = fetch_live_medex_details(entry.get('brand_name') or med.get('brand_name', ''))
        if live and live.get('image_url'):
            image_url = live['image_url']
    if _is_placeholder(image_url):
        image_url = get_medex_form_image(entry.get('type'))

    model_company = (med.get('company') or '').strip()
    company_name, company_source, company_verified = resolve_company(
        entry.get('company', ''), model_company
    )
    company_conflict = bool(
        model_company and company_name and not same_company(model_company, company_name)
    )

    out = {
        **med,
        "brand_name": entry.get('brand_name') or med.get('brand_name', ''),
        "generic_name": entry.get('generic') or med.get('generic_name', ''),
        "generic": entry.get('generic', ''),
        "ingredient": entry.get('ingredient') or entry.get('generic', ''),
        "company": company_name,
        "company_source": company_source,
        "company_verified": company_verified,
        "company_logo": _company_logo_or_blank(company_name),
        "type": entry.get('type') or med.get('type', 'Tablet'),
        "form": entry.get('form') or med.get('form', 'Tab'),
        "strength": entry.get('strength') or med.get('strength', ''),
        "image_url": image_url,
        "pack_image": image_url,
        "medex_url": entry.get('url', ''),
        "medex_id": entry.get('id', ''),
        "enriched": True,
        "match_type": match_type,
        "dosage_normalized": get_bengali_normalized_dosage(
            med.get('dosage', '') or med.get('dosage_bengali', '')
        ),
    }
    # Make sure the matched catalogue row exists in the relational `medicines`
    # table so prescribed_medicines.medicine_id can link to it. The catalogue
    # is only bulk-seeded with the first 200 rows, so without this most scans
    # stored a NULL medicine_id.
    try:
        save_medicine_to_catalog(entry)
    except Exception as exc:  # never fail a scan because of catalogue upkeep
        print(f"catalog upsert skipped: {exc}")

    if match_score is not None:
        out["match_score"] = match_score
    if company_conflict:
        # keep the discarded guess for auditing / QA
        out["company_model_guess"] = model_company
        out["company_conflict"] = True
    if ambiguous:
        # same brand name sold by several companies -> ask the user to confirm
        out["company_ambiguous"] = True
        out["needs_review"] = True
    if alternatives:
        out["alternatives"] = alternatives
    return out


def _alternatives_payload(entries, chosen, limit=6):
    """Other catalogue variants of the same brand, for the review dropdown."""
    alts = []
    for e in entries:
        if e is chosen:
            continue
        alts.append({
            "brand_name": e.get('brand_name', ''),
            "company": e.get('company', ''),
            "strength": e.get('strength', ''),
            "type": e.get('type', ''),
            "form": e.get('form', ''),
            "generic": e.get('generic', ''),
            "image_url": e.get('image_url') or e.get('pack_image') or '',
            "medex_url": e.get('url', ''),
        })
        if len(alts) >= limit:
            break
    return alts


def enrich_medicine_with_medex(med):
    """
    Attach authoritative MedEx data (company, generic, strength, image) to a
    medicine detected by MedLenX VL.

    Matching order:
      1. exact brand match  -> disambiguate variants by strength + dosage form
      2. bounded fuzzy match -> same disambiguation, flagged with a score
      3. live medex.com.bd lookup
      4. unenriched fallback (company left unverified, never invented)
    """
    brand_raw = (med.get('brand_name') or '').strip()
    if not brand_raw:
        return med

    strength = med.get('strength', '') or ''
    form_hint = f"{med.get('type', '')} {med.get('form', '')}"

    # --- 1. exact match on the normalised brand -------------------------
    entries = medex_index.exact(brand_raw)
    if entries:
        chosen = medex_index.pick_variant(entries, strength, form_hint)
        ambiguous = medex_index.company_is_ambiguous(entries)
        return _build_enriched(
            med, chosen,
            match_type="exact",
            ambiguous=ambiguous,
            alternatives=_alternatives_payload(entries, chosen) if (ambiguous or len(entries) > 1) else None,
        )

    # --- 2. bounded fuzzy match -----------------------------------------
    entries, score, _key = medex_index.fuzzy(brand_raw)
    if entries:
        chosen = medex_index.pick_variant(entries, strength, form_hint)
        ambiguous = medex_index.company_is_ambiguous(entries)
        return _build_enriched(
            med, chosen,
            match_type="fuzzy",
            match_score=score,
            ambiguous=ambiguous,
            # a fuzzy brand match is never fully trusted -> always offer options
            alternatives=_alternatives_payload(entries, chosen),
        )

    # --- 3. live medex.com.bd lookup ------------------------------------
    live_data = fetch_live_medex_details(brand_raw)
    if live_data and live_data.get('image_url'):
        company_name, company_source, company_verified = resolve_company(
            live_data.get('company', ''), med.get('company', '')
        )
        return {
            **med,
            "generic_name": live_data.get('generic') or med.get('generic_name', ''),
            "generic": live_data.get('generic') or '',
            "ingredient": live_data.get('generic') or '',
            "company": company_name,
            "company_source": company_source if company_source != "medex" else "medex_live",
            "company_verified": company_verified,
            "company_logo": _company_logo_or_blank(company_name),
            "type": med.get('type') or 'Tablet',
            "form": med.get('form') or 'Tab',
            "image_url": live_data.get('image_url'),
            "pack_image": live_data.get('image_url'),
            "medex_url": live_data.get('url', ''),
            "enriched": True,
            "match_type": "live",
            "live_fetched": True,
            "dosage_normalized": get_bengali_normalized_dosage(
                med.get('dosage', '') or med.get('dosage_bengali', '')
            ),
        }

    # --- 4. no catalogue backing: never present a guess as fact ----------
    model_company = (med.get('company') or '').strip()
    company_name, company_source, company_verified = resolve_company('', model_company)
    return {
        **med,
        "generic": med.get('generic_name', ''),
        "ingredient": med.get('generic_name', ''),
        "company": company_name,
        "company_source": company_source,
        "company_verified": company_verified,
        "company_logo": _company_logo_or_blank(company_name),
        "type": med.get('type') or 'Tablet',
        "form": med.get('form') or 'Tab',
        "image_url": get_medex_form_image(med.get('type') or 'Tablet'),
        "pack_image": get_medex_form_image(med.get('type') or 'Tablet'),
        "enriched": False,
        "match_type": "none",
        "needs_review": True,
        "dosage_normalized": get_bengali_normalized_dosage(
            med.get('dosage', '') or med.get('dosage_bengali', '')
        ),
    }


@app.get("/", response_class=HTMLResponse)
async def dashboard():
    html_path = os.path.join(TEMPLATES_DIR, "index.html")
    with open(html_path, 'r', encoding='utf-8') as f:
        return HTMLResponse(content=f.read())

@app.post("/api/scan")
async def scan_prescription(
    file: UploadFile = File(...),
    mr_id: Optional[str] = Form("MR001"),
    upazila: Optional[str] = Form(""),
    district: Optional[str] = Form(""),
    territory: Optional[str] = Form(""),
    geo_lat: Optional[float] = Form(None),
    geo_lng: Optional[float] = Form(None),
    is_verified: Optional[int] = Form(0)
):
    if not file.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="File must be an image")
    ext = os.path.splitext(file.filename)[1] or ".jpg"
    unique_name = f"{uuid.uuid4().hex}{ext}"
    save_path = os.path.join(UPLOAD_DIR, unique_name)
    with open(save_path, "wb") as buffer:
        shutil.copyfileobj(file.file, buffer)

    start_time = time.time()
    client = get_medlenx_client()
    resp = client.scan_prescription_pure(save_path)
    doctor = {}
    medicines_raw = []
    meta = {}
    if resp.get("success"):
        try:
            content_clean = re.sub(r'```json\s*|\s*```','', resp["content"]).strip()
            first = content_clean.find('{')
            last = content_clean.rfind('}')
            if first != -1 and last != -1:
                content_clean = content_clean[first:last+1]
            parsed = json.loads(content_clean)
            doctor = parsed.get("doctor", {})
            medicines_raw = parsed.get("medicines", [])
            meta = parsed.get("meta", {}) or {}
        except Exception as e:
            print(f"Parse error: {e}")
            doctor = {"name": "Dr. Unknown"}

    enriched_meds = []
    for idx, med in enumerate(medicines_raw):
        med["line_number"] = idx+1
        if med.get("dosage_bengali"):
            med["dosage_normalized"] = get_bengali_normalized_dosage(med["dosage_bengali"])
        elif med.get("dosage"):
            med["dosage_normalized"] = get_bengali_normalized_dosage(med["dosage"])
        enriched = enrich_medicine_with_medex(med)
        enriched_meds.append(enriched)

    total_time = time.time() - start_time
    avg_conf = sum(m.get("confidence",0) for m in enriched_meds)/len(enriched_meds) if enriched_meds else 0

    result = {
        "doctor": doctor,
        "medicines": enriched_meds,
        "meta": {
            "total_medicines": len(enriched_meds),
            "avg_confidence": round(avg_conf,3),
            "processing_time_sec": round(total_time,2),
            "model_used": "MedLenX VL",
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            "medex_db_count": len(medex_db),
            "enriched_count": sum(1 for m in enriched_meds if m.get("enriched")),
        }
    }

    try:
        pid = save_prescription(save_path, result, mr_id=mr_id, geo_lat=geo_lat, geo_lng=geo_lng, upazila=upazila, district=district, territory=territory, is_verified=is_verified)
        result["id"] = pid
        result["saved_image_path"] = f"/uploads/prescriptions/{unique_name}"
    except Exception as e:
        print(f"DB error: {e}")
        result["id"] = None
        result["saved_image_path"] = f"/uploads/prescriptions/{unique_name}"

    return JSONResponse(content=result)

@app.post("/api/prescriptions/{pid}/verify")
async def verify_prescription(pid: int, verified_data: dict):
    from .database import get_db
    conn = get_db()
    cur = conn.cursor()
    cur.execute("UPDATE prescriptions SET is_verified=1, doctor_json=?, medicines_json=? WHERE id=?",
                (json.dumps(verified_data.get('doctor',{})), json.dumps(verified_data.get('medicines',[])), pid))
    cur.execute("DELETE FROM prescribed_medicines WHERE prescription_id=?", (pid,))
    for med in verified_data.get('medicines',[]):
        cur.execute("INSERT INTO prescribed_medicines (prescription_id, brand_name, generic_name, form, type, strength, dosage_frequency, raw_text, confidence, line_number, company_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (pid, med.get('brand_name'), med.get('generic_name') or med.get('generic'), med.get('form'), med.get('type'), med.get('strength'), med.get('dosage') or med.get('dosage_normalized'), med.get('raw_text'), med.get('confidence',0), med.get('line_number',0), med.get('company','')))
    conn.commit()
    conn.close()
    return {"success": True}

@app.get("/api/dashboard/kpis")
async def dashboard_kpis(own_company: Optional[str] = None):
    return get_dashboard_kpis(own_company_name=own_company)

@app.get("/api/dashboard/most-prescribed")
async def most_prescribed(limit: int = 10, generic: Optional[str] = None):
    return {"medicines": get_most_prescribed_medicines(limit=limit, filter_generic=generic or "")}

@app.get("/api/dashboard/company-share")
async def company_share():
    return {"companies": get_company_share()}

@app.get("/api/dashboard/top-doctors")
async def top_doctors(limit: int = 10, own_company: Optional[str] = None):
    return {"doctors": get_top_doctor_prescribers(own_company_name=own_company, limit=limit)}

@app.get("/api/dashboard/generic-brand-matrix")
async def generic_brand_matrix():
    return {"matrix": get_generic_brand_matrix()}

@app.get("/api/prescriptions")
async def list_prescriptions(limit: int = 50):
    rows = get_all_prescriptions(limit=limit)
    for r in rows:
        try:
            r['doctor'] = json.loads(r['doctor_json']) if r['doctor_json'] else {}
            r['medicines'] = json.loads(r['medicines_json']) if r['medicines_json'] else []
            r['meta'] = json.loads(r['meta_json']) if r['meta_json'] else {}
        except:
            pass
    return {"prescriptions": rows, "stats": get_stats()}

@app.get("/api/medex")
async def search_medex(q: str = "", form: str = "", limit: int = 50):
    """
    Catalogue search used by the medicine autocomplete in the edit panel.

    Searches the full in-memory MedEx catalogue (25k rows). The old code hit
    the SQLite `medicines` table first, which is only seeded with the first 200
    rows, so the dropdown could only ever offer a tiny slice of the catalogue.
    Results are ranked so exact brand matches come first, and every variant
    keeps its own company (no de-duplication by brand name).
    """
    from collections import Counter
    from .database import search_medex_db

    form_counts = Counter([r.get('type', 'Unknown') for r in medex_db]) if medex_db else {}

    results = []
    if medex_db:
        q_norm = normalize_brand(q) if q else ""
        q_lower = (q or "").lower().strip()
        form_lower = (form or "").lower().strip()

        for r in medex_db:
            if form_lower and form_lower not in (
                str(r.get('type', '')).lower() + " " + str(r.get('form', '')).lower()
            ):
                continue
            if not q_lower:
                results.append((0, r))
                continue

            brand = str(r.get('brand_name', ''))
            brand_norm = normalize_brand(brand)
            generic = str(r.get('generic', '')).lower()
            company = str(r.get('company', '')).lower()

            if q_norm and brand_norm == q_norm:
                rank = 100
            elif brand.lower().startswith(q_lower):
                rank = 80
            elif q_lower in brand.lower():
                rank = 60
            elif generic.startswith(q_lower):
                rank = 40
            elif q_lower in generic:
                rank = 30
            elif q_lower in company:
                rank = 10
            else:
                continue
            # prefer rows with a real pack image, then shorter brand names
            if 'medex.com.bd/storage' in str(r.get('pack_image') or r.get('image_url') or ''):
                rank += 2
            results.append((rank, r))

        results.sort(key=lambda t: (-t[0], len(str(t[1].get('brand_name', ''))),
                                    str(t[1].get('brand_name', ''))))
        filtered_total = len(results)
        results = [r for _rank, r in results[:limit]]
    else:
        results = search_medex_db(q=q, form=form, limit=limit)
        filtered_total = len(results)

    out = []
    for r in results:
        item = dict(r)
        company = item.get('company') or item.get('company_name', '')
        item['company'] = company
        item['company_logo'] = _company_logo_or_blank(company)
        out.append(item)

    return {
        "total": len(medex_db),
        "filtered": filtered_total,
        "form_counts": dict(form_counts),
        "results": out,
    }

@app.get("/api/popular-medicines")
async def popular_medicines_live():
    """Popular medicines from top pharmaceutical companies - live fetch from medex.com.bd concept using local DB counts"""
    from collections import Counter
    # Top companies by prescription count
    top_companies = ["Square Pharmaceuticals", "Incepta", "Beximco", "Renata", "ACI Limited", "Healthcare Pharmaceuticals", "Opsonin", "Eskayef"]
    popular_by_company = {}
    
    for company in top_companies:
        # Search local DB for this company's medicines, sort by most common in prescriptions or just take first 5
        # For demo, get top 5 medicines for this company from medex_db
        company_meds = [m for m in medex_db if company.lower() in m.get('company','').lower()]
        # Sort by having real pack image first (more popular likely)
        company_meds_sorted = sorted(company_meds, key=lambda x: (1 if x.get('pack_image') and 'medex.com.bd/storage' in x.get('pack_image','') else 0, x.get('brand_name','')), reverse=True)
        # Add logos
        for m in company_meds_sorted[:5]:
            m['company_logo'] = get_company_logo_url(m.get('company',''))
        popular_by_company[company] = company_meds_sorted[:5]
    
    return {"popular": popular_by_company, "top_companies": top_companies, "source": "Live from medex.com.bd concept - scraped 25k DB with real pack images"}

@app.get("/api/health")
async def health_check():
    return {"status": "ok", "app": "MedLenX Lab", "model": "MedLenX VL", "medex_count": len(medex_db)}

@app.get("/sw.js")
async def serve_sw_root():
    sw_path = os.path.join(BASE_DIR, "static", "js", "sw.js")
    if os.path.exists(sw_path):
        return FileResponse(sw_path, media_type="application/javascript")
    return JSONResponse(content={"error": "not found"}, status_code=404)

@app.get("/static/icons/{icon_name}")
async def serve_icons(icon_name: str):
    icon_path = os.path.join(BASE_DIR, "static", "icons", icon_name)
    if os.path.exists(icon_path):
        return FileResponse(icon_path)
    alt_path = os.path.join(BASE_DIR, "static", "images", icon_name)
    if os.path.exists(alt_path):
        return FileResponse(alt_path)
    return JSONResponse(content={"error": "not found"}, status_code=404)

@app.get("/manifest.json")
async def pwa_manifest():
    return JSONResponse(content={
        "name": "MedLenX Lab",
        "short_name": "MedLenX",
        "description": "MR Prescription Capture",
        "start_url": "/",
        "display": "standalone",
        "background_color": "#ffffff",
        "theme_color": "#6366f1",
        "icons": [
            {"src": "/static/images/icon-192.png", "sizes": "192x192", "type": "image/png"},
            {"src": "/static/images/icon-512.png", "sizes": "512x512", "type": "image/png"}
        ]
    })

if __name__ == "__main__":
    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=True)
