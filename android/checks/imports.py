#!/usr/bin/env python3
"""
Symbol checks that `audit.py` cannot do.

`audit.py` counts declarations and queries; it never resolves a name. That is
why this session shipped four unresolved-reference errors to the user's
compiler, and one duplicate DAO method that broke KSP. Every check here is the
kind of mistake that compiles cleanly in nobody's favour: the code looks fine
until Kotlin resolves the name and fails.

Checks
------
1. Missing imports - a known symbol is used but its import is absent.
2. Duplicate member declarations - the same `fun`/`val`/`var` name declared
   twice in one interface or class (conflicting overloads at codegen time).
3. Orphaned `private set` - a `private set` that does not follow a property.
4. `@Composable` call inside `remember { }` - that lambda is not a composable
   context, so `LocalContext.current` there is illegal.

Usage
-----
    python3 android/checks/imports.py
"""

import os
import re
import sys
from collections import defaultdict

ROOT = os.path.join("app", "src", "main", "java")

# Symbol -> the import its use requires. Kept to symbols this codebase actually
# touches; extend as new APIs are adopted.
REQUIRED = {
    # foundation
    "background": "androidx.compose.foundation.background",
    "border": "androidx.compose.foundation.border",
    "clickable": "androidx.compose.foundation.clickable",
    "horizontalScroll": "androidx.compose.foundation.horizontalScroll",
    "verticalScroll": "androidx.compose.foundation.verticalScroll",
    "detectTransformGestures": "androidx.compose.foundation.gestures.detectTransformGestures",
    # layout
    "fillMaxSize": "androidx.compose.foundation.layout.fillMaxSize",
    "fillMaxWidth": "androidx.compose.foundation.layout.fillMaxWidth",
    "fillMaxHeight": "androidx.compose.foundation.layout.fillMaxHeight",
    "heightIn": "androidx.compose.foundation.layout.heightIn",
    "IntrinsicSize": "androidx.compose.foundation.layout.IntrinsicSize",
    "height": "androidx.compose.foundation.layout.height",
    "size": "androidx.compose.foundation.layout.size",
    "aspectRatio": "androidx.compose.foundation.layout.aspectRatio",
    "offset": "androidx.compose.foundation.layout.offset",
    "padding": "androidx.compose.foundation.layout.padding",
    # NB: `weight` is deliberately absent - it is a MEMBER of RowScope/ColumnScope,
    # not an extension, so it resolves with no import inside a Row or Column.
    # drawing
    "clip": "androidx.compose.ui.draw.clip",
    "drawBehind": "androidx.compose.ui.draw.drawBehind",
    "drawWithContent": "androidx.compose.ui.draw.drawWithContent",
    "graphicsLayer": "androidx.compose.ui.graphics.graphicsLayer",
    "ColorFilter": "androidx.compose.ui.graphics.ColorFilter",
    "ColorMatrix": "androidx.compose.ui.graphics.ColorMatrix",
    "Stroke": "androidx.compose.ui.graphics.drawscope.Stroke",
    "Offset": "androidx.compose.ui.geometry.Offset",
    "Size": "androidx.compose.ui.geometry.Size",
    "ContentScale": "androidx.compose.ui.layout.ContentScale",
    "pointerInput": "androidx.compose.ui.input.pointer.pointerInput",
    "RoundedCornerShape": "androidx.compose.foundation.shape.RoundedCornerShape",
    "CircleShape": "androidx.compose.foundation.shape.CircleShape",
    # material3
    "Dialog": "androidx.compose.ui.window.Dialog",
    "DialogProperties": "androidx.compose.ui.window.DialogProperties",
    # runtime
    "remember": "androidx.compose.runtime.remember",
    "LaunchedEffect": "androidx.compose.runtime.LaunchedEffect",
    "mutableStateOf": "androidx.compose.runtime.mutableStateOf",
    "mutableFloatStateOf": "androidx.compose.runtime.mutableFloatStateOf",
    "mutableIntStateOf": "androidx.compose.runtime.mutableIntStateOf",
    # platform
    "LocalContext": "androidx.compose.ui.platform.LocalContext",
    # coil
    "AsyncImage": "coil.compose.AsyncImage",
    # coroutines
    "Dispatchers": "kotlinx.coroutines.Dispatchers",
    "withContext": "kotlinx.coroutines.withContext",
    "isActive": "kotlinx.coroutines.isActive",
    "coroutineContext": "kotlinx.coroutines.coroutineContext",
    "Mutex": "kotlinx.coroutines.sync.Mutex",
    "withLock": "kotlinx.coroutines.sync.withLock",
    "Job": "kotlinx.coroutines.Job",
    # project
    "Mlx": "com.medlenx.lab.ui.theme.Mlx",
    "MlxType": "com.medlenx.lab.ui.theme.MlxType",
    "MlxShape": "com.medlenx.lab.ui.theme.MlxShape",
    "MlxD": "com.medlenx.lab.ui.theme.MlxD",
    "MedexProduct": "com.medlenx.lab.data.model.MedexProduct",
    "AssetCatalogue": "com.medlenx.lab.data.local.AssetCatalogue",
    "ScannedMedicineEntity": "com.medlenx.lab.data.local.ScannedMedicineEntity",
}

# Only flag a symbol when it is actually being *called* or *referenced as a
# value*, not when it is merely a named argument (`border = Mlx.Brand200`) or a
# parameter name. `weight`/`padding`/`size`/`height`/`offset` are common enough
# as named arguments that they are checked only in modifier-call position.
CALL_ONLY = {"padding", "size", "height", "offset", "border", "background"}


def strip_comments(text: str) -> str:
    out = []
    for line in text.split("\n"):
        s = line.strip()
        if s.startswith("//") or s.startswith("*") or s.startswith("/*"):
            out.append("")
        else:
            out.append(line)
    return "\n".join(out)


def used_symbols(code: str, symbol: str):
    """Positions where `symbol` is genuinely invoked/referenced."""
    hits = []
    # Modifier.foo(  /  .foo(  -> always a call
    for m in re.finditer(r"(?:\.|\bModifier\b\.)" + symbol + r"\s*\(", code):
        hits.append(m.group(0))
    if symbol not in CALL_ONLY:
        # Capitalised API used bare: Stroke(, Offset(, Dialog(
        for m in re.finditer(r"(?<![\w.])" + symbol + r"\s*[\(\.]", code):
            hits.append(m.group(0))
    return hits


def check_missing_imports():
    findings = []
    for dirpath, _, files in os.walk(ROOT):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            raw = open(path, encoding="utf-8").read()
            code = strip_comments(raw)
            package = ""
            m = re.search(r"^package\s+([\w.]+)", raw, re.M)
            if m:
                package = m.group(1)
            for symbol, imp in REQUIRED.items():
                # same-package types need no import
                if imp.rsplit(".", 1)[0] == package:
                    continue
                if used_symbols(code, symbol) and imp not in code:
                    findings.append((path, symbol, imp))
    return findings


def check_duplicate_members():
    """Duplicate `fun`/`val`/`var` names declared directly in one class/interface.

    Scope-sensitive on purpose: only declarations sitting at the container's own
    brace depth count. Local variables inside functions live at a deeper depth,
    so they are ignored -- counting them flagged dozens of false positives.
    """
    findings = []
    for dirpath, _, files in os.walk(ROOT):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            code = strip_comments(open(path, encoding="utf-8").read())
            lines = code.split("\n")
            depth = 0
            container = None  # (name, brace depth its members sit at)
            seen = defaultdict(list)

            def flush():
                for name, ls in seen.items():
                    if len(ls) > 1 and container:
                        findings.append((path, container[0], name, ls))

            for i, line in enumerate(lines, 1):
                # leaving the container?
                if container and depth < container[1]:
                    flush()
                    container, seen = None, defaultdict(list)
                m = re.match(
                    r"\s*(?:private |internal |abstract |sealed |data |open |final )*"
                    r"(?:class|interface|object)\s+(\w+)",
                    line,
                )
                if m:
                    flush()
                    container, seen = (m.group(1), depth + 1), defaultdict(list)
                elif container and depth == container[1]:
                    dm = re.search(r"\b(?:suspend\s+)?fun\s+(\w+)\s*[\(<]", line)
                    if dm:
                        seen[dm.group(1)].append(i)
                    else:
                        pm = re.search(r"\b(?:val|var)\s+(\w+)\s*[:=]", line)
                        if pm:
                            seen[pm.group(1)].append(i)
                depth += line.count("{") - line.count("}")
            flush()
    return findings


def check_orphan_private_set():
    findings = []
    prop = re.compile(r"\b(?:val|var)\s+\w+")
    for dirpath, _, files in os.walk(ROOT):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            lines = open(path, encoding="utf-8").read().split("\n")
            for i, line in enumerate(lines):
                if line.strip() != "private set":
                    continue
                prev = next(
                    (l for l in reversed(lines[:i]) if l.strip()), ""
                ).strip()
                if not prop.search(prev):
                    findings.append((path, i + 1, prev))
    return findings


def check_composable_in_remember():
    findings = []
    for dirpath, _, files in os.walk(ROOT):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            lines = open(path, encoding="utf-8").read().split("\n")
            for i, line in enumerate(lines):
                if "LocalContext.current" not in line:
                    continue
                # `remember {` may open on the same line or on one of the lines above.
                opened_here = re.search(r"remember[^{]*\{", line)
                opened_above = any(
                    re.search(r"remember[^\n]*\{\s*$", l)
                    for l in lines[max(0, i - 3):i]
                )
                if opened_here or opened_above:
                    findings.append((path, i + 1, line.strip()))
    return findings


def main() -> int:
    findings = 0

    miss = check_missing_imports()
    if miss:
        findings += len(miss)
        print("MISSING IMPORTS")
        for path, symbol, imp in miss:
            print(f"  {path}\n      uses {symbol}  ->  needs: {imp}")
        print()

    dup = check_duplicate_members()
    if dup:
        findings += len(dup)
        print("DUPLICATE MEMBER DECLARATIONS")
        for path, container, name, lines in dup:
            print(f"  {path}\n      {container}.{name} declared at lines {lines}")
        print()

    orphan = check_orphan_private_set()
    if orphan:
        findings += len(orphan)
        print("ORPHANED `private set`")
        for path, line, prev in orphan:
            print(f"  {path}:{line}  (preceded by: {prev!r})")
        print()

    comp = check_composable_in_remember()
    if comp:
        findings += len(comp)
        print("@Composable CALL INSIDE remember {}")
        for path, line, text in comp:
            print(f"  {path}:{line}  {text}")
        print()

    if findings:
        print(f"imports: {findings} finding(s)")
        return 1

    print("imports: no findings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
