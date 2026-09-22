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
5. Missing return in a block-bodied function - `fun f(): T { ... }` must return
   explicitly; only `= expr` bodies infer their result.

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



def check_undefined_symbols():
    """A capitalised name that is used but declared nowhere and imported nowhere.

    This is the check that was missing when a slice-and-replace deleted
    `MapBubbleSpec` and `LegendDot` from TeamSections.kt while leaving every call
    site intact. imports.py stayed silent -- it only asks whether a *known* symbol
    has its import, never whether an unknown symbol exists at all -- and the file
    still looked fine in review. It was caught by reading the diff, which is luck,
    not process.

    Kotlin resolves a capitalised name three ways: it is declared in this file or
    elsewhere in the project, it comes in through an import, or it is auto-imported
    from kotlin.* / java.lang.*. Anything else is unresolved and will not compile.

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
            text = re.sub(r'"(?:[^"\\]|\\.)*"', '""', text)
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
                    if name in declared or name in visible or name in AUTO_IMPORTED:
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
