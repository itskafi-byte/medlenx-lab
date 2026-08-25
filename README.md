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

## API

- `POST /api/scan` - upload image, returns doctor + medicines with MedEx images
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
