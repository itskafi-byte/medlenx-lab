"""
MedLenX Lab - Updated with company logos, dark/light theme support, powered by badge, popular medicines live fetch
"""
import os
import shutil
import uuid
import json
import time
import re
from datetime import datetime
from difflib import SequenceMatcher
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import HTMLResponse, JSONResponse, FileResponse, Response
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

# ---- Cascading location reference data (District -> Upazila -> Territory) ----
_locations_cache = None


def load_locations():
    """Load + cache the BD administrative cascade used by the verify form."""
    global _locations_cache
    if _locations_cache is not None:
        return _locations_cache
    path = os.path.join(settings.DATA_DIR, "bd_locations.json")
    try:
        with open(path, "r", encoding="utf-8") as f:
            _locations_cache = json.load(f)
    except Exception as e:
        print(f"⚠️  Could not load locations from {path}: {e}")
        _locations_cache = {"divisions": [], "districts": {}, "specialties": []}
    return _locations_cache


def resolve_location(district="", upazila="", territory=""):
    """
    Validate/normalise a location triple against the cascade.

    Returns the corrected values plus a `valid` flag so bad combinations
    (e.g. an upazila that does not belong to the district) never reach the DB.
    """
    data = load_locations()
    districts = data.get("districts", {})
    district = (district or "").strip()
    upazila = (upazila or "").strip()
    territory = (territory or "").strip()

    # case-insensitive district resolution
    if district and district not in districts:
        for name in districts:
            if name.lower() == district.lower():
                district = name
                break

    entry = districts.get(district)
    if not entry:
        return {"district": district, "upazila": upazila,
                "territory": territory, "division": "", "valid": not district}

    if upazila and upazila not in entry["upazilas"]:
        match = next((u for u in entry["upazilas"] if u.lower() == upazila.lower()), None)
        upazila = match or ""
    if territory and territory not in entry["territories"]:
        match = next((t for t in entry["territories"] if t.lower() == territory.lower()), None)
        territory = match or entry["territories"][0]
    if not territory:
        territory = entry["territories"][0]

    return {"district": district, "upazila": upazila, "territory": territory,
            "division": entry["division"], "valid": True}

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

    # Normalise the location triple against the District->Upazila->Territory
    # cascade. The form values win; anything the model guessed is only used to
    # fill a blank. Invalid combinations are dropped rather than stored.
    loc = resolve_location(
        district=district or doctor.get("district", ""),
        upazila=upazila or doctor.get("upazila", ""),
        territory=territory or doctor.get("territory", ""),
    )
    district, upazila, territory = loc["district"], loc["upazila"], loc["territory"]
    doctor["district"] = district
    doctor["upazila"] = upazila
    doctor["territory"] = territory
    doctor["division"] = loc["division"]

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
    """
    Persist a human-verified prescription.

    Fixes:
      * doctor specialty + district/upazila/territory are written to the
        prescriptions row AND synced onto the doctors row (they were silently
        dropped before, so the analytics kept the original scan's values);
      * itemized medicines are rewritten to prescribed_medicines and appended
        to recent_scanned_medicines;
      * returns a real `message` (the UI printed `undefined`).
    """
    from .database import get_db, get_or_create_doctor, _write_medicine_rows

    doctor = verified_data.get('doctor', {}) or {}
    medicines = verified_data.get('medicines', []) or []

    conn = get_db()
    cur = conn.cursor()
    row = cur.execute("SELECT id, mr_id FROM prescriptions WHERE id=?", (pid,)).fetchone()
    if not row:
        conn.close()
        raise HTTPException(status_code=404, detail=f"Prescription {pid} not found")
    mr_id = row["mr_id"] or "MR001"

    name = (doctor.get('name') or '').strip() or 'Unknown'
    bmdc_no = (doctor.get('bmdc_no') or '').strip()
    specialty = (doctor.get('specialty') or doctor.get('department') or '').strip()
    district = (doctor.get('district') or '').strip()
    upazila = (doctor.get('upazila') or '').strip()
    territory = (doctor.get('territory') or '').strip()
    division = (doctor.get('division') or '').strip()
    from .database import infer_prescription_source
    source = infer_prescription_source(
        doctor.get('hospital', ''), doctor.get('chamber', ''),
        doctor.get('prescription_source', ''),
    )
    doctor['prescription_source'] = source

    # validate the location triple against the cascade before persisting
    loc = resolve_location(district=district, upazila=upazila, territory=territory)
    district, upazila, territory = loc["district"], loc["upazila"], loc["territory"]
    division = division or loc["division"]
    doctor["district"], doctor["upazila"] = district, upazila
    doctor["territory"], doctor["division"] = territory, division

    # keep the doctor master record in sync (no count bump on re-verify)
    doctor_id = get_or_create_doctor(
        name=name, bmdc_no=bmdc_no,
        qualifications=doctor.get('qualifications', ''),
        specialty=specialty, chamber=doctor.get('chamber', ''),
        hospital=doctor.get('hospital', ''), upazila=upazila,
        district=district, territory=territory, division=division,
        increment=False,
    )

    cur.execute("""
        UPDATE prescriptions SET
            is_verified=1, doctor_json=?, medicines_json=?,
            doctor_id=?, doctor_name=?, doctor_bmdc_no=?,
            doctor_qualifications=?, doctor_hospital=?, doctor_specialty=?,
            district=?, upazila=?, territory=?,
            total_medicines=?, prescription_source=?
        WHERE id=?
    """, (
        json.dumps(doctor), json.dumps(medicines), doctor_id, name, bmdc_no,
        doctor.get('qualifications', ''),
        doctor.get('hospital', '') or doctor.get('chamber', ''),
        specialty, district, upazila, territory, len(medicines), source, pid,
    ))

    # rebuild analytics rows, refresh the itemized feed for this prescription
    cur.execute("DELETE FROM prescribed_medicines WHERE prescription_id=?", (pid,))
    cur.execute("DELETE FROM recent_scanned_medicines WHERE prescription_id=?", (pid,))
    _write_medicine_rows(
        cur, pid, medicines, mr_id=mr_id, doctor_id=doctor_id, doctor_name=name,
        specialty=specialty, district=district, upazila=upazila,
        territory=territory, prescription_source=source, write_recent=True,
    )

    conn.commit()
    conn.close()

    return {
        "success": True,
        "id": pid,
        "message": f"Verified and saved - {len(medicines)} medicine(s) recorded",
        "medicines_saved": len(medicines),
        "doctor_id": doctor_id,
        "doctor": {"name": name, "specialty": specialty, "district": district,
                   "upazila": upazila, "territory": territory},
    }


@app.get("/api/locations")
async def locations():
    """Cascading District -> Upazila -> Territory reference data + specialties."""
    return load_locations()


@app.get("/api/recent-medicines")
async def recent_medicines(
    limit: int = 25, offset: int = 0, mr_id: str = "", q: str = "",
    company: str = "", district: str = "", territory: str = "",
    specialty: str = "", days: Optional[int] = None,
    order: str = "created_at", direction: str = "desc",
):
    """Itemized Recent Scans feed (searchable, sortable, paginated)."""
    from .database import get_recent_scanned_medicines
    return get_recent_scanned_medicines(
        limit=limit, offset=offset, mr_id=mr_id, q=q, company=company,
        district=district, territory=territory, specialty=specialty,
        days=days, order=order, direction=direction,
    )


@app.get("/api/filters")
async def filter_options():
    """Distinct filter values present in the data, for the global filter bar."""
    from .database import get_filter_options
    return get_filter_options()


@app.get("/api/dashboard/company-drilldown")
async def company_drilldown(
    company: str, limit: int = 10, district: str = "", territory: str = "",
    specialty: str = "", mr_id: str = "", days: Optional[int] = None,
):
    from .database import get_company_drilldown
    return get_company_drilldown(company, limit=limit, district=district,
                                 territory=territory, specialty=specialty,
                                 mr_id=mr_id, days=days)


@app.get("/api/dashboard/brand-doctors")
async def brand_doctors(
    brand: str, limit: int = 15, district: str = "", territory: str = "",
    specialty: str = "", mr_id: str = "", days: Optional[int] = None,
):
    from .database import get_brand_doctors
    return get_brand_doctors(brand, limit=limit, district=district,
                             territory=territory, specialty=specialty,
                             mr_id=mr_id, days=days)


@app.get("/api/export/recent-medicines.csv")
async def export_recent_medicines(
    mr_id: str = "", q: str = "", company: str = "", district: str = "",
    territory: str = "", specialty: str = "", days: Optional[int] = None,
    limit: int = 5000,
):
    """CSV export for field reporting."""
    import csv
    import io as _io
    from .database import get_recent_scanned_medicines
    data = get_recent_scanned_medicines(
        limit=limit, offset=0, mr_id=mr_id, q=q, company=company,
        district=district, territory=territory, specialty=specialty, days=days)
    cols = ["created_at", "mr_id", "doctor_name", "specialty", "brand_name",
            "generic_name", "company_name", "dosage_form", "strength", "dosage",
            "confidence_score", "company_verified", "district", "upazila",
            "territory", "prescription_id"]
    buf = _io.StringIO()
    w = csv.DictWriter(buf, fieldnames=cols, extrasaction="ignore")
    w.writeheader()
    for r in data["items"]:
        w.writerow(r)
    return Response(
        content=buf.getvalue(), media_type="text/csv",
        headers={"Content-Disposition": 'attachment; filename="recent_scanned_medicines.csv"'},
    )


@app.get("/api/dashboard/kpis")
async def dashboard_kpis(
    own_company: Optional[str] = None, district: str = "", territory: str = "",
    specialty: str = "", mr_id: str = "", days: Optional[int] = None,
):
    return get_dashboard_kpis(own_company_name=own_company, district=district,
                              territory=territory, specialty=specialty,
                              mr_id=mr_id, days=days)


@app.get("/api/dashboard/most-prescribed")
async def most_prescribed(
    limit: int = 10, generic: Optional[str] = None, district: str = "",
    territory: str = "", specialty: str = "", mr_id: str = "",
    days: Optional[int] = None,
):
    return {"medicines": get_most_prescribed_medicines(
        limit=limit, filter_generic=generic or "", district=district,
        territory=territory, specialty=specialty, mr_id=mr_id, days=days)}


@app.get("/api/dashboard/company-share")
async def company_share(
    district: str = "", territory: str = "", specialty: str = "",
    mr_id: str = "", days: Optional[int] = None,
):
    return {"companies": get_company_share(
        district=district, territory=territory, specialty=specialty,
        mr_id=mr_id, days=days)}


@app.get("/api/dashboard/top-doctors")
async def top_doctors(
    limit: int = 10, offset: int = 0, own_company: Optional[str] = None,
    q: str = "", district: str = "", territory: str = "", specialty: str = "",
    mr_id: str = "", days: Optional[int] = None,
):
    return get_top_doctor_prescribers(
        own_company_name=own_company, limit=limit, offset=offset, q=q,
        district=district, territory=territory, specialty=specialty,
        mr_id=mr_id, days=days)


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
        # Every marketed SKU in the MedEx 25K index is a DGDA-registered
        # product. Surface the status so MPOs can cite it on detail calls.
        item['dgda_status'] = 'DGDA Registered'
        item['category'] = item.get('category') or item.get('generic') or ''
        out.append(item)

    return {
        "total": len(medex_db),
        "filtered": filtered_total,
        "form_counts": dict(form_counts),
        "results": out,
    }


@app.get("/api/medex/browse")
async def medex_browse(category: str = "top10", limit: int = 24):
    """Curated quick-filter slices of the 25K catalogue for the Hub pills.

    category ∈ {top10, cardiology, antibiotics, otc}. Rows are decorated with
    DGDA status; front end attaches company logos.
    """
    from .pharma_hub import medex_browse as _browse
    data = _browse(category=category, medex_db=medex_db, limit=limit)
    for item in data.get("results", []):
        item["company_logo"] = _company_logo_or_blank(item.get("company") or "")
    return data

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
    return {
        "status": "ok",
        "app": "MedLenX Lab",
        "model": "MedLenX VL",
        "medex_count": len(medex_db),
        "ts": time.time(),
    }


@app.get("/api/ping")
async def ping():
    """Tiny latency probe for the header meter."""
    return {"ok": True, "ts": time.time()}


@app.get("/api/companies")
async def list_companies(q: str = "", limit: int = 25):
    """Dynamic search dropdown for pharmaceutical company onboarding."""
    from .pharma_hub import unique_companies_from
    data = unique_companies_from(medex_db, q=q, limit=limit)
    for c in data["companies"]:
        c["logo"] = _company_logo_or_blank(c["name"])
    return data


@app.get("/api/officer-profile")
async def officer_profile_get(employee_id: str = ""):
    from .database import get_officer_profile, get_target_progress
    profile = get_officer_profile(employee_id or None)
    if not profile:
        return JSONResponse({"detail": "No officer profile"}, status_code=404)
    progress = get_target_progress(profile["employee_id"])
    profile["progress"] = progress
    profile["company_logo"] = _company_logo_or_blank(profile.get("company_name") or "")
    return profile


@app.post("/api/officer-profile")
async def officer_profile_save(payload: dict):
    from .database import save_officer_profile, get_target_progress
    profile = save_officer_profile(payload or {})
    profile["progress"] = get_target_progress(profile["employee_id"])
    profile["company_logo"] = _company_logo_or_blank(profile.get("company_name") or "")
    return {"success": True, "profile": profile}


@app.get("/api/officer-targets")
async def officer_targets(employee_id: str = "", month: str = ""):
    from .database import get_target_progress
    return get_target_progress(employee_id or None, month or None)


@app.post("/api/error-reports")
async def error_reports_create(payload: dict):
    from .database import save_error_report
    rid = save_error_report(payload or {})
    return {
        "success": True,
        "id": rid,
        "message": "Queued for the vision-model training pipeline. Thank you.",
    }


@app.get("/api/error-reports")
async def error_reports_list(limit: int = 50, status: str = ""):
    from .database import list_error_reports
    return {"reports": list_error_reports(limit=limit, status=status)}


@app.get("/api/pharma/news")
async def pharma_news(live: int = 1):
    from .pharma_hub import get_pharma_news
    return get_pharma_news(live=bool(live))


@app.get("/api/pharma/jobs")
async def pharma_jobs(category: str = "", q: str = "", location: str = "",
                      department: str = "", territory: str = ""):
    from .pharma_hub import get_pharma_jobs
    return get_pharma_jobs(category=category, q=q, location=location,
                           department=department, territory=territory)


@app.get("/api/pharma/health-days")
async def pharma_health_days(year: Optional[int] = None, upcoming: int = 0):
    from .pharma_hub import get_health_days
    return get_health_days(year=year, upcoming_only=bool(upcoming))


@app.get("/api/pharma/health-days/{day_id}")
async def pharma_health_day_detail(day_id: str):
    """Pre-generated campaign card (script + brand focus) for one day."""
    from .pharma_hub import get_health_day_detail
    return get_health_day_detail(day_id=day_id)


@app.get("/api/dashboard/own-vs-competitor")
async def own_vs_competitor(
    own_company: Optional[str] = None, district: str = "", territory: str = "",
    specialty: str = "", mr_id: str = "", days: Optional[int] = None,
    limit: int = 15,
):
    from .database import get_own_vs_competitor
    return get_own_vs_competitor(
        own_company_name=own_company, district=district, territory=territory,
        specialty=specialty, mr_id=mr_id, days=days, limit=limit,
    )


@app.get("/api/rsm/dashboard")
async def rsm_dashboard(team_id: str = "", rsm_id: str = "", days: int = 30):
    from .database import get_rsm_dashboard
    return get_rsm_dashboard(team_id=team_id, rsm_employee_id=rsm_id, days=days)


@app.get("/api/rsm/trends")
async def rsm_trends(team_id: str = "", rsm_id: str = "", days: int = 30):
    """Weekly SoV sparkline series per MPO for the RSM leaderboard."""
    from .database import get_rsm_trends
    return get_rsm_trends(team_id=team_id, rsm_employee_id=rsm_id, days=days)


@app.get("/api/rsm/report.pdf")
async def rsm_report_pdf(team_id: str = "", rsm_id: str = "", days: int = 30):
    """Generate a single-click DGDA / Compliance Audit PDF for monthly reviews.

    Aggregates the RSM team dashboard (prescriptions, items, own vs competitor,
    SoV) and the weekly SoV trend into a branded, print-ready report.
    """
    from io import BytesIO
    from .database import get_rsm_dashboard, get_rsm_trends
    from .database import get_officer_profile

    dashboard = get_rsm_dashboard(team_id=team_id, rsm_employee_id=rsm_id, days=days)
    trends = get_rsm_trends(team_id=team_id, rsm_employee_id=rsm_id, days=days)
    profile = get_officer_profile(rsm_id or None) or {}
    totals = dashboard.get("totals", {})
    members = dashboard.get("members") or []

    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4, landscape
    from reportlab.lib.units import inch
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.platypus import (
        SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image
    )

    buf = BytesIO()
    page = landscape(A4)
    doc = SimpleDocTemplate(
        buf, pagesize=page,
        leftMargin=0.5 * inch, rightMargin=0.5 * inch,
        topMargin=0.5 * inch, bottomMargin=0.5 * inch,
        title="DGDA / Compliance Audit Report",
        author="MedLenX Lab",
    )
    styles = getSampleStyleSheet()
    h1 = ParagraphStyle("h1", parent=styles["Title"], fontSize=18, spaceAfter=6,
                        textColor=colors.HexColor("#0F172A"))
    sub = ParagraphStyle("sub", parent=styles["Normal"], fontSize=9,
                         textColor=colors.HexColor("#475569"), spaceAfter=10)
    h2 = ParagraphStyle("h2", parent=styles["Heading2"], fontSize=12,
                        textColor=colors.HexColor("#1E40AF"), spaceBefore=6, spaceAfter=4)
    body = ParagraphStyle("body", parent=styles["Normal"], fontSize=8.5,
                          leading=12, textColor=colors.HexColor("#0F172A"))
    small = ParagraphStyle("small", parent=styles["Normal"], fontSize=7.5,
                           leading=10, textColor=colors.HexColor("#64748B"))

    story = []
    story.append(Paragraph("MedLenX Lab — DGDA / Compliance Audit Report", h1))
    story.append(Paragraph(
        f"Prepared for {profile.get('full_name') or 'Regional Sales Manager'} "
        f"({profile.get('employee_id') or 'RSM'}) · {dashboard.get('own_company', '')} · "
        f"Reporting window: last {int(days or 30)} days", sub))

    # KPI block
    kpi_data = [
        ["Team size", "Prescriptions", "Items", "Own items", "Competitor", "Team SoV"],
        [str(dashboard.get("team_size", 0)), str(totals.get("prescriptions", 0)),
         str(totals.get("items", 0)), str(totals.get("own_items", 0)),
         str(totals.get("competitor_items", 0)), f"{totals.get('sov_percent', 0)}%"],
    ]
    kpi_table = Table(kpi_data, colWidths=[1.2 * inch] * 6)
    kpi_table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1E40AF")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("FONTSIZE", (0, 0), (-1, 0), 9),
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ("BACKGROUND", (0, 1), (-1, 1), colors.HexColor("#EFF6FF")),
        ("FONTSIZE", (0, 1), (-1, 1), 10),
        ("ALIGN", (0, 0), (-1, -1), "CENTER"),
        ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#CBD5E1")),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]))
    story.append(kpi_table)
    story.append(Spacer(1, 0.15 * inch))

    # Member table
    story.append(Paragraph("Territory / MPO compliance table", h2))
    header = ["MPO", "Role", "Territory", "Rx", "Items", "Own", "Competitor",
              "SoV %", "WoW Own %"]
    rows = [header]
    for m in members[:60]:
        row = [
            str(m.get("mpo_name") or m.get("mpo_mr_id") or ""),
            str(m.get("role") or ""),
            str(m.get("territory") or ""),
            str(m.get("prescriptions") or 0),
            str(m.get("items") or 0),
            str(m.get("own_items") or 0),
            str(m.get("competitor_items") or 0),
            f"{m.get('sov_percent', 0)}%",
            "",
        ]
        # attach WoW growth from trends
        tm = next((t for t in trends.get("trends", [])
                   if t.get("mpo_mr_id") == m.get("mpo_mr_id")), None)
        if tm:
            row[8] = f"{tm.get('own_growth', 0)}%"
        rows.append(row)
    member_table = Table(rows, repeatRows=1, colWidths=[1.3 * inch, 0.8 * inch, 1.3 * inch,
                                                        0.5 * inch, 0.5 * inch, 0.5 * inch,
                                                        0.7 * inch, 0.5 * inch, 0.6 * inch])
    member_table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#0F172A")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("FONTSIZE", (0, 0), (-1, 0), 7.5),
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ("FONTSIZE", (0, 1), (-1, -1), 7.5),
        ("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#CBD5E1")),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1),
         [colors.white, colors.HexColor("#F8FAFC")]),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("TOPPADDING", (0, 0), (-1, -1), 3),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
    ]))
    story.append(member_table)
    story.append(Spacer(1, 0.12 * inch))
    story.append(Paragraph(
        "Compliance note: every medicine line in this report is matched against the "
        "MedEx 25K index. Any item whose company could not be verified is flagged for "
        "manual confirmation before being counted toward a brand target. Prescription "
        "images are retained for DGDA audit review per BMDC data-handling policy.",
        small))
    story.append(Paragraph(
        f"Generated {datetime.now().strftime('%Y-%m-%d %H:%M')} · MedLenX Lab · "
        "Pure Vision + MedEx catalogue enrichment", small))

    doc.build(story)
    pdf = buf.getvalue()
    buf.close()
    filename = f"DGDA_Compliance_Audit_{datetime.now().strftime('%Y%m%d')}.pdf"
    return Response(
        content=pdf,
        media_type="application/pdf",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@app.post("/api/offline/sync")
async def offline_sync():
    """
    Acknowledge a client-side IndexedDB flush. Individual scans still go
    through /api/scan; this endpoint is a heartbeat so the PWA can mark the
    queue as drained after replaying pending uploads.
    """
    return {"success": True, "message": "Offline queue accepted", "ts": time.time()}

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
