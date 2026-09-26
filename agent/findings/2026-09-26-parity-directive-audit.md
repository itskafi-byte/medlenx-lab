# Parity directive audit — 2026-09-26

Audit of the three-module parity directive (verification brand variants, generic
substitution engine, prescription audit summary) against the actual state of
`android/`, with the web references it cites resolved to real code.

**The directive's web file references do not exist.** It names
`components/prescription/DataVerification.tsx`, `PrescriptionAuditSummary.tsx`,
and `findGenericSubstitution()`. `origin/main` is a **Flask/Python** app with a
single `templates/index.html` (4,507 lines) — there is no React, no TypeScript,
and no `components/` directory. Everything below was located in the real source.

---

## Verdict per module

| Module | Data / logic layer | UI layer | Real gap |
|---|---|---|---|
| 1 — brand form variations | **present** | **missing** | render the accordion, apply the pick |
| 2 — generic substitution | **present** | **partial** | card is absent from the scan review stream; no Copy pitch / Mark as won |
| 3 — audit summary | **present** | **stub** | `RxBreakdownSheet` is 159 lines of raw rows; the drawer it should be does not exist |

Two of the three "completely omits" claims are wrong. What is missing in all
three cases is the **Compose layer**, not the model, the resolver, or the math.

---

## Module 1 — brand variants

Web: `index.html:1911`, inside the verification medicine card.

```js
const altOptions = (med.alternatives && med.alternatives.length)
  ? `<details class="mt-1.5"><summary>...${med.alternatives.length} other match${...'es':''} for this brand</summary>`
```

Populated by `main.py:404 _alternatives_payload(entries, chosen, limit=6)` — every
catalogue variant of the matched brand except the chosen one — and installed at
`main.py:473`/`:485` whenever the brand is ambiguous or has more than one entry.
Clicking a variant (`index.html:3312 attachAlternativePickers`) makes the **user's
choice authoritative**: it writes company / strength / type / generic / image URL,
then sets `company_source='user'`, `company_verified=true`,
`company_ambiguous=false`, `company_conflict=false`, `needs_review=false`, and
re-renders.

Android already has all of this except the UI:

* `data/model/ScanModels.kt:97` — `alternatives: List<MedexProduct>`
* `data/repo/MedicineEnricher.kt:129` — `alternatives = variants.filter { it !== pick }`
  with the comment *"so the rep can pick a different strength or company by hand
  when the automatic choice is wrong"*
* **`ui/theme/Color.kt:82` — indigo tokens labelled `alternatives picker`.** The
  colour was defined for a picker that was never built.
* `grep -rn "alternatives" ui/` → only that colour token. The UI never reads it.

So the directive's `BrandVariant` data class would be a **second model for the
same thing**: `MedexProduct` (`data/model/Catalogue.kt`) already carries
`brandName, generic, strength, form, type, company, ingredient, category,
imageUrl, packImage, url, mrp, pack` — strictly more than the proposed shape.

## Module 2 — generic substitution

Web: `app/intelligence.py:174 generic_substitution()` and `:239 smart_pitch_note()`,
rendered by `index.html:1696 substitutionCard()`, installed onto every non-own
medicine by `main.py:424 _attach_field_intelligence()`.

Payload: `competitor{...}` / `own_brand{...}` (brand, company, generic, strength,
type, image_url, mrp, pack), `generic`, `unit_difference`, `unit_difference_label`
(`"X.XX BDT lower per unit"`), `pitch`.

The directive calls the badge a **percentage** ("35% less"). The web computes a
**per-unit BDT delta** — `unit_diff = round(o_mrp - d_mrp, 2)`. There is no
percentage anywhere in the code path.

Android: `Substitution` (`ScanModels.kt:160`) with exactly those fields, computed
in `ScanViewModel` from the officer's own company, plus
`Compliance.substitutionEvidenceNotes()` for the bioequivalence lines. `RxAuditScreen`
already renders the card and offers the pitch. **The gap is that it is not in the
scan review stream**, where the web puts it (`index.html:1965`, inside the
verification card), and neither `Copy pitch` nor `Mark as won` exists.

`Mark as won` on the web calls `queueTrainingItem(..., 'Marked as won conversion on
substitution pitch')` — it queues a **retraining example**, not a "weekly
performance record" as the directive states. Android's equivalent table already
exists: `ErrorReportEntity` (`error_reports`).

## Module 3 — audit summary

Web: drawer markup `index.html:552-630`, render `:2807-2995`, opened by
`openRxAuditDrawer(pid)` from the "Recent Prescriptions" cards on the workspace and
dashboard tabs (`:2734`). Backed by **`GET /api/prescriptions/{pid}`**
(`main.py:928`) — the same route the directive calls `/audit`.

Drawer content: header (`doctor (specialty) • N Medicines Detected • MR x • district`),
duplicate-Rx fraud tag, search box, four filter pills with live counts, clinical
strip (polypharmacy / antibiotic stewardship / off-territory), therapeutic-class
segmented bar + legend, item table with per-row NEML / TRIPS / ABX★ / class /
confidence / own-portfolio badges, market-share footer, CSV + clipboard export.

Android has every helper:

* `data/repo/RxAudit.kt` — `buildMarketShare`, `sameCompanyLoose`, `itemsToCsv`,
  `itemsToClipboard`, `isDuplicateHash`
* `data/repo/Compliance.kt` — `polypharmacyIndex`, `therapyBreakdown`, `nemlLookup`,
  `isAntibiotic`, `isBroadSpectrum`, `resolveTherapeuticClass`, `priceCeilingAlert`,
  `tripsLookup`
* `data/local/Entities.kt:113` — `ScannedMedicineEntity` persists every flag the
  drawer badges read: `is_own`, `is_antibiotic`, `broad_spectrum`,
  `therapeutic_class`, `neml_listed`, `dgda_flagged`, `trips_watch`,
  `confidence_score`, `image_url`, `generic`, `strength`, `dosage_form`, `dosage`
* `prescriptionDao.medicinesFor(id)` loads a stored prescription's items

What exists instead: `ui/screens/analytics/RxBreakdownSheet.kt` (159 lines) —
thumbnail, brand, `strength · form · dosage`, company, confidence. That is the
"raw medicine text strings or basic counters" in the report, and it is accurate.

So `PrescriptionAuditSummarySheet.kt` as a *new file* would duplicate a sheet that
is already wired end-to-end (`MedLenXShell.kt:532` → `analyticsVm.breakdown`). The
work is to grow that sheet, not to add a parallel one.

---

## Consequences for the build

1. **No new data models.** `MedexProduct` is the brand-variant row; `MedexProduct`
   and `Substitution` need no companion types.
2. **No new resolver.** `Intelligence.kt` already computes substitutions.
3. **No parallel sheet.** `RxBreakdownSheet` becomes the audit summary.
4. Three UI builds, in ascending order of blast radius: Module 1 (one card),
   Module 2 (one card + two actions), Module 3 (one sheet, five sections).
5. Where the directive and the web disagree — the price badge percentage, the
   "weekly performance records" — **the web wins**, per the standing rule that
   parity is judged against `origin/main`.
