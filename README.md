# MedLenX Lab - Pure MedLenX VL Prescription Scanner

Pure vision-language model only - no TF-IDF, no Trie, no Phonetic, no Fixes. Just **MedLenX VL** reads prescription + enriches from scraped **medex.com.bd** DB with images.

App name: **MedLenX Lab**

## Features

- **Pure MedLenX VL**: Single call to vision model reads doctor block (name, qualifications on top) + medicines
- **MedEx Full Scraper**: Scrapes every medicine from medex.com.bd
  - Forms: Tablet, Capsule, Syrup, Injection, IV Infusion, Cream, Drop, Inhaler, Suppository, Powder, Suspension, Ointment, Gel, Lotion, Sachet, Vial, Ampoule, Spray, Nebuliser, Eye Drop, Nasal Drop, etc.
  - Data: brand_name, generic, strength, form, type, company, ingredient, category, pack image URL, medex URL
  - Images: Pack images scraped from `https://medex.com.bd/storage/images/packaging/...` + added after detection
- **Image after detection**: Each medicine card shows scraped pack image from MedEx
- **Dashboard** saves all scans

## Quick Start

```bash
pip install -r requirements.txt
cp .env.example .env
# edit .env add OPENROUTER_API_KEY from https://openrouter.ai/keys

python run.py
# http://localhost:8000
```

Without API key runs in MOCK demo mode.

## Scrape MedEx Every Medicine

Scraper at `app/scraper/medex_scraper.py` - scrapes every form:

```bash
# Scrape 500 medicines (quick demo)
python -m app.scraper.medex_scraper --limit 500 --output data/medex_full.json

# Scrape with images downloaded to static/images/medicines/
python -m app.scraper.medex_scraper --limit 1000 --download-images

# Scrape specific alphabets
python -m app.scraper.medex_scraper --alpha a,b,c --limit 1000

# Scrape ALL ~17k medicines (takes hours, polite delay)
python -m app.scraper.medex_scraper --all --download-images
```

Output JSON example:
```json
{
  "id": "13717",
  "brand_name": "3 Bion",
  "generic": "Vitamin B1, B6 & B12",
  "strength": "100 mg+200 mg+200 mcg",
  "form": "Tablet",
  "type": "Tablet",
  "company": "Jenphar Bangladesh Ltd.",
  "ingredient": "Vitamin B1... composition",
  "image_url": "https://medex.com.bd/storage/images/packaging/3-bion-...webp",
  "pack_image": "https://medex.com.bd/...webp",
  "url": "https://medex.com.bd/brands/13717/3-bion-100-mg-tablet"
}
```

## Rx Audit Drawer, Duplicate-Rx Fraud Alert & Doctor Target Tracker

- **Prescription Audit Summary drawer** — click any card under *Recent Prescriptions*
  (Analytics Dashboard) to slide open an isolated item breakdown: medicine brand +
  dosage form, generic molecule, pharmaceutical manufacturer badge and AI
  confidence badge (`<80%` rows glow soft orange with a **Verify against Medex**
  button that queues the item for the handwriting-retraining pipeline).
- **Search + filter inside the drawer**: `All (n) | Own Pharma (n) | Competitors (n) | <80% (n)` pills and a free-text search over scanned items.
- **Own Portfolio Match pill** — competitor rows expand into the client company's
  matching brand (e.g. Seclo/Square -> Opal/Healthcare) with price difference and
  an MPO pitch note, for in-chamber detailing.
- **Market Share Summary** for the Rx (own vs competitor, counts + %).
- **Export Rx Items as CSV** and **Copy List to Clipboard** for audit reporting.
- **Crop preview on hover** — hovering a medicine name pops a thumbnail of the
  prescription scan.
- **Duplicate Rx fraud alert** — every scan is fingerprinted with a pure-Python
  DCT perceptual hash (`app/rx_audit.py`). Re-uploading the same physical Rx
  (even resized / recompressed) flags a red **Duplicate Rx Detected** tag on the
  card, in the drawer and as a post-scan alert, so target inflation via
  double-scanning is caught before it reaches KPIs.
- **Doctor Detailing Target Tracker** (RSM Command tab) — RSMs attach target
  doctor lists to MPOs; every scan whose doctor matches a target auto-logs a
  visit (deduped per prescription), with progress bars and a live visit log.

## DGDA NEML & Price Ceiling Monitor, Prescribing Analytics, Pitch Cards, Geofencing

- **NEML Compliance Badge** — drawer rows show a blue `NEML Listed` pill for
  molecules on the DGDA National Essential Medicines List (`data/neml_list.json`,
  ~295-molecule NEML alignment: Omeprazole, Metformin, Amlodipine, Azithromycin...).
- **MRP Ceiling Violation Warning** — red `DGDA Price Alert` flag when a brand is
  banned, its MRP was ceiling-adjusted by gazette, or a captured price exceeds
  the DGDA ceiling; unverified pricing gets a muted note instead of false alarms.
- **Polypharmacy Risk Counter** — `⚠️ 8+ Meds Prescribed — High Polypharmacy`
  top-level drawer badge (5-7 = moderate).
- **Therapeutic Class Breakdown** — stacked percentage bar (Cardiology /
  Gastroenterology / Antibiotics / ...) from NEML classes + MedEx categories.
- **Antibiotic Stewardship Tag** — `ABX` pill per row; broad-spectrum molecules
  (Azithromycin, Cefixime, fluoroquinolones...) get a red `ABX ★` watch-list tag
  and a stewardship summary badge.
- **MPO Detailing Action Cards** — every Own Portfolio Match row has a
  **Generate Doctor Pitch Card** button: a mobile-friendly modal (MRP delta,
  pack, strength/type, compliance evidence, smart pitch script) plus a
  one-page **PDF** download (`/api/prescriptions/{pid}/pitch-card.pdf?idx=`).
- **Geofenced Audit Verification** — the workspace has a **GPS** pin button;
  scans are geofenced against the officer's assigned territory using
  `data/bd_geo.json` district centroids, and mismatches surface as an
  **Off-Territory Audit** flag in the drawer and a red RSM Command card
  (`/api/rsm/off-territory`).
- **Density Clustering Map** — the RSM heatmap gains a *Density clusters* view:
  zoom-aware grid clusters of audit pins with counts, chamber hotspots and
  off-territory/duplicate counters (`/api/rsm/scan-points`).

## API

- `POST /api/scan` - upload image, returns doctor + medicines with MedEx images (+ `duplicate` fraud alert when the same Rx was scanned before)
- `GET /api/prescriptions/{pid}` - full Prescription Audit Summary drawer payload (items, market share, duplicate flag, portfolio matches)
- `GET /api/prescriptions/{pid}/export.csv` - download the Rx's detected items as CSV
- `GET /api/prescriptions/{pid}/clipboard` - plain-text audit list for reporting channels
- `GET|POST /api/rsm/doctor-targets` - doctor detailing target list / attach target (retro-counts this month's scans)
- `DELETE /api/rsm/doctor-targets/{id}` - remove a doctor target
- `GET /api/rsm/doctor-targets/visits` - auto-logged visit feed from prescription scans
- `GET /api/prescriptions/{pid}/pitch-card.pdf?idx=` - one-page MPO Doctor Pitch Card (PDF) for a matched competitor row
- `GET /api/rsm/off-territory` - geofenced audits captured outside the officer's assigned territory
- `GET /api/rsm/scan-points` - point-level audit locations for the density-clustering map
- `GET /api/medex?q=Napa&form=Tablet&limit=20` - search scraped DB
- `GET /api/health` - model + DB count

## Pure MedLenX VL Prompt

No approaches:
```
You are MedLenX VL, expert BD prescription reader.
Analyze image, output JSON with doctor and medicines list.
No DB lookup, just read handwriting.
```

Then backend enriches with MedEx DB fuzzy match to get image.

## Structure

```
medlenx_lab/
├── app/
│   ├── main.py (FastAPI pure)
│   ├── config.py (MedLenX VL branding)
│   ├── medlenx_client.py (OpenRouter wrapper, branded MedLenX)
│   ├── database.py
│   └── scraper/medex_scraper.py (scrapes every form + images)
├── data/
│   ├── medex_full.json (scraped full - every medicine)
│   └── medex_extended.json (fallback 150)
├── templates/index.html (MedLenX Lab UI)
├── static/images/medicines/ (downloaded pack images)
└── uploads/prescriptions/
```

## Model

Behind MedLenX VL branding uses `qwen/qwen3-vl-235b-a22b-instruct` via OpenRouter, but all Qwen references removed from UI - shows as **MedLenX VL 1.0-Pro**.

## License

MIT - MedLenX Lab

## Enterprise Dashboard & Data Integrity (v3.1)

### Cascading location engine
`data/bd_locations.json` holds all 8 divisions, **64 districts, 508 upazilas**
and pharma sales territories. The verification form renders bound `<select>`
elements (District → Upazila → Territory); changing the district invalidates
its dependents. `resolve_location()` validates every triple server-side, so an
upazila that does not belong to its district is dropped instead of stored.

```bash
GET /api/locations      # cascade + specialty list
```

### Itemized recent scans
Every detected medicine is written to `recent_scanned_medicines` (append-only,
keyed by `mr_id`) in addition to the `prescribed_medicines` analytics junction.
Re-verifying a prescription replaces its rows rather than duplicating them.

```bash
GET /api/recent-medicines?q=&mr_id=&limit=&offset=&order=&direction=
GET /api/export/recent-medicines.csv
```

### Global filters & drill-down
Every dashboard widget honours Territory / District / Specialty / MR / date range.

```bash
GET /api/filters                            # distinct values present in data
GET /api/dashboard/kpis?district=&days=
GET /api/dashboard/company-drilldown?company=
GET /api/dashboard/brand-doctors?brand=
```

### Design system — light only
Dark mode is fully removed (no `dark:` classes, no toggle, `<html class="light">`).

| Token | Colour | Use |
|---|---|---|
| Primary | `#0F172A` | Headers, primary buttons |
| Secondary | `#2563EB` | Active states, links |
| Success | `#059669` | Verified badges |
| Warning | `#D97706` | Needs review |
| Surface | `#F8FAFC` | App background |
| Card | `#FFFFFF` | Panels |
| Border | `#E2E8F0` | Dividers |

Company chart colours come from `COMPANY_COLOR_PALETTE` with a deterministic
HSL hash fallback, so every manufacturer gets a distinct, stable slice.

## Tests

```bash
.venv/bin/python -m pytest tests/ -q        # 77 unit/integration tests
node tests/dom_smoke.js                     # 34 DOM tests (server on :8000)
.venv/bin/python tests/diagnose_issues.py   # issue reproduction harness
```
