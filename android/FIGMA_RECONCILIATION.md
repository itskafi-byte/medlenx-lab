# Figma Export → Android Reconciliation

Analysis of the React/Vite project exported from the Figma Make workspace, retrieved
from branch **`itskafi-byte-patch-1`** via the GitHub API (attachments would not upload
to the sandbox).

Source files analysed, at the branch root:

| File | Size | Relevance |
|---|---|---|
| `App.tsx` | 145,034 B / 2,282 lines | **The whole generated UI** — 57 components |
| `index.css` | 3,685 B | `@theme` tokens + keyframes |
| `package.json` | 612 B | React 19, Tailwind v4, recharts 3.10, Vite 8 |
| `index.html` | 440 B | Shell only |
| `main.tsx` | 232 B | Entry only |

> **Important:** the export expresses its design system as **inline React `style={{}}`
> objects with numeric values**, not Tailwind utility classes. Tailwind appears only in
> `index.css` (the `@theme` block and keyframes). Any audit that greps for `rounded-*`
> or `text-*` classes finds nothing — the numbers have to be read out of the styles.

---

## 1. Verdict on the palette: effectively a match

The Figma `const C` block and `@theme` declare the same ramp the web app uses, and my
`ui/theme/Color.kt` (derived from `templates/index.html`) matches it **hex for hex**:

```
brand 900/800/700/600/500/400/300/200/100/50  — all 10 identical
ok  600/500/100/50                            — all 4 identical
warn 600/500/100/50                           — all 4 identical
orange 600 #EA580C · bg #FFF7ED · border #FDBA74   — identical
red    600 #DC2626 · bg #FEE2E2 · border #FCA5A5   — identical
violet 600 #7C3AED · bg #EDE9FE · border #C4B5FD   — identical
cyan   #0891B2 · #0284C7 · #38BDF8                 — identical
chart  [#1E40AF,#059669,#D97706,#0891B2,#7C3AED,#DC2626]  — identical, same order
```

Counts **before** the fixes: Kotlin theme 60 distinct colours, Figma 58. After adding
the five missing tokens below, Kotlin is 65 and the gap is 1 (`#0E7490`, data-driven).

### Six colours Figma uses that my theme does not define

| Hex | Uses in Figma | What it is | Action |
|---|---|---|---|
| `#BFDBFE` | 6 | blue-200 — borders/light text on blue pills | **added** as `Mlx.Blue200` |
| `#B31D1D` | 3 | red-800 — darker red text than my `DangerText #B91C1C` | **added** as `Mlx.DangerDeep` |
| `#F97316` | 1 | orange-500 — gradient end on the unverified company pill | **added** as `Mlx.GuessSoft` |
| `#FB923C` | 1 | orange-400 — bounding-box stroke | was a literal in `ui/screens/scan/PrescriptionImageViewer.kt:193-194`; now `Mlx.GuessLight` |
| `#FBBF24` | 1 | amber-400 | added as `Mlx.Amber400` |
| `#0E7490` | 1 | cyan-700 — a *health-day* colour, **not** a theme token | no action (see below) |

`#0E7490` appears at `App.tsx:1498` inside a hardcoded list:
`{day:10,name:"World COPD Day",color:"#0E7490"}`. The real dataset
(`data/health_days.json`, shape `{source, year, days:[…]}`, line 34) says
`{"id":"copd","month":11,"day":18,"org":"GOLD","focus":["Pulmonology"],"color":"#0E7490"}`.

> **Figma's `HubHealthDays` uses inline mock data that disagrees with the dataset**
> (day 10 vs. month 11 / day 18 — the dataset's own summary reads "Third Wednesday of
> November"). Step 7 must read `health_days.json` and render the *dataset's* values,
> treating Figma only as the layout source. The same caution applies to the other
> Hub screens.

### Eight colours in my theme that Figma never uses

`#065F46` `#06B6D4` `#0D9488` `#4338CA` `#5B21B6` `#92400E` `#A5F3FC` `#C2410C`

These are extras I derived from the web app (pill text colours and the company
palette). Harmless to keep — they are used by `companyColor()`, which Figma does not
reimplement.

### Naming only, no colour difference

Figma calls `#0284C7` **cyan500** and `#38BDF8` **cyan300** (`App.tsx:20`); I named them
`Cyan700` and `Cyan400`. Same hexes. Cosmetic — not worth churn.

---

## 2. Divergences — all applied

| # | Property | Figma (evidence) | Was | Now |
|---|---|---|---|---|
| 1 | Bottom nav height | `height:60` (App.tsx:361) | `56.dp` | **60.dp** — `Dimens.kt:40` |
| 2 | Active nav shape | **pill** `9999` + `padding:"6px 16px"`, hugging content | `RoundedCornerShape(12.dp)` on the full cell | `MlxShape.Pill` on an inner Column inside a weighted, centred Box — `MlxBottomNav.kt:70-72` |
| 3 | Nav icon size | active **14px** / inactive **16px** | 16dp both | `if (active) 14.dp else 16.dp` — line 80 |
| 4 | Nav label weight | active **600** / inactive **500** | one style | `SemiBold` / `Medium` — line 86 |
| 5 | App bar blur | `backdropFilter:"blur(12px)"` over `rgba(255,255,255,0.92)` | not implemented | **implemented** with `dev.chrisbanes.haze` 1.0.2 — see note below |
| 6 | Wordmark tracking | `letterSpacing:"-0.02em"` | absent | `letterSpacing = (-0.02).em` — `Type.kt:90` |
| 7 | Search field | h**36**, `fontSize:11`, `padding:"0 12px 0 32px"`, 1px border `#E2E8F0`→`#2563EB` + `box-shadow:0 0 0 2px rgba(37,99,235,0.20)` on focus | 12sp, 12dp padding, **no border, no focus ring** | all four, in `SearchField` — `MlxTopBar.kt:127-190` |
| 8 | Tab-strip radius | container `12`, items `10`, active **white + shadow** | `MlxShape.Chip` (8) and active = **slate-900** (inverted) | `MlxShape.Tab` (10) added; `MlxSegmented` rewritten white-on-slate — `Buttons.kt` |
| 9 | Latency chip | not in Figma | implemented | **kept**, documented as an Android-only addition |
| 10 | Company chip text | `"Set company"` | `"Set company in Settings"` | **unchanged** — the web app itself says `"Set company in Settings"` (`templates/index.html:176`); Figma is the one truncating |
| 11 | Online chip dot | 6px `#047857` circle | none | `StatusPill(dotColor=…)`, dot = `Mlx.Ok600` = `#047857` |

Also applied: the placeholder text was shortened to `"Search medicines, doctors, generics"`;
restored to the web's full `"Search medicines, doctors, generics - always accessible from
anywhere..."` under the anti-truncation contract. And the app bar row now sets its own
height — Figma's `height:64` is border-box, and since `MedLenXShell` draws the 1dp border
as a separate Divider, the bar is 63dp so the pair totals 64.

### How fix 5 was done

Compose has no `backdrop-filter`, so this needed a library. Added **`dev.chrisbanes.haze`
1.0.2** (released 2024-11-15, chosen to match the pinned Compose BOM 2024.09.02 era;
group and version verified against the repo tag, since Maven Central is unreachable
from this sandbox).

- `MedLenXShell` owns `val hazeState = remember { HazeState() }`, applies
  `Modifier.haze(state = hazeState)` to the scrolling content and passes the state down.
- `MlxTopBar` is the haze child: `Modifier.hazeChild(state, style = AppBarHaze)` where
  `AppBarHaze = HazeStyle(backgroundColor = Color.White.copy(alpha = 0.92f), blurRadius = 12.dp)`
  — Figma's glass, expressed literally.
- `haze-materials` is **not** a dependency; the explicit `HazeStyle` is closer to the
  mock than any `HazeMaterials` preset.

The structural part mattered more than the dependency: a backdrop blur only has
something to sample if content passes *behind* the bar. The shell's content Box used to
take `.padding(inner)`, which pushed everything below the bar. It now takes only the
bottom-bar inset, and the top offset moved into the screen — carried **inside**
`ScanScreen`'s `verticalScroll` so content scrolls under the glass. Non-scrolling
screens are offset by `AppBarHeight + SectionGap` until Steps 5-9 give them real
scrolling content. Haze uses `RenderEffect` on API 31+ and degrades to the plain scrim
below that, which is what the bar already drew.

### Fix 8 scope, corrected

`MlxSegmented` had **zero call sites**, so rewriting it was safe. But the other 18 uses
of `borderRadius:10` all belong to components that do not exist yet — `MedicineCardComponent`
(×5, Step 4), `TeamTiers`/`TeamTargets` (×8, Step 8), `SettingsScreen` (×2, Step 9),
`DoctorPitchCard`, `HubDrugIndex`, `TeamMap`, `EmptyStatesShowcase`. `MlxShape.Tab` is
therefore staged for them, with `MedicineCardComponent` the first consumer.

### Missing radius token

Figma's radius histogram: `12` (54×) · `9999` (40×) · `8` (25×) · **`10` (20×)** ·
`16` (8×) · `6` (7×) · `4` (1×). My `MlxShape` has 4/6/8/12/16/pill but **no 10dp**.

### Type scale — complete, no gaps

Frequency in Figma: `11` (113×) · `10` (101×) · `12` (42×) · `14` (38×) · `9` (32×) ·
`13` (23×) · `18` (12×) · `16` (9×) · `20` (6×) · `24` (3×) — **all present in my scale**.

> **Correction.** An earlier draft of this doc claimed 22 / 28 / 32 were missing type
> sizes. They are not. Reading each occurrence shows every one is an **emoji glyph size
> inside an icon container**, not typography:
>
> | Size | Occurrences | What it actually is |
> |---|---|---|
> | 22 | 1 | emoji in the 48×48 `EmptyState` circle (App.tsx:214) |
> | 28 | 3 | emoji in the 64×64 pack-image placeholders (App.tsx:510, 1324) |
> | 32 | 2 | emoji in the 72×72 splash tile and 80×80 scan hero tile (App.tsx:2178, 406) |
> | 40 / 48 / 60 | 4 | larger glyphs, same story |
>
> So the typography scale needs **no additions at all**.

### Two Step 4 measurements worth carrying forward

- The medicine pack image is **64×64, `borderRadius:10`**, `background:#F8FAFC`,
  `border:"2px solid #F1F5F9"`, with a 💊 at 28 as the placeholder (App.tsx:510).
  The drug-index variant is the same 64×64 but `borderRadius:12` and a 1px `#E2E8F0`
  border (App.tsx:1324).
- The scan upload hero is `borderRadius:16` with a **`2px dashed #BFDBFE`** border and
  `padding:"40px 24px"`; its title is 20/700 and its subtitle 13 (App.tsx:406). That
  dashed `#BFDBFE` is what accounts for 6 of the 6 uses of that hex.

---

## 3. Component mapping — 57 Figma components

Figma's component list maps almost 1:1 onto the 39-frame inventory in
`FIGMA_ANDROID_UI_PROMPT.md`. Notably, **Figma reaches Help & Guide from Settings**
(App.tsx:2056 "Help & Guide entry", opened via `setShowHelp`) — the same decision made
in Step 1, which is reassuring.

| Group | Figma components | Kotlin status |
|---|---|---|
| Splash | `SplashScreen` | pending (Step 9) |
| Shell | `AppBar` `BottomNav` `ContentArea` `Card` `SectionHeader` `SectionLabel` | **done** (Step 1) |
| Primitives | `StatusPill` `RegPill` `ConfBadge` `CompanyBadge` `CompanyPill` `FilterChip` `PrimaryBtn` `GhostBtn` `SuccessBtn` `RefreshPill` `ProgressBar` `MiniKPI` `KPICard` `EmptyState` `SkeletonRow` `DarkHero` `Toast` `Icon` | **done** except `Toast`, `CompanyBadge`, `RefreshPill` |
| Scan | `ScanTab` `ScanEmpty` `ScanCamera` `ScanInProgress` `ScanSaved` `VerifyDoctor` `VerifyMedicines` `VerifyGPS` `MedicineCardComponent` | Scan flow **done** (Step 3); `Verify*` + `MedicineCardComponent` are Step 4 |
| Analytics | `AnalyticsOverview` `LiveRecentScans` `RecentPrescriptions` `FilterSheet` `SearchOverlay` `RxAuditSummary` `DoctorPitchCard` | pending (Steps 5–6) |
| Hub | `HubScreen` `HubDrugIndex` `HubTrips` `HubNews` `HubJobs` `HubHealthDays` | pending (Step 7) |
| Team | `TeamScreen` `TeamMap` `TeamTiers` `TeamLeaderboard` `TeamTargets` `TeamOffTerritory` `TeamStewardship` | pending (Step 8) |
| Settings / Help | `SettingsScreen` `HelpScreen` | pending (Step 9) |
| Extra | `EmptyStatesShowcase` | matches my Frame 38 |

**Hub sub-tab order matches exactly:** DrugIndex → Trips → News → Jobs → HealthDays
(App.tsx:1581–1585).

## 4. Animations — verified against `index.css`

| Animation | Figma | My implementation | Status |
|---|---|---|---|
| Scan laser | 3px, `transparent→#0284C7→#38BDF8→#0284C7→transparent`, glow `0 0 12px 4px rgba(6,182,212,0.7)`, **2.2s** | 3dp, same gradient, 2200ms | ✅ duration & gradient match |
| Laser travel | `top: 8% → 88% → 8%` | was `0f → 0.98f` | ✅ now **`0.08f → 0.88f`** (lines 56-57) |
| Shimmer | `translateX(-100%) → 200%`, **1.5s** | was `-1f → 2f`, 1600ms | ✅ now **1500ms** (line 107); range was already correct |
| Live dot ping | `scale(1)→scale(2.5)`, opacity 1→0 at 75%, 1.5s | not yet built | needed for Step 5 |
| Hero circle | 256px, `top:-80px right:-80px`, `rgba(255,255,255,0.10)`, `blur(40px)` | specified, not built | Step 5 |
| Sheet overlay | `rgba(0,0,0,0.4)` | specified | Step 6 |
| Scan brackets | **3px** `#38BDF8` corners, each with a **4px outer radius** (`border-radius: 4px 0 0 0` etc.) — `index.css:128-132` | spec'd 40dp arms (`FIGMA_ANDROID_UI_PROMPT.md:358`); not built | build with 3dp stroke + 4dp outer corner radius |
| Progress track | `#E2E8F0`, radius 9999, **6px** | `ProgressBarThin = 6.dp` | ✅ |
| Map | `#F8FAFC`, 1px `#E2E8F0`, radius **12** | 12dp | ✅ |

## 5. Decision needed: emoji vs vector icons

The Figma export renders every icon as an **emoji glyph** at a `fontSize`:

- Nav: `📋 📈 ⚗️ 🗂 ⚙️`
- Logo: `🔬` · Search: `🔍` · Avatar: `👤`
- Chips: `☁ 0 queued`, `🏛 Set company`

My Step 1–3 code uses **Material vector icons** instead. Emoji is faithful to the design
but renders inconsistently across Android versions and OEM fonts, cannot be tinted, and
looks wrong at nav size. Vector icons are the normal Android choice and match the web
app's Font Awesome intent.

**Recommendation: keep vector icons.** Flagging rather than silently deciding, since
"same to same" could be read either way.

---

## Summary

The palette is a hex-for-hex match, which means Steps 1–3 were built on the right
colours. The divergences are small and measurable: **11 property differences**, **6
missing colours**, **1 missing radius token** and **2 animation timings** — the type
scale turned out to need nothing — all listed above with file and line.

Nothing found in the Figma export invalidates the architecture. Two caveats carried
forward: the export's **content is mock data** (proven above for health days), so
`data/*.json` stays the source of truth for Step 7; and the 11 property fixes above are
applied. The backdrop blur needed the `haze` library and a shell restructure so
content actually scrolls behind the bar; both are done.

## Verification

Static analysis only. There is no JDK, Gradle, Kotlin or Android SDK in this sandbox
(`dl.google.com`, `repo.maven.apache.org` unreachable), so **none of this has been
compiled or run**. Every figure above comes from a direct read of the named file and
line, and the colour counts were produced by diffing the two hex sets with a script
(Kotlin `0xFFRRGGBB` → `[2:]`, Figma `#RRGGBB`), run twice with identical results.

