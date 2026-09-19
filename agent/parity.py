#!/usr/bin/env python3
"""
Web (origin/main, Python) <-> Android (Kotlin) feature parity checker.

Why this exists
---------------
The Android app is a port of the Python/Flask web app on origin/main. Ports drift:
a helper gets reimplemented, a route never gets a screen, a field the web UI shows
has no counterpart. Reading both trees side by side does not scale, so this
greps for evidence instead.

It is a TRIAGE tool, not a verdict. It answers "is there any trace of this name
in the Kotlin tree?" A miss means LOOK AT IT, not necessarily "missing" -- the
Kotlin port may legitimately rename things (snake_case -> camelCase is handled,
but `pitch_card_pdf` may well be `DoctorPitchCard`). Every miss is meant to be
read by a human and marked in a findings file.

Usage
-----
    python3 agent/parity.py                # full report
    python3 agent/parity.py routes          # just the Flask route table
    python3 agent/parity.py tabs            # just the web UI tab/section table
    python3 agent/parity.py <ModuleName>    # one module, e.g. python3 agent/parity.py compliance
"""

import re
import subprocess
import sys

# Every Python module on main that carries product logic (not plumbing).
PY_MODULES = [
    "app/main.py",
    "app/medicine_matcher.py",
    "app/medlenx_client.py",
    "app/pharma_hub.py",
    "app/rx_audit.py",
    "app/compliance.py",
    "app/intelligence.py",
    "app/database.py",
    "app/config.py",
]

# Functions that exist only to serve the web/HTTP layer, so a native port
# legitimately has no counterpart.
WEB_ONLY = {
    "dashboard",          # renders index.html
    "health_check",
    "get_medlenx_client",
    "load_medex_db",
    "get_current_own_company",
}


def git_show(path: str) -> str:
    out = subprocess.run(
        ["git", "show", f"origin/main:{path}"], capture_output=True, text=True
    )
    return out.stdout


def kotlin_corpus() -> str:
    """All Kotlin/Android source concatenated, for plain substring search."""
    files = subprocess.run(
        ["git", "ls-files", "--", "android"], capture_output=True, text=True
    ).stdout.split()
    parts = []
    for f in files:
        if f.endswith((".kt", ".kts", ".xml")):
            try:
                parts.append(open(f, encoding="utf-8", errors="replace").read())
            except OSError:
                pass
    return "\n".join(parts)


def to_camel(snake: str) -> str:
    head, *rest = snake.split("_")
    return head + "".join(w.capitalize() for w in rest)


def public_functions(src: str) -> list[tuple[int, str]]:
    out = []
    for i, line in enumerate(src.split("\n"), 1):
        m = re.match(r"\s*(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", line)
        if not m:
            continue
        name = m.group(1)
        if name.startswith("_") or name in WEB_ONLY:
            continue
        out.append((i, name))
    return out


def found(corpus: str, name: str) -> bool:
    for candidate in {name, to_camel(name), name.replace("_", ""), to_camel(name).capitalize()}:
        if len(candidate) < 3:
            continue
        if re.search(r"\b" + re.escape(candidate) + r"\b", corpus):
            return True
    return False


def report_modules(corpus: str, only: str | None = None) -> None:
    print("=" * 72)
    print("FEATURE PARITY: origin/main (Python) -> Android (Kotlin)")
    print("=" * 72)
    total = unmatched = 0
    misses: list[tuple[str, int, str]] = []
    for mod in PY_MODULES:
        if only and only.lower() not in mod.lower():
            continue
        src = git_show(mod)
        fns = public_functions(src)
        if not fns:
            continue
        mod_miss = [(mod, ln, n) for ln, n in fns if not found(corpus, n)]
        total += len(fns)
        unmatched += len(mod_miss)
        misses.extend(mod_miss)
        flag = "OK " if not mod_miss else f"{len(mod_miss):2d}?"
        print(f"  [{flag}] {mod:<28} {len(fns) - len(mod_miss):>2}/{len(fns):<2} public fns traceable")
    print()
    print(f"  traceable: {total - unmatched}/{total}   to-review: {unmatched}")
    if misses:
        print()
        print("  Names with no trace in the Kotlin tree -- LOOK AT EACH ONE:")
        cur = None
        for mod, ln, name in misses:
            if mod != cur:
                print(f"\n    {mod}")
                cur = mod
            print(f"      :{ln:<5} {name}   (camel: {to_camel(name)})")
    print()


def report_routes() -> None:
    src = git_show("app/main.py")
    print("=" * 72)
    print("FLASK ROUTES on origin/main  (each needs an Android equivalent)")
    print("=" * 72)
    lines = src.split("\n")
    for i, line in enumerate(lines):
        m = re.match(r'@app\.(get|post|put|delete)\("([^"]+)"', line)
        if not m:
            continue
        verb, path = m.group(1).upper(), m.group(2)
        nxt = lines[i + 1] if i + 1 < len(lines) else ""
        m2 = re.search(r"def\s+([A-Za-z_][A-Za-z0-9_]*)", nxt)
        print(f"  {verb:<6} {path:<45} -> {m2.group(1) if m2 else '?'}")
    print()


def report_tabs() -> None:
    src = git_show("templates/index.html")
    print("=" * 72)
    print("WEB UI TABS / SECTIONS on origin/main")
    print("=" * 72)
    seen = set()
    for m in re.finditer(r'data-tab="([a-z0-9-]+)"', src):
        if m.group(1) not in seen:
            seen.add(m.group(1))
            print(f"  tab        {m.group(1)}")
    for m in re.finditer(r'id="(hub-[a-z0-9-]+)"', src):
        print(f"  hub-panel  {m.group(1)}")
    print()


def report_destinations() -> None:
    print("=" * 72)
    print("ANDROID DESTINATIONS")
    print("=" * 72)
    try:
        src = open(
            "android/app/src/main/java/com/medlenx/lab/ui/navigation/Destination.kt",
            encoding="utf-8",
        ).read()
    except OSError:
        print("  (Destination.kt not found)")
        return
    for m in re.finditer(r"^\s*(?:data\s+)?object\s+([A-Za-z]+)|^\s*([A-Z][A-Za-z]*)\s*\(\s*\$?", src, re.M):
        name = m.group(1) or m.group(2)
        if name:
            print(f"  {name}")
    print()


def main() -> int:
    arg = sys.argv[1] if len(sys.argv) > 1 else None
    if arg == "routes":
        report_routes()
        return 0
    if arg == "tabs":
        report_tabs()
        return 0
    if arg == "dest":
        report_destinations()
        return 0
    corpus = kotlin_corpus()
    report_modules(corpus, only=arg)
    report_destinations()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
