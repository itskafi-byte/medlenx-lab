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

    # 7. recent_scanned_medicines - itemized feed of every detected medicine.
    # prescribed_medicines is the analytics junction (rewritten on re-verify);
    # this table is the append-only per-MR activity feed that powers the
    # "Recent Scans" data grid. Kept separate so re-verifying a prescription
    # never erases the field rep's scan history.
    cur.execute("""
    CREATE TABLE IF NOT EXISTS recent_scanned_medicines (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        prescription_id INTEGER NOT NULL,
        mr_id TEXT NOT NULL,
        brand_name TEXT NOT NULL,
        generic_name TEXT,
        company_name TEXT,
        dosage_form TEXT,
        strength TEXT,
        dosage TEXT,
        confidence_score REAL,
        company_verified INTEGER DEFAULT 0,
        needs_review INTEGER DEFAULT 0,
        doctor_id INTEGER,
        doctor_name TEXT,
        specialty TEXT,
        district TEXT,
        upazila TEXT,
        territory TEXT,
        image_url TEXT,
        medex_url TEXT,
        created_at TEXT NOT NULL,
        FOREIGN KEY (prescription_id) REFERENCES prescriptions(id) ON DELETE CASCADE
    )
    """)

    cur.execute("""
    CREATE TABLE IF NOT EXISTS officer_profiles (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        employee_id TEXT UNIQUE NOT NULL,
        full_name TEXT,
        role TEXT DEFAULT 'MPO',
        company_name TEXT,
        territory TEXT,
        zone TEXT,
        division TEXT,
        portfolio TEXT,
        team_id TEXT DEFAULT 'TEAM-DHK-S',
        is_active INTEGER DEFAULT 1,
        created_at TEXT,
        updated_at TEXT
    )
    """)
    cur.execute("""
    CREATE TABLE IF NOT EXISTS brand_targets (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        employee_id TEXT NOT NULL,
        brand_name TEXT NOT NULL,
        monthly_target INTEGER DEFAULT 0,
        month TEXT NOT NULL,
        UNIQUE(employee_id, brand_name, month)
    )
    """)
    cur.execute("""
    CREATE TABLE IF NOT EXISTS error_reports (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        prescription_id INTEGER,
        mr_id TEXT,
        brand_name TEXT,
        reported_text TEXT,
        correction TEXT,
        notes TEXT,
        status TEXT DEFAULT 'queued',
        created_at TEXT NOT NULL
    )
    """)
    cur.execute("""
    CREATE TABLE IF NOT EXISTS team_members (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        team_id TEXT NOT NULL,
        rsm_employee_id TEXT NOT NULL,
        mpo_mr_id TEXT UNIQUE NOT NULL,
        mpo_name TEXT,
        territory TEXT,
        zone TEXT,
        division TEXT,
        role TEXT DEFAULT 'MPO'
    )
    """)
    cur.execute("""
    CREATE TABLE IF NOT EXISTS app_kv (
        key TEXT PRIMARY KEY,
        value TEXT
    )
    """)
    # 8. vision_training - Human-in-the-loop handwriting retraining queue.
    # Stores the cropped prescription slice + the officer's corrected text so
    # the Vision AI can learn local Bangladeshi doctor handwriting patterns.
    cur.execute("""
    CREATE TABLE IF NOT EXISTS vision_training (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        prescription_id INTEGER,
        mr_id TEXT,
        brand_name TEXT,
        corrected_brand TEXT,
        corrected_company TEXT,
        raw_text TEXT,
        image_path TEXT,
        confidence REAL,
        notes TEXT,
        status TEXT DEFAULT 'queued',
        created_at TEXT NOT NULL
    )
    """)
    cur.execute("""
    CREATE TABLE IF NOT EXISTS dgda_flags (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        brand_name TEXT NOT NULL,
        generic_name TEXT,
        company_name TEXT,
        flag_type TEXT,            -- banned | price_adjusted
        severity TEXT DEFAULT 'warn',
        note TEXT,
        source TEXT DEFAULT 'dgda',
        created_at TEXT NOT NULL
    )
    """)

    # 9. doctor_targets - RSM-attached target doctor lists per MPO. Powers the
    # Doctor Detailing Target Tracker: whenever a scanned prescription's
    # doctor matches a target, a visit is auto-logged in doctor_target_visits.
    cur.execute("""
    CREATE TABLE IF NOT EXISTS doctor_targets (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        mpo_id TEXT NOT NULL,
        mpo_name TEXT,
        doctor_name TEXT NOT NULL,
        specialty TEXT,
        territory TEXT,
        monthly_target INTEGER DEFAULT 0,
        month TEXT NOT NULL,
        created_at TEXT,
        UNIQUE(mpo_id, doctor_name, month)
    )
    """)
    cur.execute("""
    CREATE TABLE IF NOT EXISTS doctor_target_visits (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        target_id INTEGER NOT NULL,
        prescription_id INTEGER,
        mr_id TEXT,
        doctor_name TEXT,
        visited_at TEXT NOT NULL,
        UNIQUE(target_id, prescription_id),
        FOREIGN KEY (target_id) REFERENCES doctor_targets(id)
    )
    """)

    conn.commit()

    # ---- lightweight migrations for pre-existing databases ----
    def _cols(table):
        return {r[1] for r in cur.execute(f"PRAGMA table_info({table})")}

    for table, column, ddl in [
        ("prescribed_medicines", "mr_id", "TEXT"),
        ("prescribed_medicines", "company_verified", "INTEGER DEFAULT 0"),
        ("prescribed_medicines", "needs_review", "INTEGER DEFAULT 0"),
        ("doctors", "division", "TEXT"),
        ("prescriptions", "prescription_source", "TEXT"),
        ("prescriptions", "image_phash", "TEXT"),
        ("prescriptions", "duplicate_of", "INTEGER"),
        ("prescriptions", "off_territory", "INTEGER DEFAULT 0"),
        ("prescriptions", "territory_note", "TEXT"),
        ("recent_scanned_medicines", "prescription_source", "TEXT"),
        ("recent_scanned_medicines", "specialty", "TEXT"),
    ]:
        try:
            if column not in _cols(table):
                cur.execute(f"ALTER TABLE {table} ADD COLUMN {column} {ddl}")
        except sqlite3.OperationalError:
            pass

    # ---- repair legacy 'YYYY-MM-DD HH:MM:SS' timestamps to ISO-8601 ----
    try:
        cur.execute("""
            UPDATE prescriptions
            SET timestamp = substr(timestamp,1,10) || 'T' || substr(timestamp,12)
            WHERE timestamp LIKE '____-__-__ __:__:%'
        """)
    except sqlite3.OperationalError:
        pass

    # ---- indexes: the dashboard filters on these constantly ----
    for stmt in [
        "CREATE INDEX IF NOT EXISTS idx_rsm_created ON recent_scanned_medicines(created_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_rsm_mr ON recent_scanned_medicines(mr_id)",
        "CREATE INDEX IF NOT EXISTS idx_rsm_brand ON recent_scanned_medicines(brand_name)",
        "CREATE INDEX IF NOT EXISTS idx_rsm_company ON recent_scanned_medicines(company_name)",
        "CREATE INDEX IF NOT EXISTS idx_rsm_presc ON recent_scanned_medicines(prescription_id)",
        "CREATE INDEX IF NOT EXISTS idx_pm_presc ON prescribed_medicines(prescription_id)",
        "CREATE INDEX IF NOT EXISTS idx_pm_company ON prescribed_medicines(company_name)",
        "CREATE INDEX IF NOT EXISTS idx_pm_brand ON prescribed_medicines(brand_name)",
        "CREATE INDEX IF NOT EXISTS idx_presc_ts ON prescriptions(timestamp DESC)",
        "CREATE INDEX IF NOT EXISTS idx_presc_doctor ON prescriptions(doctor_id)",
        "CREATE INDEX IF NOT EXISTS idx_presc_district ON prescriptions(district)",
        "CREATE INDEX IF NOT EXISTS idx_med_brand ON medicines(brand_name)",
    ]:
        try:
            cur.execute(stmt)
        except sqlite3.OperationalError:
            pass

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

    _seed_enterprise(cur)
    conn.commit()
    conn.close()
    print(f"✅ MedLenX Relational DB initialized at {DB_PATH}")


_TERRITORY_CYCLE = [
    ("Dhaka South", "Dhaka South", "Dhaka"),
    ("Dhaka North", "Dhaka North", "Dhaka"),
    ("Chattogram Metro", "Chattogram Metro", "Chattogram"),
    ("Chattogram North", "Chattogram North", "Chattogram"),
    ("Rajshahi Metro", "Rajshahi", "Rajshahi"),
    ("Khulna Metro", "Khulna", "Khulna"),
    ("Sylhet Metro", "Sylhet", "Sylhet"),
    ("Barishal Sadar", "Barishal", "Barishal"),
    ("Rangpur Metro", "Rangpur", "Rangpur"),
    ("Mymensingh Sadar", "Mymensingh", "Mymensingh"),
    ("Gazipur", "Dhaka North", "Dhaka"),
    ("Narayanganj", "Dhaka South", "Dhaka"),
    ("Comilla", "Chattogram North", "Chattogram"),
    ("Bogura", "Rajshahi", "Rajshahi"),
    ("Jessore", "Khulna", "Khulna"),
]


def _seed_enterprise(cur):
    """Idempotent seed for officer profile + a 50+ MPO field team."""
    cur.execute("SELECT COUNT(*) AS c FROM officer_profiles")
    if cur.fetchone()["c"] == 0:
        now = datetime.now().isoformat()
        cur.execute("""
            INSERT INTO officer_profiles
            (employee_id, full_name, role, company_name, territory, zone,
             division, portfolio, team_id, is_active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
        """, ("MR001", "Field Officer", "MPO",
              "Healthcare Pharmaceuticals Ltd.", "Dhaka South", "Dhaka South",
              "Dhaka", json.dumps(["Cardiology", "Gastroenterology"]),
              "TEAM-DHK-S", now, now))
        cur.execute("INSERT OR REPLACE INTO app_kv (key, value) VALUES ('current_employee_id', 'MR001')")
        print("✅ Seeded default officer profile")

    cur.execute("SELECT COUNT(*) AS c FROM team_members")
    if cur.fetchone()["c"] == 0:
        names = [
            "Rahim Uddin", "Fatema Khatun", "Sajid Hasan", "Nusrat Jahan",
            "Imran Kabir", "Sharmin Akter", "Tanvir Ahmed", "Lamia Chowdhury",
            "Mahmudul Hasan", "Rokeya Sultana", "Arif Hossain", "Mim Akter",
            "Shahriar Kabir", "Farzana Islam", "Nayeem Khan", "Sumaiya Rahman",
            "Jahidul Islam", "Tania Sultana", "Rashedul Karim", "Moumita Das",
        ]
        rows = []
        for i in range(1, 52):
            terr, zone, div = _TERRITORY_CYCLE[(i - 1) % len(_TERRITORY_CYCLE)]
            mr = f"MR{i:03d}"
            name = names[(i - 1) % len(names)] + f" {i}"
            role = "RSM" if i == 50 else ("RSO" if i % 10 == 0 else "MPO")
            rsm = "MR050"
            rows.append(("TEAM-BD-1", rsm, mr, name, terr, zone, div, role))
        cur.executemany("""
            INSERT OR IGNORE INTO team_members
            (team_id, rsm_employee_id, mpo_mr_id, mpo_name, territory, zone, division, role)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, rows)
        print("✅ Seeded 51-member RSM field team")

    cur.execute("SELECT COUNT(*) AS c FROM brand_targets")
    if cur.fetchone()["c"] == 0:
        month = datetime.now().strftime("%Y-%m")
        for brand, tgt in [("Seclo", 40), ("Histacin", 25), ("Napa", 15)]:
            cur.execute("""
                INSERT OR IGNORE INTO brand_targets (employee_id, brand_name, monthly_target, month)
                VALUES ('MR001', ?, ?, ?)
            """, (brand, tgt, month))
        print("✅ Seeded brand targets")

# ============ Helper: Get or Create ============

def get_or_create_doctor(name, bmdc_no="", qualifications="", specialty="",
                         chamber="", hospital="", upazila="", district="",
                         territory="", division="", increment=True):
    """
    Upsert a doctor and KEEP THEIR PROFILE IN SYNC.

    The previous version only bumped prescription_count for an existing doctor
    and never wrote back specialty/district/upazila/territory. Verifying a
    prescription therefore appeared to save those fields while the doctors
    table (which drives the specialty + territory analytics) kept the stale
    values from the very first scan.

    `increment=False` is used by the verify flow so re-verifying the same
    prescription does not inflate prescription_count.
    """
    conn = get_db()
    cur = conn.cursor()

    name = (name or "").strip()
    bmdc_no = (bmdc_no or "").strip()

    row = None
    if bmdc_no:
        cur.execute("SELECT * FROM doctors WHERE bmdc_no=? AND bmdc_no!=''", (bmdc_no,))
        row = cur.fetchone()
    if not row and name:
        cur.execute("SELECT * FROM doctors WHERE name=? COLLATE NOCASE", (name,))
        row = cur.fetchone()

    if row:
        doctor_id = row["id"]
        existing = dict(row)
        # Fill in / correct the profile. A non-empty incoming value always wins
        # (it came from a human verifying the prescription).
        updates, params = [], []
        for col, val in [
            ("name", name), ("bmdc_no", bmdc_no), ("qualifications", qualifications),
            ("specialty", specialty), ("chamber", chamber), ("hospital", hospital),
            ("upazila", upazila), ("district", district), ("territory", territory),
            ("division", division),
        ]:
            val = (val or "").strip()
            if val and val != (existing.get(col) or ""):
                updates.append(f"{col}=?")
                params.append(val)
        if increment:
            updates.append("prescription_count = prescription_count + 1")
        if updates:
            params.append(doctor_id)
            cur.execute(f"UPDATE doctors SET {', '.join(updates)} WHERE id=?", params)
            conn.commit()
    else:
        cur.execute("""
        INSERT INTO doctors (name, bmdc_no, qualifications, specialty, chamber,
                             hospital, upazila, district, territory, division,
                             created_at, prescription_count)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (name or "Unknown", bmdc_no, qualifications, specialty, chamber,
              hospital, upazila, district, territory, division,
              datetime.now().isoformat(), 1 if increment else 0))
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
    
    generic_id = get_or_create_generic(brand_data.get('generic',''), brand_data.get('category',''))
    company_id = get_or_create_company(brand_data.get('company',''))

    # Dedupe on brand + strength + form + COMPANY. Without the company in the
    # key, two manufacturers selling the same brand name collapsed into a
    # single row and the surviving row's company was applied to both.
    cur.execute(
        """
        SELECT id FROM medicines
        WHERE brand_name=? AND IFNULL(strength,'')=IFNULL(?,'')
          AND IFNULL(form,'')=IFNULL(?,'') AND IFNULL(company_id,-1)=IFNULL(?,-1)
        """,
        (brand_data.get('brand_name'), brand_data.get('strength'),
         brand_data.get('form'), company_id),
    )
    if cur.fetchone():
        conn.close()
        return
    
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

def _find_medicine_id(cur, brand_name="", company="", strength="", form=""):
    """
    Resolve a catalogue medicines.id for a prescribed medicine.

    Narrows by company first (the authoritative field), then strength, then
    dosage form, so brands marketed by several companies map to the right row.
    """
    brand_name = (brand_name or "").strip()
    if not brand_name:
        return None

    cur.execute(
        """
        SELECT m.id, m.strength, m.type, m.form, c.name AS company
        FROM medicines m
        LEFT JOIN pharma_companies c ON m.company_id = c.id
        WHERE m.brand_name = ? COLLATE NOCASE
        """,
        (brand_name,),
    )
    rows = cur.fetchall()
    if not rows:
        return None
    if len(rows) == 1:
        return rows[0]["id"]

    def norm(v):
        return "".join(str(v or "").lower().split())

    want_company = norm(company)
    want_strength = norm(strength)
    want_form = norm(form)

    best, best_score = rows[0], -1
    for r in rows:
        score = 0
        rc = norm(r["company"])
        if want_company and rc:
            if rc == want_company or rc.startswith(want_company) or want_company.startswith(rc):
                score += 10
        if want_strength and norm(r["strength"]) == want_strength:
            score += 5
        if want_form and want_form in (norm(r["type"]) + norm(r["form"])):
            score += 2
        if score > best_score:
            best, best_score = r, score
    return best["id"]


def _normalize_timestamp(value):
    """
    Coerce any incoming timestamp to ISO-8601 ('YYYY-MM-DDTHH:MM:SS').

    All date filtering compares timestamps as strings, so the separator must be
    consistent: ' ' (0x20) sorts before 'T' (0x54), which silently broke the
    Today/Week/Month KPIs and every date-range filter.
    """
    if not value:
        return datetime.now().isoformat()
    text = str(value).strip()
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S",
                "%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S.%f"):
        try:
            return datetime.strptime(text, fmt).isoformat()
        except ValueError:
            continue
    try:
        return datetime.fromisoformat(text.replace(" ", "T")).isoformat()
    except ValueError:
        return datetime.now().isoformat()


def infer_prescription_source(hospital="", chamber="", explicit=""):
    """Hospital vs Private Chamber — used as a first-class filter tag."""
    explicit = (explicit or "").strip()
    if explicit in ("Hospital", "Private Chamber"):
        return explicit
    chamber = (chamber or "").lower()
    hospital = (hospital or "").lower()
    if hospital and any(k in hospital for k in ("hospital", "medical college", "cmch", "dmch", "bsmmu")):
        return "Hospital"
    if "chamber" in chamber or "diagnostic" in chamber or "clinic" in chamber:
        return "Private Chamber"
    if chamber and not hospital:
        return "Private Chamber"
    if hospital:
        return "Hospital"
    return ""


def _write_medicine_rows(cur, prescription_id, medicines, *, mr_id="MR001",
                         doctor_id=None, doctor_name="", specialty="",
                         district="", upazila="", territory="",
                         prescription_source="", write_recent=True):
    """
    Write every detected medicine to BOTH:
      * prescribed_medicines - analytics junction (replaced on re-verify)
      * recent_scanned_medicines - append-only itemized activity feed

    Previously only the junction was written and the itemized feed did not
    exist, so the "Recent Scans" view could never show individual medicines.
    """
    now = datetime.now().isoformat()
    for idx, med in enumerate(medicines or []):
        brand = (med.get('brand_name') or '').strip()
        if not brand:
            continue
        generic = med.get('generic_name') or med.get('generic') or ''
        company = med.get('company') or ''
        dosage = (med.get('dosage_normalized') or med.get('dosage')
                  or med.get('dosage_frequency') or '')
        confidence = med.get('confidence', 0) or 0
        verified = 1 if med.get('company_verified') else 0
        review = 1 if (med.get('needs_review') or med.get('company_ambiguous')
                       or med.get('company_conflict')) else 0

        medicine_id = _find_medicine_id(
            cur, brand_name=brand, company=company,
            strength=med.get('strength', ''),
            form=med.get('type', '') or med.get('form', ''),
        )

        cur.execute("""
        INSERT INTO prescribed_medicines
        (prescription_id, medicine_id, brand_name, generic_name, form, type,
         strength, dosage_frequency, raw_text, confidence, line_number,
         company_name, mr_id, company_verified, needs_review)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            prescription_id, medicine_id, brand, generic,
            med.get('form', ''), med.get('type', ''), med.get('strength', ''),
            dosage, med.get('raw_text', ''), confidence,
            med.get('line_number', idx + 1), company, mr_id, verified, review,
        ))

        if write_recent:
            cur.execute("""
            INSERT INTO recent_scanned_medicines
            (prescription_id, mr_id, brand_name, generic_name, company_name,
             dosage_form, strength, dosage, confidence_score, company_verified,
             needs_review, doctor_id, doctor_name, specialty, district,
             upazila, territory, image_url, medex_url, created_at,
             prescription_source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                prescription_id, mr_id, brand, generic, company,
                med.get('type', '') or med.get('form', ''),
                med.get('strength', ''), dosage, confidence, verified, review,
                doctor_id, doctor_name, specialty, district, upazila, territory,
                med.get('image_url', '') or med.get('pack_image', ''),
                med.get('medex_url', ''), now, prescription_source or "",
            ))


def find_duplicate_prescription(cur, image_phash, exclude_id=None,
                                threshold=8):
    """Return the earlier prescription this scan duplicates, or None.

    Uses a DCT perceptual hash so re-uploads of the same physical Rx — even
    after phone re-compression — are caught as target inflation.
    """
    from .rx_audit import hamming_distance
    if not image_phash:
        return None
    rows = cur.execute(
        "SELECT id, mr_id, image_phash, timestamp FROM prescriptions "
        "WHERE image_phash IS NOT NULL AND image_phash != '' ORDER BY id ASC"
    ).fetchall()
    best, best_dist = None, None
    for r in rows:
        if exclude_id is not None and r["id"] == exclude_id:
            continue
        dist = hamming_distance(image_phash, r["image_phash"])
        if dist is not None and dist <= threshold and \
                (best_dist is None or dist < best_dist):
            best, best_dist = r, dist
    return dict(best) if best else None


def save_prescription(image_path, result, mr_id="MR001", geo_lat=None, geo_lng=None, upazila="", district="", territory="", is_verified=0, image_phash=None, off_territory=0, territory_note=""):
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
    source = infer_prescription_source(
        doctor_info.get('hospital', ''), doctor_info.get('chamber', ''),
        doctor_info.get('prescription_source', ''),
    )

    cur.execute("""
    INSERT INTO prescriptions 
    (timestamp, image_path, image_url, doctor_id, doctor_name, doctor_qualifications, doctor_hospital, doctor_bmdc_no, doctor_specialty, doctor_json, medicines_json, meta_json, avg_confidence, total_medicines, processing_time, model_used, mr_id, geo_lat, geo_lng, upazila, district, territory, patient_info_masked, is_verified, prescription_source, image_phash, duplicate_of, off_territory, territory_note)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, (
        # Always store a real ISO-8601 timestamp. The scan meta uses
        # "%Y-%m-%d %H:%M:%S" (space separator); a space sorts BEFORE 'T', so
        # storing it verbatim broke every string range comparison and the
        # "Today" KPI always read 0.
        _normalize_timestamp(meta.get('timestamp')),
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
        is_verified,
        source,
        image_phash or "",
        dup["id"] if (dup := find_duplicate_prescription(cur, image_phash)) else None,
        1 if off_territory else 0,
        territory_note or "",
    ))
    prescription_id = cur.lastrowid
    
    # Insert into prescribed_medicines junction + itemized recent-scan feed
    _write_medicine_rows(
        cur, prescription_id, medicines,
        mr_id=mr_id, doctor_id=doctor_id, doctor_name=doctor_name,
        specialty=doctor_info.get('specialty','') or doctor_info.get('department',''),
        district=district, upazila=upazila, territory=territory,
        prescription_source=source, write_recent=True,
    )

    # Doctor Detailing Target Tracker: a scanned doctor name matching an
    # RSM-attached target auto-logs the visit.
    try:
        matched = match_doctor_targets(
            cur, doctor_name, mr_id=mr_id, prescription_id=prescription_id,
        )
        if matched:
            meta["doctor_targets_hit"] = matched
    except Exception as exc:
        print(f"⚠️  doctor target visit log failed: {exc}")

    conn.commit()
    conn.close()
    return prescription_id

# ============ Analytics for Dashboard ============

def get_dashboard_kpis(own_company_name=None, district="", territory="",
                       specialty="", mr_id="", days=None):
    """KPI strip with period-over-period deltas, honouring global filters."""
    conn = get_db()
    cur = conn.cursor()

    fsql, fparams = _filter_sql(district, territory, specialty, mr_id, None)

    def count_since(dt):
        return cur.execute(f"""
            SELECT COUNT(DISTINCT p.id) AS c FROM prescriptions p
            LEFT JOIN doctors d ON p.doctor_id = d.id
            WHERE p.timestamp >= ? {fsql}""",
            [dt.isoformat()] + fparams).fetchone()["c"]

    now = datetime.now()
    today = now.replace(hour=0, minute=0, second=0, microsecond=0)
    total_today = count_since(today)
    total_week = count_since(now - timedelta(days=7))
    total_month = count_since(now - timedelta(days=30))
    total_all = cur.execute(f"""
        SELECT COUNT(DISTINCT p.id) AS c FROM prescriptions p
        LEFT JOIN doctors d ON p.doctor_id = d.id WHERE 1=1 {fsql}""",
        fparams).fetchone()["c"]

    # window used for the headline numbers + its immediately preceding window
    win = int(days) if days else 30
    cur_start = now - timedelta(days=win)
    prev_start = now - timedelta(days=win * 2)

    scans_cur = count_since(cur_start)
    scans_prev = cur.execute(f"""
        SELECT COUNT(DISTINCT p.id) AS c FROM prescriptions p
        LEFT JOIN doctors d ON p.doctor_id = d.id
        WHERE p.timestamp >= ? AND p.timestamp < ? {fsql}""",
        [prev_start.isoformat(), cur_start.isoformat()] + fparams).fetchone()["c"]

    def pct_delta(cur_v, prev_v):
        if not prev_v:
            return 100.0 if cur_v else 0.0
        return round((cur_v - prev_v) / prev_v * 100, 1)

    dsql, dparams = _filter_sql(district, territory, specialty, mr_id, days)

    items_total = cur.execute(f"""
        SELECT COUNT(*) AS c FROM prescribed_medicines pm
        JOIN prescriptions p ON pm.prescription_id = p.id
        LEFT JOIN doctors d ON p.doctor_id = d.id WHERE 1=1 {dsql}""",
        dparams).fetchone()["c"]

    row = cur.execute(f"""
        SELECT pm.brand_name, pm.company_name, COUNT(*) AS cnt
        FROM prescribed_medicines pm
        JOIN prescriptions p ON pm.prescription_id = p.id
        LEFT JOIN doctors d ON p.doctor_id = d.id
        WHERE IFNULL(pm.brand_name,'')!='' {dsql}
        GROUP BY pm.brand_name ORDER BY cnt DESC LIMIT 1""",
        dparams).fetchone()
    top_brand = ({"brand": row["brand_name"], "company": row["company_name"],
                  "count": row["cnt"]} if row else
                 {"brand": "N/A", "company": "", "count": 0})

    if not own_company_name:
        r = cur.execute(
            "SELECT name FROM pharma_companies WHERE is_own_company=1 LIMIT 1").fetchone()
        own_company_name = r["name"] if r else "Square Pharmaceuticals Ltd."
    own_token = own_company_name.split()[0] if own_company_name else ""

    own_count = cur.execute(f"""
        SELECT COUNT(*) AS c FROM prescribed_medicines pm
        JOIN prescriptions p ON pm.prescription_id = p.id
        LEFT JOIN doctors d ON p.doctor_id = d.id
        WHERE pm.company_name LIKE ? {dsql}""",
        [f"%{own_token}%"] + dparams).fetchone()["c"]

    market_share = round(own_count / items_total * 100, 1) if items_total else 0.0

    # previous-window share for the delta
    prev_items = cur.execute(f"""
        SELECT COUNT(*) AS c FROM prescribed_medicines pm
        JOIN prescriptions p ON pm.prescription_id = p.id
        LEFT JOIN doctors d ON p.doctor_id = d.id
        WHERE p.timestamp >= ? AND p.timestamp < ? {fsql}""",
        [prev_start.isoformat(), cur_start.isoformat()] + fparams).fetchone()["c"]
    prev_own = cur.execute(f"""
        SELECT COUNT(*) AS c FROM prescribed_medicines pm
        JOIN prescriptions p ON pm.prescription_id = p.id
        LEFT JOIN doctors d ON p.doctor_id = d.id
        WHERE pm.company_name LIKE ? AND p.timestamp >= ? AND p.timestamp < ? {fsql}""",
        [f"%{own_token}%", prev_start.isoformat(), cur_start.isoformat()] + fparams
    ).fetchone()["c"]
    prev_share = round(prev_own / prev_items * 100, 1) if prev_items else 0.0

    active_doctors = cur.execute(f"""
        SELECT COUNT(DISTINCT p.doctor_id) AS c FROM prescriptions p
        LEFT JOIN doctors d ON p.doctor_id = d.id WHERE 1=1 {dsql}""",
        dparams).fetchone()["c"]
    total_doctors = cur.execute("SELECT COUNT(*) AS c FROM doctors").fetchone()["c"]

    conn.close()
    return {
        "total_prescriptions": {"today": total_today, "week": total_week,
                                "month": total_month, "all": total_all,
                                "window": scans_cur,
                                "delta_percent": pct_delta(scans_cur, scans_prev)},
        "identified_items": {"count": items_total},
        "top_brand": top_brand,
        "market_share": {"own_company": own_company_name, "own_count": own_count,
                         "total": items_total, "percentage": market_share,
                         "delta_percent": round(market_share - prev_share, 1)},
        "doctor_coverage": {"active": active_doctors, "total": total_doctors},
    }


def get_most_prescribed_medicines(limit=10, filter_generic="", district="",
                                  territory="", specialty="", mr_id="", days=None):
    """Widget A: Most Prescribed Medicines - honours the global filter bar."""
    conn = get_db()
    cur = conn.cursor()

    fsql, fparams = _filter_sql(district, territory, specialty, mr_id, days)
    params = list(fparams)
    gen_sql = ""
    if filter_generic:
        gen_sql = " AND pm.generic_name LIKE ?"
        params.append(f"%{filter_generic}%")

    cur.execute(f"""
    SELECT pm.brand_name, pm.generic_name, pm.company_name, COUNT(*) as capture_count
    FROM prescribed_medicines pm
    JOIN prescriptions p ON pm.prescription_id = p.id
    LEFT JOIN doctors d ON p.doctor_id = d.id
    WHERE 1=1 {fsql}{gen_sql}
    GROUP BY pm.brand_name, pm.company_name
    ORDER BY capture_count DESC
    LIMIT ?
    """, params + [limit])

    rows = cur.fetchall()
    total = sum(r["capture_count"] for r in rows) or 1
    result = [{
        "brand_name": r["brand_name"],
        "generic": r["generic_name"],
        "manufacturer": r["company_name"],
        "capture_count": r["capture_count"],
        "market_share_percent": round(r["capture_count"] / total * 100, 1),
    } for r in rows]
    conn.close()
    return result


def get_company_share(district="", territory="", specialty="", mr_id="",
                      days=None, min_percent=3.0):
    """Widget B: Company Share of Voice - filtered, with clean Others bucket."""
    conn = get_db()
    cur = conn.cursor()
    fsql, fparams = _filter_sql(district, territory, specialty, mr_id, days)

    cur.execute(f"""
    SELECT pm.company_name, COUNT(*) as cnt
    FROM prescribed_medicines pm
    JOIN prescriptions p ON pm.prescription_id = p.id
    LEFT JOIN doctors d ON p.doctor_id = d.id
    WHERE IFNULL(pm.company_name,'') != ''
      AND pm.company_name NOT LIKE '%Unknown%'
      AND pm.company_name NOT LIKE '%Live search failed%'
      {fsql}
    GROUP BY pm.company_name ORDER BY cnt DESC
    """, fparams)
    rows = cur.fetchall()
    conn.close()

    total = sum(r["cnt"] for r in rows) or 1
    result = []
    for r in rows:
        name = r["company_name"]
        if not name or "unknown" in name.lower():
            continue
        result.append({"company": name, "count": r["cnt"],
                       "percentage": round(r["cnt"] / total * 100, 1)})

    main = [x for x in result if x["percentage"] >= min_percent]
    others = [x for x in result if x["percentage"] < min_percent]
    if others:
        cnt = sum(o["count"] for o in others)
        if cnt:
            main.append({"company": "Others", "count": cnt,
                         "percentage": round(cnt / total * 100, 1),
                         "is_others": True,
                         "members": [o["company"] for o in others][:40]})
    return main


def get_top_doctor_prescribers(own_company_name=None, limit=10, district="",
                               territory="", specialty="", mr_id="", days=None,
                               q="", offset=0):
    """Widget C: Doctor Conversion Leaderboard - searchable + paginated."""
    conn = get_db()
    cur = conn.cursor()

    if not own_company_name:
        row = cur.execute(
            "SELECT name FROM pharma_companies WHERE is_own_company=1 LIMIT 1").fetchone()
        own_company_name = row["name"] if row else "Square Pharmaceuticals Ltd."

    fsql, fparams = _filter_sql(district, territory, specialty, mr_id, days)
    params = list(fparams)
    qsql = ""
    if q:
        qsql = " AND (p.doctor_name LIKE ? OR d.chamber LIKE ? OR d.specialty LIKE ?)"
        params.extend([f"%{q}%"] * 3)

    total = cur.execute(f"""
        SELECT COUNT(*) AS c FROM (
          SELECT p.doctor_id FROM prescriptions p
          LEFT JOIN doctors d ON p.doctor_id = d.id
          WHERE 1=1 {fsql}{qsql} GROUP BY p.doctor_id)
    """, params).fetchone()["c"]

    # Own-company match token: first significant word (e.g. "Square")
    own_token = own_company_name.split()[0] if own_company_name else ""

    cur.execute(f"""
    SELECT p.doctor_id, p.doctor_name, d.chamber, d.specialty, d.district,
           d.territory,
           COUNT(DISTINCT p.id) AS total_prescriptions,
           SUM(CASE WHEN pm.id IS NOT NULL THEN 1 ELSE 0 END) AS total_meds,
           SUM(CASE WHEN pm.company_name LIKE ? THEN 1 ELSE 0 END) AS own_meds
    FROM prescriptions p
    LEFT JOIN doctors d ON p.doctor_id = d.id
    LEFT JOIN prescribed_medicines pm ON pm.prescription_id = p.id
    WHERE 1=1 {fsql}{qsql}
    GROUP BY p.doctor_id
    ORDER BY total_prescriptions DESC, total_meds DESC
    LIMIT ? OFFSET ?
    """, [f"%{own_token}%"] + params + [int(limit), int(offset)])

    result = []
    for r in cur.fetchall():
        total_cnt = r["total_meds"] or 0
        own_cnt = r["own_meds"] or 0
        result.append({
            "doctor_id": r["doctor_id"],
            "doctor_name": r["doctor_name"] or "Unknown",
            "chamber": r["chamber"] or "N/A",
            "specialty": r["specialty"] or "General",
            "district": r["district"] or "",
            "territory": r["territory"] or "",
            "prescriptions": r["total_prescriptions"] or 0,
            "prescription_volume_own": own_cnt,
            "prescription_volume_competitor": max(total_cnt - own_cnt, 0),
            "total": total_cnt,
            "conversion_rate": round(own_cnt / total_cnt * 100, 1) if total_cnt else 0.0,
        })
    conn.close()
    return {"doctors": result, "total": total, "own_company": own_company_name,
            "limit": int(limit), "offset": int(offset)}


def get_company_drilldown(company, limit=10, district="", territory="",
                          specialty="", mr_id="", days=None):
    """Drill-down: top generics + brands for one company (donut slice click)."""
    conn = get_db()
    cur = conn.cursor()
    fsql, fparams = _filter_sql(district, territory, specialty, mr_id, days)

    cur.execute(f"""
    SELECT IFNULL(NULLIF(pm.generic_name,''),'Unspecified') AS generic,
           COUNT(*) AS cnt
    FROM prescribed_medicines pm
    JOIN prescriptions p ON pm.prescription_id = p.id
    LEFT JOIN doctors d ON p.doctor_id = d.id
    WHERE pm.company_name = ? {fsql}
    GROUP BY generic ORDER BY cnt DESC LIMIT ?
    """, [company] + fparams + [limit])
    generics = [{"generic": r["generic"], "count": r["cnt"]} for r in cur.fetchall()]

    cur.execute(f"""
    SELECT pm.brand_name, COUNT(*) AS cnt
    FROM prescribed_medicines pm
    JOIN prescriptions p ON pm.prescription_id = p.id
    LEFT JOIN doctors d ON p.doctor_id = d.id
    WHERE pm.company_name = ? {fsql}
    GROUP BY pm.brand_name ORDER BY cnt DESC LIMIT ?
    """, [company] + fparams + [limit])
    brands = [{"brand_name": r["brand_name"], "count": r["cnt"]} for r in cur.fetchall()]

    cur.execute(f"""
    SELECT p.doctor_name, COUNT(*) AS cnt
    FROM prescribed_medicines pm
    JOIN prescriptions p ON pm.prescription_id = p.id
    LEFT JOIN doctors d ON p.doctor_id = d.id
    WHERE pm.company_name = ? {fsql} AND IFNULL(p.doctor_name,'')!=''
    GROUP BY p.doctor_name ORDER BY cnt DESC LIMIT ?
    """, [company] + fparams + [limit])
    doctors = [{"doctor_name": r["doctor_name"], "count": r["cnt"]} for r in cur.fetchall()]

    total = sum(g["count"] for g in generics)
    conn.close()
    return {"company": company, "total": total, "generics": generics,
            "brands": brands, "doctors": doctors}


def get_brand_doctors(brand_name, limit=15, district="", territory="",
                      specialty="", mr_id="", days=None):
    """Drill-down: which doctors prescribed a given brand (bar click)."""
    conn = get_db()
    cur = conn.cursor()
    fsql, fparams = _filter_sql(district, territory, specialty, mr_id, days)
    cur.execute(f"""
    SELECT p.doctor_name, d.specialty, d.chamber, COUNT(*) AS cnt,
           MAX(p.timestamp) AS last_seen
    FROM prescribed_medicines pm
    JOIN prescriptions p ON pm.prescription_id = p.id
    LEFT JOIN doctors d ON p.doctor_id = d.id
    WHERE pm.brand_name = ? {fsql}
    GROUP BY p.doctor_id ORDER BY cnt DESC LIMIT ?
    """, [brand_name] + fparams + [limit])
    rows = [{"doctor_name": r["doctor_name"] or "Unknown",
             "specialty": r["specialty"] or "General",
             "chamber": r["chamber"] or "N/A",
             "count": r["cnt"], "last_seen": r["last_seen"]}
            for r in cur.fetchall()]
    conn.close()
    return {"brand_name": brand_name, "doctors": rows}


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


# ============ Issue 3: itemized recent scans feed ============

def get_recent_scanned_medicines(limit=50, offset=0, mr_id="", q="",
                                 company="", district="", territory="",
                                 specialty="", days=None, order="created_at",
                                 direction="desc", source=""):
    """Paginated, searchable itemized feed backing the Recent Scans data grid."""
    conn = get_db()
    cur = conn.cursor()

    where, params = ["1=1"], []
    if mr_id:
        where.append("mr_id = ?"); params.append(mr_id)
    if company:
        where.append("company_name LIKE ?"); params.append(f"%{company}%")
    if district:
        where.append("district = ?"); params.append(district)
    if territory:
        where.append("territory = ?"); params.append(territory)
    if specialty:
        where.append("specialty = ?"); params.append(specialty)
    if source:
        where.append("prescription_source = ?"); params.append(source)
    if days:
        where.append("created_at >= ?")
        params.append((datetime.now() - timedelta(days=int(days))).isoformat())
    if q:
        where.append("(brand_name LIKE ? OR generic_name LIKE ? OR "
                     "company_name LIKE ? OR doctor_name LIKE ?)")
        params.extend([f"%{q}%"] * 4)

    allowed_order = {
        "created_at": "created_at", "brand_name": "brand_name",
        "company_name": "company_name", "confidence": "confidence_score",
        "doctor_name": "doctor_name",
    }
    col = allowed_order.get(order, "created_at")
    dirn = "ASC" if str(direction).lower() == "asc" else "DESC"
    clause = " AND ".join(where)

    total = cur.execute(
        f"SELECT COUNT(*) AS c FROM recent_scanned_medicines WHERE {clause}",
        params).fetchone()["c"]

    rows = cur.execute(
        f"""SELECT * FROM recent_scanned_medicines WHERE {clause}
            ORDER BY {col} {dirn}, id DESC LIMIT ? OFFSET ?""",
        params + [int(limit), int(offset)]).fetchall()
    conn.close()
    return {"total": total, "limit": int(limit), "offset": int(offset),
            "items": [dict(r) for r in rows]}


def get_filter_options():
    """Distinct values actually present in the data, for the global filter bar."""
    conn = get_db()
    cur = conn.cursor()

    def distinct(sql):
        return [r[0] for r in cur.execute(sql) if r[0]]

    out = {
        "districts": distinct(
            "SELECT DISTINCT district FROM prescriptions WHERE IFNULL(district,'')!='' ORDER BY district"),
        "territories": distinct(
            "SELECT DISTINCT territory FROM prescriptions WHERE IFNULL(territory,'')!='' ORDER BY territory"),
        "specialties": distinct(
            "SELECT DISTINCT specialty FROM doctors WHERE IFNULL(specialty,'')!='' ORDER BY specialty"),
        "companies": distinct(
            "SELECT DISTINCT company_name FROM prescribed_medicines WHERE IFNULL(company_name,'')!='' ORDER BY company_name"),
        "mr_ids": distinct(
            "SELECT DISTINCT mr_id FROM prescriptions WHERE IFNULL(mr_id,'')!='' ORDER BY mr_id"),
        "sources": distinct(
            "SELECT DISTINCT prescription_source FROM prescriptions WHERE IFNULL(prescription_source,'')!='' ORDER BY prescription_source"),
    }
    conn.close()
    return out


# ============ Global dashboard filtering ============

def _filter_sql(district="", territory="", specialty="", mr_id="", days=None,
                alias="p", doctor_alias="d", source=""):
    """Shared WHERE fragment so every widget honours the global filter bar."""
    where, params = [], []
    if district:
        where.append(f"{alias}.district = ?"); params.append(district)
    if territory:
        where.append(f"{alias}.territory = ?"); params.append(territory)
    if mr_id:
        where.append(f"{alias}.mr_id = ?"); params.append(mr_id)
    if specialty:
        where.append(f"{doctor_alias}.specialty = ?"); params.append(specialty)
    if source:
        where.append(f"{alias}.prescription_source = ?"); params.append(source)
    if days:
        where.append(f"{alias}.timestamp >= ?")
        params.append((datetime.now() - timedelta(days=int(days))).isoformat())
    return (" AND " + " AND ".join(where)) if where else "", params


# ============ Enterprise: officer profile, targets, reports, RSM ============

def _kv_get(key, default=None):
    conn = get_db()
    row = conn.execute("SELECT value FROM app_kv WHERE key=?", (key,)).fetchone()
    conn.close()
    return row["value"] if row else default


def _kv_set(key, value):
    conn = get_db()
    conn.execute("INSERT OR REPLACE INTO app_kv (key, value) VALUES (?, ?)", (key, value))
    conn.commit()
    conn.close()


def get_current_employee_id():
    return _kv_get("current_employee_id", "MR001") or "MR001"


def set_own_company(name):
    """Mark exactly one manufacturer as the officer's own company."""
    name = (name or "").strip()
    if not name:
        return None
    cid = get_or_create_company(name)
    conn = get_db()
    cur = conn.cursor()
    cur.execute("UPDATE pharma_companies SET is_own_company=0")
    cur.execute("UPDATE pharma_companies SET is_own_company=1 WHERE id=?", (cid,))
    conn.commit()
    conn.close()
    return cid


def get_officer_profile(employee_id=None):
    employee_id = employee_id or get_current_employee_id()
    conn = get_db()
    cur = conn.cursor()
    row = cur.execute(
        "SELECT * FROM officer_profiles WHERE employee_id=?", (employee_id,)
    ).fetchone()
    if not row:
        conn.close()
        return None
    profile = dict(row)
    try:
        profile["portfolio"] = json.loads(profile.get("portfolio") or "[]")
    except (TypeError, json.JSONDecodeError):
        profile["portfolio"] = []
    month = datetime.now().strftime("%Y-%m")
    targets = cur.execute(
        "SELECT brand_name, monthly_target, month FROM brand_targets "
        "WHERE employee_id=? AND month=?", (employee_id, month)
    ).fetchall()
    profile["targets"] = [dict(t) for t in targets]
    profile["month"] = month
    conn.close()
    return profile


def save_officer_profile(payload):
    """Upsert the current officer identity card + company + brand targets."""
    employee_id = (payload.get("employee_id") or "MR001").strip() or "MR001"
    now = datetime.now().isoformat()
    portfolio = payload.get("portfolio") or []
    if isinstance(portfolio, str):
        portfolio = [p.strip() for p in portfolio.split(",") if p.strip()]
    company_name = (payload.get("company_name") or "").strip()
    conn = get_db()
    cur = conn.cursor()
    existing = cur.execute(
        "SELECT id FROM officer_profiles WHERE employee_id=?", (employee_id,)
    ).fetchone()
    fields = (
        payload.get("full_name") or "Field Officer",
        payload.get("role") or "MPO",
        company_name,
        payload.get("territory") or "",
        payload.get("zone") or payload.get("territory") or "",
        payload.get("division") or "",
        json.dumps(portfolio),
        payload.get("team_id") or "TEAM-BD-1",
        now,
    )
    if existing:
        cur.execute("""
            UPDATE officer_profiles SET full_name=?, role=?, company_name=?,
                territory=?, zone=?, division=?, portfolio=?, team_id=?, updated_at=?
            WHERE employee_id=?
        """, fields + (employee_id,))
    else:
        cur.execute("""
            INSERT INTO officer_profiles
            (employee_id, full_name, role, company_name, territory, zone,
             division, portfolio, team_id, is_active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
        """, (employee_id,) + fields + (now,))
    cur.execute(
        "INSERT OR REPLACE INTO app_kv (key, value) VALUES ('current_employee_id', ?)",
        (employee_id,),
    )
    conn.commit()
    conn.close()
    if company_name:
        set_own_company(company_name)

    targets = payload.get("targets") or []
    if targets:
        save_brand_targets(employee_id, targets, payload.get("month"))
    return get_officer_profile(employee_id)


def save_brand_targets(employee_id, targets, month=None):
    month = month or datetime.now().strftime("%Y-%m")
    conn = get_db()
    cur = conn.cursor()
    cur.execute("DELETE FROM brand_targets WHERE employee_id=? AND month=?",
                (employee_id, month))
    for t in targets:
        brand = (t.get("brand_name") or "").strip()
        if not brand:
            continue
        try:
            tgt = int(t.get("monthly_target") or 0)
        except (TypeError, ValueError):
            tgt = 0
        cur.execute("""
            INSERT INTO brand_targets (employee_id, brand_name, monthly_target, month)
            VALUES (?, ?, ?, ?)
        """, (employee_id, brand, tgt, month))
    conn.commit()
    conn.close()
    return True


def get_target_progress(employee_id=None, month=None):
    """Compare this month's captured Rx against the officer's brand targets."""
    employee_id = employee_id or get_current_employee_id()
    month = month or datetime.now().strftime("%Y-%m")
    conn = get_db()
    cur = conn.cursor()
    targets = cur.execute(
        "SELECT brand_name, monthly_target FROM brand_targets "
        "WHERE employee_id=? AND month=?", (employee_id, month)
    ).fetchall()
    start = f"{month}-01T00:00:00"
    out = []
    for t in targets:
        brand = t["brand_name"]
        captured = cur.execute("""
            SELECT COUNT(*) AS c FROM prescribed_medicines pm
            JOIN prescriptions p ON pm.prescription_id = p.id
            WHERE p.mr_id=? AND p.timestamp >= ? AND pm.brand_name = ? COLLATE NOCASE
        """, (employee_id, start, brand)).fetchone()["c"]
        tgt = t["monthly_target"] or 0
        pct = round(captured / tgt * 100, 1) if tgt else 0.0
        out.append({
            "brand_name": brand,
            "monthly_target": tgt,
            "captured": captured,
            "remaining": max(tgt - captured, 0),
            "percent": min(pct, 999.0),
            "month": month,
        })
    conn.close()
    return {"employee_id": employee_id, "month": month, "brands": out}


# ============ Doctor Detailing Target Tracker (RSM → MPO) ============

def normalize_doctor_name(name: str) -> str:
    """Loose doctor-name normaliser for matching scans to target lists.

    Strips honorifics (Dr./ডা.), punctuation and collapses whitespace so
    "Dr. A. K. M. Rahman" and "A.K.M. Rahman" land on the same target.
    """
    import re as _re
    text = (name or "").strip().lower()
    text = _re.sub(r"^(dr\.?|ডা\.?|prof\.?|প্রফেসর)\s*", "", text)
    text = _re.sub(r"[.,()\[\]-]", " ", text)
    return " ".join(text.split())


def _doctor_names_match(a: str, b: str) -> bool:
    na, nb = normalize_doctor_name(a), normalize_doctor_name(b)
    if not na or not nb:
        return False
    if na == nb:
        return True
    if na in nb or nb in na:
        return True
    # Token overlap: >=2 shared tokens or >=50% of the shorter name's tokens.
    ta, tb = set(na.split()), set(nb.split())
    if not ta or not tb:
        return False
    shared = ta & tb
    return len(shared) >= min(2, max(1, int(len(ta) * 0.5)))


def match_doctor_targets(cur, doctor_name, mr_id="", prescription_id=None):
    """Auto-log a visit on every target whose doctor matches this scan.

    Runs inside the save_prescription transaction (uses the live cursor).
    UNIQUE(target_id, prescription_id) + INSERT OR IGNORE guarantee the same
    physical Rx never inflates a doctor's visit count twice.
    """
    if not doctor_name:
        return []
    month = datetime.now().strftime("%Y-%m")
    rows = cur.execute(
        "SELECT id, doctor_name, mpo_id, monthly_target FROM doctor_targets "
        "WHERE month=?", (month,)
    ).fetchall()
    now = datetime.now().isoformat()
    matched = []
    for r in rows:
        if not _doctor_names_match(doctor_name, r["doctor_name"]):
            continue
        cur.execute("""
            INSERT OR IGNORE INTO doctor_target_visits
            (target_id, prescription_id, mr_id, doctor_name, visited_at)
            VALUES (?, ?, ?, ?, ?)
        """, (r["id"], prescription_id, mr_id, doctor_name, now))
        matched.append({
            "target_id": r["id"], "doctor_name": r["doctor_name"],
            "mpo_id": r["mpo_id"],
        })
    return matched


def add_doctor_target(payload: dict):
    """RSM attaches a target doctor to an MPO for the current month."""
    conn = get_db()
    cur = conn.cursor()
    mpo_id = (payload.get("mpo_id") or "").strip()
    doctor_name = (payload.get("doctor_name") or "").strip()
    if not mpo_id or not doctor_name:
        conn.close()
        raise ValueError("mpo_id and doctor_name are required")
    month = payload.get("month") or datetime.now().strftime("%Y-%m")
    cur.execute("""
        INSERT INTO doctor_targets
        (mpo_id, mpo_name, doctor_name, specialty, territory, monthly_target, month, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(mpo_id, doctor_name, month)
        DO UPDATE SET monthly_target=excluded.monthly_target,
                      mpo_name=excluded.mpo_name,
                      specialty=excluded.specialty,
                      territory=excluded.territory
    """, (
        mpo_id,
        payload.get("mpo_name") or "",
        doctor_name,
        payload.get("specialty") or "",
        payload.get("territory") or "",
        int(payload.get("monthly_target") or 0),
        month,
        datetime.now().isoformat(),
    ))
    # Any scans already saved this month for this doctor retro-actively count.
    hits = []
    scans = cur.execute(
        "SELECT id, doctor_name, mr_id FROM prescriptions "
        "WHERE timestamp >= ? ORDER BY id ASC", (f"{month}-01T00:00:00",)
    ).fetchall()
    for s in scans:
        if _doctor_names_match(s["doctor_name"], doctor_name):
            hits += match_doctor_targets(cur, s["doctor_name"],
                                         mr_id=s["mr_id"], prescription_id=s["id"])
    conn.commit()
    conn.close()
    return {"ok": True, "retro_visits_logged": len(hits)}


def remove_doctor_target(target_id: int):
    conn = get_db()
    cur = conn.cursor()
    cur.execute("DELETE FROM doctor_target_visits WHERE target_id=?", (target_id,))
    cur.execute("DELETE FROM doctor_targets WHERE id=?", (target_id,))
    conn.commit()
    conn.close()
    return {"ok": True}


def get_doctor_targets(mpo_id="", month=None):
    """Target list + auto-logged visit progress for the tracker card."""
    month = month or datetime.now().strftime("%Y-%m")
    conn = get_db()
    cur = conn.cursor()
    sql = "SELECT * FROM doctor_targets WHERE month=?"
    params = [month]
    if mpo_id:
        sql += " AND mpo_id=?"
        params.append(mpo_id)
    sql += " ORDER BY id DESC"
    rows = cur.execute(sql, params).fetchall()
    out = []
    for r in rows:
        visits = cur.execute(
            "SELECT v.*, p.image_path FROM doctor_target_visits v "
            "LEFT JOIN prescriptions p ON v.prescription_id = p.id "
            "WHERE v.target_id=? ORDER BY v.visited_at DESC", (r["id"],)
        ).fetchall()
        visit_count = len(visits)
        tgt = r["monthly_target"] or 0
        out.append({
            "id": r["id"],
            "mpo_id": r["mpo_id"],
            "mpo_name": r["mpo_name"],
            "doctor_name": r["doctor_name"],
            "specialty": r["specialty"],
            "territory": r["territory"],
            "monthly_target": tgt,
            "month": r["month"],
            "visits": visit_count,
            "remaining": max(tgt - visit_count, 0),
            "percent": round(visit_count / tgt * 100, 1) if tgt else 0.0,
            "last_visit": visits[0]["visited_at"] if visits else "",
            "visit_log": [dict(v) for v in visits[:5]],
        })
    conn.close()
    return {"month": month, "targets": out}


def get_recent_target_visits(limit=25, mpo_id=""):
    """Global visit log feed (newest first) for the RSM tracker card."""
    conn = get_db()
    cur = conn.cursor()
    sql = """
        SELECT v.id, v.target_id, v.prescription_id, v.mr_id, v.doctor_name,
               v.visited_at, t.mpo_id AS target_mpo_id, t.doctor_name AS target_doctor
        FROM doctor_target_visits v
        LEFT JOIN doctor_targets t ON v.target_id = t.id
    """
    params = []
    if mpo_id:
        sql += " WHERE t.mpo_id=?"
        params.append(mpo_id)
    sql += " ORDER BY v.visited_at DESC, v.id DESC LIMIT ?"
    params.append(limit)
    rows = cur.execute(sql, params).fetchall()
    conn.close()
    return [dict(r) for r in rows]


# ============ Geofenced audit verification (off-territory) ============

def find_off_territory_audits(days=30, limit=50, mr_id=""):
    """Scans flagged outside the officer's assigned territory, newest first."""
    conn = get_db()
    cur = conn.cursor()
    since = (datetime.now() - timedelta(days=int(days))).isoformat()
    sql = """
        SELECT id, timestamp, mr_id, doctor_name, doctor_specialty,
               upazila, district, territory, geo_lat, geo_lng,
               territory_note, off_territory, image_path, total_medicines
        FROM prescriptions
        WHERE off_territory=1 AND timestamp >= ?
    """
    params = [since]
    if mr_id:
        sql += " AND mr_id=?"
        params.append(mr_id)
    sql += " ORDER BY timestamp DESC LIMIT ?"
    params.append(limit)
    rows = cur.execute(sql, params).fetchall()
    conn.close()
    out = []
    for r in rows:
        d = dict(r)
        if d.get("image_path"):
            d["image_url"] = f"/uploads/prescriptions/{os.path.basename(d['image_path'])}"
        out.append(d)
    return out


def get_scan_points(days=30, limit=2000):
    """Point-level scan locations for the density-clustering map.

    Falls back to the district centroid (data/bd_geo.json) with a small
    deterministic jitter when the scan has no GPS pin, so district-level
    audits still cluster.
    """
    conn = get_db()
    cur = conn.cursor()
    since = (datetime.now() - timedelta(days=int(days))).isoformat()
    rows = cur.execute("""
        SELECT p.id, p.timestamp, p.mr_id, p.doctor_name, p.district,
               p.territory, p.geo_lat, p.geo_lng, p.off_territory,
               p.duplicate_of,
               (SELECT COUNT(*) FROM prescribed_medicines pm
                  WHERE pm.prescription_id = p.id) AS items
        FROM prescriptions p
        WHERE p.timestamp >= ?
        ORDER BY p.id DESC LIMIT ?
    """, (since, limit)).fetchall()
    conn.close()

    geo_path = os.path.join(os.path.dirname(os.path.dirname(__file__)),
                            "data", "bd_geo.json")
    centroids = {}
    try:
        with open(geo_path, "r", encoding="utf-8") as f:
            centroids = json.load(f).get("districts", {}) or {}
    except Exception:
        pass

    out = []
    for r in rows:
        d = dict(r)
        lat, lng = d.pop("geo_lat", None), d.pop("geo_lng", None)
        if lat is None or lng is None:
            c = centroids.get(d.get("district") or "", {})
            lat, lng = c.get("lat"), c.get("lng")
            if lat is not None and lng is not None:
                # deterministic jitter so same-district audits don't stack
                seed = d.get("id") or 0
                lat += ((seed % 13) - 6) * 0.008
                lng += ((seed % 7) - 3) * 0.008
        d["lat"], d["lng"] = lat, lng
        out.append(d)
    return [d for d in out if d.get("lat") is not None and d.get("lng") is not None]


# ============ TRIPS Waiver Portfolio Tracker (PMD) ============

def get_trips_portfolio(days=90):
    """Field-volume trends for TRIPS-waiver watch molecules, per territory.

    Joins the watch list (compliance.load_trips) against prescribed_medicines
    so PMD sees where high-priority generics are actually being written and
    whether that volume is rising or falling versus the previous period.
    """
    from .compliance import load_trips, _molecule_key_match
    data = load_trips()
    conn = get_db()
    cur = conn.cursor()
    now = datetime.now()
    since = (now - timedelta(days=int(days))).isoformat()
    prev_since = (now - timedelta(days=2 * int(days))).isoformat()

    rows = cur.execute("""
        SELECT pm.generic_name, pm.brand_name, p.territory, p.district,
               p.timestamp
        FROM prescribed_medicines pm
        JOIN prescriptions p ON pm.prescription_id = p.id
        WHERE p.timestamp >= ?
    """, (prev_since,)).fetchall()
    conn.close()

    stats = {}
    boundary = since
    for r in rows:
        g = (r["generic_name"] or "").strip()
        if not g:
            continue
        entry = None
        gk = " ".join(str(g).lower().split())
        for mk, m in (data.get("_index") or {}).items():
            if _molecule_key_match(mk, gk):
                entry = m
                break
        if not entry:
            continue
        key = entry["molecule"]
        st = stats.setdefault(key, {"current": 0, "prev": 0,
                                    "territories": {}, "brands": set(),
                                    "meta": entry})
        if r["timestamp"] >= boundary:
            st["current"] += 1
        else:
            st["prev"] += 1
        terr = r["territory"] or r["district"] or "Unknown"
        st["territories"][terr] = st["territories"].get(terr, 0) + 1
        if r["brand_name"]:
            st["brands"].add(r["brand_name"])

    out = []
    for key, st in stats.items():
        meta = st["meta"]
        delta = st["current"] - st["prev"]
        pct = round(delta * 100.0 / st["prev"], 1) if st["prev"] else None
        top_terr = sorted(st["territories"].items(),
                          key=lambda kv: -kv[1])[:3]
        out.append({
            "molecule": key,
            "class": meta.get("class", ""),
            "originator": meta.get("originator", ""),
            "watch_level": meta.get("watch_level", "medium"),
            "note": meta.get("note", ""),
            "current_volume": st["current"],
            "prev_volume": st["prev"],
            "delta": delta,
            "delta_pct": pct,
            "top_territories": [{"name": t, "count": c} for t, c in top_terr],
            "brands": sorted(st["brands"])[:5],
        })
    # dataset order (watch_level critical first) for molecules with no field
    # volume yet, so PMD still sees the full watch list
    seen = {o["molecule"] for o in out}
    for m in data.get("molecules", []):
        if m["molecule"] not in seen:
            out.append({
                "molecule": m["molecule"], "class": m.get("class", ""),
                "originator": m.get("originator", ""),
                "watch_level": m.get("watch_level", "medium"),
                "note": m.get("note", ""), "current_volume": 0,
                "prev_volume": 0, "delta": 0, "delta_pct": None,
                "top_territories": [], "brands": [],
            })
    level_rank = {"critical": 0, "high": 1, "medium": 2, "low": 3}
    out.sort(key=lambda o: (level_rank.get(o["watch_level"], 9),
                            -o["current_volume"], o["molecule"]))
    return {
        "waiver_expiry": data.get("waiver_expiry", ""),
        "ldc_graduation": data.get("ldc_graduation", ""),
        "context": data.get("context", ""),
        "days": int(days),
        "molecules": out,
        "totals": {
            "watched": len(out),
            "with_field_volume": sum(1 for o in out if o["current_volume"]),
            "volume": sum(o["current_volume"] for o in out),
            "rising": sum(1 for o in out if o["delta"] > 0),
        },
    }


# ============ Antibiotic Stewardship Monitor (per doctor chamber) ============

def get_stewardship_summary(days=30, limit=100):
    """Per-doctor antibiotic prescribing audit for field managers.

    Classifies every prescribed item's molecule with the compliance layer and
    aggregates per doctor: items, antibiotic items, broad-spectrum items and
    the antibiotic share of that chamber's prescribing.
    """
    from .compliance import is_antibiotic, is_broad_spectrum
    conn = get_db()
    cur = conn.cursor()
    since = (datetime.now() - timedelta(days=int(days))).isoformat()
    rows = cur.execute("""
        SELECT p.doctor_id, p.doctor_name, d.specialty, d.district,
               pm.generic_name, pm.brand_name
        FROM prescribed_medicines pm
        JOIN prescriptions p ON pm.prescription_id = p.id
        LEFT JOIN doctors d ON p.doctor_id = d.id
        WHERE p.timestamp >= ? AND IFNULL(p.doctor_name,'') != ''
    """, (since,)).fetchall()
    conn.close()

    docs = {}
    for r in rows:
        key = r["doctor_id"] or r["doctor_name"]
        d = docs.setdefault(key, {
            "doctor_name": r["doctor_name"], "specialty": r["specialty"] or "",
            "district": r["district"] or "", "rx_ids": set(),
            "items": 0, "abx_items": 0, "broad_items": 0, "abx_brands": set(),
        })
        d["rx_ids"].add(True)
        d["items"] += 1
        g = r["generic_name"] or ""
        if is_antibiotic(g):
            d["abx_items"] += 1
            if r["brand_name"]:
                d["abx_brands"].add(r["brand_name"])
            if is_broad_spectrum(g):
                d["broad_items"] += 1

    out = []
    for d in docs.values():
        share = round(d["abx_items"] * 100.0 / d["items"], 1) if d["items"] else 0.0
        out.append({
            "doctor_name": d["doctor_name"],
            "specialty": d["specialty"],
            "district": d["district"],
            "rx": len(d["rx_ids"]),
            "items": d["items"],
            "abx_items": d["abx_items"],
            "broad_items": d["broad_items"],
            "abx_share_pct": share,
            "abx_brands": sorted(d["abx_brands"])[:5],
        })
    out.sort(key=lambda x: (-x["abx_items"], -x["abx_share_pct"]))
    flagged = [x for x in out if x["abx_items"] > 0]
    return {
        "days": int(days),
        "doctors": out[:limit],
        "totals": {
            "doctors_audited": len(out),
            "doctors_with_abx": len(flagged),
            "items": sum(x["items"] for x in out),
            "abx_items": sum(x["abx_items"] for x in out),
            "broad_items": sum(x["broad_items"] for x in out),
            "abx_share_pct": round(
                (sum(x["abx_items"] for x in out) * 100.0
                 / max(sum(x["items"] for x in out), 1)), 1),
        },
    }


def save_error_report(payload):
    conn = get_db()
    cur = conn.cursor()
    cur.execute("""
        INSERT INTO error_reports
        (prescription_id, mr_id, brand_name, reported_text, correction, notes, status, created_at)
        VALUES (?, ?, ?, ?, ?, ?, 'queued', ?)
    """, (
        payload.get("prescription_id"),
        payload.get("mr_id") or get_current_employee_id(),
        payload.get("brand_name") or "",
        payload.get("reported_text") or "",
        payload.get("correction") or "",
        payload.get("notes") or "",
        datetime.now().isoformat(),
    ))
    rid = cur.lastrowid
    conn.commit()
    conn.close()
    return rid


def list_error_reports(limit=50, status=""):
    conn = get_db()
    cur = conn.cursor()
    if status:
        rows = cur.execute(
            "SELECT * FROM error_reports WHERE status=? ORDER BY id DESC LIMIT ?",
            (status, int(limit)),
        ).fetchall()
    else:
        rows = cur.execute(
            "SELECT * FROM error_reports ORDER BY id DESC LIMIT ?", (int(limit),)
        ).fetchall()
    conn.close()
    return [dict(r) for r in rows]


def get_own_vs_competitor(own_company_name=None, district="", territory="",
                          specialty="", mr_id="", days=None, limit=15):
    """Per-doctor Own Brand vs Competitor share — commercial conversion view."""
    conn = get_db()
    cur = conn.cursor()
    if not own_company_name:
        r = cur.execute(
            "SELECT name FROM pharma_companies WHERE is_own_company=1 LIMIT 1"
        ).fetchone()
        own_company_name = r["name"] if r else "Healthcare Pharmaceuticals Ltd."
    own_token = own_company_name.split()[0] if own_company_name else ""
    fsql, fparams = _filter_sql(district, territory, specialty, mr_id, days)
    cur.execute(f"""
        SELECT p.doctor_name, d.specialty, d.district, d.territory,
               COUNT(*) AS total,
               SUM(CASE WHEN pm.company_name LIKE ? THEN 1 ELSE 0 END) AS own_cnt
        FROM prescribed_medicines pm
        JOIN prescriptions p ON pm.prescription_id = p.id
        LEFT JOIN doctors d ON p.doctor_id = d.id
        WHERE IFNULL(p.doctor_name,'')!='' {fsql}
        GROUP BY p.doctor_id
        HAVING total > 0
        ORDER BY total DESC
        LIMIT ?
    """, [f"%{own_token}%"] + fparams + [int(limit)])
    doctors = []
    for r in cur.fetchall():
        total = r["total"] or 0
        own = r["own_cnt"] or 0
        comp = max(total - own, 0)
        doctors.append({
            "doctor_name": r["doctor_name"],
            "specialty": r["specialty"] or "General",
            "district": r["district"] or "",
            "territory": r["territory"] or "",
            "own": own,
            "competitor": comp,
            "total": total,
            "own_share": round(own / total * 100, 1) if total else 0.0,
            "competitor_share": round(comp / total * 100, 1) if total else 0.0,
        })
    conn.close()
    return {"own_company": own_company_name, "doctors": doctors}


def get_rsm_dashboard(team_id="", rsm_employee_id="", days=30):
    """Aggregated prescription audits across the RSM's MPO team (50+)."""
    conn = get_db()
    cur = conn.cursor()
    where, params = ["1=1"], []
    if team_id:
        where.append("team_id=?"); params.append(team_id)
    if rsm_employee_id:
        where.append("rsm_employee_id=?"); params.append(rsm_employee_id)
    members = [dict(r) for r in cur.execute(
        f"SELECT * FROM team_members WHERE {' AND '.join(where)} ORDER BY mpo_mr_id",
        params,
    ).fetchall()]
    since = (datetime.now() - timedelta(days=int(days or 30))).isoformat()
    own_row = cur.execute(
        "SELECT name FROM pharma_companies WHERE is_own_company=1 LIMIT 1"
    ).fetchone()
    own_company = own_row["name"] if own_row else "Healthcare Pharmaceuticals Ltd."
    own_token = own_company.split()[0]

    team = []
    tot_rx = tot_items = tot_own = 0
    for m in members:
        mr = m["mpo_mr_id"]
        stats = cur.execute("""
            SELECT COUNT(DISTINCT p.id) AS rx,
                   COUNT(pm.id) AS items,
                   SUM(CASE WHEN pm.company_name LIKE ? THEN 1 ELSE 0 END) AS own_items
            FROM prescriptions p
            LEFT JOIN prescribed_medicines pm ON pm.prescription_id = p.id
            WHERE p.mr_id=? AND p.timestamp >= ?
        """, (f"%{own_token}%", mr, since)).fetchone()
        rx = stats["rx"] or 0
        items = stats["items"] or 0
        own_items = stats["own_items"] or 0
        tot_rx += rx
        tot_items += items
        tot_own += own_items
        team.append({
            **m,
            "prescriptions": rx,
            "items": items,
            "own_items": own_items,
            "competitor_items": max(items - own_items, 0),
            "sov_percent": round(own_items / items * 100, 1) if items else 0.0,
        })
    team.sort(key=lambda x: x["prescriptions"], reverse=True)
    conn.close()
    return {
        "team_size": len(members),
        "days": int(days or 30),
        "own_company": own_company,
        "totals": {
            "prescriptions": tot_rx,
            "items": tot_items,
            "own_items": tot_own,
            "competitor_items": max(tot_items - tot_own, 0),
            "sov_percent": round(tot_own / tot_items * 100, 1) if tot_items else 0.0,
        },
        "members": team,
    }


def get_rsm_trends(team_id="", rsm_employee_id="", days=30):
    """Week-over-week Share-of-Voice series per team member.

    Buckets the `days` window into ~7-day bins and returns per-MPO a list of
    `{week, own, competitor, rx, sov}` points so the RSM can render a sparkline
    showing whether the team is gaining ground on competitors.
    """
    conn = get_db()
    cur = conn.cursor()
    where, params = ["1=1"], []
    if team_id:
        where.append("team_id=?"); params.append(team_id)
    if rsm_employee_id:
        where.append("rsm_employee_id=?"); params.append(rsm_employee_id)
    members = [dict(r) for r in cur.execute(
        f"SELECT * FROM team_members WHERE {' AND '.join(where)} ORDER BY mpo_mr_id",
        params,
    ).fetchall()]
    days = int(days or 30)
    base = datetime.now() - timedelta(days=days)
    since = base.isoformat()
    bucket_count = max(1, (days + 6) // 7)
    own_row = cur.execute(
        "SELECT name FROM pharma_companies WHERE is_own_company=1 LIMIT 1"
    ).fetchone()
    own_company = own_row["name"] if own_row else "Healthcare Pharmaceuticals Ltd."
    own_token = own_company.split()[0]

    def _bucket(iso):
        try:
            dt = datetime.fromisoformat(str(iso))
        except Exception:
            return -1
        diff = (dt - base).days
        return int(diff // 7) if diff >= 0 else -1

    trends = []
    for m in members:
        mr = m["mpo_mr_id"]
        rows = cur.execute("""
            SELECT p.timestamp, pm.company_name
            FROM prescriptions p
            LEFT JOIN prescribed_medicines pm ON pm.prescription_id = p.id
            WHERE p.mr_id=? AND p.timestamp >= ?
        """, (mr, since)).fetchall()
        own = [0] * bucket_count
        comp = [0] * bucket_count
        rx = [0] * bucket_count
        for r in rows:
            b = _bucket(r["timestamp"])
            if b < 0 or b >= bucket_count:
                continue
            rx[b] += 1
            if own_token and own_token.lower() in (r["company_name"] or "").lower():
                own[b] += 1
            else:
                comp[b] += 1
        series = []
        for b in range(bucket_count):
            total = own[b] + comp[b]
            series.append({
                "week": b + 1,
                "label": f"W{b + 1}",
                "own": own[b],
                "competitor": comp[b],
                "rx": rx[b],
                "sov": round(own[b] / total * 100, 1) if total else 0.0,
            })
        # week-over-week growth of own prescriptions (last full vs previous)
        growth = 0.0
        prev = None
        for s in series:
            if prev is None:
                prev = s["own"]
                continue
            if prev > 0:
                growth = round((s["own"] - prev) / prev * 100, 1)
            prev = s["own"]
        trends.append({
            **m,
            "series": series,
            "own_growth": growth,
        })
    conn.close()
    return {
        "own_company": own_company,
        "days": days,
        "bucket_count": bucket_count,
        "trends": trends,
    }


# ===========================================================================
# Vision training queue (Human-in-the-loop handwriting retraining)
# ===========================================================================

def save_training_item(payload: dict, image_path: str = "") -> int:
    conn = get_db()
    cur = conn.cursor()
    now = datetime.now().isoformat()
    cur.execute("""
        INSERT INTO vision_training
        (prescription_id, mr_id, brand_name, corrected_brand, corrected_company,
         raw_text, image_path, confidence, notes, status, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, (
        payload.get("prescription_id"),
        payload.get("mr_id") or get_current_employee_id() or "",
        payload.get("brand_name") or "",
        payload.get("corrected_brand") or "",
        payload.get("corrected_company") or "",
        payload.get("raw_text") or "",
        image_path,
        payload.get("confidence"),
        payload.get("notes") or "",
        payload.get("status") or "queued",
        now,
    ))
    rid = cur.lastrowid
    conn.commit()
    conn.close()
    return rid


def list_training_queue(limit: int = 100, status: str = "", mr_id: str = ""):
    conn = get_db()
    cur = conn.cursor()
    where, params = ["1=1"], []
    if status:
        where.append("status=?"); params.append(status)
    if mr_id:
        where.append("mr_id=?"); params.append(mr_id)
    rows = [dict(r) for r in cur.execute(
        f"SELECT * FROM vision_training WHERE {' AND '.join(where)} "
        f"ORDER BY created_at DESC LIMIT ?",
        params + [int(limit)],
    ).fetchall()]
    conn.close()
    return {"items": rows, "total": len(rows)}


def training_queue_stats():
    conn = get_db()
    cur = conn.cursor()
    rows = cur.execute(
        "SELECT status, COUNT(*) AS c FROM vision_training GROUP BY status"
    ).fetchall()
    conn.close()
    return {r["status"]: r["c"] for r in rows}


# ===========================================================================
# Doctor prescribing tiering (A/B/C) + at-risk switchers
# ===========================================================================

def get_doctor_tiers(own_company_name="", territory="", district="", specialty="",
                     days=30, tier="", min_rx=1, limit=200):
    """Classify doctors by monthly audit volume and flag at-risk switchers.

    Tier A  >10 prescriptions/month   (high prescriber)
    Tier B   4-9 prescriptions/month (medium)
    Tier C  <=3 prescriptions/month  (occasional)

    `at_risk` marks high-volume doctors whose own-brand share is low, i.e. they
    are writing competitor brands most of the time -> a switch-away risk.
    """
    conn = get_db()
    cur = conn.cursor()
    since = (datetime.now() - timedelta(days=int(days or 30))).isoformat()
    own_row = cur.execute(
        "SELECT name FROM pharma_companies WHERE is_own_company=1 LIMIT 1"
    ).fetchone()
    own_company = own_company_name or (own_row["name"] if own_row else "Healthcare Pharmaceuticals Ltd.")
    own_token = own_company.split()[0]

    where = ["p.timestamp >= ?", "IFNULL(p.doctor_name,'') != ''"]
    params = [since]
    if territory:
        where.append("p.territory = ?"); params.append(territory)
    if district:
        where.append("p.district = ?"); params.append(district)
    if specialty:
        where.append("d.specialty = ?"); params.append(specialty)
    wsql = " AND ".join(where)

    rows = cur.execute(f"""
        SELECT p.doctor_id, p.doctor_name, d.specialty AS d_specialty,
               d.district AS d_district, d.territory AS d_territory,
               COUNT(DISTINCT p.id) AS rx,
               COUNT(pm.id) AS items,
               SUM(CASE WHEN pm.company_name LIKE ? THEN 1 ELSE 0 END) AS own_items
        FROM prescriptions p
        LEFT JOIN prescribed_medicines pm ON pm.prescription_id = p.id
        LEFT JOIN doctors d ON p.doctor_id = d.id
        WHERE {wsql}
        GROUP BY p.doctor_id
        HAVING rx >= ?
        ORDER BY rx DESC
        LIMIT ?
    """, [f"%{own_token}%"] + params + [int(min_rx), int(limit)]).fetchall()

    tiers = []
    for r in rows:
        rx = r["rx"] or 0
        items = r["items"] or 0
        own = r["own_items"] or 0
        sov = round(own / items * 100, 1) if items else 0.0
        t = "A" if rx > 10 else ("B" if rx >= 4 else "C")
        # at-risk: high-volume doctor but own share < 30%
        at_risk = (rx >= 4) and (sov < 30)
        tiers.append({
            "doctor_id": r["doctor_id"],
            "doctor_name": r["doctor_name"],
            "specialty": r["d_specialty"] or "General",
            "district": r["d_district"] or "",
            "territory": r["d_territory"] or "",
            "rx": rx,
            "items": items,
            "own_items": own,
            "competitor_items": max(items - own, 0),
            "sov": sov,
            "tier": t,
            "at_risk": at_risk,
        })
    conn.close()
    if tier:
        tiers = [t for t in tiers if t["tier"] == tier.upper()]
    return {
        "own_company": own_company,
        "days": int(days or 30),
        "total": len(tiers),
        "doctors": tiers,
    }


# ===========================================================================
# Geo heatmap / territory penetration
# ===========================================================================

def _district_centroid(district):
    """Best-effort (lat, lng) anchor for a district from bd_geo.json."""
    try:
        path = os.path.join(os.path.dirname(os.path.dirname(__file__)),
                            "data", "bd_geo.json")
        with open(path, "r", encoding="utf-8") as f:
            geo = json.load(f)
        d = (geo.get("districts") or {}).get(district, {})
        return d.get("lat"), d.get("lng")
    except Exception:
        return None, None


def get_geo_heatmap(own_company_name="", days=30, division="", district=""):
    """Aggregate prescriptions by district + upazila for the BD heatmap.

    Returns own vs competitor item counts + market penetration score per region,
    plus a district rollup. Geo coords come from the prescriptions row (the
    itemized feed does not carry lat/lng); when missing we fall back to the
    district centroid so the map always renders.
    """
    conn = get_db()
    cur = conn.cursor()
    since = (datetime.now() - timedelta(days=int(days or 30))).isoformat()
    own_row = cur.execute(
        "SELECT name FROM pharma_companies WHERE is_own_company=1 LIMIT 1"
    ).fetchone()
    own_company = own_company_name or (own_row["name"] if own_row else "Healthcare Pharmaceuticals Ltd.")
    own_token = own_company.split()[0]

    where = ["pm.prescription_id IS NOT NULL", "p.timestamp >= ?", "IFNULL(p.district,'') != ''"]
    params = [since]
    if division:
        where.append("p.division = ?"); params.append(division)
    if district:
        where.append("p.district = ?"); params.append(district)
    wsql = " AND ".join(where)

    rows = cur.execute(f"""
        SELECT p.district, p.upazila, p.territory, p.geo_lat, p.geo_lng,
               COUNT(DISTINCT p.id) AS rx,
               COUNT(pm.id) AS items,
               SUM(CASE WHEN pm.company_name LIKE ? THEN 1 ELSE 0 END) AS own_items
        FROM prescriptions p
        LEFT JOIN prescribed_medicines pm ON pm.prescription_id = p.id
        WHERE {wsql}
        GROUP BY p.district, p.upazila
        ORDER BY items DESC
    """, [f"%{own_token}%"] + params).fetchall()

    regions = []
    district_rollup = {}
    for r in rows:
        items = r["items"] or 0
        own = r["own_items"] or 0
        sov = round(own / items * 100, 1) if items else 0.0
        lat, lng = r["geo_lat"], r["geo_lng"]
        if lat is None or lng is None:
            clat, clng = _district_centroid(r["district"])
            lat = lat if lat is not None else clat
            lng = lng if lng is not None else clng
        reg = {
            "district": r["district"],
            "upazila": r["upazila"] or r["district"],
            "territory": r["territory"] or "",
            "rx": r["rx"] or 0,
            "items": items,
            "own_items": own,
            "competitor_items": max(items - own, 0),
            "sov": sov,
            "lat": lat,
            "lng": lng,
        }
        regions.append(reg)
        d = district_rollup.setdefault(r["district"], {
            "district": r["district"], "division": "", "items": 0, "own_items": 0,
            "rx": 0, "lat": None, "lng": None,
        })
        d["items"] += items
        d["own_items"] += own
        d["rx"] += r["rx"] or 0
        if d["lat"] is None and lat:
            d["lat"] = lat
        if d["lng"] is None and lng:
            d["lng"] = lng

    districts = []
    for name, d in district_rollup.items():
        sov = round(d["own_items"] / d["items"] * 100, 1) if d["items"] else 0.0
        lat, lng = d["lat"], d["lng"]
        if lat is None or lng is None:
            clat, clng = _district_centroid(name)
            lat = lat if lat is not None else clat
            lng = lng if lng is not None else clng
        districts.append({
            "district": name,
            "division": d["division"],
            "items": d["items"],
            "own_items": d["own_items"],
            "competitor_items": max(d["items"] - d["own_items"], 0),
            "rx": d["rx"],
            "sov": sov,
            "penetration": sov,
            "lat": lat,
            "lng": lng,
        })
    districts.sort(key=lambda x: (-x["items"], x["district"]))
    conn.close()
    return {
        "own_company": own_company,
        "days": int(days or 30),
        "regions": regions,
        "districts": districts,
    }
