"""
Diagnostic harness: proves each reported issue BEFORE the fix.
Run against a live server:  .venv/bin/python tests/diagnose_issues.py
"""
import io
import json
import os
import re
import sqlite3
import sys
import urllib.request

BASE = os.environ.get("BASE", "http://localhost:8000")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB = os.path.join(ROOT, "data", "medlenx.db")
TPL = os.path.join(ROOT, "templates", "index.html")

results = []


def check(issue, name, failing, detail):
    results.append((issue, name, failing, detail))
    flag = "REPRODUCED" if failing else "ok"
    print(f"[{flag:^11}] {issue} :: {name}")
    if detail:
        for line in str(detail).splitlines():
            print(f"              {line}")


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=30) as r:
        return json.loads(r.read().decode())


def post_json(path, payload):
    req = urllib.request.Request(
        BASE + path, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())


def scan():
    """Upload a dummy image through /api/scan (mock VL mode)."""
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", (400, 600), "white").save(buf, format="JPEG")
    body = buf.getvalue()
    boundary = "----medlenxdiag"
    payload = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
        f"filename=\"rx.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"
    ).encode() + body + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(
        BASE + "/api/scan", data=payload,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST")
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode())


html = open(TPL, encoding="utf-8").read()

print("=" * 78)
print("ISSUE 1 - Specialty / District / Upazila / Territory mapping & sync")
print("=" * 78)

# 1a. Is there any cascading location data source at all?
has_location_api = False
try:
    get("/api/locations")
    has_location_api = True
except Exception:
    pass
check("ISSUE-1", "cascading District->Upazila->Territory data source exists",
      not has_location_api,
      "No /api/locations endpoint; fields are free-text inputs with no mapping.")

# 1b. Are the location fields free-text inputs rather than bound selects?
free_text = bool(re.search(r'fieldRow\(\s*[\'"]District[\'"]', html))
check("ISSUE-1", "District/Upazila/Territory are bound selects (not free text)",
      free_text,
      "Rendered via fieldRow() -> plain <input>. No option list, no cascade, "
      "so any typo becomes a new district and analytics group wrongly.")

# 1c. Does the verify payload actually persist location + specialty columns?
doc_cols = []
if os.path.exists(DB):
    con = sqlite3.connect(DB)
    doc_cols = [r[1] for r in con.execute("PRAGMA table_info(prescriptions)")]
    con.close()

s = scan()
pid = s.get("id")
verify_payload = {
    "doctor": {
        "name": "Dr. Sync Test", "bmdc_no": "A-99999",
        "specialty": "Cardiology", "chamber": "Test Chamber",
        "qualifications": "MBBS", "hospital": "Test Hospital",
        "upazila": "Savar", "district": "Dhaka", "territory": "Dhaka North",
    },
    "medicines": s.get("medicines", []),
}
post_json(f"/api/prescriptions/{pid}/verify", verify_payload)

con = sqlite3.connect(DB)
con.row_factory = sqlite3.Row
row = con.execute(
    "SELECT doctor_specialty, upazila, district, territory, doctor_json "
    "FROM prescriptions WHERE id=?", (pid,)).fetchone()
lost = []
if (row["district"] or "") != "Dhaka":
    lost.append(f"prescriptions.district = {row['district']!r} (expected 'Dhaka')")
if (row["upazila"] or "") != "Savar":
    lost.append(f"prescriptions.upazila = {row['upazila']!r} (expected 'Savar')")
if (row["territory"] or "") != "Dhaka North":
    lost.append(f"prescriptions.territory = {row['territory']!r} (expected 'Dhaka North')")
if (row["doctor_specialty"] or "") != "Cardiology":
    lost.append(f"prescriptions.doctor_specialty = {row['doctor_specialty']!r} (expected 'Cardiology')")

# doctors table too - specialty drives the Generic/Brand matrix widget
drow = con.execute(
    "SELECT specialty, district, upazila, territory FROM doctors WHERE bmdc_no='A-99999'"
).fetchone()
if drow is None:
    lost.append("doctors row for BMDC A-99999 not created/updated by verify")
else:
    if (drow["specialty"] or "") != "Cardiology":
        lost.append(f"doctors.specialty = {drow['specialty']!r} (expected 'Cardiology')")
    if (drow["district"] or "") != "Dhaka":
        lost.append(f"doctors.district = {drow['district']!r} (expected 'Dhaka')")
con.close()

check("ISSUE-1", "verify persists specialty/district/upazila/territory",
      bool(lost), "\n".join(lost) or "all persisted")

print()
print("=" * 78)
print("ISSUE 2 - 'Verify & Save to DB' has no UI feedback")
print("=" * 78)

uses_alert = "alert('Verified & Saved!" in html or 'alert("Verified & Saved!' in html
check("ISSUE-2", "uses toast (not blocking alert)", uses_alert,
      "Success path calls window.alert() - blocking modal, no toast system.")

has_toast = "function toast" in html or "showToast" in html
check("ISSUE-2", "a toast/notification system exists", not has_toast,
      "No toast helper defined anywhere in the template.")

has_spinner = bool(re.search(r"btn\.disabled\s*=\s*true", html)) and \
              bool(re.search(r"fa-(circle-notch|spinner)\s+fa-spin", html))
check("ISSUE-2", "save button shows loading state / is disabled during save",
      not has_spinner,
      "Button is never disabled -> double-click submits the prescription twice.")

resets = bool(re.search(r"verifySaveBtn[\s\S]{0,1200}?resetWorkspace", html))
check("ISSUE-2", "workspace resets to idle after successful save", not resets,
      "After save the verification panel stays open with stale data; "
      "only Cancel hides it.")

# does the API even return a message the UI prints?
r = post_json(f"/api/prescriptions/{pid}/verify", verify_payload)
check("ISSUE-2", "verify API returns a 'message' field the UI displays",
      "message" not in r,
      f"API returned {r} but UI prints data.message -> shows 'undefined'.")

print()
print("=" * 78)
print("ISSUE 3 - Scanned medicines not saved to Recent Scans (itemized)")
print("=" * 78)

con = sqlite3.connect(DB)
tables = [r[0] for r in con.execute(
    "SELECT name FROM sqlite_master WHERE type='table'")]
check("ISSUE-3", "dedicated itemized recent-scan table exists",
      "recent_scanned_medicines" not in tables,
      f"tables = {tables}")

has_mr_on_items = False
if "prescribed_medicines" in tables:
    cols = [r[1] for r in con.execute("PRAGMA table_info(prescribed_medicines)")]
    has_mr_on_items = "mr_id" in cols
    check("ISSUE-3", "itemized rows carry mr_id for field-rep filtering",
          not has_mr_on_items,
          f"prescribed_medicines columns = {cols}")
con.close()

try:
    get("/api/recent-medicines")
    has_recent_api = True
except Exception:
    has_recent_api = False
check("ISSUE-3", "API to list recent scanned medicines exists", not has_recent_api,
      "No /api/recent-medicines endpoint; Recent Scans shows prescriptions only, "
      "never the individual detected medicines.")

renders_items = "recentMedicines" in html or "recent-medicines" in html
check("ISSUE-3", "UI renders itemized recent medicines", not renders_items,
      "loadHistory() lists prescriptions with a count only.")

print()
print("=" * 78)
print("ISSUE 4 - Company share chart colour conflict")
print("=" * 78)

m = re.search(r"const colors=data2\.companies\.map\(([^;]+)\);", html)
snippet = m.group(0) if m else "(not found)"
two_tone = bool(m and "'#e5e7eb'" in m.group(0))
check("ISSUE-4", "donut uses distinct per-company colours", two_tone,
      f"{snippet}\n-> every company that is not 'square'/'healthcare' gets the "
      "SAME grey #e5e7eb, so all competitors are visually identical.")

has_palette = "COMPANY_COLOR_PALETTE" in html or "getCompanyColor" in html
check("ISSUE-4", "a company colour mapper exists", not has_palette,
      "No palette/hash fallback defined.")

purple = html.count("#8b5cf6")
check("ISSUE-4", "charts avoid bright violet (enterprise palette)", purple > 0,
      f"bright violet #8b5cf6 used {purple}x in chart configs.")

print()
print("=" * 78)
print("DESIGN - dark mode removal")
print("=" * 78)
dark_classes = len(re.findall(r"\bdark:", html))
has_toggle = "themeToggle" in html or "applyTheme" in html
check("DESIGN", "dark mode fully removed", dark_classes > 0 or has_toggle,
      f"{dark_classes} 'dark:' utility classes, theme toggle present={has_toggle}")

print()
print("=" * 78)
repro = [r for r in results if r[2]]
print(f"SUMMARY: {len(repro)} of {len(results)} checks reproduce a defect")
for issue, name, failing, _ in results:
    if failing:
        print(f"   - {issue}: {name}")
print("=" * 78)
sys.exit(0)
