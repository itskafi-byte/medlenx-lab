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
4. Orphaned KDoc - a doc block immediately followed by another one, which means
   something was inserted between the doc and the declaration it described.
5. `@Composable` call inside `remember { }` - that lambda is not a composable
   context, so `LocalContext.current` there is illegal.
6. Scope leak - a layout-scope member (`.weight`, `.align`, ...) called from a
   receiver that is not the enclosing scope.
7. Missing icon import - `Icons.Filled.X` used without its per-icon import.
8. Unresolved symbol - a name used, declared in no file and imported nowhere.
9. Missing return in a block-bodied function - `fun f(): T { ... }` must return
   explicitly; only `= expr` bodies infer their result.
10. Unknown theme token - `MlxShape.Medium2`. Check 8 sees only the segment
   before the dot, so the member after it is never resolved.
11. Missing import for a project declaration used from another package.

Run it from anywhere: the source root is derived from this file's own path. A
previous revision used a relative root, so running it from the repo root walked a
directory that did not exist, found no files and reported a clean tree that had
never been looked at.

Usage
-----
    python3 android/checks/imports.py
"""

import os
import re
import sys
from collections import defaultdict

# Resolved from this file's own location, NOT from the current directory. The
# relative path this used to hold meant running `python3 android/checks/imports.py`
# from the repo root walked a directory that does not exist, found zero files and
# printed "no findings" -- a pass that was silently vacuous. Any invocation now
# checks the same tree.
HERE = os.path.dirname(os.path.abspath(__file__))            # .../android/checks
ROOT = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "java"))

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



def mask_literals(text: str) -> str:
    """
    Blank out the *text* of string and char literals, keeping `${...}` as code.

    The regex this replaces was `"(?:[^"\\]|\\.)*"`, which is not template
    aware. On `"... ${x.ifBlank { "Regional Sales Manager" }} ..."` it matched
    from the first quote to the quote opening the *nested* literal, so
    `Regional Sales Manager` was left in the text and read as four unresolved
    types. Single-token nested literals like `ifEmpty { "form" }` slipped
    through only because the check is restricted to capitalised names.

    A template expression is Kotlin code: it can reference a real type, and it
    can contain further literals. So it is kept verbatim and scanned
    recursively, while the surrounding literal text is replaced by `""`.

    Raw strings (`\"\"\"`) are handled first, and their content is replaced by
    spaces *rather than removed*, keeping the newlines in place. Without that
    branch the first two of the three opening quotes read as an empty literal and
    everything after them was treated as code: `Bengali` inside the VL prompt in
    MedLenXVlClient.kt was reported as a missing import, and because the masker
    collapsed a multi-line literal onto one line, every line number after it was
    wrong as well.
    """
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '"' and text.startswith('"""', i):
            masked, i = _mask_raw_string(text, i)
            out.append(masked)
            continue
        if c == '"' or c == "'":
            masked, i = _mask_one_literal(text, i)
            out.append(masked)
            continue
        out.append(c)
        i += 1
    return "".join(out)


def _mask_raw_string(text: str, i: int):
    """`i` is the first of the three opening quotes. (replacement, index past close)."""
    n = len(text)
    i += 3
    parts = ['""']
    while i < n:
        if text.startswith('"""', i):
            return "".join(parts), i + 3
        c = text[i]
        if c == "$" and i + 1 < n and text[i + 1] == "{":
            inner, i = _mask_template(text, i + 2)
            parts.append(inner)
            continue
        if c == "$" and i + 1 < n and (text[i + 1].isalpha() or text[i + 1] == "_"):
            # `$name` is a property reference, kept for the same reason as above.
            j = i + 1
            while j < n and (text[j].isalnum() or text[j] == "_"):
                j += 1
            parts.append(text[i:j])
            i = j
            continue
        # A raw string has no escapes, so a backslash is literal text.
        parts.append("\n" if c == "\n" else " ")
        i += 1
    return "".join(parts), i


def _mask_one_literal(text: str, i: int):
    """`i` is the opening quote. Returns (replacement, index past the close)."""
    quote = text[i]
    n = len(text)
    i += 1
    parts = [quote, quote]           # `""` — the check only cares about length>0
    while i < n:
        c = text[i]
        if c == "\\":
            i += 2
            continue
        if c == quote:
            return "".join(parts), i + 1
        if c == "$" and quote == '"' and i + 1 < n:
            nxt = text[i + 1]
            if nxt == "{":
                inner, i = _mask_template(text, i + 2)
                parts.append(inner)
                continue
            if nxt.isalpha() or nxt == "_":
                # `$name` — a property reference, kept so a capitalised one is
                # still visible to the check.
                j = i + 1
                while j < n and (text[j].isalnum() or text[j] == "_"):
                    j += 1
                parts.append(text[i:j])
                i = j
                continue
        i += 1
    return "".join(parts), i


def _mask_template(text: str, i: int):
    """`i` is just past `${`. Returns (replacement, index past the matching `}`)."""
    n = len(text)
    parts = []
    depth = 1
    while i < n:
        c = text[i]
        if c == '"' or c == "'":
            masked, i = _mask_one_literal(text, i)
            parts.append(masked)
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return "".join(parts), i + 1
        parts.append(c)
        i += 1
    return "".join(parts), i



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



def _fun_signature(lines, start_index, name_end):
    """
    A normalised `(param, types)` string for the `fun` whose name ends at
    `name_end` on `lines[start_index]`.

    Only the types matter for identity, but the names are kept too so that
    `keyOf(name: String)` and `keyOf(entity: PrescriptionEntity)` do not collide
    purely because both are one argument. Parameter names are part of neither
    Kotlin's overload rule nor this one -- they are kept only so that two
    same-arity functions without types still differ.
    """
    text = "\n".join(lines[start_index:start_index + 12])
    open_at = text.find("(", name_end)
    if open_at == -1:
        return "<no-params>"
    depth, i, n = 0, open_at, len(text)
    while i < n:
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    raw = text[open_at + 1:i]
    params = []
    for part in re.split(r",(?![^<]*>)", raw):
        part = part.strip()
        if not part:
            continue
        # Drop the default value; `a: Int = 3` and `a: Int` are the same overload.
        part = part.split("=")[0].strip()
        params.append(re.sub(r"\s+", " ", part))
    return "(" + ", ".join(params) + ")"


def check_duplicate_members():
    """Duplicate `fun`/`val`/`var` *signatures* declared in one class/interface.

    Scope-sensitive on purpose: only declarations sitting at the container's own
    brace depth count. Local variables inside functions live at a deeper depth,
    so they are ignored -- counting them flagged dozens of false positives.

    Compares the parameter list, not just the name. Overloads are legal Kotlin,
    and `R.string.keyOf(attrs)` alongside `R.string.keyOf(entity)` is a real
    convenience rather than a duplicate -- keying on the name alone reported it as
    one. Two declarations are a duplicate only when their arity and text agree;
    a genuine same-name-same-arity clash is still caught.
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
            # line number -> normalised parameter list, filled as members are seen.
            signatures: dict[int, str] = {}

            def flush():
                for name, ls in seen.items():
                    # ls is a list of line numbers; a name can appear more than
                    # once legitimately as an overload, so count how many times
                    # each *signature* occurs rather than each name.
                    sig_lines: dict[str, list[int]] = defaultdict(list)
                    for line_no in ls:
                        sig_lines[signatures.get(line_no, "")].append(line_no)
                    for sig, hits in sig_lines.items():
                        if len(hits) > 1 and container:
                            findings.append((path, container[0], f"{name}{sig}", hits))

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
                    dm = re.search(r"\b(?:suspend\s+)?fun\s+(\w+)\s*([\(<])", line)
                    if dm:
                        seen[dm.group(1)].append(i)
                        signatures[i] = _fun_signature(lines, i - 1, dm.end(1))
                    else:
                        pm = re.search(r"\b(?:val|var)\s+(\w+)\s*[:=]", line)
                        if pm:
                            seen[pm.group(1)].append(i)
                            # A property has no parameter list, so its signature is
                            # its declared type -- two `val x: Int` and `val x: String`
                            # in one class is also a clash, but two `val x` is not
                            # distinguishable without more parsing, so type only.
                            tm = re.search(r":\s*([\w.<>?]+)", line[pm.end():])
                            signatures[i] = f":{tm.group(1)}" if tm else ""
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


def _object_members(text: str) -> dict[str, set[str] | None]:
    """
    For each top-level `object X { ... }` in a theme file, the set of its member
    names, or None when the object cannot be enumerated safely.

    Members are collected only at depth 0 inside the body. A plain
    `^\s*val (\w+)` over the whole body also matches *local* vals declared inside
    the object's functions, which would have added `hue`, `sat` and `a` to `Mlx`
    and quietly turned the check into a no-op for any wrong token sharing a name
    with a local variable.
    """
    out: dict[str, set[str] | None] = {}
    for m in re.finditer(r"^object\s+(\w+)\s*(:\s*[\w.<>]+)?\s*\{", text, re.M):
        name, supertype = m.group(1), m.group(2)
        i = m.end() - 1
        depth = 0
        for j in range(i, len(text)):
            if text[j] == "{":
                depth += 1
            elif text[j] == "}":
                depth -= 1
                if depth == 0:
                    break
        body = text[i + 1 : j]
        if supertype or "override " in body:
            # Inherited members cannot be enumerated from this file alone.
            out[name] = None
            continue
        members, d = set(), 0
        for line in body.split("\n"):
            stripped = line.strip()
            if d == 0:
                vm = re.match(r"(?:const\s+)?val\s+(\w+)", stripped)
                fm = re.match(r"(?:private\s+|internal\s+)?fun\s+(\w+)", stripped)
                if vm:
                    members.add(vm.group(1))
                elif fm:
                    members.add(fm.group(1))
            d += line.count("{") - line.count("}")
        out[name] = members
    return out


def check_theme_tokens():
    """
    A theme token that does not exist: `MlxShape.Medium2`.

    `check_undefined_symbols` structurally cannot see this. Its pattern captures
    only the segment before the dot, so it resolves `MlxShape` (which is imported
    or declared) and never looks at the member after it. That blind spot is why
    `Icons.Filled.Share` shipped broken; icons got their own check, and the theme
    objects are the other half of it.

    Only the `object`s declared in `ui/theme` are checked, and only those whose
    members are all declared in the file (no supertype, no `override`). Those
    objects are plain `val` containers, so a name missing from them is missing,
    full stop -- no type inference required and no false positives.
    """
    theme_dir = os.path.join(ROOT, "com", "medlenx", "lab", "ui", "theme")
    members: dict[str, set[str] | None] = {}
    if os.path.isdir(theme_dir):
        for fn in sorted(os.listdir(theme_dir)):
            if fn.endswith(".kt"):
                path = os.path.join(theme_dir, fn)
                members.update(_object_members(open(path, encoding="utf-8").read()))
    members = {k: v for k, v in members.items() if v}
    if not members:
        return []

    findings = []
    for dirpath, _, files in os.walk(ROOT):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            code = mask_literals(strip_comments(open(path, encoding="utf-8").read()))
            for m in re.finditer(r"\b(\w+)\.(\w+)", code):
                obj, member = m.group(1), m.group(2)
                if obj not in members:
                    continue
                if member not in members[obj]:
                    findings.append(
                        (path, code[: m.start()].count("\n") + 1, obj, member,
                         sorted(members[obj]))
                    )
    return findings


# ── Declarations and imports, for resolving a cross-package reference ────────

# A top-level declaration name, anchored to the start of a line -- so every use
# must pass re.M, and a call that forgets it silently matches nothing at all.
# `fun` demands `(` or `<` straight after the name, because without that an
# *extension* receiver reads as a declaration: `private fun RowScope.Foo(` indexed
# `RowScope` as a type declared in that file, and `private fun
# FilterState.drilldownCaption()` made `FilterState` look locally declared in
# AnalyticsScreen.kt -- hiding the missing import this check exists to find.
_TOP_DECL = re.compile(
    r"^[ \t]*"
    r"(?:@\w+(?:\([^)]*\))?[ \t]*)*"
    r"(?:(?:public|internal|private|abstract|open|sealed|data|enum|annotation|"
    r"value|inline|suspend|operator|override|tailrec|external|const|lateinit)[ \t]+)*"
    r"(?:class|interface|object|typealias|val|var)[ \t]+"
    r"([A-Z]\w*)",
    re.M,
)
# Any declaration at all, at any indentation: used only to decide whether a name
# is resolvable *within* the file being read, so over-collecting is harmless.
_ANY_DECL = re.compile(
    r"\b(?:class|interface|object|typealias)\s+([A-Z]\w*)"
    r"|\bfun\s+([A-Z]\w*)\s*[(<]"
    r"|\b(?:val|var)\s+([A-Z]\w*)\s*[:=]"
)
_IMPORT = re.compile(r"^import\s+([\w.]+?)(?:\.\*)?(?:\s+as\s+(\w+))?\s*$", re.M)


def _package_of(code: str) -> str:
    m = re.search(r"^package\s+([\w.]+)", code, re.M)
    return m.group(1) if m else ""


def _enum_entry_names(code: str) -> set[str]:
    """
    The entry names of every `enum class` in the file.

    An enum entry is a declaration and a use at the same time: in
    `enum class MatchType { Exact("..."), None("...") }` the `None` is not a
    reference to anything, but it reads exactly like one. Seven of the ten false
    positives the check first produced were entries -- `Failed` in
    CatalogueState, `None` in CompanyVerification, `Violet(Mlx.VioletBg, ...)`
    in a pill tone enum, `HealthDays("...")` in HubTab.

    Entries are the leading identifiers of the comma-separated pieces at depth 0
    of the body, up to the `;` that separates them from the members. That handles
    a one-line enum (`{ A, B, C }`) and a constructor-argument entry
    (`None("No catalogue match"),`) alike.
    """
    names: set[str] = set()
    for m in re.finditer(r"\benum\s+class\s+\w+[^{]*\{", code):
        body, _ = _balanced_body(code, m.end() - 1)
        # Entries end at the `;` that introduces the members, if there is one.
        cut = body.find(";")
        if cut != -1:
            body = body[:cut]
        for piece in body.split(","):
            em = re.search(r"^[ \t]*([A-Z]\w*)", piece, re.M)
            if em:
                names.add(em.group(1))
    return names


def _balanced_body(text: str, open_idx: int) -> tuple[str, int]:
    """Body between the `{` at open_idx and its match, plus the index past it."""
    depth, i, in_str = 0, open_idx, False
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
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[open_idx + 1 : i], i + 1
        i += 1
    return text[open_idx + 1 :], len(text)


def _project_decl_packages() -> dict[str, set[str]]:
    """Capitalised top-level declaration name -> the packages declaring it."""
    out: dict[str, set[str]] = defaultdict(set)
    for dirpath, _, files in os.walk(ROOT):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            code = mask_literals(strip_comments(open(path, encoding="utf-8").read()))
            pkg = _package_of(code)
            for m in _TOP_DECL.finditer(code):
                name = m.group(1)
                if name:
                    out[name].add(pkg)
    return out


def check_missing_project_imports():
    """
    A project declaration used from another package without importing it.

    This is the fault the compiler reported as 30 errors, of which 5 were the
    cause: `DoctorDao`, `DoctorEntity` and `DoctorIdentity` referenced from
    ScanRepository.kt, and `FilterState` / `DrillCountRow` from
    AnalyticsScreen.kt. Every remaining error was a cascade from those -- a type
    that cannot be resolved has no members, so `.copy(...)`, `.name` and `.id`
    all fail on it too.

    Nothing here caught it, and the reason is worth keeping:

      * `check_missing_imports` works from a hand-written symbol -> import table,
        so a symbol added after that table was written is simply not in it.
      * `check_undefined_symbols` reports a name declared *nowhere* and imported
        nowhere. These were declared -- just not anywhere this file could see.
        Treating "declared in the project" as "resolvable here" is precisely the
        question an import decides.

    Deliberately not flagged:

      * a name used qualified (`com.foo.Bar(...)`, `HubTab.HealthDays`) -- no
        import is needed, and a segment after a dot is not a reference;
      * a name declared anywhere in the same file, or in the same package;
      * a name covered by a star import;
      * lowercase names. A top-level `fun` or property with a lowercase name is
        far more likely to be a member or local of the surrounding scope, and
        the false positives would drown the finding.
    """
    declared = _project_decl_packages()
    if not declared:
        return []

    findings = []
    for dirpath, _, files in os.walk(ROOT):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            code = mask_literals(strip_comments(open(path, encoding="utf-8").read()))
            pkg = _package_of(code)

            imported, starred = set(), set()
            for m in _IMPORT.finditer(code):
                target = m.group(1)
                if m.group(2):
                    imported.add(m.group(2))
                elif m.group(0).rstrip().endswith("*"):
                    starred.add(target)
                else:
                    imported.add(target.split(".")[-1])

            local = {g for m in _ANY_DECL.finditer(code) for g in m.groups() if g}
            local |= _enum_entry_names(code)
            local |= {name for name, pkgs in declared.items() if pkg in pkgs}

            for m in re.finditer(r"(?<![.\w])([A-Z]\w*)", code):
                name = m.group(1)
                if name in local or name in imported:
                    continue
                pkgs = declared.get(name)
                if not pkgs or all(p == pkg for p in pkgs):
                    continue
                if any(s == q or q.startswith(s + ".") for s in starred for q in pkgs):
                    continue
                findings.append(
                    (path, code[: m.start()].count("\n") + 1, name, sorted(pkgs))
                )
    return findings


def check_orphaned_kdoc():
    """
    A KDoc block immediately followed by another one.

    Two consecutive doc comments are never meaningful in Kotlin -- the second
    attaches to the declaration and the first attaches to nothing -- and the way
    they arise is always the same: a function is inserted *between* a doc comment
    and the function it described. The text then sits above the wrong function,
    reads as if it documents it, and the real one silently loses its docs.

    This has happened twice in this project (`copyToClipboard`'s doc above
    `shareSummary`, and `saveVerified`'s above `resolveDoctorId`), which makes it
    worth a check rather than a habit of reading upwards before every edit.
    """
    findings = []
    for dirpath, _, files in os.walk(ROOT):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            lines = open(path, encoding="utf-8").read().split("\n")
            i = 0
            while i < len(lines):
                if lines[i].strip().startswith("/**"):
                    # End of this doc block.
                    j = i
                    while j < len(lines) and "*/" not in lines[j]:
                        j += 1
                    # Skip blank lines; another doc block right after is the fault.
                    k = j + 1
                    while k < len(lines) and not lines[k].strip():
                        k += 1
                    if k < len(lines) and lines[k].strip().startswith("/**"):
                        findings.append((path, i + 1, lines[k].strip()))
                        i = k
                        continue
                    i = j + 1
                    continue
                i += 1
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
                #
                # The pattern must match a *call* to remember, not any word that
                # starts with it. `fun rememberDocumentSaver(...): DocumentSaver {`
                # matched the old `remember[^{]*{` and flagged the function's own
                # body — and `rememberXxx()` is the standard name for a composable
                # that returns a remembered value, so the false positive was
                # guaranteed to recur. Anchoring on a call shape fixes it: the
                # name may not be continued by a word character, and what follows
                # is either `{` directly or `(keys) {`.
                opened_here = re.search(r"(?<![\w])remember\s*(\([^)]*\))?\s*\{", line)
                opened_above = any(
                    re.search(r"(?<![\w])remember\s*(\([^)]*\))?\s*\{\s*$", l)
                    for l in lines[max(0, i - 3):i]
                )
                if opened_here or opened_above:
                    findings.append((path, i + 1, line.strip()))
    return findings
# TODO() and error() return Nothing, so a body ending in one is complete.
#
# Labeled returns (`return@use`, `return@launch`) are deliberately NOT counted: they
# return from the enclosing LAMBDA, not from the function, so a body whose only
# returns are labeled still has no way out. The case this check was written for was
# exactly that -- a body full of `return@use` and no return of its own.
TERMINATOR = re.compile(r"\breturn(?!@)\b|\bthrow\b|\bTODO\s*\(|\berror\s*\(")

#kotlin.* and java.lang.* are auto-imported, so these need no import line.
AUTO_IMPORTED = {
    # kotlin primitives and collections
    "Any", "Nothing", "Unit", "Boolean", "Byte", "Short", "Int", "Long", "Float",
    "Double", "Char", "String", "Number", "Array", "ByteArray", "CharArray",
    "ShortArray", "IntArray", "LongArray", "FloatArray", "DoubleArray",
    "BooleanArray", "List", "MutableList", "Map", "MutableMap", "Set",
    "MutableSet", "Entry", "MutableEntry", "Iterable", "MutableIterable",
    "Iterator", "MutableIterator", "Collection", "MutableCollection", "Sequence",
    "Comparable", "Comparator", "Pair", "Triple", "Result", "Lazy", "LazyThreadSafetyMode",
    "Enum", "Annotation", "Throwable", "Error", "Exception", "RuntimeException",
    "IllegalArgumentException", "IllegalStateException", "IndexOutOfBoundsException",
    "NullPointerException", "ClassCastException", "UnsupportedOperationException",
    "NoSuchElementException", "ArithmeticException", "NumberFormatException",
    "ConcurrentModificationException", "AssertionError", "OutOfMemoryError",
    # java.lang
    "Object", "System", "Math", "Thread", "Runnable", "Integer", "Character",
    "StringBuilder", "StringBuffer", "Class", "ClassLoader", "Process", "Package",
    "StackTraceElement", "Void", "Iterable", "AutoCloseable",
    # kotlin.collections.* / kotlin.text.*  (default imports, no import line)
    "ArrayList", "HashMap", "HashSet", "LinkedHashMap", "LinkedHashSet",
    "ArrayDeque", "TreeMap", "TreeSet", "Regex", "RegexOption", "MatchResult",
    "MatchGroup", "MatchGroupCollection", "GroupCollection",
    # kotlin.text.* is a default import too. `Charsets` was missing, so a
    # `toByteArray(Charsets.UTF_8)` read as an unresolved symbol.
    "Charsets", "Charset", "Appendable",
    # kotlin.jvm.* annotations and friends
    "Volatile", "OptIn", "Suppress", "JvmStatic", "JvmName", "JvmOverloads",
    "JvmField", "JvmDefault", "JvmSuppressWildcards", "JvmWildcard", "Transient",
    "Synchronized", "Throws", "Strictfp", "Override", "Deprecated", "Repeatable",
    "Retention", "Target", "MustBeDocumented", "InlineOnly", "PublishedApi",
}


def project_declarations() -> set[str]:
    """Every name declared anywhere under ROOT, at any depth."""
    names: set[str] = set()
    for dirpath, _, files in os.walk(ROOT):
        for fn_ in sorted(files):
            if not fn_.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn_)
            names |= declared_names_in(open(path, encoding="utf-8").read())
    return names


def file_imported_names(path: str) -> set[str]:
    """Names this file can see through its imports, plus same-package siblings."""
    text = strip_comments(open(path, encoding="utf-8").read())
    names: set[str] = set()
    for m in re.finditer(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?", text, re.M):
        fq, alias = m.group(1), m.group(2)
        if alias:
            names.add(alias)
        elif fq.endswith(".*"):
            names.add(fq[:-2])          # wildcard: remember the package stem
        else:
            names.add(fq.rsplit(".", 1)[-1])
    # Kotlin resolves same-package names with no import at all.
    pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
    if pkg:
        for dirpath, _, files in os.walk(ROOT):
            for fn_ in sorted(files):
                if not fn_.endswith(".kt"):
                    continue
                other = os.path.join(dirpath, fn_)
                ot = open(other, encoding="utf-8").read()
                op = re.search(r"^package\s+([\w.]+)", ot, re.M)
                if op and op.group(1) == pkg.group(1):
                    names |= declared_names_in(ot)
    return names


DECL_PAT = re.compile(
    r"^[ \t]*(?:@\w+(?:\([^)]*\))?[ \t]*)*(?:public |internal |private |protected |"
    r"open |data |sealed |value |actual |expect |inline |suspend |abstract |final |"
    r"const |lateinit |operator |infix |override )*"
    r"(?:(?:class|object|interface|enum class|typealias)\s+([A-Za-z_]\w*)"
    # `fun|val|var` may carry a receiver: `fun RowScope.Spacer1Cell()`.
    r"|(?:fun|val|var)\s+(?:[\w.]+[ \t]*\.[ \t]*)?([A-Za-z_]\w*))",
    re.M,
)


def declared_names_in(text: str) -> set[str]:
    """Every name declared at any depth: top level, nested, const val, enum entry."""
    text = strip_comments(text)
    names = {m.group(1) or m.group(2) for m in DECL_PAT.finditer(text)}
    # Enum entries are declared neither by `class` nor by `val`: they are bare
    # identifiers inside the enum body, and they resolve as names.
    for m in re.finditer(r"\benum class\s+\w+[^{]*\{", text):
        depth, i = 1, m.end()
        while i < len(text) and depth:
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
            i += 1
        body = text[m.end() : i - 1]
        # Only up to the first `;`, which ends the entry list.
        body = body.split(";")[0]
        for em in re.finditer(r"(?:^|[,{\s])([A-Z]\w*)", body):
            names.add(em.group(1))
    return names

# Compose members that exist ONLY as extensions on a layout scope. Calling one
# from a function that does not carry that receiver will not compile, and the
# error is reported at the call site with no hint that the real cause is the
# enclosing function's signature.
SCOPE_MEMBERS = {
    "align": ("BoxScope", "RowScope", "ColumnScope"),
    "matchParentSize": ("BoxScope",),
    "alignBy": ("RowScope", "ColumnScope"),
    "alignByBaseline": ("RowScope", "ColumnScope"),
}
# Calls whose trailing lambda runs with one of those scopes as its receiver.
SCOPE_OPENERS = {
    "Box": "BoxScope",
    "BoxWithConstraints": "BoxScope",
    "Row": "RowScope",
    "Column": "ColumnScope",
}
# `Modifier.weight()` is a Row/ColumnScope extension too, but it is used so widely
# and in such varied nesting that it has produced nothing but noise. Excluded.

FUN_DECL = re.compile(
    r"(?:@\w+(?:\([^)]*\))?[ \t]*)*(?:public |internal |private |protected |open |"
    r"inline |suspend |override )*fun\s+(?:(\w+)\s*\.\s*)?(?:(\w+)\s*\.\s*)?(\w+)\s*\("
)


def blank_keeping_lines(text: str) -> str:
    """Blank out string and comment bodies without removing any line.

    Replacing a block with "" collapses it and shifts every line number after it,
    which is worse than a false positive: it makes the reported location wrong. A
    21-line KDoc once moved a real finding from :165 to :144.
    """
    def repl(m: re.Match) -> str:
        return "\n" * m.group(0).count("\n")

    text = re.sub(r'"""(?:[^"]|"(?!""))*"""', repl, text, flags=re.S)
    text = re.sub(r"/\*.*?\*/", repl, text, flags=re.S)
    text = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', text)
    text = re.sub(r"//.*$", "", text, flags=re.M)
    return text


def check_scope_leak():
    """A layout-scope member called where that scope is not the receiver.

    Splitting TeamMapSection into separate layers lifted the bubble loop out of
    the BoxWithConstraints content lambda into a plain function. `Modifier.align()`
    resolved there before only because that lambda is a BoxScope; once lifted, the
    receiver was gone and it became `Unresolved reference 'align'`.

    This is invisible to check_undefined_symbols, which looks at capitalised type
    names, and to every other check here. It is also invisible in review: the call
    site is unchanged and correct-looking, and the defect is in the signature
    several lines above.

    A scope member is legal in exactly two places: inside a function declared as an
    extension on that scope, or inside the trailing lambda of a call that provides
    it. Both are tracked here.
    """
    findings = []
    use = re.compile(r"\.\s*(" + "|".join(SCOPE_MEMBERS) + r")\s*\(")
    for dirpath, _, files in os.walk(ROOT):
        for fn_ in sorted(files):
            if not fn_.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn_)
            raw = open(path, encoding="utf-8").read()
            text = blank_keeping_lines(raw)

            # A bracket stack, not a depth counter. `Box( modifier = Modifier
            # .height(x) )` closes a paren back to the depth the Box opened at, so a
            # depth-only stack evicts the Box the moment its own modifier chain
            # ends and reports its perfectly legal contents as out of scope.
            # Frame: (bracket, active scope, enclosing fun receiver,
            #         deferred scope, deferred fun receiver).
            stack: list[tuple[str, str | None, str | None, str | None, str | None]] = []
            pending_open: str | None = None
            pending_fun: str | None = None
            # A scope deferred across a call's argument list. `Box(modifier = ...)`
            # is NOT inside the Box's content -- a child's .align() is legal there
            # only because it is a different composable. The scope activates at the
            # trailing lambda, so `Box(...) {` keeps it on the `{`, while
            # `Box(modifier = Modifier.align(...))` never activates it at all.
            trailing: str | None = None
            opener_re = re.compile(
                r"(?<![\w.])(" + "|".join(SCOPE_OPENERS) + r")\s*[({]"
            )
            for lineno, line in enumerate(text.split("\n"), 1):
                events: list[tuple[int, str, object]] = []
                for m in FUN_DECL.finditer(line):
                    events.append((m.start(), "fun", m.group(2) or m.group(1)))
                for m in opener_re.finditer(line):
                    events.append((m.start(), "open", SCOPE_OPENERS[m.group(1)]))
                for m in use.finditer(line):
                    events.append((m.start(), "use", m.group(1)))
                for i, ch in enumerate(line):
                    if ch in "{([":
                        events.append((i, "push", ch))
                    elif ch in "})]":
                        events.append((i, "pop", ch))
                events.sort(key=lambda e: e[0])

                for _, kind, val in events:
                    if kind == "fun":
                        pending_fun = val
                    elif kind == "open":
                        pending_open = val
                    elif kind == "push":
                        # Scope binds to a lambda body, never to an argument list.
                        scope = (pending_open or trailing) if val == "{" else None
                        # A parameter list suspends both: the body `{` that follows
                        # it is what actually carries the function's receiver, just
                        # as the trailing lambda carries the layout scope.
                        stack.append((
                            val, scope,
                            pending_fun if val == "{" else None,
                            pending_open if val == "(" else None,
                            pending_fun if val == "(" else None,
                        ))
                        pending_open = None
                        pending_fun = None
                        trailing = None
                    elif kind == "pop":
                        if stack:
                            char, _, _, deferred, deferred_fun = stack.pop()
                            trailing = deferred if char == "(" else None
                            if char == "(":
                                pending_fun = deferred_fun
                    else:  # use
                        allowed = SCOPE_MEMBERS[val]
                        if any(sc in allowed for _, sc, _, _, _ in stack):
                            continue
                        recv = next(
                            (rv for _, _, rv, _, _ in reversed(stack) if rv), None
                        )
                        if recv in allowed:
                            continue
                        findings.append((path, lineno, val, recv))
            del raw, lineno
    return findings



# Emitted by build tooling into the applicationId package, so they appear in no
# .kt file. A source-only scanner has no way to see them; listing them here is
# honest about that limit rather than pretending the scan is complete.
GENERATED_SYMBOLS = {"BuildConfig", "R", "BuildConfigKt"}


def check_undefined_symbols():
    """A capitalised name that is used but declared nowhere and imported nowhere.

    This is the check that was missing when a slice-and-replace deleted
    `MapBubbleSpec` and `LegendDot` from TeamSections.kt while leaving every call
    site intact. imports.py stayed silent -- it only asks whether a *known* symbol
    has its import, never whether an unknown symbol exists at all -- and the file
    still looked fine in review. It was caught by reading the diff, which is luck,
    not process.

    Kotlin resolves a capitalised name four ways: it is declared in this file or
    elsewhere in the project, it comes in through an import, it is auto-imported
    from kotlin.* / java.lang.*, or build tooling generated it into the
    applicationId package (BuildConfig, R). Anything else is unresolved and will
    not compile.

    That fourth case is the honest limit of this check: it reads source, so a
    generated class is invisible to it and has to be named in GENERATED_SYMBOLS.

    Restricted to capitalised identifiers on purpose. Lowercase names are locals,
    parameters and properties, which cannot be resolved without a real scope model,
    and guessing there produced nothing but noise.
    """
    declared = project_declarations()
    findings = []
    # Used as a type, a constructor call, a generic argument, or the root of a
    # qualified reference: `Foo(`, `: Foo`, `<Foo`, `Foo<`, `Foo.`
    use = re.compile(r"(?<![\w.])([A-Z]\w*)")
    for dirpath, _, files in os.walk(ROOT):
        for fn_ in sorted(files):
            if not fn_.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn_)
            text = strip_comments(open(path, encoding="utf-8").read())
            # Triple-quoted blocks hold prompt prose ("Dr", "Dhaka", "FCPS") that
            # is capitalised exactly like a type. Strip before scanning or every
            # word of the VL system prompt reads as an unresolved symbol.
            text = re.sub(r'""".*?"""', '""', text, flags=re.S)
            # Drop string and char literals, and the import block itself.
            # mask_literals keeps `${...}` as code -- see its docstring for the
            # nested-literal false positive the previous regex produced.
            text = mask_literals(text)
            # Trailing comments too: `22.65f, 89.78f,  // Bagerhat` reads the
            # district name as a type. strip_comments only drops whole-line ones.
            # Safe here because string literals are already gone, so a "//" inside
            # a URL cannot be mistaken for a comment.
            text = re.sub(r"//.*$", "", text, flags=re.M)
            text = re.sub(r"^import\s+[\w.]+.*$", "", text, flags=re.M)
            visible = file_imported_names(path)
            seen: dict[str, int] = {}
            for i, line in enumerate(text.split("\n"), 1):
                for m in use.finditer(line):
                    name = m.group(1)
                    if len(name) == 1:
                        continue        # a generic parameter: T, E, R, K, V
                    if (
                        name in declared
                        or name in visible
                        or name in AUTO_IMPORTED
                        or name in GENERATED_SYMBOLS
                    ):
                        continue
                    # Enum entries and nested references resolve through their parent.
                    seen.setdefault(name, i)
            for name, line in sorted(seen.items()):
                findings.append((path, line, name))
    return findings



def match_paren(text: str, open_idx: int) -> int:
    """Index of the `)` matching the `(` at open_idx."""
    depth = 0
    for i in range(open_idx, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return len(text)



# The Material icon packs, and the import path each chain needs.
ICON_FAMILIES = {
    "Filled": "androidx.compose.material.icons.filled",
    "Outlined": "androidx.compose.material.icons.outlined",
    "Rounded": "androidx.compose.material.icons.rounded",
    "Sharp": "androidx.compose.material.icons.sharp",
    "TwoTone": "androidx.compose.material.icons.twotone",
    "AutoMirrored.Filled": "androidx.compose.material.icons.automirrored.filled",
    "AutoMirrored.Outlined": "androidx.compose.material.icons.automirrored.outlined",
}


def check_icon_imports():
    """`Icons.Filled.Foo` with no `import ...filled.Foo`.

    check_undefined_symbols cannot see these. Its use pattern requires the
    character before a capitalised name to not be a dot, so in
    `Icons.AutoMirrored.Filled.KeyboardArrowRight` it captures only `Icons` --
    which is always imported -- and the four segments after it go unexamined.
    That blind spot shipped a missing icon import: the nested form reads as a
    qualified member access, and every piece of it is capitalised, so nothing
    looks wrong.

    The rule here is exact rather than heuristic: an icon is an extension
    property in a per-family package, so `Icons.Rounded.X` does not compile
    unless `androidx.compose.material.icons.rounded.X` is imported (or that
    package is wildcarded). There is no other way for the reference to resolve.
    """
    findings = []
    for dirpath, _, files in os.walk(ROOT):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            code = mask_literals(strip_comments(open(path, encoding="utf-8").read()))
            imports = set()
            for m in re.finditer(r"^import\s+([\w.*]+)", code, re.M):
                imports.add(m.group(1))
            for m in re.finditer(
                r"(?<![\w.])Icons\.(" + "|".join(
                    k.replace(".", "\\.") for k in ICON_FAMILIES
                ) + r")\.(\w+)", code
            ):
                family, icon = m.group(1), m.group(2)
                package = ICON_FAMILIES[family]
                if f"{package}.{icon}" in imports or f"{package}.*" in imports:
                    continue
                line = code[: m.start()].count("\n") + 1
                findings.append((path, line, f"Icons.{family}.{icon}", f"{package}.{icon}"))
    return findings


def check_missing_return():
    """Block-bodied function with a non-Unit return type and no return/throw.

    Kotlin only infers a result for an EXPRESSION body (`fun f(): T = ...`). A block
    body (`fun f(): T { ... }`) has to return explicitly, so ending one on a bare
    expression is a compile error -- "Missing return statement".

    It is an easy mistake to introduce by extraction: lifting a lambda body into a
    named function converts an expression body, where the last expression WAS the
    result, into a block body, where it is only a discarded value. That is exactly
    how this one reached the user's compiler.

    Two false-positive sources were designed out, both found by running it:
      * Anchoring on `): Type {` alone also matched CLASS declarations carrying a
        supertype (`class X(...) : ViewModelProvider.Factory {`), which are not
        functions. The match now starts at `fun`.
      * Letting the type span newlines made a bodyless abstract method
        (`abstract fun rsmDao(): RsmDao`) run on into the next `companion object {`.
        The type may not cross a line break now; only the separator before the
        colon may, so multi-line signatures still match.
    """
    findings = []
    # Optional generic list, so `fun <T> foo(): T {` is covered too.
    decl = re.compile(r"\bfun\b(\s*<[^>]*>)?\s+\w+\s*\(")
    for dirpath, _, files in os.walk(ROOT):
        for fn_ in sorted(files):
            if not fn_.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn_)
            text = strip_comments(open(path, encoding="utf-8").read())
            for m in decl.finditer(text):
                close = match_paren(text, m.end() - 1)
                mm = re.match(
                    r"\s*:\s*([A-Za-z_][ \t\w.<>?\[\]\->]*?)[ \t]*\n?[ \t]*\{",
                    text[close + 1 :],
                )
                if not mm:
                    continue
                ret = mm.group(1).strip()
                if ret in ("Unit", ""):
                    continue
                open_brace = close + 1 + mm.end() - 1
                depth = 0
                body_end = len(text)
                for i in range(open_brace, len(text)):
                    if text[i] == "{":
                        depth += 1
                    elif text[i] == "}":
                        depth -= 1
                        if depth == 0:
                            body_end = i
                            break
                body = text[open_brace + 1 : body_end]
                if TERMINATOR.search(body):
                    continue
                line = text[: m.start()].count("\n") + 1
                findings.append((path, line, ret))
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

    stranded = check_missing_project_imports()
    if stranded:
        findings += len(stranded)
        print("MISSING IMPORT - declared in the project, used from another package")
        for path, line, name, pkgs in stranded:
            print(f"  {path}:{line}  {name}  ->  import {min(pkgs, key=len)}.{name}")
        print()

    tokens = check_theme_tokens()
    if tokens:
        findings += len(tokens)
        print("UNKNOWN THEME TOKEN - no such member on the theme object")
        for path, line, obj, member, available in tokens:
            near = [a for a in available if a[:3].lower() == member[:3].lower()]
            hint = f"  did you mean: {', '.join(near)}" if near else ""
            print(f"  {path}:{line}  {obj}.{member} is not declared on {obj}{hint}")
        print()

    kdoc = check_orphaned_kdoc()
    if kdoc:
        findings += len(kdoc)
        print("ORPHANED KDoc - a doc block whose declaration was pushed away")
        for path, line, following in kdoc:
            print(f"  {path}:{line}  followed by another doc block: {following[:60]!r}")
        print()

    comp = check_composable_in_remember()
    if comp:
        findings += len(comp)
        print("@Composable CALL INSIDE remember {}")
        for path, line, text in comp:
            print(f"  {path}:{line}  {text}")
        print()

    leak = check_scope_leak()
    if leak:
        findings += len(leak)
        print("SCOPE LEAK - layout-scope member called outside its scope")
        for path, line, name, recv in leak:
            got = recv or "no receiver"
            print(f"  {path}:{line}  .{name}() needs one of "
                  f"{SCOPE_MEMBERS[name]}, enclosing fun has {got}")
        print()

    icons = check_icon_imports()
    if icons:
        findings += len(icons)
        print("MISSING ICON IMPORT")
        for path, line, ref, imp in icons:
            print(f"  {path}:{line}  {ref}  ->  needs: import {imp}")
        print()

    undef = check_undefined_symbols()
    if undef:
        findings += len(undef)
        print("UNRESOLVED SYMBOL - used but declared nowhere and imported nowhere")
        for path, line, name in undef:
            print(f"  {path}:{line}  {name}")
        print()

    noret = check_missing_return()
    if noret:
        findings += len(noret)
        print("MISSING RETURN IN BLOCK-BODIED FUNCTION")
        for path, line, ret in noret:
            print(f"  {path}:{line}  returns {ret} but the body has no return/throw")
            print("       a block body needs `return`; only `= expr` bodies infer it")
        print()

    if findings:
        print(f"imports: {findings} finding(s)")
        return 1

    print("imports: no findings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
