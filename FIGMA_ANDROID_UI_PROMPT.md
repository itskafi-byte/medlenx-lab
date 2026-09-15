# MedLenX Lab → Android App · Figma Design Prompt

> Reverse-engineered from `templates/index.html` (4,507 lines) of `itskafi-byte/medlenx-lab`.
> Every colour, label, badge, table column and empty-state below is quoted from the
> actual source, so the Android design can be a 1:1 port with **zero feature loss**.

---

## 0. How to use this file

1. Paste **§1 MASTER PROMPT** into Figma's *First Draft / Generate design* (or hand it to a
   designer as a brief). It sets the design system + shell.
2. Then generate **one screen at a time** using the prompts in **§4**. Figma AI degrades
   badly if you ask for 39 screens in one shot.
3. Keep **§3 ANTI-TRUNCATION RULES** pinned next to you while reviewing output — they are
   the "nothing must be truncated" contract.
4. Use **§5 COVERAGE CHECKLIST** to audit the finished file line by line.

**Target frame size:** `412 × 915` (Pixel 7 / Material 3 default).
Also export 2 tablet variants at `840 × 1160` for the Analytics and Team tabs.

---

## 1. MASTER PROMPT  —  (copy everything between the rules)

```
Design a native Android app (Material 3) called "MedLenX Lab", a pharmaceutical
field-intelligence app for Bangladeshi medical promotion officers (MPOs) that scans
handwritten prescriptions with an AI vision model, verifies the read against the
MedEx / DGDA drug catalogue, and reports competitive Share of Voice.

The app must look like an existing light-mode web dashboard, ported 1:1 to mobile.
It is a dense, data-heavy enterprise tool — NOT a consumer app. Preserve every field,
badge, pill, table column, footnote and empty state. Nothing may be simplified away.

────────────────────────────────────────────
DESIGN TOKENS (use exactly these values)
────────────────────────────────────────────
Font: Inter, weights 400 / 500 / 600 / 700 only.

Colour — brand ramp
  brand/900  #0F172A   app text, primary buttons, active nav, dark hero gradient start
  brand/800  #172554
  brand/700  #1E40AF   section icon accents, hero gradient end
  brand/600  #1D4ED8   primary action, focus ring
  brand/500  #2563EB   links, focus border
  brand/400  #94A3B8   muted icons
  brand/300  #CBD5E1   dashed borders
  brand/200  #E2E8F0   all card/input borders
  brand/100  #F1F5F9   subtle fills
  brand/50   #F8FAFC   SCREEN BACKGROUND (slate-50)

Semantic
  ok/600 #047857   ok/500 #059669   ok/100 #D1FAE5   ok/50 #ECFDF5   → verified / own-brand
  warn/600 #B45309 warn/500 #D97706 warn/100 #FEF3C7 warn/50 #FFFBEB → needs review
  AI-guess band   #EA580C on #FFF7ED border #FDBA74  (distinct from warn/amber)
  danger          #DC2626 on #FEE2E2 border #FCA5A5  → duplicate Rx, off-territory, DGDA ban
  violet          #7C3AED on #EDE9FE border #C4B5FD  → own portfolio match, doctor tier A
  cyan            #0891B2 / #0284C7 / #38BDF8        → scan laser + shimmer loading
  chart series    #1E40AF, #059669, #D97706, #0891B2, #7C3AED, #DC2626
  map — Share of Voice: ≥60% #10B981 · 35–59% #F59E0B · <35% #EF4444
  map — density clusters: ≥10 #7C3AED · 5–9 #0284C7 · 2–4 #10B981 · 1 #94A3B8

Surfaces
  screen        #F8FAFC
  card          #FFFFFF, border 1px #E2E8F0, radius 12dp, elevation-1
  panel (hero)  radius 16dp
  control       radius 12dp (buttons/inputs) · 8dp (small chips) · full (pills)
  sheet/modal   radius 16dp top corners, elevation-5
  dark hero     linear-gradient 135° #0F172A → #1E40AF, white text, plus a soft
                256px white 10%-opacity blurred circle bleeding off the top-right corner

Type scale (web sizes kept, mapped to sp)
  Display / hero H1     20sp / 700  (tight tracking)
  Card title            14sp / 600
  Section label         12sp / 600, uppercase, tracking +1.2%, #64748B
  Field label           10sp / 700, uppercase, tracking +1.0%, #64748B
  Body                  12–13sp / 400
  Meta / footnote       11sp / 400  #64748B
  Micro pill            10sp / 600
  Regulatory micro pill  9sp / 700
  KPI number            24sp / 700  ·  mini KPI 20sp / 700

Spacing: 4dp grid. Screen margin 16dp. Card padding 16dp (20dp for panels). Card gap 12dp.
Iconography: Font Awesome 6 solid equivalents, 16dp in nav, 12–14dp inline.

────────────────────────────────────────────
APP SHELL
────────────────────────────────────────────
Bottom navigation bar, 56dp + gesture inset, white, 1px top border #E2E8F0, five
destinations with icon over 10sp label. Active item = pill filled #0F172A, white icon
+ white label. Inactive = #64748B.
  1. Scan        (columns icon)
  2. Analytics   (chart-line)
  3. Hub         (flask)
  4. Team        (sitemap)
  5. Settings    (cog)

Top app bar, 64dp, translucent white with 12dp blur, 1px bottom border #E2E8F0:
  left  — 32dp rounded-8 tile, gradient #0F172A→#1E40AF, white microscope glyph,
          wordmark "MedLenX" 14sp/700
  centre— search field, radius 12, 1px #E2E8F0, magnifier at left,
          placeholder "Search medicines, doctors, generics - always accessible
          from anywhere..."
  right — status chips, then 32dp circular avatar #0F172A with user glyph
Status chips (10sp/500, radius full, 10dp×4dp padding):
  · "Online"      — #ECFDF5 bg, #047857 text, 1px #A7F3D0, 6dp dot
  · "0 queued"    — #0F172A bg, white text, cloud-up arrow in #93C5FD  (offline scan queue)
  · company chip  — #0F172A bg, white text, building glyph #93C5FD, default
                    "Set company in Settings"
  · latency chip  — #ECFDF5 bg, #065F46 text, "— ms"
  · "Install"     — #0F172A pill (hide on Android, replace with nothing)

Content area scrolls under the app bar with 16dp horizontal padding and 24dp vertical,
plus 24dp bottom clearance above the bottom nav.

Brand lockup (used on splash + sidebar header):
  40dp rounded-12 tile, gradient #0F172A→#1E40AF, white microscope,
  "MedLenX Lab" 16sp/700 tight, sub-label "Prescription Intelligence" 10sp #64748B

────────────────────────────────────────────
GLOBAL COMPONENT LIBRARY (build these first)
────────────────────────────────────────────
1.  Status pill           — radius full, 1px border, 10sp/600. Variants: emerald,
                            amber, orange, red, blue, violet, slate, dark.
2.  Regulatory micro-pill — radius full, 1px border, 9sp/700, 12dp icon + label.
    Exact label set: "NEML Listed" (blue), "DGDA Price Alert" (red), "ABX" (amber),
    "ABX ★" (red), "TRIPS Watch" (amber), therapeutic-class chip (slate),
    "Duplicate Rx Detected" (red), "Off-Territory Audit" (red), "also Duplicate" (white/red).
3.  Confidence badge      — radius full, 1px border, 10sp/600:
                            ≥85% emerald · <80% orange "NN% AI Guess" with robot glyph
                            <70% amber "NN% manual flag" with flag glyph · else amber "NN%"
4.  Pharma company badge  — 20dp white rounded-6 chip, 1px #E2E8F0, containing the
                            company logo (initials on #0284C7 as fallback) + optional
                            label. Size variants 20 / 28 / 36dp.
5.  Verified company pill — solid #2563EB, white text, logo + name + check-circle.
    Unverified variant — gradient #F59E0B→#F97316, white text, warning triangle.
    No-company variant — #E2E8F0 bg, #475569 text, "Company not identified".
6.  KPI card              — white, 12dp radius, 20dp padding: 11sp grey label, 24sp/700
                            value, delta chip 10sp/600 (▲ green / ▼ red), 40dp rounded-12
                            tinted icon tile top-right, optional row of 3 micro pills
                            ("Today n", "Week n", "Month n") and a 6dp progress track.
7.  Mini KPI tile         — 12dp radius, 12dp padding, 10sp label + 20sp/700 value.
                            Used inside dark heroes (white 10% fill) and light cards.
8.  Filter chip           — radius full, 12sp/600, 14dp×6dp padding; active = #0F172A
                            fill + white text, inactive = white + 1px #E2E8F0 + #475569.
9.  Segmented toggle      — 8dp radius container, active segment #0F172A white.
10. Select field          — 12dp radius, 1px #E2E8F0, 12sp, chevron right.
11. Text field            — 12dp radius, 1px #E2E8F0, 14sp, focus = 1px #2563EB + 2dp
                            #2563EB/20 outer ring. Amber-tint variant for low confidence.
12. Primary button        — #0F172A, white, radius 12, 14sp/500, 12dp×10dp padding.
13. Success button        — #059669 (press #047857), white, radius 12.
14. Ghost/outline button  — white, 1px #E2E8F0, #0F172A text, radius 12.
15. Icon button           — 32dp, radius 8, 1px #E2E8F0.
16. Refresh pill          — #F1F5F9, radius full, 11sp, rotate glyph.
17. Data table → mobile   — convert every web table into a stacked card row. Show ALL
                            columns as labelled micro-rows or trailing badges. Header
                            row becomes an optional 11sp/600 sticky label strip.
18. Progress bar          — 6dp track #E2E8F0, 6dp fill; emerald ≥100/target met,
                            amber 50–99, rose <50. For SoV/ABX share use a 8dp track.
19. Sparkline             — 72×22dp inline SVG-equivalent polyline, #059669.
20. Skeleton / shimmer    — #F1F5F9 blocks with a cyan (#06B6D4 18%) sweep; loading
                            rows carry a 4dp cyan left edge.
21. Scan laser            — 3dp horizontal bar, gradient transparent→#0284C7→#38BDF8
                            →#0284C7→transparent, 12dp cyan glow, sweeping vertically
                            over 2.2s; plus a full-panel cyan gradient wash.
22. Toast / Snackbar      — top-anchored card, min 280dp / max 380dp wide, radius 12,
                            1px border, leading 16dp icon. Variants: loading (blue
                            spinner), success (emerald check), error (red bang),
                            info (blue i).
23. Empty state           — 48dp circle #F1F5F9 with #94A3B8 glyph, 12sp #64748B line,
                            optional primary pill CTA.
24. Bottom sheet          — 16dp top radius, 40dp grabber, drag handle area 24dp.

────────────────────────────────────────────
SCREEN INVENTORY — 39 frames, name them exactly
────────────────────────────────────────────
00 Splash
01 Shell — App Bar + Bottom Nav (base template)
02 Global Search — results overlay
03 Snackbar / Toast states

04 Scan — Empty state (upload)
05 Scan — Camera capture
06 Scan — AI scanning in progress
07 Verify — Prescription image viewer
08 Verify — Doctor info (BMDC verification)
09 Verify — Medicine list & confidence
10 Medicine card — all states
11 Verify — GPS territory strip & save
12 Verify — Saved / synced

13 Analytics — Overview
14 Analytics — Filter bottom sheet
15 Analytics — Live Recent Scans
16 Analytics — Recent Prescriptions
17 Rx Audit Summary
18 Rx Audit — item row states
19 Own Portfolio Match — expanded row
20 Doctor Pitch Card
21 Drill-down sheet

22 Hub — 25K+ Drug Index
23 Hub — Popular medicines hero
24 Hub — TRIPS Waiver Tracker
25 Hub — Industry News
26 Hub — Health & Pharma Jobs
27 Hub — Health Days calendar
28 Health Day campaign card

29 Team — Command overview
30 Team — Territory penetration map
31 Team — Doctor tiering A/B/C
32 Team — MPO leaderboard
33 Team — Doctor detailing target tracker
34 Team — Off-territory audit verification
35 Team — Antibiotic stewardship monitor

36 Settings — Enterprise settings & officer profile
37 Help & Guide
38 Empty / Loading / Error / Offline states

Produce the component library page first, then one frame per screen, each named as
listed, laid out in a 12-column flow on a #F8FAFC canvas with 120dp gutters.
```

---

## 2. Screen-by-screen facts (source of truth)

Everything the web app renders, per tab. Use it to verify the Figma output.

| # | Source | Content |
|---|---|---|
| Nav | sidebar = **6** destinations, bottom nav = **5** (Help is desktop-only → on Android put it in Settings) |
| Tabs | `workspace` · `dashboard` · `database` · `rsm` · `settings` · `help` |
| Hub sub-tabs | **5** — 25K+ Drug Index · TRIPS Waiver Tracker · Industry News · Health & Pharma Jobs · Health Days |
| Tables | **9** total (live recent scans, Rx audit items, TRIPS, doctor tiers, team leaderboard, doctor targets, stewardship, pitch-card compare, drill lists) |
| Overlays | Rx Audit drawer, Drill-down modal, Doctor Pitch Card modal, Health Day modal, Global search dropdown, Crop-preview popover, Toasts |

---

## 3. ANTI-TRUNCATION RULES  ← this is the "nothing must be truncated" contract

1. **No ellipsis on any semantic value.** Brand names, generics, strengths, company
   names, doctor names, BMDC numbers and territory names must wrap to a second line or
   expand the card. Only free-text summaries may clamp to 2 lines (that matches the web
   `line-clamp-2`).
2. **Every web table column survives.** Convert tables to stacked rows and keep all
   columns:
   - Live Recent Scans (7): Time · Doctor · Specialty · Medicine (brand + strength +
     dosage form) · Company (colour dot + verified/unverified icon) · Conf. · Location
   - Rx Audit items (4): Medicine Brand (+ type • dosage, + all pills) · Generic
     Composition (+ NEML, + TRIPS) · Pharmaceutical (logo + name + own-brand house icon) ·
     Conf.
   - TRIPS watch list (8): Molecule · Class · Originator · Watch level · Window ·
     Field volume · Δ vs prev (+%) · Top territories
   - Doctor tiers (10): Tier · Doctor · Specialty · Territory · Rx/mo · Items · Own ·
     Competitor · SoV · Status (⚠ At risk / —)
   - Team leaderboard (10): MPO · Role · Territory · Rx · Items · Own · Competitor ·
     SoV · SoV trend sparkline · WoW (▲/▼ %)
   - Doctor targets (8): MPO · Target doctor · Specialty · Target · Visits (auto-logged)
     · Progress (bar + %) · Last visit · delete action
   - Stewardship (8): Doctor (chamber) · Specialty · District · Rx audited · ABX items
     · Broad ★ · ABX share (bar + %) · Brands seen
   - Pitch card compare (5): Brand · Company · Generic · Strength/Form · MRP (BDT + pack)
3. **Never merge two badges into one.** `NEML Listed`, `DGDA Price Alert`, `ABX ★`,
   `TRIPS Watch`, therapeutic-class chip and `Duplicate Rx Detected` are independent and
   can all appear on the same row at once. Lay them out in a wrapping chip row.
4. **Keep the footnotes.** Strings such as
   `Hover a medicine name to preview the prescription crop` →
   `Long-press a medicine name to preview the prescription crop`;
   `Show during chamber visit`; `Source: Live from medex.com.bd with real pack images •
   Updates on each load • 25k medicines DB`;
   `Offline-first PWA: scans cache in IndexedDB when the rural network drops, then sync
   on reconnect.` All of these must appear somewhere on the matching screen.
5. **Keep every empty state, word for word.** The web app has 10+ distinct ones (see §5).
6. **Keep the three confidence bands visually distinct** — emerald ≥85%, orange AI-guess
   <80%, amber manual-flag <70%. Do not collapse them into one grey "confidence".
7. **Keep the regulatory semantics colour-coded exactly**: emerald = own/verified,
   amber = review, orange = AI guess, red = fraud/ban/off-territory, violet = own
   portfolio match, blue = NEML/listed, cyan = system working.
8. **Text must not sit under the app bar or bottom nav.** 24dp bottom clearance.
9. **Charts must keep their axis labels, legends and the "locked until N scans" states.**
10. **48dp minimum touch target** on every control, even where the web used 10px text.

---

## 4. PER-SCREEN PROMPTS

### 4.1 · Shell & system

**00 Splash**
```
Android splash, 412x915, background #F8FAFC. Centred brand lockup: 72dp rounded-16 tile
with 135° gradient #0F172A→#1E40AF and a white microscope glyph; below it "MedLenX Lab"
28sp/700 #0F172A with tight tracking, then "Prescription Intelligence" 13sp #64748B.
At the bottom a 4dp progress track #E2E8F0 with a 40% #1D4ED8 fill and the caption
"Connecting to MedLenX VL…" 11sp #64748B. No status bar mock, no other chrome.
```

**01 Shell — App Bar + Bottom Nav**
```
Base app shell template, 412x915. Top: 64dp translucent-white app bar (blur 12dp, 1px
bottom border #E2E8F0) holding the 32dp gradient logo tile + "MedLenX" wordmark, a
search field with placeholder "Search medicines, doctors, generics - always accessible
from anywhere...", and a right cluster of chips: "Online" (emerald), "0 queued" (dark
with cloud-up arrow), company chip "Set company in Settings" (dark, building glyph),
latency "— ms" (emerald), and a 32dp dark avatar. Bottom: 56dp white bottom nav with
1px top border, five items Scan / Analytics / Hub / Team / Settings, "Scan" active as a
filled #0F172A pill. Middle: empty #F8FAFC content area with 16dp side padding.
```

**02 Global Search — results overlay**
```
Frame 02: the shell with the search field focused (border #2563EB, 2dp #2563EB/20 ring)
and a results sheet dropping from it — white, 12dp radius, 1px #E2E8F0, elevation-4,
max height 320dp, scrollable. Six result rows, each 48dp+: 40dp rounded-8 pack shot on
#F8FAFC with 1px border, brand name 12sp/600, meta line 10sp #64748B
"Tablet 500 mg • Square Pharmaceuticals Ltd." (truncate company at 25 chars as the web
does), and a blue micro-pill with the dosage form. 1px #F1F5F9 dividers between rows.
Show one row pressed (#F8FAFC).
```

**03 Snackbar / Toast states**
```
Frame 03: a spec strip showing the four toast variants stacked on a dimmed app: loading
(white, 1px #E2E8F0, blue spinner, "Extracting with MedLenX VL…"), success (white,
1px #A7F3D0, emerald check, "Target attached — visits to Dr. A. K. M. Rahman will
auto-log from scans"), error (white, 1px #FCA5A5, red bang, "Could not attach target:
HTTP 400"), info (white, 1px #BFDBFE, blue i, "Off-territory audits refreshed").
Each 340dp wide, 12dp radius, 16dp×12dp padding, 14sp/500, anchored top-right with 16dp
inset. Add one Android snackbar at the bottom: #0F172A, 14sp white text "Pitch script
copied".
```

---

### 4.2 · Scan flow (Workspace tab)

**04 Scan — Empty state**
```
Frame 04, 412x915, on the shell with "Scan" active. A single hero card filling the width:
white, 16dp radius, 2px DASHED border #BFDBFE, elevation-1. Inside, 40dp×40dp padding,
centred: an 80dp rounded-16 tile with 135° gradient #0F172A→#1E40AF and a white
file-medical glyph at 32dp with a soft shadow; heading "Drop prescription image here"
20sp/700; body 13sp #64748B, max 320dp wide, centred: "Drag & drop or click to upload.
Supports camera capture, rotation, contrast adjustment for cursive handwriting."
Then two buttons side by side, 16dp gap: primary #0F172A white "Choose File" with an
upload glyph, and outline white "Open Camera" with a camera glyph.
Below the card, a subtle caption 11sp #64748B: "Offline-first — scans cache on device
when the rural network drops, then sync on reconnect."
```

**05 Scan — Camera capture**
```
Frame 05: full-bleed rear-camera viewfinder on a black background. A rounded-16
transparent window showing a prescription pad, with 40dp corner brackets in #38BDF8.
Top overlay: a translucent black pill "Hold still for 1 second" 11sp white. Bottom
control deck on #0F172A: a 72dp white circular shutter with a 4dp #F8FAFC ring, a 40dp
icon button left (gallery) and a 40dp icon button right (torch). Above the deck a row of
three chips: "Portrait", "Landscape", "Flash off". Caption under the deck, 10sp
#94A3B8: "Tap Open Camera (not Choose File) on phones. Hold still 1s. Review confidence
badges."
```

**06 Scan — AI scanning in progress**
```
Frame 06: the prescription image fills the top 45% of the screen inside a white 16dp
card. Over the image: the dual-glow radar scan — a 3dp horizontal laser bar with
gradient transparent→#0284C7→#38BDF8→#0284C7→transparent and a 12dp cyan glow, mid-sweep,
plus a soft cyan gradient wash. Top-right of the image, a pill on #0891B2/90 white 10sp/700
with a pulsing white dot: "Scanning…".
Below, a white card with a spinner and "Extracting with MedLenX VL..." 12sp, plus a 6dp
track #E2E8F0 with a 40% #1D4ED8 fill.
Then skeleton loading: a 16dp×128dp label block, two doctor-row skeletons and four
medicine-row skeletons — each #F1F5F9 with a 4dp cyan-500 left edge, 12dp radius, 16dp
padding, containing a 1/3-width and a 1/4-width #E2E8F0 bar, with a cyan shimmer sweep.
```

**07 Verify — Prescription image viewer**
```
Frame 07: full-screen white viewer sheet. Header row 56dp: title "Prescription Image
Viewer" 14sp/600 with a blue image glyph, then six 32dp icon buttons in a row —
zoom in, zoom out, rotate left, rotate right, contrast (half-filled circle), fit.
Below the header a 32dp hint strip on #F8FAFC with 1px bottom border, 11sp #64748B:
"Scroll to zoom · Drag to pan · 100%" (on Android: "Pinch to zoom · Drag to pan · 100%").
Body: the prescription image centred on #F1F5F9, filling the remaining height, with an
amber (#FB923C) bounding-box outline pulsing over the second medicine line and a small
label chip. Bottom-left corner overlay pill on #0F172A/80 white 10sp:
"Left: Image Viewer • Right: Data Verification" → reword for mobile as
"Swipe up for data verification".
Bottom sticky bar on #F8FAFC with 1px top border: three text inputs (Upazila, District,
Territory) at 11sp, radius 8, and a "GPS" outline button with an emerald location glyph.
A chevron-up handle at the very bottom hints the verification sheet.
```

**08 Verify — Doctor info (BMDC verification)**
```
Frame 08: bottom-sheet style panel over the viewer, white, 16dp top radius. Handle row,
then a header with title "Data Verification - Side-by-side" 14sp/600 with an emerald
clipboard-check glyph, an amber pill "Pending Verification" (clock glyph, #FEF3C7 bg,
#B45309 text, 10sp), and a 32dp grey ✕ button.
Section label 12sp/600 uppercase tracking-widest #64748B: "Doctor Information - BMDC
Verification".
Then nine fields stacked with 12dp gaps. Each field: 10sp/700 uppercase #64748B label,
then a 12dp-radius input 14sp with a trailing status glyph — green check-circle for
confidence ≥85%, amber warning triangle otherwise (low-confidence fields get #FFFBEB
fill + 1px #FDE68A border):
  Doctor Name · BMDC Reg No · Qualifications · Hospital/Chamber
Then a full-width select "Specialty" with hint "Drives the specialty analytics".
Then a 3-column grid of selects: District · Upazila · Territory, each with placeholder
"Select district first" for the dependent two — add a note 9sp #94A3B8 that changing
the district invalidates its dependents.
Then a full-width select "Prescription Source" (Hospital / Private Chamber) with hint
"Drives the source filter tag".
```

**09 Verify — Medicine list & confidence**
```
Frame 09: same sheet, scrolled to the medicines section. Section label 12sp/600
uppercase tracking-widest #64748B: "Medicines Order & Confidence Review" with a
secondary 10sp normal-case line under it: "Green ✓ high confidence · Orange 🤖 AI Guess
<80% · Hover a name to highlight it on the scan" (reword to "Tap a name to highlight it
on the scan").
Four medicine cards stacked with 12dp gaps (spec in Frame 10).
Sticky footer on #F8FAFC with 1px top border, 16dp padding, two buttons: a flexible
emerald #059669 "Verify & Save to DB" with a check glyph, and a fixed-width outline
"Cancel".
```

**10 Medicine card — all states (component spec sheet)**
```
Frame 10: a spec canvas (not a phone frame) laying out the medicine card in every state,
each 380dp wide on #F8FAFC.

Base card: white, 12dp radius, 1px border, 12dp padding, elevation-1.
Layout: 80dp rounded-12 pack image on #F8FAFC with 2px #F1F5F9 border, with a 24dp white
circular badge at its bottom-right holding a blue image glyph. To its right:
  · brand-name text field, 15sp/700, transparent bg, 2dp bottom border #E2E8F0 (turns
    #2563EB on focus), full width
  · a 20dp circular status disc — emerald-100/emerald-600 check, or orange-100/orange-600
    robot, or amber-100/amber-600 bang
  · a confidence pill, 10sp/600
  · a 2×2 grid of tinted fact tiles, 10dp radius, 10dp padding, each with a 9sp/700
    uppercase tracking-widest label and a value:
       TYPE        #EFF6FF bg, 1px #DBEAFE, label #2563EB, value with capsules glyph
       INGREDIENT  #EFF6FF bg, 1px #DBEAFE, label #1E40AF
       MG/STRENGTH #ECFDF5 bg, 1px #D1FAE5, label #059669, value 13sp/700
       DOSAGE      #FFFBEB bg, 1px #FEF3C7, label #D97706, editable, placeholder "1+0+1"
  · the company badge row (see below)
  · a footer line 10sp #94A3B8: Raw: "…" • MedEx exact match / MedEx fuzzy match (NN%) /
    medex.com.bd live / No catalogue match • #<line number>
  · a right-aligned text button "Report mis-ID" 10sp #B45309

Show these seven variants:
 A. High confidence — border #E2E8F0, pill "96%" emerald, company pill solid #2563EB
    with logo + name + white check-circle.
 B. AI guess (<80%) — border #FDBA74, fill #FFF7ED, robot disc, pill "72% AI Guess"
    orange, plus a full-width orange strip: robot glyph + "AI Guess —" + underlined
    dotted link "Tap to verify against Medex".
 C. Manual flag (<70%) — border #FDE68A, fill #FFFBEB, pill "61% manual flag" with flag
    glyph.
 D. Ambiguous / unverified company — company pill gradient #F59E0B→#F97316 white text
    with a warning triangle.
 E. No company found — #E2E8F0 pill #475569 "Company not identified" with a
    building-circle-xmark glyph.
 F. Catalogue override note — amber strip: "Scan read "XYZ" - corrected from MedEx
    catalogue."
 G. Expanded extras — a collapsed row "3 other matches for this brand" in #4F46E5 that
    expands into three selectable rows "Square Pharmaceuticals • 20 mg Tablet", plus a
    substitution card, plus a red DGDA flag card.
```

**11 Verify — GPS territory strip & save**
```
Frame 11: focus on the bottom dock of the verification sheet. White sheet, #F8FAFC dock
with 1px top border. Row one: three equal inputs (Upazila / District / Territory) 11sp
radius 8 with values "Dhanmondi" / "Dhaka" / "Dhaka South", plus a "GPS" outline button
with an emerald location glyph. Row two: a status line 10sp — an emerald variant
"GPS pinned · 23.7465, 90.3760 · Dhaka South (in territory)" and a red variant
"Off-Territory Audit — scanned in Khulna, assigned Dhaka South". Row three: the two
buttons "Verify & Save to DB" (emerald, flexible) and "Cancel" (outline).
```

**12 Verify — Saved / synced**
```
Frame 12: the workspace after a successful save. A white 16dp card with a 56dp
#ECFDF5 circle holding an emerald check, heading "Prescription saved" 18sp/700,
sub-line 12sp #64748B "Rx #A-128 · 6 medicines · Dhaka South · MR001", then two
outline chips "Open audit summary" and "Scan another". Below, a "Recent Prescriptions"
card (see Frame 16) with three rows. Top of the screen, a success toast.
```

---

### 4.3 · Analytics Dashboard

**13 Analytics — Overview**
```
Frame 13, 412x915 (make it a tall scrollable frame, roughly 412x2600, and mark the
scroll bounds). Sections top to bottom, 24dp apart:

1. Dark hero, 16dp radius, gradient 135° #0F172A→#1E40AF, 24dp padding, white text,
   with a 256dp white-10% blurred circle bleeding off the top-right. Heading 20sp/700
   "Prescription Capture - Primary Action", body 13sp white/80 "Drop prescription image
   here or click to upload. Supports camera, rotation, contrast." On the right (stack
   below on mobile) a dashed 1px white/20 rounded-12 drop zone on white/10 with blur,
   "Drop prescription or click" 14sp/500 with a cloud-up glyph and
   "Full-width banner - primary action" 11sp white/70.

2. Filter bar — white card, 12dp radius, 12dp padding, 1px #E2E8F0. Leading label
   11sp/700 uppercase tracking-wider #64748B with a blue filter glyph: "Filters".
   Then a horizontally scrollable row of selects, each min 130dp, 12sp, radius 8:
   All Territories · All Districts · All Specialties · All MRs · All Sources
   (Hospital / Private Chamber) · Last 30 Days (also All Time, Last 7 Days, Last 90
   Days, Last 12 Months) · an outline "Reset" button with a rotate-left glyph ·
   a right-aligned active-count caption 10sp #94A3B8 · and a dark "Export Data" button
   with a file-csv glyph. On Android collapse this into a single "Filters (2)" chip that
   opens Frame 14, BUT keep every option inside the sheet.

3. Section header 14sp/600 with a blue chart-line glyph: "Top Summary KPIs", then a
   2-column grid of four KPI cards:
   · "Total Captured" 24sp/700 with delta chip, a 40dp rounded-12 tinted tile with a
     file-medical glyph, and a row of three micro pills "Today 0", "Week 0", "Month 0"
     (the Month pill is blue-tinted #EFF6FF / #1E40AF / 1px #BFDBFE).
   · "Identified Medicines" with value and a 11sp line "Top: Napa (12)" — this line must
     truncate gracefully, not cut the number.
   · "Target Share" showing "38%" + delta chip + 11sp company line + a 6dp track with an
     emerald fill, tile icon chart-pie.
   · "Active Doctor Coverage" showing "24 / 61" where the denominator is 14sp #64748B,
     plus 11sp "Unique doctors", tile icon user-doctor.

4. Chart A — white card, header 12sp/600 "A. Most Prescribed Medicines - Bar Chart",
   body height 240dp, vertical bar chart in the six-colour chart palette with y-axis
   tick labels and brand names rotated 45° under each bar (never truncated — abbreviate
   with the full name in a legend list below).

5. Chart B — "B. Company Share of Voice - Donut": 240dp donut, 28dp stroke, six-colour
   palette, centre label "SoV 38%", and a legend list below with colour dot + company +
   percentage. Add a "Locked" overlay variant: white/60 blur, "Competitor analysis
   locked" 14sp/500 #475569 and "Scan 10 prescriptions to see Square vs Incepta vs Your
   Company." 11sp #64748B.

6. Widget C — "C. Top Doctor Prescribers - Leaderboard": stacked rows, each white, 12dp
   radius, 1px border, 12dp padding — a 28dp circular #EFF6FF rank number in #1E40AF
   11sp/700; a flexible middle block with the doctor name 12sp/600, the meta line 10sp
   #64748B "Cardiology • Ibn Sina Chamber • Dhaka", and a 6dp #F1F5F9 track with an
   #10B981 fill; a right block with "12 / 5" (own emerald / competitor grey) 11sp/700,
   "67% conv" (emerald if ≥50, amber below) 10sp/600, and "18 Rx" 9sp #94A3B8.
   Below: "1-8 of 42" 10sp #64748B with outline Prev / Next buttons.

7. Chart D — "D. Generic vs Brand Matrix - Stacked Bar by Specialty": 240dp stacked
   horizontal bars, one per specialty, with a six-colour legend.

8. Live Recent Scans card (Frame 15).
9. Recent Prescriptions card (Frame 16).
```

**14 Analytics — Filter bottom sheet**
```
Frame 14: bottom sheet over a dimmed analytics screen. 16dp top radius, grabber, header
"Filters" 16sp/700 with a blue filter glyph and a right-aligned outline "Reset".
Body: six labelled select fields stacked, each with a 10sp/700 uppercase label —
Territory, District, Specialty, MR, Source, Date range. "Source" shows the two options
Hospital / Private Chamber as selectable chips; "Date range" shows five chips:
All Time · Last 7 Days · Last 30 Days (selected, dark) · Last 90 Days · Last 12 Months.
Footer: a caption 10sp #94A3B8 "2 filters active" and two buttons — outline "Cancel"
and dark "Apply & Export Data".
```

**15 Analytics — Live Recent Scans**
```
Frame 15: white card, 20dp padding. Header: title 14sp/600 "Live Recent Scans" preceded
by a live indicator (an 8dp emerald dot with a ping ring), sub-line 10sp #64748B "Every
individual medicine detected, per MR". Right side: a 200dp search field with a
magnifier, placeholder "Search medicine, company, doctor...", plus an outline "CSV"
button and a 32dp refresh icon button.
Below: a tag row — a 10sp/700 uppercase #94A3B8 label "Tags" then selectable chips
"Hospital" and "Private Chamber" (white, 1px #E2E8F0, 10sp/500 #475569; active = dark
fill white text).
Then the itemised list. Each row is a white 12dp-radius card with 1px #F1F5F9 border and
12dp padding, containing ALL SEVEN web columns:
  line 1 — brand name 13sp/600 #0F172A, then strength + dosage form 10sp #64748B
  line 2 — a wrapping chip row: company chip (8dp colour dot + name + a green check for
           verified / amber triangle for unverified), confidence pill
           (emerald ≥85 / amber 60–84 / red <60), specialty chip
  line 3 — meta 10sp #64748B: doctor name · "timeAgo" · "Dhanmondi, Dhaka"
Sorting headers become a sort bar: "Time", "Doctor", "Medicine", "Company", "Conf."
as 11sp chips with an up/down arrow, the active one dark.
Footer: "Showing 1-25 of 1,284 medicines" 11sp #64748B with outline Prev / Next buttons.
Empty state: 48dp #F1F5F9 circle with a pills glyph, "No scanned medicines yet. Scan a
prescription to populate this feed."
```

**16 Analytics — Recent Prescriptions**
```
Frame 16: white card, 20dp padding. Header row: "Recent Prescriptions" 14sp/600 and a
"Refresh" pill (#F1F5F9, 11sp). A vertically scrolling list, max 384dp. Each row: 12dp
radius, 1px #E2E8F0, white, 8dp padding, press state adds a 2dp #0F172A/10 ring —
a 40dp rounded-8 prescription thumbnail on #F1F5F9; a flexible block with
"Dr. A. K. M. Rahman • A-12345" 12sp/500 (with an optional red micro-pill
"Duplicate Rx" carrying a warning triangle), and a meta line 10sp #64748B
"6 meds • Dhanmondi Dhaka • MR001 • tap for item breakdown"; a trailing 10sp #CBD5E1
chevron-right. Empty state: "No history yet - scan your first prescription to unlock
analytics".
```

**17 Rx Audit Summary** (the web slide-over drawer → full-screen Android screen)
```
Frame 17, 412x915, background #F8FAFC, with a back chevron in the app bar.
Header card (white, 1px bottom border): title 14sp/700
"📄 Prescription Audit Summary — Rx #A-128" with an optional red micro-pill
"Duplicate Rx Detected" (warning triangle, #FEE2E2 bg, #B31D1D text, 1px #FCA5A5);
sub-line 11sp #64748B "Dr. A. K. M. Rahman (Cardiology) • 6 Medicines Detected • MR MR001
• Dhaka".

Toolbar card: a search field, placeholder "Search scanned items...", then four filter
pills with live counts — "All (6)" (active: #0F172A fill, white text), "Own Pharma (2)",
"Competitors (4)", "<80% (1)".

Clinical & regulatory strip card: a wrapping badge row —
 · polypharmacy badge: red variant "⚠️ 8+ Meds Prescribed — High Polypharmacy"
   (#FEE2E2/#B31D1D/1px #FCA5A5), amber variant for 5–7, slate variant "6 Meds
   Prescribed" otherwise
 · "🦠 Antibiotic Stewardship: 3 in Rx · 1 broad-spectrum" (amber)
 · "📍 Off-Territory Audit" (red)
Then "THERAPEUTIC CLASS BREAKDOWN" as a 9sp/700 uppercase tracking-widest label with a
chart-pie glyph, a 12dp full-width segmented bar (no gaps, radius full, six palette
colours), and a wrapping legend 9sp #475569: "● Cardiology: 33%  ● Gastroenterology: 17%
● Antibiotics: 17% …".

Duplicate fraud note (only when flagged): a red card, #FEF2F2 bg, 1px #FECACA, 12dp
radius, 11sp #B31D1D: "Fraud alert: this physical prescription appears to have been
scanned before — first captured as Rx #A-104 (MR004, 2026-08-02 14:11). Excluded from
target credit pending RSM review."

Item list — stacked cards, one per medicine, 12dp radius, 1px #E2E8F0 (low-confidence
rows get #FFF7ED fill). Each card keeps all four web columns:
  · brand + strength 13sp/600 underlined dotted #CBD5E1 (long-press previews the crop),
    then 10sp #94A3B8 "Tablet • 1+0+1"
  · a wrapping chip row with every applicable micro-pill
  · if a portfolio match exists: two violet action pills — outline
    "✨ Own Portfolio Match: Opal" and solid #7C3AED "🪪 Generate Doctor Pitch Card"
  · if low confidence: an orange underlined link "❔ Verify against Medex"
  · generic composition 12sp #475569, then "NEML Listed" (blue) and "TRIPS Watch"
    (amber) micro-pills
  · pharmaceutical block: 20dp company logo chip + name (bold violet if own brand, with
    a house-medical glyph) or italic #94A3B8 "Unknown Brand"
  · trailing confidence badge: emerald ≥90, orange if low, slate otherwise

Footer card: market share summary 11sp #475569 —
 "Market Share Summary for this Rx:" 700 with a briefcase glyph,
 "• Square Pharmaceuticals PLC: 2 / 6 (33%)" (green if >0, red if 0),
 "• Competitor brands identified: 4 / 6 (67%)" (amber).
Then two outline buttons: "Export Rx Items as CSV" (emerald file-csv glyph) and
"Copy List to Clipboard" (blue clipboard glyph), plus a right-aligned note 10sp #94A3B8
"Long-press a medicine name to preview the prescription crop".
```

**18 Rx Audit — item row states**
```
Frame 18: a spec canvas showing the Rx audit item card in six states, each 380dp wide:
 (1) clean own-brand row — violet company name + house glyph, emerald 96% badge
 (2) competitor row with NEML + therapeutic-class chips
 (3) antibiotic row — "ABX" amber chip
 (4) broad-spectrum row — "ABX ★" red chip (#FEF2F2/#B31D1D/1px #FCA5A5)
 (5) DGDA violation row — "DGDA Price Alert" red chip with a ban glyph
 (6) low-confidence row — #FFF7ED fill, orange 72% badge, "Verify against Medex" link,
     plus a "TRIPS Watch" amber chip and a therapeutic-class slate chip on the same row
     to prove the chips coexist.
```

**19 Own Portfolio Match — expanded row**
```
Frame 19: the substitution card that expands under a competitor row. Container
#F5F3FF/50 with a 1px #DDD6FE bottom border, 12dp padding. Label 9sp/700 uppercase
tracking-widest #6D28D9: "✨ Own Portfolio Match — for MPO doctor detailing".
Then a flow row: a white 8dp-radius chip "Seclo (Square Pharmaceuticals PLC) 20 mg
Tablet" (brand bold, company #94A3B8), a violet arrow-right-long glyph, then a white
chip with a 1px #C4B5FD border and the own brand in bold #6D28D9 "Opal (Healthcare
Pharmaceuticals Ltd.) 20 mg Tablet", then a delta pill — emerald "−12% per unit" if
cheaper, amber otherwise.
Then the pitch line: white card, 1px #E2E8F0, 12dp radius, 12dp×8dp padding, 11sp
#475569 italic with an amber bullhorn glyph prefix.
```

**20 Doctor Pitch Card**
```
Frame 20: modal bottom sheet, max height 88% of the screen, 16dp top radius.
Header on gradient 90° #7C3AED→#4F46E5, white, 20dp×16dp padding: "🩺 Doctor Pitch Card"
14sp/700, sub-line 10sp white/80 "Dr. A. K. M. Rahman · Rx #A-128 · Seclo → Opal",
and a 32dp ✕ button.
Body (scrollable, 12sp base):
 · a wrapping row of compliance pills: blue "NEML Listed · Omeprazole", red
   "DGDA Price Alert"
 · a comparison table, 11sp, with a header row in #94A3B8 — blank / "Competitor" /
   "Your Brand" (violet #6D28D9) — and rows separated by 1px #F1F5F9:
     Brand · Company · Generic (spanning both columns) · Strength / Form ·
     MRP (competitor "120 BDT (10's)", own brand bold violet "105 BDT (10's)")
 · a price-position strip: emerald (#ECFDF5/#047857) or amber (#FFFBEB/#B45309) with a
   scale-balanced glyph: "Price position: 12.5% lower per unit"
 · a bioequivalence card: 12dp radius, 1px #DBEAFE, #EFF6FF/60 fill, 12dp padding —
   9sp/700 uppercase tracking-widest #1D4ED8 with a flask-vial glyph "Bioequivalence &
   dosage evidence", then two 11sp #334155 paragraphs
 · a pitch-script card: 12dp radius, 1px #E2E8F0, #F8FAFC fill — 9sp/700 uppercase
   tracking-widest #64748B with an amber bullhorn "Smart pitch script", then the script
   in 11sp #334155 italic
 · footnote 9sp #94A3B8: "Bioequivalence & pack data from the MedEx-audited catalogue ·
   verify sample stock before the visit."
Footer on #F8FAFC with 1px top border: dark "Download PDF" (red file-pdf glyph) and
outline "Copy pitch script" (blue clipboard), right-aligned note 9sp #94A3B8
"Show during chamber visit".
```

**21 Drill-down sheet**
```
Frame 21: a bottom sheet, 16dp top radius, max 85% height. Header: title 16sp/700
(e.g. "Square Pharmaceuticals PLC") and sub-line 11sp #64748B ("8 brands · 214 items ·
Dhaka, Chittagong"), plus a 32dp ✕. Body: a scrollable ranked list — each row has a
6dp colour dot from the company palette, a label 13sp/500, a 6dp track with a coloured
fill, and a right-aligned value 12sp/700. Used for both the company drill-down and the
brand→doctors drill-down, so show two variants.
```

---

### 4.4 · Pharma Intelligence Hub

**22 Hub — 25K+ Drug Index**
```
Frame 22, on the shell with "Hub" active.
Header card (white, 16dp radius): "Pharma Intelligence Hub" 18sp/700, sub-line 11sp
#64748B "Real-time Medex market intelligence, DGDA notifications, industry jobs & WHO
health campaigns". Below it a segmented tab bar on #F1F5F9, 12dp radius, 4dp padding —
five segments at 12sp/600, active = white with elevation-1 and #0F172A text, inactive
#475569, each horizontally scrollable: "💊 25K+ Drug Index" (active) ·
"🌐 TRIPS Waiver Tracker" · "📰 Industry News" · "💼 Health & Pharma Jobs" ·
"🗓️ Health Days".
Then the drug index card: title 14sp/600 with a blue database glyph "25K+ Drug Index &
Search"; a 2×2 grid of four mini stat tiles (12dp radius, 1px #E2E8F0, 12dp padding,
10sp label + 20sp/700 value) — Total medicines / Forms / Companies / DGDA registered;
then a wrapping row of four filter chips at 11sp/600 radius-full, active = #0F172A fill:
"🏆 Top 10 Pharma" · "🫀 Cardiology" · "🦠 Antibiotics" · "💊 OTC";
then a full-width search field 14sp, placeholder "Search Napa, Seclo, Injection, Square,
Omeprazole...";
then the info line 11sp #64748B with a blue filter glyph: "Showing curated Top 10 Pharma
slice — 24 results from the 25K catalogue. Every product is DGDA-registered.";
then a single-column list of product cards, each white 12dp radius 1px #E2E8F0 12dp
padding, horizontal: a 64dp rounded-12 pack shot on #F8FAFC with 1px border; then
brand 12sp/700, "Tablet 20 mg" 10sp #64748B, the generic 10sp #64748B, a company row
(16dp logo + 9sp #475569 name), and an emerald micro-pill "🛡 DGDA Registered"
(#ECFDF5/#047857/1px #A7F3D0, 8sp/600). Nothing truncated — let the generic wrap to two
lines.
```

**23 Hub — Popular medicines hero**
```
Frame 23: a dark panel, 16dp radius, gradient 135° #0F172A→#1E40AF, 24dp padding, white
text, with the 256dp white-10% blurred circle top-right. Heading 18sp/700 with a fire
glyph: "Currently Popular Medicines from Top Pharmaceutical Companies". Sub-line 13sp
white/80: "Live fetch from medex.com.bd - Top pharma: Square, Incepta, Beximco, Renata,
ACI, Healthcare, Opsonin, Eskayef".
Then, per company, a full-width group header: a 28dp white company-logo chip + the
company name 14sp/600 white, plus a pill "12 popular" on white/10, 12sp #BAE6FD.
Under each header, a 2-column grid of cards on white/10 with 1px white/20, 12dp radius,
12dp padding, blur: a 56dp rounded-12 pack shot on white with 4dp padding and elevation;
brand 12sp/700; "Tablet 500 mg • Paracetamol" 10sp white/70; a 20dp company logo chip
with a 12sp #CBD5E1 label.
Footer note 11sp white/60: "Source: Live from medex.com.bd with real pack images •
Updates on each load • 25k medicines DB".
```

**24 Hub — TRIPS Waiver Tracker**
```
Frame 24: white 16dp card, 20dp padding. Header row: title 14sp/600 with an amber globe
glyph "TRIPS Waiver Portfolio Tracker"; an amber pill 10sp/700 "LDC pharma waiver →
2033-01-01" (#FEF3C7/#B45309/1px #FDE68A); a select "90 days" (30 / 90 / 180); a refresh
pill.
Sub-line 11sp #64748B: "High-priority generic molecules on the LDC pharmaceutical TRIPS
waiver (to 2033) with real field volume from prescription scans — PMD visibility into
where watch-list brands are being written and whether volume is rising."
Then a 2×2 grid of mini tiles — the first tinted amber (1px #FDE68A, #FFFBEB fill,
10sp #D97706 label "Molecules on watch", 20sp/700 #B45309 value), the other three neutral:
"With field volume (90d)", "Watch-list items scanned", "Rising vs prev period"
(emerald if >0).
Then the watch list. Each row is a white 12dp card with 1px #F1F5F9, keeping all eight
columns:
  · molecule 13sp/600, then class + originator 10sp #64748B
  · a watch-level pill 10sp/700: CRITICAL red / HIGH amber / MEDIUM slate / LOW pale
  · window 10sp #94A3B8 "→ 2033-01-01"
  · field volume 13sp/700
  · delta: "▲ +14" emerald or "▼ -6" red or "—" #CBD5E1, with "(+38%)" 9sp #94A3B8
  · top territories 10sp #64748B "Dhaka South (9), Chittagong (4)"
```

**25 Hub — Industry News**
```
Frame 25: white card, 20dp padding. Header: "Industry News & Market Trends" 14sp/600,
sub-line 11sp #64748B "Medex market board, DGDA regulatory desk, WHO live RSS &
Bangladeshi pharma reports.", and an outline "Refresh" button.
Then a 10sp/700 uppercase tracking-widest #94A3B8 label with an amber star:
"Featured story", followed by one featured card — gradient 135° #0F172A→#1E40AF, 16dp
radius, 20dp padding, white: a source pill (amber for regulatory, blue otherwise),
an optional emerald "LIVE" marker, a right-aligned date 10sp white/60, the headline
14sp/700, a 11sp white/80 summary, and a blue-100 link "Open source" with an
external-link glyph.
Then the label "Market place timeline" with a blue stream glyph, then a scrolling list
of compact cards — each white, 12dp radius, 1px #E2E8F0, 12dp padding, with a 4dp
full-height left bar in amber-400 (regulatory) or blue-400; a source micro-pill 9sp/700
uppercase; an optional "LIVE" emerald marker 9sp; a right-aligned date 9sp #94A3B8; a
12sp/600 headline; a 10sp #64748B summary clamped to 2 lines; and a "Open source" link
10sp #1D4ED8.
```

**26 Hub — Health & Pharma Jobs**
```
Frame 26: a dark hero card, gradient 90° #0F172A→#1E40AF, 16dp radius, 20dp padding,
white: "Health & Pharma Job Board" 14sp/600, sub-line 11sp white/70 "Curated MPO, Senior
Territory Manager, Executive Product Management (PMD) & Regulatory Affairs vacancies
with direct apply links.", and two white/10 tiles 12dp radius 12dp×8dp padding —
"Open roles" 10sp white/60 with a 18sp/700 count, and "Fresh today".
Then a white card: a wrapping filter row with three selects (All roles: MPO /
RSO-RSM / Product Management / Sales Ops / Regulatory Affairs · All departments ·
All territories) plus a 160dp search field "Company, city, keyword...".
Then a single-column list of job cards, each white 12dp radius 1px #E2E8F0 12dp padding:
title 12sp/700, company 11sp #475569, a right-aligned blue category pill 10sp/600,
a location line 11sp #64748B with a location glyph "Dhaka • 2-4 yrs", a 11sp #475569
description clamped to 2 lines, a wrapping row of slate tag chips 9sp/600, a divider
then a footer row 10sp #64748B with the salary left and "Posted today" (emerald/600) or
"Posted 12 Sep" right, and finally a full-width dark "Apply now" button 11sp/600 with a
paper-plane glyph.
```

**27 Hub — Health Days calendar**
```
Frame 27: a dark hero card, gradient 90° #1D4ED8→#0F172A, 16dp radius, 20dp padding,
white — label 11sp uppercase tracking-widest white/70 "Next campaign window", heading
18sp/700 "World Diabetes Day — 14 Nov", body 13sp white/85 with the MPO tip ("Plan
glucometer camps, metformin/SGLT2/DPP4 detailing, and diet leaflets 3 weeks ahead.
Highest MPO activity day after Heart Day."), then a 11sp #BFDBFE line
"23 days out • Focus: Endocrinology, Medicine, Cardiology".
(The calendar ships 33 WHO / UN health days for 2026; each carries its own accent
colour used as the cell's 3dp top border — e.g. World Braille Day #1D4ED8, World Cancer
Day #BE185D, World Pneumonia Day #1D4ED8, World COPD Day #0E7490.)
Then a white card: "International Health Days calendar" 14sp/600, sub-line 11sp #64748B
"Click any WHO / global health day to pull a pre-generated promotional script & campaign
brand focus for your doctor visits.", and a month stepper — 32dp outline chevron-left,
"November 2026" 12sp/700 centred, 32dp outline chevron-right.
Then the calendar: a 7-column weekday header row (Sun…Sat) at 10sp/700 uppercase #94A3B8,
then a 7-column grid with 6dp gaps. Health-day cells are tappable buttons, min 74dp
tall, 12dp radius, 1px #E2E8F0, white, with a 3dp coloured top border, a 10sp/700 #94A3B8
day number, a 9sp/600 name clamped to 2 lines, and a hover hint replaced on Android by a
permanent 8sp #2563EB "✨ Campaign" caption. Today gets a 1px #60A5FA border and #EFF6FF
fill with #1E40AF text. Empty days are #F8FAFC tiles with 1px #F1F5F9 and a 10sp #CBD5E1
number.
Below the grid, a 1px #F1F5F9 divider then a wrapping row of the month's day chips
10sp/600 radius-full: today = #EFF6FF/#1E40AF/1px #60A5FA, upcoming = white/#475569,
past = #F8FAFC/#94A3B8, each labelled "World AIDS Day • 01 Dec".
```

**28 Health Day campaign card**
```
Frame 28: modal bottom sheet, 16dp top radius, max 88% height. Header on gradient 90°
#0F172A→#1E40AF, white: title 16sp/700 "Health day campaign card", sub-line 11sp
white/70 with the day name and date, and a 32dp ✕. Body: scrollable 12sp content —
the day summary, the focus specialties as chips, the MPO talking script in an italic
#334155 card, and a share row. Footer on #F8FAFC with 1px top border, 11sp #64748B with
an amber lightbulb: "Use the script to open the conversation, then tailor the brand
focus to the specialty of the day."
```

---

### 4.5 · RSM Command / Team

**29 Team — Command overview**
```
Frame 29, on the shell with "Team" active, tall scrollable frame.
1. Dark hero, gradient 135° #0F172A→#1E40AF, 16dp radius, 24dp padding, white:
   label 11sp uppercase tracking-widest white/70 "Multi-tenant org hierarchy"; heading
   20sp/700 "Territory Manager / RSM Command"; body 13sp white/80 "Aggregated
   prescription audits across a 50+ MPO field team, with competitive Share of Voice."
   A white button, #0F172A text, 14sp/700, radius 12, with a red file-pdf glyph:
   "Generate DGDA / Compliance Audit PDF".
   Below, a 2×2 grid of four white/10 tiles 12dp radius 12dp padding — 10sp white/70
   labels "Team size", "Prescriptions", "Own items", "Team SoV" with 20sp/700 values.
2. Territory Penetration Heatmap card (Frame 30)
3. Doctor Prescribing Tiering Matrix (Frame 31)
4. Team leaderboard (Frame 32)
5. Doctor Detailing Target Tracker (Frame 33)
6. Off-Territory Audit Verification (Frame 34)
7. Antibiotic Stewardship Monitor (Frame 35)
```

**30 Team — Territory penetration map**
```
Frame 30: white card, 20dp padding. Title 14sp/600 with a blue map-location-dot glyph
"Territory Penetration Heatmap"; sub-line 11sp #64748B "Live scan locations mapped
across Bangladesh — bubble size = volume, colour = your company's market penetration
(green = dominant, red = competitor-heavy)."
Controls row: a segmented toggle, 8dp radius container 1px #E2E8F0, segments "SoV"
(active, #0F172A fill white text) and "Density clusters"; a select "30 days"
(7 / 30 / 90); a 32dp refresh icon button.
A 2×2 grid of mini tiles: "Districts covered", "Total items", "Market penetration (own
SoV)" (emerald if ≥50, amber below), "Owned vs competitor-heavy" (green number / red
number).
Then a 300dp map of Bangladesh on #F8FAFC with 1px #E2E8F0 and 12dp radius, showing
circle markers sized by volume, filled green #10B981 / amber #F59E0B / red #EF4444 at
50% opacity with a 2dp solid stroke. Show one selected marker with a white popup card:
district bold, "Dhaka · Dhaka South", "Items: 214 (own 128 / comp 86)", "SoV: 60%",
"Rx: 41".
Add a second variant of the same map in cluster mode with purple #7C3AED / blue #0284C7
/ green #10B981 / grey #94A3B8 bubbles carrying counts.
Legend row 10sp #64748B: green dot "Own-company dominant", amber dot "Competitive /
mixed", red dot "Competitor-heavy", right-aligned "Bubble size = prescription volume".
```

**31 Team — Doctor tiering A/B/C**
```
Frame 31: white card, 20dp padding. Title 14sp/600 with a violet user-doctor glyph
"Doctor Prescribing Tiering Matrix (A/B/C)"; sub-line 11sp #64748B "Auto-classified from
audit volume — Tier A >10 Rx, B 4–9 Rx, C ≤3 Rx/month. At-risk = high-volume doctors
with low own-brand share." (render "At-risk" in red 600).
Control row: four chips "All" (active dark) · "Tier A" · "Tier B" · "Tier C" and a
select "All specialties".
A 2×2 grid of tinted tiles: violet "Tier A · High prescriber" (#F5F3FF/1px #DDD6FE,
label #7C3AED, value #6D28D9), blue "Tier B · Medium", slate "Tier C · Occasional",
red "At-risk switchers".
Then the list, one card per doctor with 12dp radius 1px #F1F5F9, keeping all ten
columns:
 · a tier badge 10sp/700 radius-6 (A violet, B blue, C slate) + doctor name 13sp/500
 · meta 10sp #64748B: specialty · territory
 · a four-up micro stat row 11sp: "12 Rx" · "48 items" · own in #047857 600 ·
   competitor in #B45309
 · an SoV value 12sp/700 with a 6dp track
 · a status pill — red "⚠ At risk" (#FEE2E2/#B31D1D/1px #FECACA) or a 10sp #94A3B8 "—"
```

**32 Team — MPO leaderboard**
```
Frame 32: white card, 20dp padding. Header "Team leaderboard" 14sp/600 with a select
"30 days". A list of MPO rows, each a 12dp card with 1px #F1F5F9, keeping all ten
columns: a 28dp circular rank chip; name 13sp/500 with role + territory 10sp #64748B
underneath; a micro stat row "38 Rx · 142 items"; own count #047857 600 and competitor
#B45309; SoV 12sp/700; a 72×22dp sparkline in #059669; and a WoW delta 11sp/600 with an
up arrow in emerald or a down arrow in red.
```

**33 Team — Doctor detailing target tracker**
```
Frame 33: white card, 20dp padding. Title 14sp/600 with a rose bullseye glyph "Doctor
Detailing Target Tracker" and a refresh pill. Sub-line 11sp #64748B: "Attach target
doctor lists to MPOs. Every scanned prescription whose doctor matches a target
auto-logs a visit — duplicates of the same physical Rx never count twice."
Then an "Attach target" form as a stacked card on #F8FAFC, 12dp radius, 12dp padding,
with four labelled fields (10sp/600 #64748B labels) — a select "MPO", a text field
"Target doctor" placeholder "Dr. A. K. M. Rahman", a text field "Specialty" placeholder
"Orthopedics", a number field "Monthly visits target" default 4 — and a dark button
"＋ Attach target".
Then the target list, one card per row with all eight columns: MPO name 13sp/500 ·
target doctor · specialty 10sp #64748B · "Target 4" · "Visits 3 / 4" (emerald 700 when
met) · a 8dp progress track (emerald ≥100%, amber ≥50%, rose below) with "75%" 9sp
#94A3B8 · last visit 10sp #64748B · a trash icon button #CBD5E1 turning red on press.
Then a sub-section: 10sp/700 uppercase tracking-widest #64748B with an emerald route
glyph "Auto-visit log (from prescription scans)", then rows of 8dp-radius cards 1px
#F1F5F9, 11sp: an 6dp emerald dot, the doctor name bold, "visited by", the MR id, a
right-aligned timestamp 10sp #94A3B8, and a blue link "Rx #A-128".
Empty states: "No doctor targets attached yet — add one above." and
"No auto-logged visits yet."
```

**34 Team — Off-territory audit verification**
```
Frame 34: white card, 20dp padding. Title 14sp/600 with a red location-crosshairs glyph
"Off-Territory Audit Verification", a count pill 10sp/700 ("4 flagged" — red #FEE2E2 /
#B31D1D when >0, slate otherwise) and a refresh pill. Sub-line 11sp #64748B: "GPS-pinned
and location-resolved audits are geofenced against each MPO's assigned territory —
Off-Territory Audit uploads (e.g. a Dhaka South MR scanning in Khulna) are listed here
alongside the pHash duplicate guard."
Then rows: 12dp radius, 1px #FECACA, #FEF2F2/60 fill, 10dp padding — a 36dp rounded-8
white thumbnail with 1px #FECACA; a flexible block with the doctor name 12sp/600 plus a
red micro-pill "📍 Off-Territory Audit" and, when applicable, a white/red micro-pill
"⧉ also Duplicate"; the territory note 11sp #475569; a meta line 10sp #94A3B8
"MR MR001 · Khulna Sadar, Khulna · 2026-09-02 14:11"; and a right-aligned blue link
10sp "Review Rx".
Empty state 11sp #94A3B8: "No off-territory audits flagged — every scan matches its
assigned zone."
```

**35 Team — Antibiotic stewardship monitor**
```
Frame 35: white card, 20dp padding. Title 14sp/600 with a red bacterium glyph
"Antibiotic Stewardship Monitor", a share chip 10sp/700 ("38% ABX share" — red ≥40%,
amber ≥20%, slate below) and a refresh pill. Sub-line 11sp #64748B: "Broad-spectrum
antibiotic prescribing audited per doctor chamber (AWaRe watch list) — regional
stewardship compliance and detailing focus at a glance."
A 2×2 grid of mini tiles: "Chambers audited", "Chambers prescribing ABX" (amber 700
when >0), "ABX items (30d)", "Broad-spectrum ★" (red 700 when >0).
Then rows per chamber, 12dp cards 1px #F1F5F9, with all eight columns: doctor (chamber)
13sp/500 · specialty + district 10sp #64748B · "12 Rx audited" · ABX items bold (red
when ≥4, amber otherwise) with "/ 48 items" in #94A3B8 · "★ 3" red or a pale "0" · an
8dp share track (red ≥40%, amber ≥20%, emerald below) with "38%" 9sp #94A3B8 · the
brands-seen line 10sp #64748B "Azithromycin, Cefixime".
Empty states: "No stewardship data yet — scan prescriptions to audit ABX trends." and
"No antibiotic prescribing in this window."
```

---

### 4.6 · Settings, Help, States

**36 Settings — Enterprise settings & officer profile**
```
Frame 36, on the shell with "Settings" active.
White 16dp card, 24dp padding: title 14sp/700 "Enterprise Settings & Officer Profile";
sub-line 11sp #64748B "Onboard your company, identity card and monthly brand targets.
Vision-model hits on those brands become KPI progress automatically."
Then the form, 20dp gaps:
 · "PHARMACEUTICAL COMPANY" 10sp/700 uppercase tracking-wider #64748B, an autocomplete
   field 14sp placeholder "Search Square, Beximco, Incepta..." and a 11sp #64748B helper
   line below ("Selected: Square Pharmaceuticals PLC"). Show the open dropdown state:
   white, 12dp radius, 1px #E2E8F0, elevation-4, max 224dp, rows with a 20dp logo chip
   + 12sp name.
 · a 2-column grid of labelled fields: "EMPLOYEE ID" placeholder MR001 · "FULL NAME"
   placeholder "Your name" · a select "ROLE" (MPO / RSO / RSM / Territory Manager /
   Product Manager) · "DIVISION" placeholder Dhaka · "DESIGNATED TERRITORY / ZONE"
   placeholder "Dhaka South, Chittagong Metro..." · "ASSIGNED PRODUCT PORTFOLIO"
   placeholder "Cardiology, Gastroenterology"
 · "MONTHLY BRAND TARGETS" with a right-aligned blue 11sp/600 link "＋ Add brand".
   Then repeatable rows: a brand autocomplete + a numeric target, with a remove icon.
   Below, a progress block: per brand, the name, "128 / 200", a 6dp track with an
   emerald fill and "64%" 10sp #64748B.
 · a dark button "Save officer card" with a floppy-disk glyph.
Then a note card on #F8FAFC, 12dp radius, 1px #E2E8F0, 16dp padding, 11sp #475569 with
an emerald wifi glyph: "Offline-first PWA: scans cache in IndexedDB when the rural
network drops, then sync on reconnect." (reword for Android: "…scans cache on-device…")
Add a "Help & Guide" entry row at the bottom, since the web sidebar's sixth destination
has no bottom-nav slot on mobile.
```

**37 Help & Guide**
```
Frame 37: white 16dp card, 24dp padding: title 14sp/700 "Interactive Scan Guide";
sub-line 11sp #64748B "Optimal camera alignment, lighting, and cursive handwriting for
maximum vision-AI confidence."
Then a single-column list of five step cards, each #F8FAFC with 1px #E2E8F0, 12dp
radius, 12dp padding — a 10sp/700 #1D4ED8 step title and a 11sp #475569 body, verbatim:
 1. Surface — "Lay the Rx flat. Avoid folded pads and wrinkled thermal paper."
 2. Align — "All four corners visible. Portrait for Bengali pads, landscape for hospital
    sheets."
 3. Light — "Even daylight. No flash glare on laminated pads. Shadow-free medicine block."
 4. Cursive — "Move closer to the Rx lines. Use contrast toggle after capture for faint
    ink."
 5. Capture — "Tap Open Camera (not Choose File) on phones. Hold still 1s. Review
    confidence badges."
Then a dark button "Try a scan".
Then two more white 16dp cards:
 · "Error Escalation System" 14sp/600, sub-line 11sp #64748B "Report a misidentified
   drug or a novel handwritten variation. It is queued for the backend training set —
   you can also flag from any medicine card after a scan.", then a stacked form with
   three fields — "Detected brand", "Correct brand / company", and a 3-line textarea
   "What did the handwriting look like?" — plus an amber-600 button
   "Queue for training".
 · "BMDC & DGDA Reference Manual" 14sp/600 with a 12sp #475569 bulleted list, five
   items, keeping the bold lead-ins and the inline code A-12345:
     BMDC number format · Verification statuses (green >85% auto-accept; 70–85% review;
     <70% manual flag required before save) · Qualifications (MBBS, FCPS, MD, MS, MCPS) ·
     DGDA · PII masking per BMDC compliance.
```

**38 Empty / Loading / Error / Offline states**
```
Frame 38: a spec canvas collecting every state used in the app, each in a 340dp card:
 · "No data yet" — "Scan your first 5 prescriptions to unlock generic-brand trends."
   with a dark pill CTA "Start Scanning"
 · "Competitor analysis locked" — "Scan 10 prescriptions to see Square vs Incepta vs
   Your Company."
 · Doctor leaderboard empty — 48dp #F1F5F9 circle with a user-doctor glyph, "Scan
   prescriptions to unlock doctor leaderboard with conversion rates."
 · Generic-vs-brand empty — "Shows trending generics in therapeutic areas and dominant
   companies."
 · Live Recent Scans empty — pills glyph, "No scanned medicines yet. Scan a prescription
   to populate this feed."
 · Rx audit filtered empty — file-prescription glyph, "No medicines match this
   search/filter."
 · Rx history empty — "No history yet - scan your first prescription to unlock analytics"
 · Catalogue error — 12sp #DC2626 "Could not load the catalogue slice."
 · News unavailable — 12sp #DC2626 "News feed unavailable."
 · Jobs unavailable — 12sp #DC2626 "Job board unavailable."
 · Calendar unavailable — 12sp #DC2626 "Calendar unavailable."
 · Heatmap unavailable — 12sp #DC2626 "Heatmap unavailable."
 · Tier data unavailable — 12sp #DC2626 "Tier data unavailable."
 · TRIPS empty — "TRIPS watch list unavailable." / "Loading TRIPS watch list…"
 · Offline banner — a full-width amber strip #FFFBEB, 1px #FDE68A, 11sp #B45309:
   "Offline — 3 scans queued. They will sync when you reconnect." plus a dark chip in
   the app bar reading "3 queued".
```

---

## 5. COVERAGE CHECKLIST — audit the Figma output against this

Tick each. If any box is unticked, something from the web app was truncated.

**Navigation & shell**
- [ ] 5 bottom-nav destinations, active = filled #0F172A pill
- [ ] App-bar logo tile + "MedLenX" wordmark
- [ ] Global search field with the exact long placeholder + results overlay
- [ ] Chips: Online · "N queued" · company · latency · avatar
- [ ] A reachable path to Help & Guide (web has 6 sidebar items, Android bottom nav has 5)

**Scan flow**
- [ ] Dashed blue upload hero, 80dp gradient tile, both buttons, full sub-copy
- [ ] Camera screen with shutter, torch, gallery, alignment hint
- [ ] Scan laser + cyan glow + "Scanning…" pill + shimmer skeletons
- [ ] 6 viewer controls: zoom in, zoom out, rotate left, rotate right, contrast, fit
- [ ] Zoom readout + pinch/drag hint
- [ ] GPS strip: Upazila, District, Territory, GPS button, off-territory warning
- [ ] Status pill "Pending Verification" and the ✕ clear button
- [ ] All 9 doctor fields + the 3 cascading location selects + specialty + source
- [ ] Medicines section header with the green/orange legend line
- [ ] Medicine card: pack image, editable brand, status disc, confidence pill,
      4 tinted fact tiles (Type / Ingredient / MG-Strength / Dosage), company badge,
      raw-text + match-type footer, "Report mis-ID"
- [ ] All 5 company-badge variants + the MedEx-override note
- [ ] "N other matches for this brand" expander
- [ ] Substitution card + DGDA flag card
- [ ] Progress bar with "Extracting with MedLenX VL..."
- [ ] "Verify & Save to DB" (emerald) + "Cancel"

**Analytics**
- [ ] Dark hero with the inline drop zone
- [ ] All 6 filters + Reset + active count + Export Data
- [ ] 4 KPI cards, incl. the Today/Week/Month pill row and the Target Share track
- [ ] Chart A bar, Chart B donut, Widget C leaderboard, Chart D stacked bar
- [ ] All 4 chart empty/locked states
- [ ] Live Recent Scans: live dot, search, CSV, refresh, tag chips, sort bar, all 7
      columns, pagination, empty state
- [ ] Recent Prescriptions: thumbnail, doctor + BMDC, Duplicate Rx pill, meta line,
      chevron, empty state
- [ ] Drill-down sheet (company + brand variants)

**Rx Audit Summary**
- [ ] Header with Rx no., doctor line, Duplicate Rx Detected pill
- [ ] Search + 4 filter pills with counts
- [ ] Polypharmacy badge (all 3 levels) · ABX stewardship badge · Off-Territory badge
- [ ] Therapeutic class breakdown bar + legend
- [ ] Duplicate fraud note with the first-captured Rx reference
- [ ] Item rows with all 4 columns and all 8 micro-pill types
- [ ] Own Portfolio Match pill + Generate Doctor Pitch Card pill
- [ ] "Verify against Medex" link on low-confidence rows
- [ ] Market share summary (own + competitor lines)
- [ ] Export CSV + Copy to Clipboard + the crop-preview note
- [ ] Crop preview popover (long-press)
- [ ] Pitch Card modal: compliance pills, 5-row compare table, price position,
      bioequivalence card, pitch script, PDF + Copy buttons, footnote

**Hub**
- [ ] 5 sub-tabs with exact emoji labels
- [ ] Popular medicines dark panel + per-company group headers + count pills + source note
- [ ] Drug index: 4 stat tiles, 4 filter chips, search, curated-slice info line,
      product cards with the DGDA Registered pill
- [ ] TRIPS: window pill, 3 time ranges, 4 totals (first tinted amber), all 8 columns,
      watch-level pills, delta arrows
- [ ] News: featured dark card + timeline cards + source pills + LIVE markers + Refresh
- [ ] Jobs: dark hero with 2 counters, 3 selects + search, cards with tags, salary,
      posted date, Apply now
- [ ] Health Days: next-campaign hero, month stepper, 7-col grid, today state,
      colour-topped cells, month chip list
- [ ] Health day campaign modal

**Team / RSM**
- [ ] Dark hero + PDF button + 4 white/10 KPI tiles
- [ ] Heatmap: SoV / Density clusters toggle, 3 day ranges, 4 stat tiles, map, popup,
      legend (3 dots + bubble note)
- [ ] Tiers: 4 filter chips + specialty select, 4 tinted tiles, all 10 columns,
      ⚠ At risk pill
- [ ] Leaderboard: all 10 columns incl. sparkline and WoW arrow
- [ ] Doctor targets: attach form (4 fields + button), all 8 columns, progress colours,
      delete, auto-visit log, both empty states
- [ ] Off-territory: count pill, all row fields, both micro-pills, Review Rx link,
      empty state
- [ ] Stewardship: share chip with 3 thresholds, 4 tiles, all 8 columns, share bar
      colours, both empty states

**Settings & Help**
- [ ] Company autocomplete + dropdown + helper line
- [ ] 6 profile fields + role select options
- [ ] Monthly brand targets: Add brand, rows, per-brand progress
- [ ] Save officer card button
- [ ] Offline-first note
- [ ] 5 scan-guide steps with verbatim copy
- [ ] Escalation form (3 fields + amber button)
- [ ] BMDC & DGDA reference list (5 items)

**System**
- [ ] 4 toast variants + Android snackbar
- [ ] Offline queued banner + app-bar counter
- [ ] 15 empty / error states from Frame 38

---

## 6. Notes on the port (decisions you should confirm)

1. **Help & Guide** exists as a sixth sidebar destination in the web app but has **no**
   bottom-nav slot on mobile. Recommended: put it in Settings as a list row (Frame 36),
   *or* extend the bottom nav to six items. Do not drop it.
2. **Tables → cards.** Nine web tables use horizontal scroll; their declared
   `min-width`s are 560px (Rx audit items), 760px (doctor targets, stewardship),
   820px (live recent scans), 860px (TRIPS), 880px (team leaderboard) and 900px
   (doctor tiers). On Android these become stacked cards; §4 lists the full column set
   for each so nothing is lost.
3. **The Rx drawer** is a right-side slide-over on web (`max-w-2xl`). On Android it
   becomes a full-screen screen (Frame 17) — it carries too much content for a sheet.
4. **Hover affordances** (crop preview, bbox highlight) become long-press / tap. Keep the
   explanatory footnote, reworded.
5. **`theme-color` is inconsistent in the source** — the HTML `<meta>` uses `#0F172A`
   while `/manifest.json` returns `#6366f1`. For Android use `#0F172A` (matches the
   actual UI) and set the status-bar icons to light.
6. **Dark mode does not exist** in the web app — it was deliberately removed
   (`<html class="light">`, no `dark:` classes). Ship light only unless you want to add
   a dark theme as new work.
7. **Chart.js / Leaflet** need Android equivalents: MPAndroidChart or Vico for charts,
   osmdroid or the Google Maps SDK for the Bangladesh bubble map.
