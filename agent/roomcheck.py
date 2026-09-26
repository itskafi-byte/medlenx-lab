#!/usr/bin/env python3
"""
Room SQL validator.

Why this exists
---------------
The user compiles locally, not here, so a single mistyped column name in a @Query
costs a full round trip: they hit a KSP error, report it, I guess at it. Room
resolves every column in a @Query against the entity at COMPILE time, so the
check is decidable without a compiler - the names in the SQL either exist on the
entity or they do not.

Two things make this non-obvious and are the reason the naive version of this
script produced 157 bogus findings:
  * @Entity(...) args can contain nested parens (indices = [Index("x")]), so the
    annotation block needs balanced-paren matching, not a regex.
  * Columns are declared via @ColumnInfo(name = "snake_case") over camelCase
    Kotlin fields, so the SQL column set is NOT the Kotlin field set.

Checks
------
  1. Every table referenced in a @Query is a real @Entity table.
  2. Every column referenced in a @Query exists on a table that query touches.
     A column written with a table alias (`d.territory`) is resolved against the
     table that alias names, not against every table the query touches. Without
     that, a qualifier pointing at the wrong table passes whenever any other
     table in the query happens to own the column -- which is how `d.territory`
     reached a `doctors` table that had no such column.
  3. A @Query whose return type is a projection data class selects every field
     that class declares (Room binds by name and fails on a missing column).

Usage
-----
    python3 agent/roomcheck.py
"""

import os
import re
import sys

# Anchored on this script's own location: absolute paths keep the checks working
# from any directory. The relative paths this used to hold meant the tool silently
# read nothing when run from anywhere but the repo root.
_REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENTITIES = os.path.join(
    _REPO, "android/app/src/main/java/com/medlenx/lab/data/local/Entities.kt"
)
DAOS = os.path.join(
    _REPO, "android/app/src/main/java/com/medlenx/lab/data/local/Daos.kt"
)

SQL_STOP = {
    "select", "from", "where", "and", "or", "not", "null", "is", "in", "as", "on",
    "join", "left", "right", "inner", "outer", "cross", "natural", "using",
    "group", "by", "order", "limit", "offset", "desc", "asc", "count", "sum",
    "avg", "max", "min", "distinct", "case", "when", "then", "else", "end",
    "like", "glob", "between", "cast", "coalesce", "ifnull", "nullif", "values",
    "set", "into", "update", "delete", "insert", "exists", "union", "all",
    "having", "round", "abs", "length", "lower", "upper", "trim", "replace",
    "substr", "instr", "group_concat", "total", "strftime", "date", "datetime",
    "julianday", "row_number", "over", "partition", "with", "recursive",
    "rowid", "oid", "collate", "nocase", "escape", "each", "true", "false",
    "indexed", "conflict", "replace", "ignore", "fail", "abort", "rollback",
    "primary", "key", "foreign", "references", "check", "default", "unique",
    "constraint", "table", "if", "autoincrement",
}


def strip_comments(src: str) -> str:
    src = re.sub(r"/\*.*?\*/", " ", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def match_paren(src: str, open_idx: int) -> int:
    """Index of the ')' matching the '(' at open_idx."""
    depth = 0
    for i in range(open_idx, len(src)):
        if src[i] == "(":
            depth += 1
        elif src[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return len(src) - 1


def class_body(src: str, decl_idx: int) -> tuple[str, str] | None:
    """From a `class X(` declaration index, return (name, body)."""
    m = re.compile(r"(?:data\s+)?class\s+(\w+)\s*\(").match(src, decl_idx)
    if not m:
        return None
    open_idx = m.end() - 1
    return m.group(1), src[open_idx + 1 : match_paren(src, open_idx)]


def fields_of(body: str) -> set[str]:
    """Column names declared in a data class body, honouring @ColumnInfo."""
    cols: set[str] = set()
    for line in body.split("\n"):
        if "@Ignore" in line:
            continue
        cm = re.search(r'@ColumnInfo\s*\(\s*name\s*=\s*"([^"]+)"', line)
        vm = re.search(r"\bval\s+(\w+)\s*:", line)
        if vm:
            cols.add(cm.group(1) if cm else vm.group(1))
    return cols


def parse_entities(src: str) -> tuple[dict[str, set[str]], dict[str, str]]:
    """(table -> columns, table -> entity class)."""
    tables: dict[str, set[str]] = {}
    owners: dict[str, str] = {}
    for m in re.finditer(r"@Entity\b", src):
        paren = src.find("(", m.end())
        if paren == -1:
            continue
        args = src[paren + 1 : match_paren(src, paren)]
        tm = re.search(r'tableName\s*=\s*"([^"]+)"', args)
        decl = re.compile(r"(?:data\s+)?class\s+(\w+)\s*\(").search(src, match_paren(src, paren))
        if not decl:
            continue
        got = class_body(src, decl.start())
        if not got:
            continue
        cls, body = got
        table = tm.group(1) if tm else re.sub(r"(?<!^)(?=[A-Z])", "_", cls).lower()
        tables[table] = fields_of(body)
        owners[table] = cls
    return tables, owners


def parse_projections(src: str) -> dict[str, list[str]]:
    """Non-entity data classes (query return projections) -> declared fields."""
    out: dict[str, list[str]] = {}
    for m in re.finditer(r"(?:data\s+)?class\s+(\w+)\s*\(", src):
        if src[max(0, m.start() - 12) : m.start()].rstrip().endswith("@Entity"):
            continue
        got = class_body(src, m.start())
        if not got:
            continue
        cls, body = got
        if cls.endswith("Entity"):
            continue
        fields = [f for f in re.findall(r"\bval\s+(\w+)\s*:", body) if f]
        if fields:
            out[cls] = fields
    return out


def flatten_literals(text: str) -> str:
    """
    Concatenate the contents of every string literal in `text`.

    A `@Query` argument in Kotlin is a chain of adjacent literals --
    `"SELECT ... " + "FROM x " + "WHERE ..."` -- so the statement does not exist
    anywhere in the file as contiguous text. Parsing the raw source therefore sees
    fragments, and the old version of this function took only the *first* one:
    `prescriptionCountBetween` was validated as
    `SELECT COUNT(DISTINCT p.id) FROM prescriptions p ` with its JOIN, its WHERE
    and its filter fragment all missing. Seventeen queries were parsed that way.

    This is the same fault `migrationcheck.py` had, and it hides the same way: the
    check runs, finds nothing wrong with the part it read, and reports a clean
    tree that was never examined.

    Dropping the `+` tokens is right rather than lossy: they are Kotlin syntax and
    the driver never sees them. What survives a constant reference like
    `+ RX_FILTER_SQL` is the name, which [project_sql_constants] substitutes.
    """
    parts: list[str] = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end == -1 else end
            parts.append(text[i + 3 : end])
            i = end + 3
            continue
        if c == '"':
            j = i + 1
            buf = []
            while j < n:
                if text[j] == "\\":
                    buf.append(text[j + 1] if j + 1 < n else "")
                    j += 2
                    continue
                if text[j] == '"':
                    break
                buf.append(text[j])
                j += 1
            parts.append("".join(buf))
            i = j + 1
            continue
        # An identifier *between* literals is a constant reference -- the
        # `RX_FILTER_SQL` in `"..." + RX_FILTER_SQL`. Dropping it along with the
        # `+` left the statement looking complete while its filter clause was
        # absent, which is the worst of both: the text ends at a valid-looking
        # point and the substitution below never finds a name to replace.
        ident = re.match(r"[A-Za-z_]\w*", text[i:])
        if ident:
            parts.append(" " + ident.group(0) + " ")
            i += len(ident.group(0))
            continue
        i += 1
    return "".join(parts)


def project_sql_constants(root: str) -> dict[str, str]:
    """
    Every `const val` in the tree whose value is a string, by name.

    `RX_FILTER_SQL` is a `const val` holding the shared WHERE fragment, and
    queries append it by name. Without substituting its value the fragment is
    invisible to every check in this file, including the one that decides whether
    a query joins the table the fragment references.
    """
    out: dict[str, str] = {}
    for dirpath, _, files in os.walk(root):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            text = strip_comments(open(path, encoding="utf-8").read())
            for m in re.finditer(r"const\s+val\s+(\w+)\s*(?::[^=]*)?=", text):
                name = m.group(1)
                value = flatten_literals(_const_initialiser(text, m.end()))
                # Only string-valued constants are worth substituting. An `Int`
                # or a resource id flattens to an empty string, and registering
                # those as `''` would let a name that happens to appear in SQL be
                # replaced by nothing.
                if value.strip():
                    out[name] = value
    return out


def _const_initialiser(text: str, start: int) -> str:
    """
    The initialiser source from just past its `=`, joining continuation lines.

    A multi-line `const val` is a chain of literals across several lines, and
    taking only the first line is not a small loss: `RX_FILTER_SQL` declares its
    district clause first and its specialty clause last, so a one-line read
    substituted a fragment that had no specialty in it at all -- and the checks
    depending on that clause stayed blind while reporting a clean tree.
    """
    i, n = start, len(text)
    buf: list[str] = []
    while i < n:
        eol = text.find("\n", i)
        eol = n if eol == -1 else eol
        line = text[i:eol]
        buf.append(line)
        stripped = line.rstrip()
        # `const val X =` with the value on the next line, or a `+` continuation.
        if stripped.endswith("+") or (len(buf) == 1 and not stripped):
            i = eol + 1
            continue
        break
    return "\n".join(buf)


def parse_queries(src: str, constants: dict[str, str] | None = None) -> list[dict]:
    out = []
    for m in re.finditer(r"@Query\s*\(", src):
        open_idx = m.end() - 1
        close_idx = match_paren(src, open_idx)
        if close_idx == -1:
            continue
        # The argument's own parens, not the next `"` -- that was the bug.
        sql = flatten_literals(src[open_idx + 1 : close_idx])
        for name, value in (constants or {}).items():
            if name in sql:
                sql = sql.replace(name, value)
        after = close_idx + 1
        tail = src[after : after + 600]
        fm = re.search(r"\bfun\s+(\w+)", tail)
        rm = re.search(r"\)\s*:\s*([A-Za-z_][\w.<>\[\],\s?]*)", tail)
        out.append(
            {
                "line": src[: m.start()].count("\n") + 1,
                "sql": sql,
                "fn": fm.group(1) if fm else "?",
                "ret": rm.group(1).strip() if rm else "?",
            }
        )
    return out


def analyse(sql: str) -> tuple[set[str], set[str], set[str], dict[str, str], dict[str, set[str]], set[str]]:
    """(tables, column identifiers, output aliases, alias->table, alias->columns, bare columns)."""
    sql_nc = re.sub(r"'[^']*'", " ", sql)
    sql_nc = re.sub(r"\?\d*|:\w+", " ", sql_nc)

    aliases = {m.group(1) for m in re.finditer(r"\bAS\s+([A-Za-z_]\w*)", sql_nc, re.I)}
    # Drop `expr AS alias` so the alias isn't mistaken for a column reference.
    sql_nc = re.sub(r"\bAS\s+[A-Za-z_]\w*", " ", sql_nc, flags=re.I)

    tables = {
        m.group(1).lower()
        for m in re.finditer(r"\b(?:from|join|update|insert\s+into)\s+([A-Za-z_]\w*)", sql_nc, re.I)
    }

    # `FROM prescriptions p` / `JOIN scanned_medicines AS sm` -- the alias is a
    # table reference, not a column, and would otherwise read as an unknown column.
    # The mapping is kept, not just the set: `d.territory` can only be judged
    # against whatever `d` names.
    alias_to_table: dict[str, str] = {}
    for m in re.finditer(
        r"\b(?:from|join)\s+([A-Za-z_]\w*)\s+(?:AS\s+)?([A-Za-z_]\w*)", sql_nc, re.I
    ):
        if m.group(2).lower() not in SQL_STOP:
            alias_to_table[m.group(2)] = m.group(1).lower()
    table_aliases = set(alias_to_table)

    # Columns referenced through an alias, kept per alias.
    qualified: dict[str, set[str]] = {}
    for m in re.finditer(r"\b([A-Za-z_]\w*)\.([A-Za-z_]\w*)", sql_nc):
        if m.group(2).lower() in SQL_STOP:
            continue
        qualified.setdefault(m.group(1), set()).add(m.group(2))

    # Columns written *without* a qualifier. A bare name is resolved by SQLite at
    # run time against every table in scope, and if two of them have a column by
    # that name the statement fails with "ambiguous column name" -- at run time,
    # on the device. Adding the `doctors` join to these queries put a second `id`
    # in scope and turned three `COUNT(DISTINCT id)` into exactly that.
    bare: set[str] = set()
    for m in re.finditer(r"(?<![\w.])([A-Za-z_]\w*)", sql_nc):
        if m.group(1).lower() in SQL_STOP or m.group(1).isdigit():
            continue
        bare.add(m.group(1))

    cols: set[str] = set()
    for m in re.finditer(r"\b([A-Za-z_]\w*)\.([A-Za-z_]\w*)|\b([A-Za-z_]\w*)\b", sql_nc):
        ident = m.group(2) or m.group(3)
        if ident.lower() in SQL_STOP or ident.isdigit():
            continue
        cols.add(ident)
    # table names and aliases are not columns
    cols -= tables
    cols -= table_aliases
    return tables, cols, aliases, alias_to_table, qualified, bare


def check_ambiguous_columns(sql: str, tables: dict[str, set[str]]) -> list[str]:
    """
    A column written without a qualifier that exists on more than one joined table.

    SQLite resolves a bare column against every table in scope and raises
    "ambiguous column name: x" when two of them have it. Nothing about that is
    visible to Room's compile-time validator, which is looking at the schema
    rather than at the resolution, and nothing about it is visible to a build:
    it is a runtime error on the path that executes the query.

    This is not hypothetical. Adding the `doctors` join to the dashboard queries
    put a second `id` in scope next to `prescriptions.id`, and three
    `COUNT(DISTINCT id)` statements silently became ambiguous.
    """
    used, _cols, _aliases, _alias_to_table, _qualified, bare = analyse(sql)
    in_scope = [t for t in used if t in tables]
    if len(in_scope) < 2:
        return []
    problems = []
    for c in sorted(bare):
        owners = [t for t in in_scope if c in tables[t]]
        if len(owners) > 1:
            problems.append(
                f"column '{c}' is not qualified but exists on {', '.join(sorted(owners))}"
            )
    return problems


def check_unknown_alias(sql: str, tables: dict[str, set[str]]) -> list[str]:
    """
    A column written through an alias that no table in the statement declares.

    `d.specialty` is only meaningful if the statement joins `doctors` as `d`.
    Without the join SQLite reports `no such column: d.specialty` at run time --
    and only on the code path that reads the specialty, so a query missing its
    join works for every user until one of them opens the filter sheet.

    This is what closes the loop on `RX_FILTER_SQL`: because the fragment is
    substituted into the statement before parsing, a query that appends it
    without joining `doctors` is reported here rather than needing a second,
    string-matching check that could drift from the fragment.
    """
    used, _cols, _aliases, alias_to_table, qualified, _bare = analyse(sql)
    in_scope = set(t for t in used if t in tables)
    problems = []
    for alias in sorted(qualified):
        if alias in alias_to_table:
            continue
        # A schema-qualified name (`main.table`) is not a table alias.
        if alias.lower() in ("main", "temp", "sqlite_master"):
            continue
        problems.append(
            f"'{alias}.' is used as a table alias but the statement declares no "
            f"table `{alias}`"
        )
    return problems


def main() -> int:
    e_src = strip_comments(open(ENTITIES, encoding="utf-8").read())
    d_src = strip_comments(open(DAOS, encoding="utf-8").read())

    tables, owners = parse_entities(e_src)
    projections = parse_projections(e_src)
    constants = project_sql_constants(os.path.dirname(_REPO))
    queries = parse_queries(d_src, constants)
    all_cols = set().union(*tables.values()) if tables else set()

    print("=" * 72)
    print("ROOM SQL VALIDATION")
    print("=" * 72)
    print(f"  entities: {len(tables)}   projections: {len(projections)}   queries: {len(queries)}")
    for t in sorted(tables):
        print(f"    {t:<22} {len(tables[t]):>3} cols   ({owners[t]})")
    print()

    problems: list[str] = []
    for q in queries:
        used_tables, cols, aliases, alias_to_table, qualified, _bare = analyse(q["sql"])
        unknown_tables = used_tables - set(tables)
        for t in sorted(unknown_tables):
            problems.append(f"{q['fn']} (Daos.kt:{q['line']}) unknown table '{t}'")

        known = set().union(*[tables[t] for t in used_tables & set(tables)]) if used_tables & set(tables) else all_cols
        for c in sorted(cols - known - aliases):
            scope = sorted(used_tables & set(tables)) or "<no table resolved>"
            problems.append(
                f"{q['fn']} (Daos.kt:{q['line']}) column '{c}' not on {scope}"
            )

        # A qualified column is judged against the one table its alias names.
        # Unqualified columns stay on the union below: SQLite resolves those at
        # run time against every joined table, so rejecting one that lives on a
        # sibling table in the same query would be a false alarm.
        for alias, used in qualified.items():
            target = alias_to_table.get(alias)
            if target is None or target not in tables:
                continue
            for c in sorted(used - tables[target]):
                problems.append(
                    f"{q['fn']} (Daos.kt:{q['line']}) column '{c}' is not on "
                    f"table '{target}' (written as {alias}.{c})"
                )

        for p in check_ambiguous_columns(q["sql"], tables):
            problems.append(f"{q['fn']} (Daos.kt:{q['line']}) {p}")

        for p in check_unknown_alias(q["sql"], tables):
            problems.append(f"{q['fn']} (Daos.kt:{q['line']}) {p}")

        base = re.sub(r"[<>,\[\]\s?]", "", q["ret"]).split(".")[-1]
        if base in projections:
            missing = [f for f in projections[base] if f not in cols and f not in aliases]
            if missing:
                problems.append(
                    f"{q['fn']} (Daos.kt:{q['line']}) returns {base} but SQL supplies no {missing}"
                )

    if problems:
        print(f"  {len(problems)} SUSPECT(S):")
        for p in dict.fromkeys(problems):
            print("   ", p)
    else:
        print("  no problems found - every column and table resolves")
    print()
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
