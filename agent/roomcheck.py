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


def parse_queries(src: str) -> list[dict]:
    out = []
    for m in re.finditer(r'@Query\s*\(\s*("""|")', src):
        quote = m.group(1)
        start = m.end()
        if quote == '"""':
            end = src.find('"""', start)
            sql = src[start:end]
            after = end + 3
        else:
            end = src.find('"', start)
            sql = src[start:end]
            after = end + 1
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


def analyse(sql: str) -> tuple[set[str], set[str], set[str], dict[str, str], dict[str, set[str]]]:
    """(tables, column identifiers, output aliases, alias->table, alias->columns)."""
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

    cols: set[str] = set()
    for m in re.finditer(r"\b([A-Za-z_]\w*)\.([A-Za-z_]\w*)|\b([A-Za-z_]\w*)\b", sql_nc):
        ident = m.group(2) or m.group(3)
        if ident.lower() in SQL_STOP or ident.isdigit():
            continue
        cols.add(ident)
    # table names and aliases are not columns
    cols -= tables
    cols -= table_aliases
    return tables, cols, aliases, alias_to_table, qualified


def main() -> int:
    e_src = strip_comments(open(ENTITIES, encoding="utf-8").read())
    d_src = strip_comments(open(DAOS, encoding="utf-8").read())

    tables, owners = parse_entities(e_src)
    projections = parse_projections(e_src)
    queries = parse_queries(d_src)
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
        used_tables, cols, aliases, alias_to_table, qualified = analyse(q["sql"])
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
