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
All five are fast; run them together.

```bash
python3 android/checks/guard.py      # tree safety / reset detection
python3 android/checks/imports.py    # missing imports, duplicate members, composable-in-remember
python3 android/checks/audit.py      # declaration counts, Room/Hilt wiring sanity
python3 agent/roomcheck.py           # every @Query column resolves against its entity
python3 android/checks/daocalls.py   # every DAO call site matches its declaration
python3 android/checks/migrationcheck.py  # migration DDL vs the entities it creates
```
- `imports.py` healthy: `imports: no findings` (10 checks: missing imports,
  duplicate members, orphaned `private set`, orphaned KDoc,
  composable-in-`remember`, scope leak, missing icon import, unresolved symbol,
  missing return, unknown theme token)

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
- `roomcheck.py` healthy: `no problems found - every column and table resolves`
  (10 entities, 70 queries)
- `daocalls.py` healthy: `N call site(s) checked - all match their declaration`
- `migrationcheck.py` healthy: `no problems found - every migration DDL statement matches its entity`

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

`roomcheck.py` resolves a qualified column against the table its alias names, not
against the union of every table in the query. Before that, `d.territory` passed
because `territory` existed on `prescriptions` — the qualifier was decoration, and
a column written against the wrong table was invisible. Unqualified columns are
still judged against the union, deliberately: SQLite binds those at run time
against any joined table, so rejecting one that a sibling owns is a false alarm.

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
