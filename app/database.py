"""
MedLenX Lab - Relational Database Schema (PostgreSQL-style in SQLite)
Implements design from new design.pdf:
- doctors, pharma_companies, generics, medicines, prescriptions, prescribed_medicines
- For MR field view + Manager admin view analytics
"""

import sqlite3
import json
import os
import time
from datetime import datetime, timedelta
from collections import Counter, defaultdict

DB_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "data", "medlenx.db")

def get_db():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_db()
    cur = conn.cursor()
    
    # Enable foreign keys
    cur.execute("PRAGMA foreign_keys = ON")

    # 1. doctors - demographics, specialty, chamber, location (Upazila/District/Territory)
    cur.execute("""
    CREATE TABLE IF NOT EXISTS doctors (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        bmdc_no TEXT,
        qualifications TEXT,
        specialty TEXT,
        chamber TEXT,
        hospital TEXT,
        upazila TEXT,
        district TEXT,
        territory TEXT,
        contact TEXT,
        created_at TEXT,
        prescription_count INTEGER DEFAULT 0
    )
    """)

    # 2. pharma_companies - with is_own_company flag for market share
    cur.execute("""
    CREATE TABLE IF NOT EXISTS pharma_companies (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT UNIQUE NOT NULL,
        is_own_company INTEGER DEFAULT 0,
        logo_url TEXT
    )
    """)

    # 3. generics - standardized generic chemical names
    cur.execute("""
    CREATE TABLE IF NOT EXISTS generics (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT UNIQUE NOT NULL,
        category TEXT,
        description TEXT
    )
    """)

    # 4. medicines - Brand names linked to generics and companies
    cur.execute("""
    CREATE TABLE IF NOT EXISTS medicines (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        brand_name TEXT NOT NULL,
        generic_id INTEGER,
        company_id INTEGER,
        form TEXT,
        type TEXT,
        strength TEXT,
        strength_value REAL,
        ingredient TEXT,
        category TEXT,
        image_url TEXT,
        pack_image TEXT,
        price REAL,
        medex_url TEXT,
        medex_id TEXT,
        FOREIGN KEY (generic_id) REFERENCES generics(id),
        FOREIGN KEY (company_id) REFERENCES pharma_companies(id)
    )
    """)

    # 5. prescriptions - Header data for each uploaded prescription
    cur.execute("""
    CREATE TABLE IF NOT EXISTS prescriptions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        timestamp TEXT NOT NULL,
        image_path TEXT NOT NULL,
        image_url TEXT,
        doctor_id INTEGER,
        doctor_name TEXT,
        doctor_qualifications TEXT,
        doctor_hospital TEXT,
        doctor_bmdc_no TEXT,
        doctor_specialty TEXT,
        doctor_json TEXT,
        medicines_json TEXT,
        meta_json TEXT,
        avg_confidence REAL,
        total_medicines INTEGER,
        processing_time REAL,
        model_used TEXT,
        mr_id TEXT,
        geo_lat REAL,
        geo_lng REAL,
        upazila TEXT,
        district TEXT,
        territory TEXT,
        patient_info_masked TEXT,
        is_verified INTEGER DEFAULT 0,
        FOREIGN KEY (doctor_id) REFERENCES doctors(id)
    )
    """)

    # 6. prescribed_medicines - Junction table mapping drugs to prescription
    cur.execute("""
    CREATE TABLE IF NOT EXISTS prescribed_medicines (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        prescription_id INTEGER NOT NULL,
        medicine_id INTEGER,
        brand_name TEXT,
        generic_name TEXT,
        form TEXT,
        type TEXT,
        strength TEXT,
        dosage_frequency TEXT,
        raw_text TEXT,
        confidence REAL,
        line_number INTEGER,
        company_name TEXT,
        FOREIGN KEY (prescription_id) REFERENCES prescriptions(id),
        FOREIGN KEY (medicine_id) REFERENCES medicines(id)
    )
    """)

    conn.commit()

    # Seed pharma companies if empty
    cur.execute("SELECT COUNT(*) as cnt FROM pharma_companies")
    if cur.fetchone()["cnt"] == 0:
        companies = [
            ("Square Pharmaceuticals Ltd.", 0),
            ("Incepta Pharmaceuticals Ltd.", 0),
            ("Beximco Pharmaceuticals Ltd.", 0),
            ("Renata PLC", 0),
            ("ACI Limited", 0),
            ("ACME Laboratories Ltd.", 0),
            ("Opsonin Pharma Ltd.", 0),
            ("Eskayef Pharmaceuticals Ltd.", 0),
            ("Healthcare Pharmaceuticals Ltd.", 1),  # Example own company - can be changed
            ("Popular Pharmaceuticals Ltd.", 0),
        ]
        for name, is_own in companies:
            cur.execute("INSERT OR IGNORE INTO pharma_companies (name, is_own_company) VALUES (?, ?)", (name, is_own))
        print("✅ Seeded pharma_companies")

    # Seed generics if empty
    cur.execute("SELECT COUNT(*) as cnt FROM generics")
    if cur.fetchone()["cnt"] == 0:
        generics = [
            ("Omeprazole", "PPI"),
            ("Esomeprazole", "PPI"),
            ("Paracetamol", "Analgesic"),
            ("Azithromycin", "Antibiotic"),
            ("Cefixime", "Antibiotic"),
            ("Montelukast", "Antiasthmatic"),
            ("Fexofenadine", "Antihistamine"),
            ("Metformin", "Antidiabetic"),
            ("Losartan", "Antihypertensive"),
            ("Rosuvastatin", "Lipid Lowering"),
            ("Vitamin B1, B6 & B12", "Vitamin"),
            ("Thiamine Hydrochloride", "Vitamin"),
        ]
        for gname, cat in generics:
            cur.execute("INSERT OR IGNORE INTO generics (name, category) VALUES (?, ?)", (gname, cat))
        print("✅ Seeded generics")

    conn.commit()
    conn.close()
    print(f"✅ MedLenX Relational DB initialized at {DB_PATH}")

# ============ Helper: Get or Create ============

def get_or_create_doctor(name, bmdc_no="", qualifications="", specialty="", chamber="", hospital="", upazila="", district="", territory=""):
    conn = get_db()
    cur = conn.cursor()
    
    # Try find by BMDC or name
    cur.execute("SELECT * FROM doctors WHERE bmdc_no=? AND bmdc_no!=''", (bmdc_no,))
    row = cur.fetchone()
    if not row:
        cur.execute("SELECT * FROM doctors WHERE name=?", (name,))
        row = cur.fetchone()
    
    if row:
        # Update prescription_count
        cur.execute("UPDATE doctors SET prescription_count = prescription_count + 1 WHERE id=?", (row["id"],))
        conn.commit()
        doctor_id = row["id"]
    else:
        cur.execute("""
        INSERT INTO doctors (name, bmdc_no, qualifications, specialty, chamber, hospital, upazila, district, territory, created_at, prescription_count)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
        """, (name, bmdc_no, qualifications, specialty, chamber, hospital, upazila, district, territory, datetime.now().isoformat()))
        doctor_id = cur.lastrowid
        conn.commit()
    
    conn.close()
    return doctor_id

def get_or_create_company(name):
    if not name:
        return None
    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT id FROM pharma_companies WHERE name=?", (name,))
    row = cur.fetchone()
    if row:
        conn.close()
        return row["id"]
    cur.execute("INSERT INTO pharma_companies (name, is_own_company) VALUES (?, 0)", (name,))
    cid = cur.lastrowid
    conn.commit()
    conn.close()
    return cid

def get_or_create_generic(name, category=""):
    if not name:
        return None
    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT id FROM generics WHERE name=?", (name,))
    row = cur.fetchone()
    if row:
        conn.close()
        return row["id"]
    cur.execute("INSERT INTO generics (name, category) VALUES (?, ?)", (name, category))
    gid = cur.lastrowid
    conn.commit()
    conn.close()
    return gid

def save_medicine_to_catalog(brand_data):
    """Save scraped medex medicine to medicines table"""
    conn = get_db()
    cur = conn.cursor()
    
    # Check if already exists by brand_name + strength + form
    cur.execute("SELECT id FROM medicines WHERE brand_name=? AND strength=? AND form=?", 
                (brand_data.get('brand_name'), brand_data.get('strength'), brand_data.get('form')))
    if cur.fetchone():
        conn.close()
        return
    
    generic_id = get_or_create_generic(brand_data.get('generic',''), brand_data.get('category',''))
    company_id = get_or_create_company(brand_data.get('company',''))
    
    cur.execute("""
    INSERT INTO medicines (brand_name, generic_id, company_id, form, type, strength, strength_value, ingredient, category, image_url, pack_image, medex_url, medex_id)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, (
        brand_data.get('brand_name'),
        generic_id,
        company_id,
        brand_data.get('form'),
        brand_data.get('type'),
        brand_data.get('strength'),
        brand_data.get('strength_value', 0),
        brand_data.get('ingredient'),
        brand_data.get('category'),
        brand_data.get('image_url'),
        brand_data.get('pack_image'),
        brand_data.get('url'),
        brand_data.get('id')
    ))
    conn.commit()
    conn.close()

# ============ Save Prescription Workflow ============

def save_prescription(image_path, result, mr_id="MR001", geo_lat=None, geo_lng=None, upazila="", district="", territory="", is_verified=0):
    """
    Save prescription with relational links
    result contains doctor + medicines from MedLenX VL
    """
    conn = get_db()
    cur = conn.cursor()
    
    doctor_info = result.get('doctor', {})
    doctor_name = doctor_info.get('name','Unknown')
    bmdc_no = doctor_info.get('bmdc_no','') or doctor_info.get('BMDC Registration No.','')
    
    doctor_id = get_or_create_doctor(
        name=doctor_name,
        bmdc_no=bmdc_no,
        qualifications=doctor_info.get('qualifications',''),
        specialty=doctor_info.get('specialty','') or doctor_info.get('department',''),
        chamber=doctor_info.get('chamber',''),
        hospital=doctor_info.get('hospital',''),
        upazila=upazila,
        district=district,
        territory=territory
    )
    
    meta = result.get('meta', {})
    medicines = result.get('medicines', [])
    
    # Mask patient PII - store only doctor and prescription details
    patient_masked = json.dumps({"note": "Patient PII masked per BMDC compliance"})
    
    cur.execute("""
    INSERT INTO prescriptions 
    (timestamp, image_path, image_url, doctor_id, doctor_name, doctor_qualifications, doctor_hospital, doctor_bmdc_no, doctor_specialty, doctor_json, medicines_json, meta_json, avg_confidence, total_medicines, processing_time, model_used, mr_id, geo_lat, geo_lng, upazila, district, territory, patient_info_masked, is_verified)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, (
        meta.get('timestamp') or datetime.now().isoformat(),
        image_path,
        result.get('saved_image_path',''),
        doctor_id,
        doctor_name,
        doctor_info.get('qualifications',''),
        doctor_info.get('hospital',''),
        bmdc_no,
        doctor_info.get('specialty','') or doctor_info.get('department',''),
        json.dumps(doctor_info),
        json.dumps(medicines),
        json.dumps(meta),
        meta.get('avg_confidence', 0),
        meta.get('total_medicines', len(medicines)),
        meta.get('processing_time_sec', 0),
        meta.get('model_used', 'MedLenX VL'),
        mr_id,
        geo_lat,
        geo_lng,
        upazila,
        district,
        territory,
        patient_masked,
        is_verified
    ))
    prescription_id = cur.lastrowid
    
    # Insert into prescribed_medicines junction
    for med in medicines:
        # Try to find medicine_id in catalog
        cur.execute("SELECT id FROM medicines WHERE brand_name=? LIMIT 1", (med.get('brand_name',''),))
        med_row = cur.fetchone()
        medicine_id = med_row["id"] if med_row else None
        
        cur.execute("""
        INSERT INTO prescribed_medicines 
        (prescription_id, medicine_id, brand_name, generic_name, form, type, strength, dosage_frequency, raw_text, confidence, line_number, company_name)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            prescription_id,
            medicine_id,
            med.get('brand_name',''),
            med.get('generic',''),
            med.get('form',''),
            med.get('type',''),
            med.get('strength',''),
            med.get('dosage','') or med.get('dosage_frequency',''),
            med.get('raw_text',''),
            med.get('confidence',0),
            med.get('line_number',0),
            med.get('company','')
        ))
    
    conn.commit()
    conn.close()
    return prescription_id

# ============ Analytics for Dashboard ============

def get_dashboard_kpis(own_company_name=None):
    """Top Summary KPI Cards from design.pdf"""
    conn = get_db()
    cur = conn.cursor()
    
    now = datetime.now()
    today_start = now.replace(hour=0, minute=0, second=0, microsecond=0).isoformat()
    week_start = (now - timedelta(days=7)).isoformat()
    month_start = (now - timedelta(days=30)).isoformat()
    
    # Total Prescriptions Captured: Today / Week / Month
    cur.execute("SELECT COUNT(*) as cnt FROM prescriptions WHERE timestamp >= ?", (today_start,))
    total_today = cur.fetchone()["cnt"]
    cur.execute("SELECT COUNT(*) as cnt FROM prescriptions WHERE timestamp >= ?", (week_start,))
    total_week = cur.fetchone()["cnt"]
    cur.execute("SELECT COUNT(*) as cnt FROM prescriptions WHERE timestamp >= ?", (month_start,))
    total_month = cur.fetchone()["cnt"]
    cur.execute("SELECT COUNT(*) as cnt FROM prescriptions")
    total_all = cur.fetchone()["cnt"]
    
    # Top Prescribed Brand: overall most frequent
    cur.execute("""
    SELECT brand_name, COUNT(*) as cnt FROM prescribed_medicines 
    GROUP BY brand_name ORDER BY cnt DESC LIMIT 1
    """)
    row = cur.fetchone()
    top_brand = {"brand": row["brand_name"], "count": row["cnt"]} if row else {"brand": "N/A", "count": 0}
    
    # Company Market Share (%): own vs competitors
    cur.execute("SELECT company_name, COUNT(*) as cnt FROM prescribed_medicines GROUP BY company_name")
    company_counts = cur.fetchall()
    total_meds = sum([r["cnt"] for r in company_counts]) or 1
    
    # Determine own company
    if not own_company_name:
        # Try to find company marked is_own_company=1
        cur.execute("SELECT name FROM pharma_companies WHERE is_own_company=1 LIMIT 1")
        own_row = cur.fetchone()
        own_company_name = own_row["name"] if own_row else "Square Pharmaceuticals Ltd."
    
    own_count = 0
    for r in company_counts:
        if own_company_name.lower() in r["company_name"].lower() or r["company_name"].lower() in own_company_name.lower():
            own_count += r["cnt"]
    
    market_share = round((own_count / total_meds * 100), 1) if total_meds else 0
    
    # Active Doctor Coverage: unique doctors
    cur.execute("SELECT COUNT(DISTINCT doctor_id) as cnt FROM prescriptions")
    active_doctors = cur.fetchone()["cnt"]
    cur.execute("SELECT COUNT(*) as cnt FROM doctors")
    total_doctors = cur.fetchone()["cnt"]
    
    conn.close()
    
    return {
        "total_prescriptions": {"today": total_today, "week": total_week, "month": total_month, "all": total_all},
        "top_brand": top_brand,
        "market_share": {"own_company": own_company_name, "own_count": own_count, "total": total_meds, "percentage": market_share},
        "doctor_coverage": {"active": active_doctors, "total": total_doctors}
    }

def get_most_prescribed_medicines(limit=10, filter_generic=""):
    """Widget A: Most Prescribed Medicines (Market Demand) - Bar Chart"""
    conn = get_db()
    cur = conn.cursor()
    
    query = """
    SELECT pm.brand_name, pm.generic_name, pm.company_name, COUNT(*) as capture_count
    FROM prescribed_medicines pm
    GROUP BY pm.brand_name
    ORDER BY capture_count DESC
    LIMIT ?
    """
    if filter_generic:
        query = """
        SELECT pm.brand_name, pm.generic_name, pm.company_name, COUNT(*) as capture_count
        FROM prescribed_medicines pm
        WHERE pm.generic_name LIKE ?
        GROUP BY pm.brand_name
        ORDER BY capture_count DESC
        LIMIT ?
        """
        cur.execute(query, (f"%{filter_generic}%", limit))
    else:
        cur.execute(query, (limit,))
    
    rows = cur.fetchall()
    total = sum([r["capture_count"] for r in rows]) or 1
    
    result = []
    for r in rows:
        result.append({
            "brand_name": r["brand_name"],
            "generic": r["generic_name"],
            "manufacturer": r["company_name"],
            "capture_count": r["capture_count"],
            "market_share_percent": round(r["capture_count"]/total*100, 1)
        })
    
    conn.close()
    return result

def get_company_share():
    """Widget B: Company Share of Voice - Donut Pie Chart - Fixed unknown company error"""
    conn = get_db()
    cur = conn.cursor()
    # Filter out Unknown, empty, and live search failed
    cur.execute("""
    SELECT company_name, COUNT(*) as cnt FROM prescribed_medicines 
    WHERE company_name!='' 
    AND company_name NOT LIKE '%Unknown%'
    AND company_name NOT LIKE '%Live search failed%'
    GROUP BY company_name ORDER BY cnt DESC
    """)
    rows = cur.fetchall()
    conn.close()
    
    total = sum([r["cnt"] for r in rows]) or 1
    result = []
    for r in rows:
        # Skip if still unknown-like
        if not r["company_name"] or "unknown" in r["company_name"].lower():
            continue
        result.append({
            "company": r["company_name"],
            "count": r["cnt"],
            "percentage": round(r["cnt"]/total*100, 1)
        })
    # Group small <3% into Others
    main = [x for x in result if x["percentage"] >= 3]
    others = [x for x in result if x["percentage"] < 3]
    if others:
        others_count = sum([o["count"] for o in others])
        if others_count > 0:
            main.append({"company": "Others", "count": others_count, "percentage": round(others_count/total*100,1)})
    
    return main

def get_top_doctor_prescribers(own_company_name=None, limit=10):
    """Widget C: Top Doctor Prescribers - Leaderboard"""
    conn = get_db()
    cur = conn.cursor()
    
    if not own_company_name:
        cur.execute("SELECT name FROM pharma_companies WHERE is_own_company=1 LIMIT 1")
        own_row = cur.fetchone()
        own_company_name = own_row["name"] if own_row else "Square Pharmaceuticals Ltd."
    
    # Get all prescriptions with doctor
    cur.execute("""
    SELECT p.doctor_id, p.doctor_name, d.chamber, d.specialty,
           COUNT(*) as total_prescriptions
    FROM prescriptions p
    LEFT JOIN doctors d ON p.doctor_id = d.id
    GROUP BY p.doctor_id
    ORDER BY total_prescriptions DESC
    LIMIT ?
    """, (limit,))
    
    doctors = cur.fetchall()
    result = []
    for doc in doctors:
        doc_id = doc["doctor_id"]
        # Count own company drugs for this doctor
        cur.execute("""
        SELECT COUNT(*) as cnt FROM prescribed_medicines pm
        JOIN prescriptions p ON pm.prescription_id = p.id
        WHERE p.doctor_id = ? AND pm.company_name LIKE ?
        """, (doc_id, f"%{own_company_name.split()[0]}%"))
        own_cnt = cur.fetchone()["cnt"] or 0
        
        cur.execute("""
        SELECT COUNT(*) as cnt FROM prescribed_medicines pm
        JOIN prescriptions p ON pm.prescription_id = p.id
        WHERE p.doctor_id = ?
        """, (doc_id,))
        total_cnt = cur.fetchone()["cnt"] or 1
        
        competitor_cnt = total_cnt - own_cnt
        conversion_rate = round(own_cnt/total_cnt*100,1) if total_cnt else 0
        
        result.append({
            "doctor_name": doc["doctor_name"],
            "chamber": doc["chamber"] or "N/A",
            "specialty": doc["specialty"] or "General",
            "prescription_volume_own": own_cnt,
            "prescription_volume_competitor": competitor_cnt,
            "total": total_cnt,
            "conversion_rate": conversion_rate
        })
    
    conn.close()
    return result

def get_generic_brand_matrix():
    """Widget D: Generic vs Brand Share Matrix - Stacked Bar Chart by Specialty"""
    conn = get_db()
    cur = conn.cursor()
    
    cur.execute("""
    SELECT d.specialty, pm.generic_name, COUNT(*) as cnt
    FROM prescribed_medicines pm
    JOIN prescriptions p ON pm.prescription_id = p.id
    JOIN doctors d ON p.doctor_id = d.id
    WHERE pm.generic_name != '' AND d.specialty != ''
    GROUP BY d.specialty, pm.generic_name
    ORDER BY d.specialty, cnt DESC
    """)
    rows = cur.fetchall()
    conn.close()
    
    # Organize by specialty
    matrix = defaultdict(lambda: defaultdict(int))
    specialties = set()
    generics_set = set()
    for r in rows:
        spec = r["specialty"] or "General"
        gen = r["generic_name"] or "Unknown"
        matrix[spec][gen] += r["cnt"]
        specialties.add(spec)
        generics_set.add(gen)
    
    # Format for stacked bar: list of specialties each with generic counts
    result = []
    for spec in list(specialties)[:8]:  # top 8 specialties
        entry = {"specialty": spec}
        for gen in list(generics_set)[:6]:  # top 6 generics
            entry[gen] = matrix[spec][gen]
        result.append(entry)
    
    return result

def get_all_prescriptions(limit=100):
    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT * FROM prescriptions ORDER BY id DESC LIMIT ?", (limit,))
    rows = cur.fetchall()
    conn.close()
    return [dict(r) for r in rows]

def get_prescription_by_id(pid):
    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT * FROM prescriptions WHERE id=?", (pid,))
    row = cur.fetchone()
    conn.close()
    return dict(row) if row else None

def delete_prescription(pid):
    conn = get_db()
    cur = conn.cursor()
    cur.execute("DELETE FROM prescribed_medicines WHERE prescription_id=?", (pid,))
    cur.execute("DELETE FROM prescriptions WHERE id=?", (pid,))
    conn.commit()
    conn.close()
    return True

def get_stats():
    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) as total, AVG(avg_confidence) as avg_conf, SUM(total_medicines) as total_meds FROM prescriptions")
    row = cur.fetchone()
    conn.close()
    return dict(row) if row else {"total":0,"avg_conf":0,"total_meds":0}

def get_bengali_normalized_dosage(dosage_text):
    """
    Handle Bengali/English mixed prescriptions: ১+০+১ -> 1+0+1
    Doctors in BD write dosages in Bengali or shorthand
    """
    if not dosage_text:
        return ""
    bn_to_en = str.maketrans("০১২৩৪৫৬৭৮৯", "0123456789")
    return dosage_text.translate(bn_to_en)

def search_medex_db(q="", form="", limit=50):
    conn = get_db()
    cur = conn.cursor()
    query = "SELECT m.brand_name, m.form, m.type, m.strength, m.ingredient, m.category, m.image_url, m.pack_image, c.name as company, g.name as generic, m.medex_url FROM medicines m LEFT JOIN pharma_companies c ON m.company_id = c.id LEFT JOIN generics g ON m.generic_id = g.id WHERE 1=1"
    params = []
    if q:
        query += " AND (m.brand_name LIKE ? OR g.name LIKE ? OR c.name LIKE ?)"
        params.extend([f"%{q}%", f"%{q}%", f"%{q}%"])
    if form:
        query += " AND (m.type LIKE ? OR m.form LIKE ?)"
        params.extend([f"%{form}%", f"%{form}%"])
    query += " LIMIT ?"
    params.append(limit)
    cur.execute(query, params)
    rows = cur.fetchall()
    conn.close()
    return [dict(r) for r in rows]
