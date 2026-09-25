#!/usr/bin/env python3
"""
Check that every Room DAO call site matches its declaration.

Why this exists
---------------
The user compiles; this sandbox cannot. The single most likely compile error
introduced by adding a DAO method is an argument mismatch at the call site —
too many arguments, too few, a named argument the function does not have, or
two arguments silently transposed (which is worse: it compiles and returns
wrong rows).

`roomcheck.py` validates the SQL *inside* a @Query against the entity schema.
It says nothing about whether Kotlin code calls the function correctly. This
check covers the other half, and together the two cover the whole path from
call site to column.

What it checks
--------------
1. positional argument count is within [required, total] parameters
2. named arguments exist on the declaration
3. a named argument is not also filled positionally

Deliberately not checked: argument *types*. Inferring Kotlin types well enough
to compare them is a much larger job and would produce false findings; the
count and name checks catch the realistic mistakes.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "android"
SRC = ANDROID / "app" / "src" / "main" / "java"

# Files holding @Dao interfaces.
DAO_GLOBS = ("**/data/local/Daos.kt",)


def strip_comments(text: str) -> str:
    """Blank out comments but keep newlines, so offsets line up with the source."""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '"' and i + 2 < n and text[i + 1] == '"' and text[i + 2] == '"':
            j = text.find('"""', i + 3)
            j = n if j == -1 else j + 3
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j == -1 else j
            out.append(" " * (j - i))
            i = j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j == -1 else j + 2
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:j]))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)



def _is_generic_open(text: str, i: int) -> bool:
    """
    True when the '<' at i opens a generic rather than meaning "less than".

    Kotlin style makes these distinguishable: generics are written tight
    (`List<Int>`, `Map<String, Int>`) while comparisons and the `<=` operator
    are spaced (`a < b`). Counting every '<' as a bracket is what made a
    lambda body containing `it > 0` unbalance the depth and split one argument
    into two.
    """
    prev = text[i - 1] if i > 0 else ""
    nxt = text[i + 1] if i + 1 < len(text) else ""
    return (
        prev.isalnum() or prev in "_>)"
    ) and (nxt.isalnum() or nxt in "_<")


def _is_generic_close(text: str, i: int) -> bool:
    """True when the '>' at i closes a generic, not a comparison or a `->`."""
    prev = text[i - 1] if i > 0 else ""
    if prev in "-=!":  # `->` in a lambda, `>=`, `!=`-adjacent comparisons
        return False
    return prev.isalnum() or prev in "_>)"


def match_paren(text: str, open_idx: int) -> int:
    """Index of the ')' matching the '(' at open_idx, or -1."""
    depth = 0
    in_str = False
    i = open_idx
    while i < len(text):
        c = text[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def split_top_level(text: str) -> list[str]:
    """Split on commas that are not nested in (), [] or a string."""
    parts, buf = [], []
    depth = 0
    in_str = False
    i = 0
    while i < len(text):
        c = text[i]
        if in_str:
            buf.append(c)
            if c == "\\" and i + 1 < len(text):
                buf.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
        elif c == '"':
            in_str = True
            buf.append(c)
        elif c in "([{":
            depth += 1
            buf.append(c)
        elif c in ")]}":
            depth -= 1
            buf.append(c)
        elif c == "<" and _is_generic_open(text, i):
            depth += 1
            buf.append(c)
        elif c == ">" and _is_generic_close(text, i):
            depth -= 1
            buf.append(c)
        elif c == "," and depth == 0:
            parts.append("".join(buf).strip())
            buf = []
        else:
            buf.append(c)
        i += 1
    if "".join(buf).strip():
        parts.append("".join(buf).strip())
    return parts


def parse_daos() -> dict[str, dict[str, tuple[list[str], int]]]:
    """
    Map  interface -> method -> (param names, number with no default).

    Only methods declared inside a `@Dao` interface are collected, so plain
    helper functions in the same file do not collide with call sites.
    """
    sigs: dict[str, dict[str, tuple[list[str], int]]] = {}
    for pattern in DAO_GLOBS:
        for path in sorted(ANDROID.glob(pattern)):
            text = strip_comments(path.read_text(encoding="utf-8"))
            # Each @Dao interface runs to the next @Dao or end of file.
            chunks = re.split(r"@Dao\b", text)[1:]
            for chunk in chunks:
                iface = re.search(r"interface\s+(\w+)", chunk)
                if not iface:
                    continue
                table = sigs.setdefault(iface.group(1), {})
                for m in re.finditer(
                    r"\bfun\s+(\w+)\s*\(([^)]*)\)", chunk
                ):
                    name, raw_params = m.group(1), m.group(2)
                    names: list[str] = []
                    required = 0
                    for p in split_top_level(raw_params):
                        if not p:
                            continue
                        # Drop the type and any default: 'a: Int = 3' -> 'a'
                        nm = p.split(":", 1)[0].strip()
                        if not re.fullmatch(r"\w+", nm):
                            continue
                        names.append(nm)
                        if "=" not in p.split(":", 1)[-1]:
                            required += 1
                    table[name] = (names, required)
    return sigs


def find_call_sites(methods: set[str]) -> list[tuple[Path, int, str, list[str]]]:
    """Every `x.method(args)` where method is a known DAO function."""
    sites = []
    pattern = re.compile(
        r"([A-Za-z_][A-Za-z0-9_.]*)(\(\))?\s*\.\s*("
        + "|".join(sorted(methods))
        + r")\s*\("
    )
    for path in sorted(SRC.rglob("*.kt")):
        text = strip_comments(path.read_text(encoding="utf-8"))
        for m in re.finditer(pattern, text):
            # `it.all { ... }` and `dao.all()` are both `.all(`; only the second
            # is a DAO call, so the receiver has to look like one.
            receiver = m.group(1).split(".")[-1]
            if not receiver.endswith("Dao"):
                continue
            name = m.group(3)
            open_idx = m.end() - 1
            close_idx = match_paren(text, open_idx)
            if close_idx == -1:
                continue
            args = split_top_level(text[open_idx + 1 : close_idx])
            line = text[: m.start()].count("\n") + 1
            sites.append((path, line, name, args))
    return sites


def main() -> int:
    sigs = parse_daos()
    methods = {n for table in sigs.values() for n in table}
    if not methods:
        print("daocalls: no @Dao methods found -- check DAO_GLOBS", file=sys.stderr)
        return 1

    print("=" * 72)
    print("DAO CALL SITE VALIDATION")
    print("=" * 72)
    for iface, table in sorted(sigs.items()):
        print(f"  {iface:<22} {len(table)} method(s)")
    print()

    findings: list[str] = []
    checked = 0

    for path, line, name, args in find_call_sites(methods):
        # A method name can exist on more than one DAO; accept if any fits.
        options = [table[name] for table in sigs.values() if name in table]
        positional = [a for a in args if not re.match(r"^\w+\s*=", a)]
        named = [a for a in args if re.match(r"^\w+\s*=", a)]
        named_names = [re.match(r"^(\w+)\s*=", a).group(1) for a in named]

        problems = []
        ok_any = False
        for names, required in options:
            local = []
            if len(args) > len(names):
                local.append(f"too many arguments ({len(args)} > {len(names)})")
            if len(positional) > len(names) - len(named_names):
                local.append(
                    f"too many positional arguments ({len(positional)})"
                )
            missing = [n for n in names if n not in named_names]
            if len(positional) + len(named_names) < required:
                local.append(
                    f"missing required argument(s) {missing[:3]}"
                )
            unknown = [n for n in named_names if n not in names]
            if unknown:
                local.append(f"no such parameter {unknown}")
            dup = [n for n in named_names if n in named_names[: named_names.index(n)]]
            if dup:
                local.append(f"duplicate named argument {dup}")
            # A name filled both positionally and by name.
            if not unknown and len(positional) > 0:
                overlap = set(named_names) & set(names[: len(positional)])
                if overlap:
                    local.append(
                        f"passed both positionally and by name: {sorted(overlap)}"
                    )
            if not local:
                ok_any = True
                break
            problems.append("; ".join(local))
        checked += 1
        if not ok_any:
            rel = path.relative_to(ROOT)
            findings.append(f"    {rel}:{line}  {name}({len(args)} arg(s))\n      {problems[0]}")

    if findings:
        print(f"  {len(findings)} PROBLEM(S):\n")
        for f in findings:
            print(f)
        print()
        return 1

    print(f"  {checked} call site(s) checked - all match their declaration")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
