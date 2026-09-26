#!/usr/bin/env python3
"""
A composable control that can never fire.

Why this exists
---------------
Three times now this project has shipped a control that renders, responds to
touch, and does nothing:

  * `ScanScreen.kt` passed `{ /* Medex re-check - Step 7 */ }` for both
    `onVerifyAgainstMedex` and `onReportMisId`, so the buttons existed and were
    inert (`344c27c`).
  * `RecentPrescriptions` took `onSelectPrescription` with a `{}` default, and
    the caption under every row read "tap for item breakdown".
  * `PrescriptionImageViewer` declares `overlay: @Composable () -> Unit = {}`,
    calls `overlay()`, and no call site has ever supplied it - so that slot
    renders nothing, always, and no reader of the file can tell whether it is a
    deliberate extension point or an unfinished one.

None of those is a compile error, and none is visible to a symbol check: the
parameter is declared, typed, defaulted and used. The defect is that nothing
ever *supplies* it, which is a fact about the whole project rather than about
any one file - the same shape as `daocalls.py` (a call site disagreeing with a
declaration) and the same reason it needs its own pass.

What it looks for
-----------------
Every `@Composable` function parameter whose default is an empty lambda
(`= {}`, `= { _, _ -> }`) and which no call site in the project ever passes by
name. If no caller supplies it, the lambda it defaults to is what always runs,
and an empty lambda is a control wired to nothing.

Scoped to `@Composable` on purpose. A defaulted no-op lambda is a normal design
in non-UI code - an optional hook, a template method - and reporting those would
bury the two real findings above in false ones.

Known shape limits
------------------
  * Only named arguments count as supplying a parameter. Every composable call
    in this codebase names its arguments, so a positional call would be read as
    "not supplied"; a wrong answer in the loud direction is the right one here,
    but it is an assumption rather than a proof.
  * A parameter supplied only from a file outside `android/` would be missed for
    the same reason. Nothing outside `android/` calls into it.
  * It cannot tell a deliberately-unused hook from a forgotten one. Both are
    reported, and the fix for both is the same conversation.
"""

from __future__ import annotations

import os
import re
import sys

# The parameter list splitter this needs is the one `daocalls.py` already
# debugged: it treats `<`/`>` as generics only when written tight, and - the part
# that matters here - it knows `->` in a function type is not a closing bracket.
# A naive depth counter sees the `>` of `() -> Unit` as popping a level, so every
# comma after a lambda-typed parameter stops splitting and `= {}` ends up glued
# to the next parameter. That is how the first draft of this file reported
# nothing at all while 142 composables sat in front of it.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import daocalls as dc  # noqa: E402  (path set above)

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
UI = os.path.join(ROOT, "android/app/src/main/java/com/medlenx/lab")

# `@Composable` then the declaration, with or without a modifier and with a KDoc
# in between already stripped by the caller.
_COMPOSABLE = re.compile(
    r"@Composable\s*\n\s*(?:private\s+|internal\s+|public\s+)?fun\s+(\w+)\s*\("
)
# `{}`, `{ }`, `{ _, _ -> }`, `{ _ -> }` - an empty lambda, however it is spelled.
_EMPTY_LAMBDA = re.compile(r"\{\s*[\w,\s>]*\}")


def sources() -> dict[str, str]:
    out = {}
    for dirpath, _dirnames, filenames in os.walk(UI):
        for name in filenames:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as handle:
                # Comments are blanked, not removed, so line numbers survive.
                out[path] = dc.strip_comments(handle.read())
    return out


def empty_lambda_params(files: dict[str, str]) -> dict[str, list[str]]:
    """composable name -> parameters defaulted to an empty lambda."""
    found: dict[str, list[str]] = {}
    for text in files.values():
        for m in _COMPOSABLE.finditer(text):
            name = m.group(1)
            open_idx = m.end() - 1
            close_idx = dc.match_paren(text, open_idx)
            if close_idx == -1:
                continue
            for part in dc.split_top_level(text[open_idx + 1 : close_idx]):
                if "=" not in part:
                    continue
                param, default = part.split("=", 1)
                param = param.split(":", 1)[0].strip()
                if not re.fullmatch(r"\w+", param):
                    continue
                if _EMPTY_LAMBDA.fullmatch(default.strip()):
                    found.setdefault(name, []).append(param)
    return found


def supplied(name: str, param: str, files: dict[str, str]) -> bool:
    """Does any call site pass `param = ...` to `name`?"""
    pattern = re.compile(r"(?<![\w.])" + re.escape(name) + r"\s*\(")
    named = re.compile(r"(?<![\w])" + re.escape(param) + r"\s*=")
    for text in files.values():
        for m in pattern.finditer(text):
            close_idx = dc.match_paren(text, m.end() - 1)
            if close_idx == -1:
                continue
            if named.search(text[m.end() : close_idx]):
                return True
    return False


def main() -> int:
    files = sources()
    if not files:
        print(f"deadparams: no Kotlin sources under {UI}", file=sys.stderr)
        return 1

    found = empty_lambda_params(files)
    composables = len(re.findall(_COMPOSABLE, "\n".join(files.values())))
    # A parse that stopped matching would report a clean tree while looking at
    # nothing, which is the failure this project keeps re-learning.
    if composables == 0:
        print(
            "deadparams: parsed 0 @Composable functions - the discovery pattern "
            "no longer matches this codebase, so this run proved nothing",
            file=sys.stderr,
        )
        return 1

    print("=" * 72)
    print("DEAD CONTROL PARAMETERS")
    print("=" * 72)
    print(f"  {composables} @Composable function(s) parsed")
    print(f"  {len(found)} with a parameter defaulted to an empty lambda")
    print()

    problems = []
    for name in sorted(found):
        for param in found[name]:
            if not supplied(name, param, files):
                problems.append(
                    f"  {name}(...)  '{param}' defaults to an empty lambda and no "
                    f"call site ever supplies it"
                )

    total = sum(len(v) for v in found.values())
    if problems:
        print(f"  {len(problems)} PROBLEM(S) of {total} parameter(s):")
        for problem in problems:
            print(problem)
    else:
        print(f"  no problems found - all {total} parameter(s) are supplied by a caller")
    print()
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
