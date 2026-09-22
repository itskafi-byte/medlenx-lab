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
All four are fast; run them together.

```bash
python3 android/checks/guard.py      # tree safety / reset detection
python3 android/checks/imports.py    # missing imports, duplicate members, composable-in-remember
python3 android/checks/audit.py      # declaration counts, Room/Hilt wiring sanity
python3 agent/roomcheck.py           # every @Query column resolves against its entity
```
- `imports.py` healthy: `imports: no findings` (7 checks: missing imports,
  duplicate members, orphaned `private set`, composable-in-`remember`, missing
  return, unresolved symbol, scope leak)
- `roomcheck.py` healthy: `no problems found - every column and table resolves`
  (9 entities, 60 queries)

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
