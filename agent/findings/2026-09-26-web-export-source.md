# The web's CSV export advertises a filter it cannot apply — 2026-09-26

**Where:** `app/main.py:840` — `export_recent_medicines()`
**Status:** open, in the **web app**. The Android port deliberately does not
reproduce it; see "What Android does instead" below.
**Found while:** porting the `fSource` dropdown (`93dad0c`).

---

## The bug

`index.html:2255-2268` builds the export URL from the global filters:

```js
if(globalFilters.district)  p.set('district',globalFilters.district);
if(globalFilters.specialty) p.set('specialty',globalFilters.specialty);
if(globalFilters.mr_id)     p.set('mr_id',globalFilters.mr_id);
if(globalFilters.source)    p.set('source',globalFilters.source);   // ← sent
if(globalFilters.days)      p.set('days',globalFilters.days);
window.location.href='/api/export/recent-medicines.csv?'+p.toString();
```

The endpoint does not declare the parameter:

```python
@app.get("/api/export/recent-medicines.csv")
async def export_recent_medicines(
    mr_id: str = "", q: str = "", company: str = "", district: str = "",
    territory: str = "", specialty: str = "", days: Optional[int] = None,
    limit: int = 5000,
):
    data = get_recent_scanned_medicines(...)   # no source= passed
```

Flask ignores unknown query parameters, so `source=Hospital` is accepted,
parsed, and dropped. **No error is raised anywhere** — not in the response, not
in the log. The CSV comes back containing every prescription source.

The call *below* it proves this is an oversight rather than a decision:
`get_recent_scanned_medicines` (`database.py:1329`) already accepts `source=""`
and already filters on it (`database.py:1348-1349`). Only the route is missing
the parameter.

## What the user sees

The dashboard is filtered to Private Chamber. The user exports a CSV for the
field team. The CSV is unfiltered. Nothing distinguishes the two files.

## The fix

Two lines — add the parameter and forward it:

```python
async def export_recent_medicines(
    mr_id: str = "", q: str = "", company: str = "", district: str = "",
    territory: str = "", specialty: str = "", days: Optional[int] = None,
    source: str = "",
    limit: int = 5000,
):
    data = get_recent_scanned_medicines(
        limit=limit, offset=0, mr_id=mr_id, q=q, company=company,
        district=district, territory=territory, specialty=specialty,
        days=days, source=source)
```

## What Android does instead

`Daos.kt` `recentMedicineExportRows` **does** accept and apply `source`. The
export honours the filter the UI is showing. A parity bug is not worth
reproducing in a port: the divergence is recorded here and in the DAO's KDoc,
so it is a decision rather than an accident.

Note the export is not otherwise inconsistent — it is the one endpoint the web
backs with the denormalised `recent_scanned_medicines` row instead of a join, so
it filters specialty on the **captured** `p.doctor_specialty` rather than the
doctor's current profile. That is faithful and in place (`RX_FILTER_SQL_SNAPSHOT`).

## How to check the fix

Export with a source filter set and confirm the CSV narrows. The unit-level
check is that `get_recent_scanned_medicines(source="Hospital")` and
`source="Private Chamber"` disagree; the route-level one is that the two
export URLs now produce different files.
