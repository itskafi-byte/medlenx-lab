# agent/ — working folder for the Android port

Everything in here belongs to the agent, not to the app. It is committed on
purpose: **the sandbox this session runs in is periodically restored from an
older snapshot**, and anything that is not pushed to the remote is lost. Files
here survive a reset and tell the next session where things stood.

Nothing in `agent/` is compiled or shipped. `android/checks/guard.py` treats
`agent/` as an allowed root alongside `android/`.

## Read this first, in this order

| File | What it gives you |
|---|---|
| `STATE.md` | Where the port stands: done / unverified / next. Start here. |
| `COMMANDS.md` | Every command used in this project, with what "healthy" output looks like. |
| `MAP.md` | File → responsibility, and which Python module each Kotlin file ports. |
| `findings/` | Dated scan reports. The findings, not the tool output. |
| `parity.py` | Is any feature from `origin/main` missing on Android? |
| `roomcheck.py` | Does every Room `@Query` column actually exist? |

## Rules that were learned the hard way

1. **Run the guard at the start of every turn and again before every commit.**
   `python3 android/checks/guard.py`. A sandbox reset drops local `HEAD` back to
   the branch base while the working tree keeps current edits; the next commit
   then records the entire web app as deleted. This has happened three times. The
   guard refuses to let that commit happen; `python3 android/checks/recover.py
   --apply` repairs the state in one step. Only `.git` regresses -- the files
   survive -- so recovery snapshots the tree, resets to the remote tip and writes
   back only what differs.
2. **Never force-push.** On a rejected push: `git fetch` → diff → `git reset
   --hard FETCH_HEAD` → re-apply → commit → fast-forward push.
3. **Never use backticks inside `git commit -m "..."`** — bash eats them.
4. **Do not trust `grep … | head`.** It truncated a route list at 60 of 60+
   entries and produced a false "no such feature". Pipe to `wc -l` first, then
   read the range you actually need.
5. **A clean checker run proves nothing until a fault proves it fires.** Both
   tools here were self-tested with injected faults before being trusted. Also
   confirm the checker is actually reading files: `imports.py` once used a path
   relative to the CWD, so running it from the repo root walked a directory that
   did not exist and printed "no findings" for a tree it never looked at. All four
   checkers now resolve their roots from their own file path and work from any
   directory.
6. **Images cannot be read in this session.** `read_file` on any image returns
   "no vision capabilities". Screenshots are a dead design-audit channel; use
   `adb shell uiautomator dump` (text with pixel bounds) or plain descriptions.
7. **The user compiles locally** (Quail 4 | 2026.1.4, JDK 25). Do not try to
   build here — there is no JVM available and no network route to get one.

## Scope reminders

- This branch is Android-only. Only `android/` and `agent/` may change.
- Commit only Android app files (plus this folder) — never touch the Python web
  app on `main`.
- Compose + Material 3, Material vector icons, `haze` for blur (haze forces
  `compileSdk 37` — do not downgrade it).
- The app must be standalone, with the OpenRouter key read from `.env`.
