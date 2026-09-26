#!/usr/bin/env python3
"""
Cross-file references that the other checks cannot see.

Why this exists
---------------
`imports.py` resolves *symbols* - does `MlxD` have an import. It cannot tell whether
`MlxD.Space7` is a member of `MlxD`, whether `PillTone.VioletSolid` is an entry in that
enum, or whether a project function is called with the wrong number of arguments. All
three have been real defects in this repository's history:

  * `PillTone.VioletSolid` was written from memory while adding the audit drawer. The
    enum has `Violet` and `Solid`-suffixed entries for two other families and no such
    member. Nothing flagged it; it was caught by reading the enum.
  * `ItemRow(index = i)` passed an argument the function does not take.
    `audit.py` catches that one, but only for *named* arguments.
  * `Mlx.DangerSoftBorder`-style mistakes - a plausible token that does not exist - are
    invisible to every check here.

What it checks
--------------
Two spellings, against every type this project declares (241 of them, 91 files):

  * `Type.member` - the member must be declared on that type, on a nested type of it, in
    its primary constructor, in its companion object, as an extension on it, or be one of
    the members every Kotlin value has. 1931 references resolve.
  * `param.member`, where `param` is a parameter of a project type - the spelling the
    model layer uses (`row.dosageForm` in a DAO mapper). 541 references resolve.

Both bounds include the declaration forms a brace-only parser misses: a data class whose
fields live in the constructor and which has no body at all, an expression-bodied
function, an enum whose entries are comma-separated on one line.

What it deliberately does not check
-----------------------------------
**Types.** Nothing here type-checks.

  * A member reached through a *variable* (`drawer.slices`) is not resolved - only the
    `Type.member` spelling is, and `param.member` for a parameter declared with the type.
    An ambiguous short name is therefore whichever declaration the parser saw; nested
    types are indexed under their own name, so `PdfWriter.Style` and a top-level `Style`
    would collide.
  * A call with the wrong *number* of arguments is not caught (that is `audit.py`'s, and
    only for named arguments).
  * Enum entries are matched by name, not by arity: `PillTone.Violet(Color.Black)`
    passes. Entries are also members of the enclosing type, so an *entry* is both a
    declaration and a use.
  * The inside of every string literal - interpolations included - is blanked before
    matching, so a `Type.member` written in `${...}` is invisible. That was necessary:
    a parameter named `p` and a SQL alias `p.doctor_name` in a `@Query` are the same
    text to a regex, eighteen times over in `Daos.kt`.
  * Extension members are only indexed for receivers this project declares; the first
    version invented a type for every external receiver, which turned one
    `fun Modifier.something` into 400 findings on `Modifier.padding`.

It reports a clean run only after parsing something: 241 types and 1931 + 541 references, and it exits 1 saying so
if the discovery pattern stops matching, because a check that quietly matches nothing is
the one failure mode this project keeps re-learning. Every claim above about coverage was
established by injecting the defect and reading the exit code, never by reading the
pattern - see the `refcheck.py` faults in `faulttest.py`.
"""

from __future__ import annotations

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SRC = os.path.join(ROOT, "android/app/src/main/java/com/medlenx/lab")

sys.path.insert(0, HERE)
import daocalls as dc  # noqa: E402

# Members that exist on every Kotlin type, or that this net should not judge.
COMMON = {
    "equals", "hashCode", "toString", "copy", "copy$default", "component1", "compareTo",
    "name", "ordinal", "entries", "values", "valueOf", "javaClass", "let", "run", "apply",
    "also", "takeIf", "takeUnless", "with", "require", "check", "plus", "minus", "times",
    "div", "rem", "toDouble", "toFloat", "toInt", "toLong", "toString", "isBlank",
    "isNotBlank", "isNotEmpty", "isEmpty", "trim", "split", "replace", "lowercase",
    "uppercase", "orEmpty", "ifBlank", "ifEmpty", "getOrNull", "getOrElse", "first",
    "firstOrNull", "last", "lastOrNull", "map", "filter", "forEach", "forEachIndexed",
    "any", "all", "none", "count", "size", "sumOf", "sortedBy", "sortedByDescending",
    "sortedWith", "groupBy", "associate", "associateBy", "contains", "indexOf", "joinToString",
    "distinct", "distinctBy", "flatten", "flatMap", "maxByOrNull", "minByOrNull", "filterNot",
    "mapNotNull", "mapIndexed", "take", "drop", "reversed", "indices", "keys", "values",
    "toList", "toSet", "toMap", "toMutableList", "add", "addAll", "remove", "clear",
    "putAll", "copyOf", "format", "append", "buildString", "buildList", "buildMap",
    # Generated, not declared: kotlinx.serialization writes `serializer()` onto every
    # @Serializable class, and every class gets a `Companion`.
    "serializer", "Companion",
}

# Project types whose members are reached dynamically, or that come from a dependency and
# are only re-declared here as typealiases.
SKIP_TYPES = {
    "R", "BuildConfig", "MlxTouchTarget",
}

TYPE_DECL = re.compile(
    r"(?P<mods>(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|"
    r"data\s+|value\s+|enum\s+|annotation\s+|inner\s+|expect\s+|actual\s+|external\s+|"
    r"final\s+|companion\s+)*)"
    r"(?:class|interface|object)\s+(?P<name>\w+)"
)

# `fun Receiver.name(` and `val Receiver.name` - an extension is a member of the type it
# extends, and this project reaches several that way (`MedexProduct.mrpLabel` lives in
# ExportDocuments.kt, `MedexProduct.toEntity` in AssetCatalogue.kt). Without these the
# net reports the whole codebase as broken.
EXT_FUN = re.compile(
    r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w<>,.?\[\]\s]+\.)?(?P<recv>[A-Z]\w*)\.(?P<name>\w+)\s*\("
)
EXT_VAL = re.compile(
    r"\b(?:val|var)\s+(?P<recv>[A-Z]\w*)\.(?P<name>\w+)"
)
MEMBER_VAL = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:(?:public|internal|private|protected|override|open|const|lateinit|abstract|"
    r"final|actual|expect)\s+)*"
    r"(?:val|var)\s+(?P<name>\w+)",
    re.M,
)
MEMBER_FUN = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:public|internal|private|protected|override|open|abstract|final|suspend|inline|"
    r"actual|expect|operator|infix|tailrec|external|const|fun)+\s+"
    r"(?:<[^>]*>\s*)?(?:[\w<>,.?\[\]\s]+\.)?(?P<name>\w+)\s*\(",
    re.M,
)
# An entry is an identifier starting a comma-separated part of the enum body. Both
# layouts occur here - one per line (`Emerald(Mlx.Ok50, ...)`) and all on one
# (`enum class ConfidenceBand { High, AiGuess, ManualFlag }`) - so neither a line
# anchor nor a separator-prefix match finds both. Splitting on top-level commas does.
ENUM_ENTRY = re.compile(r"^\s*(?P<name>[A-Z]\w*)\s*(?:\(|$)")
FUN_DECL = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public|internal|private|protected|override|open|"
    r"abstract|final|suspend|inline|operator|infix|tailrec|external)+[\s\w<>?,.\[\]\s]*?"
    r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w<>,.?\[\]\s]+\.)?(?P<name>\w+)\s*\(",
    re.M,
)


# A function signature's parameter list, and one `name: Type` inside it.
FUN_SIG = re.compile(r"\bfun\s+(?:[A-Za-z_]\w*\.)?(?P<name>[A-Za-z_]\w*)\s*(?P<generics><[^>(]*>)?\s*\(")
# `(?:^|[(,])` and not `(?:^|,)`: the parameter list is sliced from its opening
# parenthesis, so the first parameter is preceded by `(`, not by a comma. Anchoring on
# the comma alone made the whole pass silently vacuous - it reported 76 references, every
# one of them from a function whose second parameter happened to be a project type, and
# missed `fun lineOf(row: ScannedMedicineEntity)` entirely.
PARAM = re.compile(
    r"(?:^|[(,])\s*(?:vararg\s+)?(?P<name>[a-z_]\w*)\s*:\s*(?P<type>[A-Z]\w*)\s*[?,)=]"
)

# A name bound by something other than a parameter: a local, a lambda arrow, a loop.
def _shadowed(body: str, name: str) -> bool:
    return bool(
        re.search(r"\b(?:val|var)\s+" + name + r"\b", body)
        or re.search(r"\bfor\s*\(\s*" + name + r"\b", body)
        or re.search(r"(?:\(|,)\s*" + name + r"\s*->", body)
        or re.search(r"\btry\s*\(\s*" + name + r"\b", body)
    )


def _mask_strings(text: str) -> str:
    """
    Blank the inside of every string and char literal, keeping offsets exact.

    Deliberately *not* `imports.mask_literals`: that masker rewrites a literal to `""`,
    and a shorter string moves everything after it, which glued `entry.type` to the next
    `entry` and produced four findings on code that has none. This one replaces the
    literal's characters with spaces one for one and keeps the quotes and newlines, so a
    match's offset and line are the source's.

    Interpolations are blanked along with the surrounding text. A `Type.member` written
    inside `${...}` is therefore invisible to this check - a real narrowing, stated here
    because a silently narrowed checker is how this file's predecessor failed.
    """
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c not in "\"'":
            i += 1
            continue
        raw = text.startswith('"""', i)
        j = i + (3 if raw else 1)
        depth = 0
        while j < n:
            if not raw and text[j] == "\\":
                out[j] = " "
                if j + 1 < n:
                    out[j + 1] = " "
                j += 2
                continue
            if raw and text.startswith('"""', j):
                j += 3
                break
            ch = text[j]
            if ch == "$" and j + 1 < n and text[j + 1] == "{":
                depth += 1
                j += 2
                continue
            if ch == "}" and depth > 0:
                depth -= 1
                j += 1
                continue
            if depth == 0 and not raw and ch == c:
                j += 1
                break
            if ch == "\n" and not raw:
                # A non-raw Kotlin string cannot span lines, so reaching a newline means
                # this quote was never an opening one. Abandon the literal entirely rather
                # than continuing to the next quote in the file: that is how the first
                # version of this masker blanked 400 characters of real code, 28 spans in
                # and including `companion object`, and reported eight companion members
                # as undeclared. Leave the text as it was and resume after the quote.
                return "".join(out[:i]) + text[i:]
            if ch != "\n":
                out[j] = " "
            j += 1
        i = j
    return "".join(out)


def _split_top(text: str) -> list[str]:
    """Split on commas that are not inside brackets."""
    parts, buf, depth = [], [], 0
    for ch in text:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
        else:
            buf.append(ch)
    parts.append("".join(buf))
    return parts


def _enum_entries(inner: str) -> set[str]:
    """Enum entry names from the body, in either layout, ignoring argument lists."""
    entries = set()
    for part in _split_top(inner):
        m = ENUM_ENTRY.match(part.strip() + " ")
        if m:
            entries.add(m.group("name"))
    return entries


def _enum_body(text: str, open_brace: int) -> str:
    """Text of the enum body, up to its closing brace (or the first `;`)."""
    depth = 0
    i = open_brace
    while i < len(text):
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[open_brace + 1: i]
        elif c == ";" and depth == 1:
            return text[open_brace + 1: i]
        i += 1
    return text[open_brace + 1:]


def _body_span(text: str, after: int, limit: int) -> tuple[int, int] | None:
    """
    Brace-matched body span for a declaration ending at `after`, or None.

    `limit` only decides whether this declaration *has* a body: if the next
    declaration's start comes before any `{`, then this one is expression-bodied or
    abstract and its body is empty. It must not bound the matching itself - a nested
    declaration (`sealed class VlOutcome { data class Success(...) }`) puts a next
    declaration *inside* this body, and clamping there returned the header text as the
    whole body, which made every sealed-class case read as undeclared.
    """
    open_brace = text.find("{", after)
    if open_brace == -1 or open_brace >= limit:
        return None
    depth = 0
    j = open_brace
    while j < len(text):
        c = text[j]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return open_brace + 1, j
        j += 1
    return open_brace + 1, len(text)


CTOR_PROP = re.compile(
    r"(?:^|[,(])\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*"
    r"(?:override\s+|internal\s+|private\s+)*(?P<kw>val|var)\s+(?P<pname>\w+)"
)


def _ctor_props(text: str, m: re.Match) -> set[str]:
    """
    Properties declared by a primary constructor: `data class Substitution(val own... )`.

    These have to be read from the parenthesis, not the braces - most of the model layer
    is a data class with a constructor and no body at all, and a brace-only parser reports
    every one of those fields as undeclared. A body brace that comes *before* the
    constructor paren means the paren belonged to something else, and a `:` before it
    means the next paren is a supertype call (`objects NoKey : VlOutcome()`), not a
    property list.
    """
    open_paren = text.find("(", m.end())
    if open_paren == -1:
        return set()
    body_brace = text.find("{", m.end())
    if body_brace != -1 and body_brace < open_paren:
        return set()
    between = text[m.end():open_paren]
    if ":" in between or "=" in between or "fun" in between:
        return set()
    close = dc.match_paren(text, open_paren)
    if close == -1:
        return set()
    return {x.group("pname") for x in CTOR_PROP.finditer(text[open_paren + 1:close])}


# A line that begins a declaration, used to bound an expression body.
DECL_LINE = re.compile(
    r"^(?:@|fun\b|private\b|internal\b|public\b|protected\b|sealed\b|enum\b|data\b|"
    r"object\b|class\b|interface\b|val\b|var\b|typealias\b|import\b|package\b|\}\s*$)"
)


def _indent_of(text: str, pos: int) -> int:
    """Number of spaces before the token at [pos]; its line must be indentation only."""
    line_start = text.rfind("\n", 0, pos) + 1
    return pos - line_start


def _expr_body(text: str, eq: int, indent: int) -> str:
    """
    Body of an expression-bodied function, from its `=` to the next declaration.

    `fun lineOf(row: ScannedMedicineEntity): RxAuditLine = RxAuditLine(...)` is how both
    of this project's audit mappers are written, and a brace-matching body finder sees no
    `{` at all - it then grabs whatever brace comes next in the file, so the function's
    own member reads were never scanned. Continuation lines here are indented, so the end
    is the next line that starts a declaration at column 0.
    """
    lines = text[eq:].split("\n")
    out = [lines[0]]
    for line in lines[1:]:
        line_indent = len(line) - len(line.lstrip())
        # The bound is a declaration at or above this function's own indentation, not at
        # column 0: most of these functions are members of an `object`, so their
        # successors are indented four spaces and a column-0 test never fires - the
        # expression body then swallowed the next two functions, and the pass reported
        # nothing at all for the two mappers this check was written for.
        if line_indent <= indent and DECL_LINE.match(line.strip()):
            break
        out.append(line)
    return "\n".join(out)


def parse_types(text: str, known: set[str]) -> dict[str, set[str]]:
    """
    type name -> declared member names (plus enum entries and nested types).

    [known] is every type this project declares anywhere. It has to come in from
    outside: extensions are only accepted for receivers that are project types, and
    `ExportDocuments.kt` is walked before `Catalogue.kt`, so deciding per file meant
    `fun MedexProduct.mrpLabel()` was dropped for being declared "too early".
    """
    out: dict[str, set[str]] = {}
    decls = list(TYPE_DECL.finditer(text))
    for i, m in enumerate(decls):
        name = m.group("name")
        # The *next declaration* is not necessarily outside this one - a sealed class's
        # cases are nested inside it. So the body is found by matching braces, and the
        # next declaration only bounds it when it starts before any `{` does.
        limit = decls[i + 1].start() if i + 1 < len(decls) else len(text)
        props = _ctor_props(text, m)
        span = _body_span(text, m.end(), limit)
        if span is None:
            # No body - an expression-bodied or abstract declaration, or (far more often
            # here) a data class whose fields all live in the primary constructor.
            out.setdefault(name, set()).update(props)
            continue
        lo, hi = span
        body = text[lo:hi]

        # A nested type's members belong to the nested type, not to this one. Blanking
        # their bodies first keeps `VlOutcome` from advertising `Success.result`.
        scrub = list(body)
        for nested in TYPE_DECL.finditer(body):
            inner = _body_span(body, nested.end(), len(body))
            if inner is not None:
                for k in range(inner[0], inner[1]):
                    if scrub[k] != "\n":
                        scrub[k] = " "
        prologue = "".join(scrub)
        # A `companion object` has no name, so TYPE_DECL does not match it and it was not
        # scrubbed above - but its members *are* reached through the enclosing type
        # (`Destination.bottomBar`). Put those spans back.
        for c in re.finditer(r"companion\s+object\b", body):
            span = _body_span(body, c.end(), len(body))
            if span is not None:
                prologue += "\n" + body[span[0]:span[1]]
        # The nested declarations' own *names* are members of this type.
        prologue += "\n" + "\n".join(x.group("name") for x in TYPE_DECL.finditer(body))

        members = {x.group("name") for x in MEMBER_VAL.finditer(prologue)}
        members |= {x.group("name") for x in MEMBER_FUN.finditer(prologue)}
        members |= {x.group("name") for x in TYPE_DECL.finditer(body)}
        members |= props
        if "enum" in (m.group("mods") or ""):
            entries = _enum_entries(body)
            entries.discard(name)
            members |= entries
        out.setdefault(name, set()).update(members)

    # Extensions: `fun MedexProduct.mrpLabel()` is a member of MedexProduct.
    #
    # Only when the receiver is a type *this project declares*. `out.setdefault` here
    # invented a type for every external receiver - one `fun Modifier.something` was
    # enough to make `Modifier` a project type, after which all 400-odd
    # `Modifier.padding` / `.weight` / `.fillMaxWidth` references in the codebase were
    # reported as references to members that do not exist.
    for pattern in (EXT_FUN, EXT_VAL):
        for m in pattern.finditer(text):
            if m.group("recv") in known:
                out.setdefault(m.group("recv"), set()).add(m.group("name"))
    return out


def _is_enum(text: str, pos: int) -> bool:
    line_start = text.rfind("\n", 0, pos) + 1
    return "enum" in text[line_start:pos]


def main() -> int:
    files: dict[str, str] = {}
    for dirpath, _d, names in os.walk(SRC):
        for n in names:
            if n.endswith(".kt"):
                p = os.path.join(dirpath, n)
                with open(p, encoding="utf-8") as fh:
                    # Comments first, then the insides of string literals. The masking
                    # matters more here than in the other checks: `@Query("... WHERE
                    # p.doctor_name ...")` is Kotlin, not SQL, to a regex, and a table
                    # alias colliding with a parameter name reads as a field access on
                    # the entity - eighteen of them in `Daos.kt` alone.
                    files[p] = _mask_strings(dc.strip_comments(fh.read()))

    if not files:
        print(f"refcheck: no sources under {SRC}", file=sys.stderr)
        return 1

    # Phase 1: every type name in the project, so extensions can be resolved against
    # the whole set rather than against whichever files happen to have been read.
    known: set[str] = set()
    for text in files.values():
        known |= {m.group("name") for m in TYPE_DECL.finditer(text)}

    if not known:
        print("refcheck: parsed 0 type declarations - the discovery pattern no longer "
              "matches this codebase, so this run proved nothing", file=sys.stderr)
        return 1

    # Phase 2: members.
    members: dict[str, set[str]] = {}
    for text in files.values():
        for name, ms in parse_types(text, known).items():
            members.setdefault(name, set()).update(ms)

    problems: list[str] = []
    checked = 0

    for path, text in files.items():
        rel = os.path.relpath(path, ROOT)
        # 1. Type.member
        for m in re.finditer(r"(?<![\w.])(?P<type>[A-Z]\w+)\.(?P<member>[A-Za-z_]\w*)", text):
            t, mem = m.group("type"), m.group("member")
            if t not in members or t in SKIP_TYPES:
                continue
            if mem in COMMON:
                continue
            checked += 1
            if mem not in members[t]:
                line = text[: m.start()].count("\n") + 1
                problems.append(
                    f"{rel}:{line}  {t}.{mem}  - {t} declares no member '{mem}'"
                )

    # 2. `param.member` for a parameter declared with a project type. This is the
    #    spelling the Type.member rule above cannot see, and it is where the audit-drawer
    #    work actually lived: sixteen `row.<field>` reads on a `ScannedMedicineEntity`.
    #    A name bound by a local, a lambda arrow or a loop is skipped rather than guessed
    #    at - a check that resolves a shadowed name reports nonsense on real code.
    typed = 0
    for path, text in files.items():
        rel = os.path.relpath(path, ROOT)
        for sig in FUN_SIG.finditer(text):
            open_paren = text.index("(", sig.end() - 1)
            depth, j = 0, open_paren
            while j < len(text):
                if text[j] == "(":
                    depth += 1
                elif text[j] == ")":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            # Inclusive of the `)`: PARAM needs the delimiter after the type, and a
            # slice that stops one character short silently drops the *last* parameter of
            # every function - which is the only parameter of every `lineOf(...)`.
            params = text[open_paren:j + 1]
            # Block body or expression body? Whichever of `{` / `=` comes first decides.
            # Asking the brace matcher first is not enough: for an expression body it
            # finds some later declaration's brace and hands back a body that is not this
            # function's, and the pass then reports nothing at all - the exact way this
            # checker was vacuous for the file it was written to audit.
            tail = text[j:]
            brace_at, eq_at = tail.find("{"), tail.find("=")
            if eq_at != -1 and (brace_at == -1 or eq_at < brace_at):
                body = _expr_body(text, j + eq_at, _indent_of(text, sig.start()))
            else:
                span = _body_span(text, j, len(text))
                if span is None:
                    continue
                body = text[span[0]:span[1]]
            for pm in PARAM.finditer(params):
                pname, ptype = pm.group("name"), pm.group("type")
                if ptype not in members or ptype in SKIP_TYPES:
                    continue
                if pname in COMMON or _shadowed(body, pname):
                    continue
                for use in re.finditer(r"\b" + pname + r"\.(?P<member>\w+)", body):
                    mem = use.group("member")
                    if mem in COMMON:
                        continue
                    typed += 1
                    if mem not in members[ptype]:
                        line = text[: span[0] + use.start()].count("\n") + 1
                        problems.append(
                            f"{rel}:{line}  {pname}.{mem}  - {ptype} declares no "
                            f"member '{mem}'"
                        )

    print("=" * 72)
    print("CROSS-FILE REFERENCES")
    print("=" * 72)
    print(f"  {len(members)} project type(s) indexed, {len(files)} file(s) scanned")
    print(f"  {checked} Type.member reference(s) resolved")
    print(f"  {typed} typed-parameter member reference(s) resolved")
    print()

    if problems:
        print(f"  {len(problems)} PROBLEM(S):")
        for p in problems:
            print("   ", p)
    else:
        print("  no problems found - every Type.member reference resolves")
    print()
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
