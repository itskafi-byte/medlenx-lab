# Commands

Each entry: what it does, and what healthy output looks like. If you see
something else, read the note before acting.

Runs from the repo root (`/home/user/medlenx-lab`) unless stated.

---

## Safety net — run these constantly

```bash
python3 android/checks/guard.py
```
Pre-commit guard. **Run at the start of a turn and again immediately before
every commit.** Healthy:
```
guard: ok - on arena/01a09bf9-medlenx-lab, HEAD <sha>, all changes under android/
```
If it refuses, it is almost always a sandbox reset. **Repair it in one step:**
```bash
python3 android/checks/recover.py            # report only, changes nothing
python3 android/checks/recover.py --apply    # repair
```
It snapshots every file under `android/` and `agent/`, resets git to the remote
tip, then writes back only the files that differ — i.e. exactly the work done
since the last push. Only `.git` regresses in a reset; the files survive, so
nothing is lost and nothing is ever deleted. Verified end to end against a
simulated reset (`git reset --soft 2befd2c`).

A refusal looks like:
```
guard: REFUSING - the working tree is not safe to commit
  - HEAD (2befd2c) is NOT a descendant of the remote tip (f9299fc).
        This is the sandbox-reset signature ...
  - 162 deletion(s) outside android/ -- e.g. .env.example
```

---

## Static checks — these replace compiling

The user compiles, so anything catchable without a compiler must be caught here.
All seven are fast; run them together.

```bash
python3 android/checks/guard.py      # tree safety / reset detection
python3 android/checks/imports.py    # missing imports, duplicate members, composable-in-remember
python3 android/checks/audit.py      # declaration counts, Room/Hilt wiring sanity
python3 agent/roomcheck.py           # every @Query column resolves against its entity
python3 android/checks/daocalls.py   # every DAO call site matches its declaration
python3 android/checks/migrationcheck.py  # migration DDL vs the entities it creates
python3 android/checks/deadparams.py  # a control whose callback nothing ever supplies
```

`faulttest.py` runs on its own, because it edits the tree on purpose:

```bash
python3 android/checks/faulttest.py            # all 21 faults
python3 android/checks/faulttest.py roomcheck  # one check's faults
```

It injects each fault, asserts the check reports it *and* exits non-zero,
restores, re-runs the check to confirm it is clean again, and finally re-hashes
every file under `android/` and `agent/` and fails if anything differs from the
pre-run hash. That last step is the point: fault injection used to be done by
hand, and one of those scripts crashed partway through and left `Migrations.kt`
mutated until it was caught by eye. "Restored" has to be checked, not asserted,
because the failure mode being guarded against is precisely the code path that
did not run.

It also refuses to pass a fault whose anchor text no longer appears exactly once:
a fault that has drifted out of sync with the code is a fault that silently
stopped testing anything, which is worse than a missing one.
- `imports.py` healthy: `imports: no findings` (11 checks: missing imports,
  duplicate members, orphaned `private set`, orphaned KDoc,
  composable-in-`remember`, scope leak, missing icon import, unresolved symbol,
  missing return, unknown theme token, missing cross-package import)

The eleventh check is the one the compiler finally had to make for me. The build
reported 30 errors from two files; five were the cause and the other 25 were
cascades -- a type that cannot be resolved has no members, so `.copy(...)`,
`.name` and `.id` fail on it too. Both existing symbol checks missed it:

  * `check_missing_imports` works from a hand-written symbol -> import table, so a
    symbol added after that table was written is not in it.
  * `check_undefined_symbols` reports a name declared *nowhere*. These were
    declared, just not anywhere that file could see. "Declared in the project" is
    not the same as "resolvable here", and an import is exactly what decides it.

So it indexes every capitalised top-level declaration with the package that
declares it, then flags a use from a different package that has no import. It is
verified against the compiler's own output: it reports those same six lines, and
nothing else, on the tree that failed to build.

Two things it must not flag, both found by running it: an **enum entry**, which is
a declaration and a use at once (`None("No catalogue match")` reads exactly like a
reference), and text inside a **raw string**. The second was a real bug in
`mask_literals`, shared by every check: it did not know `"""`, so the first two
quotes read as an empty literal and the rest of the prompt became code --
`Bengali` in the VL prompt was reported as a missing import. Raw strings are now
masked with their newlines kept, which also stopped a multi-line literal from
collapsing onto one line and shifting every line number after it.

`check_undefined_symbols` sees only the segment *before* a dot, so it resolves
`MlxShape` and never the `Medium2` after it -- the same blind spot that let
`Icons.Filled.Share` ship broken. Icons got their own check; the theme objects are
the other half. Only the plain `val` containers in `ui/theme` are checked (no
supertype, no `override`), so a missing name is missing without any type
inference, and members are collected at depth 0 in the object body only: a plain
`^\s*val (\w+)` over the body also matches locals declared inside the object's
functions, which added `hue`, `sat` and `a` to `Mlx` and would have accepted a
wrong `Mlx.hue`.

An orphaned KDoc is a doc block immediately followed by another one. Two
consecutive docs are never meaningful -- the second attaches to the declaration
and the first attaches to nothing -- and they only arise one way: something is
inserted *between* a doc comment and the function it described. The text then
sits above the wrong function, reading as if it documents it, while the real one
silently loses its docs. It has happened three times here (`copyToClipboard`
above `shareSummary`, `saveVerified` above `resolveDoctorId`, and `healthDays`
above `healthDayDetail`), which makes it a check rather than a habit of reading
upwards before every edit.
- `roomcheck.py` healthy: `no problems found - every column and table resolves`,
  closing with a count line: `N bind parameter(s) checked for use, M statement(s)
  checked against the filter fragment` (10 entities, 70 queries, all parsed in
  full). The count line is there to be read: if it drops, a check went quiet.
- `daocalls.py` healthy: `N call site(s) checked - all match their declaration`
  (87 sites)
- `migrationcheck.py` healthy: `no problems found - every migration DDL statement matches its entity`
- `deadparams.py` healthy: `no problems found - all N parameter(s) are supplied by a
  caller`, under a header reading `N @Composable function(s) parsed` and `M with a
  parameter defaulted to an empty lambda`. Both counts are there to be read, and
  the check **exits 1 when either parse count is 0** rather than reporting a clean
  tree — a discovery pattern that stopped matching would otherwise be the most
  reassuring possible output.

`migrationcheck.py` exists because Room's schema validation runs on the device at
first open after an upgrade and throws `Migration didn't properly handle ...` on a
mismatch — unreachable from this sandbox and fatal on the user's phone, which is
the worst pairing of the two. `roomcheck.py` cannot cover it: it resolves column
*names* and carries no type or nullability, so a migration with the right names,
the wrong affinity and a stray NOT NULL passes there and crashes at open.

It reads the DDL out of a Kotlin `execSQL(\"...\" + \"...\" + ...)` chain by
concatenating the literal *contents* first — the statement does not exist as
contiguous text in the file, and parsing the raw source silently saw fragments
while still reporting a clean run.

Migrations are replayed as a chain and the *result* is held to the entities.
Validating each statement against the current entity on its own reports a fault
Room would never raise — `MIGRATION_1_2` correctly creates `doctors` without
`territory`, which `MIGRATION_2_3` then adds — and the apparent fix, editing the
older migration, breaks every device already on that version.

Verified against nine faults, each caught: a dropped NOT NULL, a wrong affinity, a
UNIQUE index declared non-unique, an omitted column, an index name Room would not
generate, an ALTER adding the wrong type, an ALTER dropping NOT NULL, a column the
chain never creates, and `ADD COLUMN ... NOT NULL` with no DEFAULT (SQLite refuses
that on a populated table, inside `migrate()`).

One parsing trap is worth remembering: because the flattened stream concatenates
every literal in the file, a statement does not end at a newline or a `;`, so a
greedy declaration tail runs into the *next* statement. A later `WHERE x IS NOT
NULL` then satisfies the nullability check the fault was meant to fail.

Known limit: only DDL is validated. The `SELECT`/`UPDATE`/`INSERT` statements the
backfill helpers run are not checked against anything, and they execute inside
`migrate()` at first launch after an upgrade -- the least forgiving place for a
mistyped column. A migration helper written against a past schema version cannot
honestly be checked against the current entities, so the helpers guard their
column indices at runtime and return rather than write garbage. Do not delete
those guards as noise.

`roomcheck.py` resolves a qualified column against the table its alias names, not
against the union of every table in the query. Before that, `d.territory` passed
because `territory` existed on `prescriptions` — the qualifier was decoration, and
a column written against the wrong table was invisible. Unqualified columns are
still judged against the union, deliberately: SQLite binds those at run time
against any joined table, so rejecting one that a sibling owns is a false alarm.

It assembles each `@Query` argument before parsing it, and substitutes project
`const val`s by name. Both matter, and both were silent failures:

  * A `@Query` argument is a chain of adjacent literals, so the statement exists
    nowhere in the file as contiguous text. The old parser stopped at the first
    closing quote: `prescriptionCountBetween` was validated as
    `SELECT COUNT(DISTINCT p.id) FROM prescriptions p ` with its JOIN, its WHERE
    and its filter fragment missing. Six queries were parsed as bare SELECT lists
    (no `FROM` at all) and six more had no function name resolved. Now all 70
    parse in full. Same fault `migrationcheck.py` had, hiding the same way.
  * `RX_FILTER_SQL` is a `const val` appended by name, so the fragment has to be
    substituted or it is invisible. Substitution goes longest-name-first and on
    word boundaries: `RX_FILTER_SQL` is a prefix of `RX_FILTER_SQL_SNAPSHOT`, and
    a plain `str.replace` rewrote the snapshot constant into the joined-doctor
    fragment with a stranded `_SNAPSHOT` left in the SQL for every check to judge. Substituting it also means a query that
    appends the fragment without joining `doctors` is caught directly, by the
    alias check below, instead of needing a second string-matching check to
    duplicate the knowledge and drift from it.

Two checks came out of writing the specialty filter:

  * **Unknown table alias.** `d.specialty` needs a `doctors` join; without one
    SQLite reports `no such column` at run time, and only on the path that reads
    a specialty — so the query works for everyone until someone opens the filter.
  * **Ambiguous unqualified column.** A bare column that exists on more than one
    joined table is a run-time `ambiguous column name` error that Room cannot see.
    This is not hypothetical: joining `doctors` put a second `id` in scope next to
    `prescriptions.id`, and three `COUNT(DISTINCT id)` statements became ambiguous.
    All three are now `COUNT(DISTINCT p.id)`.

Both are fault-tested by injecting the fault and asserting it is reported.

Two more came out of adding the prescription-source filter, and both are about a
filter that compiles, runs, and does nothing:

  * **An unbound parameter.** Every declared method parameter must appear as
    `:name` in the assembled SQL. Room accepts a declaration whose parameter the
    statement never uses; the parameter is simply dead. Seventeen queries append
    the filter fragment by name and four repeat it as their own literal SQL. Adding
    `:source` to the constant gave those seventeen a source clause and left the
    four with a `source: String?` parameter nothing bound — a filter that reads
    "Hospital" in the sheet and filters nothing, silently, with every check in the
    repository reporting clean.
  * **A fragment that drifted.** Every statement carrying a filter fragment is
    compared, whitespace-normalised and in full, against every known variant.
    Nothing else connects the constants to the literal copies inside four
    `@Query` blocks, so a clause added to one and not the other is invisible
    otherwise. The fragment is walked as a run of `(clause) AND (clause) …`
    rather than cut between two markers, which is what lets a copy that lost its
    last clause read as a shorter run instead of the same fragment.
    `canonical_filter_fragments` raises rather than returning `{}` — with no
    canonical text, every statement would compare clean.

The count line `N bind parameter(s) checked for use, M statement(s) checked
against K filter fragment(s)` exists so both announce how much they looked at.
M is 21 and not 4 because the seventeen that append a constant are substituted
before the comparison, so they are checked too.

**There are two variants, and the check cannot tell them apart.** `RX_FILTER_SQL`
takes specialty from the joined doctor; `RX_FILTER_SQL_SNAPSHOT` takes it from
`p.doctor_specialty` and is used by `recentMedicineExportRows` alone, because
`/api/export/recent-medicines.csv` is the one endpoint the web backs with the
denormalised `recent_scanned_medicines` row (`database.py:1329`) rather than with
a join. They differ *only* in that one qualifier, so a statement that picks the
wrong variant matches the other one exactly and is not reported. What is reported
is which statements use which variant — the count line names them — so a
statement changing families shows up in the output without being a finding.

`deadparams.py` catches the defect class this project has now hit three times: a
control that renders, responds to touch, and does nothing, because its callback
parameter is declared, typed, defaulted to `{}`, and passed by no call site.
`ScanScreen.kt` shipped `{ /* Medex re-check - Step 7 */ }` for both
`onVerifyAgainstMedex` and `onReportMisId`; `RecentPrescriptions` promised "tap for
item breakdown" under every row; and `PrescriptionImageViewer.overlay` was invoked at
the end of the canvas and supplied by nobody since the file was written. None of those
is a compile error and none is visible to a symbol check — the parameter *is* declared,
*is* typed and *is* used, so it reads as intentional. Only a project-wide pass can see
that nothing supplies it.

It is scoped to `@Composable`: an optional no-op hook is normal design in non-UI code,
and reporting those would bury the real findings. It reports a deliberately-unused
extension point the same way it reports a forgotten one, which is intended — both want
the same conversation — and the fix for the dead one is removal, as
`PrescriptionImageViewer.overlay` was removed rather than given a caller.

`roomcheck.py` and `daocalls.py` are complements and cover the whole path from a
call site to a column: roomcheck validates the SQL *inside* a `@Query` against the
entity schema, and says nothing about whether Kotlin calls the function correctly.
daocalls checks the argument list — count, and that named arguments exist — which
is the single most likely compile error from adding a DAO method, since the user
compiles and this sandbox cannot.

daocalls deliberately does **not** check argument *types*; inferring Kotlin types
well enough to compare them is a much larger job and would produce false findings.
Its known shape limits: it treats `<` as a generic only when written tight
(`List<Int>`) and as a comparison only when spaced (`it > 0`) — counting every `<`
and `>` as brackets once unbalanced the depth and split one argument into two,
which is how the `ScanRepository.insert` false positive arose. It also only
accepts calls whose receiver ends in `Dao`, so `it.all { }` is not mistaken for
`dao.all()`.

All four resolve their input roots from their own file path, so they give the same
answer from the repo root or from `android/`. This matters more than it sounds:
`imports.py` used to use a CWD-relative root, so `python3 android/checks/imports.py`
from the repo root walked a non-existent directory and reported a clean tree it had
never examined. If a checker reports clean suspiciously fast, make it fail on
purpose and confirm it notices.

Self-test any of them before trusting a clean run:
```bash
python3 agent/roomcheck.py   # then inject a typo in a @Query, re-run, confirm it is caught
```
A self-test only counts if the fault actually lands. An injected fault that the
script never wrote looks exactly like a clean run: a `python3 - <<EOF` heredoc that
prints "fault injected" *unconditionally* reported a passing check while the file
was still correct. Assert the replacement happened, and re-read the file after
writing it, before believing either result.

`daocalls.py` was verified against three real faults, each caught: a dropped
argument, a misspelled named argument, and one argument too many.

```bash
python3 agent/parity.py           # web -> android feature coverage
python3 agent/parity.py routes    # the Flask route table on main
python3 agent/parity.py tabs      # the web UI tab/section list
```
`parity.py` is triage, not a verdict — a miss means *look at it*, because the
Kotlin port may legitimately rename things.

---

## Orientation

```bash
git ls-remote origin refs/heads/arena/01a09bf9-medlenx-lab   # what the remote has
git rev-parse --short HEAD                                    # what you have
git show --stat --name-only --pretty=format: HEAD             # what the last commit touched
git diff --stat                                               # uncommitted work
```
Compare the first two after any suspicious result — if local `HEAD` is behind the
remote tip, the sandbox reset.

```bash
# Anything outside android/ and agent/? Should be nothing.
git ls-tree -r --name-only HEAD | grep -v '^android/' | grep -v '^agent/'
```

---

## Committing

```bash
python3 android/checks/guard.py && python3 android/checks/imports.py \
  && python3 android/checks/deadparams.py \
  && (cd android && python3 checks/audit.py | tail -3) \
  && git add android agent \
  && git commit -q -F - <<'MSG' && git push -q origin arena/01a09bf9-medlenx-lab
<message>
MSG
```
Heredoc the message (`-F -`) so it can carry apostrophes and quotes freely.
**Never put backticks in the message.**

On a rejected push — fetch, save the work, re-apply, fast-forward. Never force.
```bash
git fetch origin arena/01a09bf9-medlenx-lab
git diff FETCH_HEAD HEAD -- android agent > /tmp/turn.patch
git reset --hard FETCH_HEAD
git apply /tmp/turn.patch
git add android agent && git commit -q -m "..." && git push -q origin arena/01a09bf9-medlenx-lab
```

---

## Device verification (the user runs these)

```bash
adb shell uiautomator dump /sdcard/w.xml && adb shell cat /sdcard/w.xml
```
The only readable way to audit layout — text plus exact pixel `bounds`. Use it to
check clipping, overlap and box position numerically. I cannot see screenshots.

```bash
adb logcat -d | grep -i "AndroidRuntime\|FATAL"
```
Needed for the unresolved camera crash.

---

## App facts, so they don't have to be re-derived

```
compileSdk 37   minSdk 26   targetSdk 36     (targetSdk deliberately not raised)
AGP/Kotlin/Room pinned in android/gradle/libs.versions.toml
VL_MODEL_PRIMARY   qwen/qwen3-vl-235b-a22b-instruct
VL_MODEL_FALLBACK  qwen/qwen3-vl-30b-a3b-instruct
```
Assets in `android/app/src/main/assets/data/`: `medex_full.json` (16 MB, 25,105
rows), `neml_list.json`, `dgda_prices.json`, `trips_waiver.json`,
`health_days.json`, `pharma_news.json`, `pharma_jobs.json`, `bd_locations.json`,
`bd_geo.json`.

## Mapbox (optional - the Team heatmap's tile map)

The heatmap renders the offline Canvas dot map by default. To switch it to Mapbox
tiles, matching the web app's Leaflet/OpenStreetMap panel:

    echo 'MAPBOX_ACCESS_TOKEN=pk.***' >> android/local.properties

`local.properties` is git-ignored, so the token never reaches version control. It
is read into `BuildConfig.MAPBOX_ACCESS_TOKEN` by the same `secret()` helper as the
OpenRouter key. With no token the app builds and runs exactly as before.

Two things worth knowing before you turn it on:
  * The Maps SDK is not on Maven Central. `settings.gradle.kts` adds Mapbox's own
    repository; without it the build fails with "Could not resolve com.mapbox.maps".
  * The `-ndk27` artifacts are used because this app targets SDK 36, and Android
    15+ devices with 16 KB pages cannot load 4 KB-page native libraries.
  * From v11.8.0 the SDK pulls in Google Play Services for an HTTP/3 client.
    Mapbox documents how to strip it if you want the app free of Play.
  * The token is injected as R.string.mapbox_access_token via resValue, NOT by
    calling MapboxOptions.accessToken. MapboxOptions lives in com.mapbox.common,
    which the SDK declares with `implementation` scope, so it is not reliably on a
    consumer's compile classpath - importing it produced "Unresolved reference".
    The string resource is the SDK's own primary lookup and needs no import.
