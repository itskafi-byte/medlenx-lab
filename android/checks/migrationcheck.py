#!/usr/bin/env python3
"""
Check that a Room migration's DDL matches the entities it creates.

Why this exists
---------------
Room validates a migrated schema when the database opens and throws
`IllegalStateException: Migration didn't properly handle ...` if the result does
not match the entity definitions. That check runs on the user's device, at
first launch after an upgrade, and there is no compiler or emulator here to run
it in advance. A mismatch is therefore invisible in this sandbox and fatal in
production, which is the worst combination available.

`roomcheck.py` cannot cover it: it resolves column *names* against an entity and
carries no type or nullability information, and the two are exactly what Room
compares. A migration can create the right column names with the wrong affinity
or a stray NOT NULL and pass roomcheck while failing at open.

Migrations are a chain, not a set
---------------------------------
Room compares the schema *after replaying every migration in order* against the
entity definitions, so a migration that leaves the table in the shape it had at
its own version is correct, not broken. `MIGRATION_1_2` creates `doctors` without
`territory` and `MIGRATION_2_3` adds it; validating either statement against the
current entity on its own reports a fault that Room would never raise, and
"fixing" it by editing the older migration is exactly the mistake that breaks
every device already on that version.

So the checks below replay `object : Migration(a, b)` blocks in `a` order,
accumulating each table's columns, and hold the *result* to the entity. A table
no migration creates (Room builds those from the entities on a fresh install)
is exempt from the whole-table comparison; its `ALTER`ed columns are still
checked one by one.

What it checks
--------------
For every `CREATE TABLE` inside a `Migration`:
  1. every column exists on the entity with the same table name
  2. the column's SQLite affinity matches the Kotlin type
     (String->TEXT, Long/Int/Short/Byte/Boolean->INTEGER, Double/Float->REAL,
      ByteArray->BLOB)
  3. NOT NULL appears exactly when the Kotlin type is non-nullable
  4. the entity has no column the migration failed to create

For every `CREATE [UNIQUE] INDEX`:
  5. the index name is the one Room derives, `index_<table>_<columns>`
  6. the uniqueness matches the entity's `Index(...)`
  7. the indexed columns exist on the entity

For every `ALTER TABLE ... ADD COLUMN`:
  8. the column exists on the entity, with matching affinity and nullability
  9. a `NOT NULL` column carries a `DEFAULT` -- SQLite will not add one to a
     table that already has rows, and that throws inside `migrate()` itself

Types it does not understand are reported as unverifiable rather than as
findings, so an unfamiliar Kotlin type cannot produce a false alarm.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "android" / "app" / "src" / "main" / "java"

MIGRATION_GLOBS = ("**/data/local/Migrations.kt",)

# Kotlin type -> the SQLite affinity Room writes for it.
AFFINITY = {
    "String": "TEXT",
    "Char": "TEXT",
    "Long": "INTEGER",
    "Int": "INTEGER",
    "Short": "INTEGER",
    "Byte": "INTEGER",
    "Boolean": "INTEGER",
    "Double": "REAL",
    "Float": "REAL",
    "ByteArray": "BLOB",
}


def strip_comments(text: str) -> str:
    """Blank comments, preserving newlines so line numbers stay true."""
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


def match_paren(src: str, open_idx: int) -> int:
    depth, i, in_str = 0, open_idx, False
    while i < len(src):
        c = src[i]
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


def parse_entities() -> dict[str, dict]:
    """
    table name -> {"columns": {col: (affinity, not_null)}, "indices": [...]}.

    Only enough Kotlin to read an `@Entity` data class. A `@ColumnInfo(name=)`
    may sit on the property's own line or the one above it; both forms are used
    in this project.
    """
    entities: dict[str, dict] = {}
    for path in sorted(SRC.rglob("Entities.kt")):
        text = strip_comments(path.read_text(encoding="utf-8"))
        for m in re.finditer(r"@Entity\s*\(", text):
            open_idx = m.end() - 1
            close_idx = match_paren(text, open_idx)
            if close_idx == -1:
                continue
            annotation = text[open_idx + 1 : close_idx]
            # The table name comes from `tableName = "..."`, and when that is
            # absent Room lowercases the class name -- so the class name is needed
            # either way and must be read first.
            cls = re.search(r"data\s+class\s+(\w+)\s*\(", text[close_idx:])
            if not cls:
                continue
            table_m = re.search(r'tableName\s*=\s*"(\w+)"', annotation)
            table = table_m.group(1) if table_m else cls.group(1).lower()
            # The primary constructor's paren, found from the class declaration
            # rather than by scanning for the next "(" -- the annotation itself
            # contains parens, and `Index(...)` inside it is a later one still.
            body_start = close_idx + cls.end() - 1
            body_close = match_paren(text, body_start)
            if body_close == -1:
                continue
            columns: dict[str, tuple[str | None, bool]] = {}
            params = text[body_start + 1 : body_close]
            for line in params.split("\n"):
                prop = re.search(r"\b(?:val|var)\s+(\w+)\s*:\s*([\w<>]+?)(\?)?\s*(?:=|,|$)", line)
                if not prop:
                    continue
                prop_name, kotlin_type, nullable = prop.group(1), prop.group(2), prop.group(3)
                named = re.search(r'@ColumnInfo\s*\(\s*name\s*=\s*"(\w+)"', line)
                column = named.group(1) if named else prop_name
                columns[column] = (
                    AFFINITY.get(kotlin_type),
                    nullable is None,
                )

            indices = []
            for im in re.finditer(r"Index\s*\(", annotation):
                io_ = im.end() - 1
                ic = match_paren(annotation, io_)
                if ic == -1:
                    continue
                args = annotation[io_ + 1 : ic]
                cols_m = re.search(r"value\s*=\s*\[([^\]]*)\]", args)
                if cols_m:
                    cols = [c.strip().strip('"') for c in cols_m.group(1).split(",")]
                else:
                    single = re.search(r'Index\s*\(\s*"(\w+)"', annotation[im.start() :])
                    cols = [single.group(1)] if single else []
                if not cols or not cols[0]:
                    continue
                # Normalise before comparing: the args are written `unique = true`,
                # so testing the space-stripped text against a spaced literal
                # never matched and every unique index looked non-unique.
                indices.append((cols, "unique=true" in args.replace(" ", "")))

            entities[table] = {"columns": columns, "indices": indices}
    return entities



def flatten_sql_literals(text: str) -> tuple[str, dict[str, int]]:
    """
    Join every string literal in the file into one stream, and map each DDL
    keyword's name to the line it appears on.

    A `db.execSQL(...)` argument in Kotlin is a chain of adjacent literals
    (`"CREATE TABLE ... (" + "`id` INTEGER..." + ...`), so the SQL statement does
    not exist anywhere in the file as contiguous text. Parsing the raw source
    therefore sees fragments: the paren matcher walks straight past the DDL and
    latches onto the `)` of `execSQL(` itself, and the statement's columns are
    never read.

    Concatenating the literal *contents* reconstructs what the driver will
    actually execute, which is the only thing worth validating. Non-literal
    tokens between the pieces vanish, which is exactly right — `+`, newlines and
    indentation are Kotlin syntax, not SQL.

    Returns (flattened_sql, {identifier: line}) so a finding can still point at a
    place in the source.
    """
    parts: list[str] = []
    lines: dict[str, int] = {}
    i, n = 0, len(text)
    while i < n:
        c = text[i]
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
            literal = "".join(buf)
            parts.append(literal)
            # Record where each DDL object name first appears.
            for m in re.finditer(
                r"CREATE\s+(?:UNIQUE\s+)?(?:TABLE|INDEX)\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?",
                literal,
            ):
                lines.setdefault(m.group(1), text[:i].count("\n") + 1)
            for m in re.finditer(r"ALTER\s+TABLE\s+`?(\w+)`?\s+ADD\s+COLUMN\s+`?(\w+)`?",
                                 literal):
                lines.setdefault(m.group(2), text[:i].count("\n") + 1)
            i = j + 1
            continue
        i += 1
    return "".join(parts), lines


def parse_migrations(raw: str) -> list[dict]:
    """
    Every `object : Migration(a, b) { ... }` block, in source order.

    Sliced from one `Migration(a, b)` marker to the next, so the private helpers
    a migration calls (`backfillDoctors`, which lives after the block it serves)
    ride along with it rather than being attributed to the following one.
    """
    marks = list(re.finditer(r":\s*Migration\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)", raw))
    out = []
    for i, m in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(raw)
        out.append(
            {
                "from": int(m.group(1)),
                "to": int(m.group(2)),
                "text": raw[m.end() : end],
                "line": raw[: m.start()].count("\n") + 1,
            }
        )
    return out


def parse_table_creates(text: str) -> list[dict]:
    """Every `CREATE TABLE [IF NOT EXISTS] x (...)` in the text."""
    out = []
    for m in re.finditer(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?\s*\(", text):
        open_idx = m.end() - 1
        close_idx = match_paren(text, open_idx)
        if close_idx == -1:
            continue
        body = text[open_idx + 1 : close_idx]
        cols = {}
        for part in split_top(body):
            part = part.strip()
            if not part:
                continue
            # Skip table constraints; they are not columns.
            if re.match(r"(?i)(PRIMARY\s+KEY|FOREIGN\s+KEY|UNIQUE|CHECK|CONSTRAINT)\s*\(", part):
                continue
            cm = re.match(r"`?(\w+)`?\s+([A-Za-z ]+)", part)
            if not cm:
                continue
            name, decl = cm.group(1), cm.group(2).strip().upper()
            affinity = None
            for a in ("INTEGER", "TEXT", "REAL", "BLOB", "NUMERIC"):
                if a in decl:
                    affinity = a
                    break
            not_null = "NOT NULL" in part.upper()
            cols[name] = (affinity, not_null, part.strip())
        out.append(
            {
                "table": m.group(1),
                "columns": cols,
                "line": text[: m.start()].count("\n") + 1,
            }
        )
    return out


def split_top(body: str) -> list[str]:
    """Split on commas at depth 0, ignoring string literals."""
    parts, buf, depth, in_str = [], [], 0, False
    for c in body:
        if in_str:
            buf.append(c)
            if c == '"':
                in_str = False
            continue
        if c == '"':
            in_str = True
            buf.append(c)
        elif c == "(":
            depth += 1
            buf.append(c)
        elif c == ")":
            depth -= 1
            buf.append(c)
        elif c == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
        else:
            buf.append(c)
    if "".join(buf).strip():
        parts.append("".join(buf))
    return parts


def parse_index_creates(text: str) -> list[dict]:
    out = []
    pat = r"CREATE\s+(UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?\s+ON\s+`?(\w+)`?\s*\(([^)]*)\)"
    for m in re.finditer(pat, text):
        cols = [c.strip().strip("`").strip() for c in m.group(4).split(",")]
        out.append(
            {
                "unique": bool(m.group(1)),
                "name": m.group(2),
                "table": m.group(3),
                "columns": [c for c in cols if c],
                "line": text[: m.start()].count("\n") + 1,
            }
        )
    return out


def parse_alter_adds(text: str) -> list[dict]:
    out = []
    # The declaration has to be bounded by hand. `flatten_sql_literals` concatenates
    # every literal in the file, so the statement does not end at a newline or a
    # `;` -- a greedy tail runs on into the *next* statement and a later `IS NOT
    # NULL` silently satisfies the nullability check it was supposed to fail.
    # Stopping at the next statement keyword keeps `NOT NULL` and `DEFAULT ...` in
    # the declaration (they are not statement starts) while cutting the run-on.
    pat = (
        r"ALTER\s+TABLE\s+`?(\w+)`?\s+ADD\s+COLUMN\s+`?(\w+)`?\s+"
        r"([A-Za-z0-9_ ']*?)"
        r"(?=\s*(?:ALTER|CREATE|SELECT|UPDATE|INSERT|DELETE|DROP|WHERE|ORDER|GROUP|HAVING|LIMIT|$))"
    )
    for m in re.finditer(pat, text):
        decl = m.group(3).strip().upper()
        affinity = next((a for a in ("INTEGER", "TEXT", "REAL", "BLOB", "NUMERIC") if a in decl), None)
        out.append(
            {
                "table": m.group(1),
                "column": m.group(2),
                "affinity": affinity,
                "not_null": "NOT NULL" in decl,
                # A `DEFAULT` clause is tracked separately: SQLite refuses
                # `ADD COLUMN ... NOT NULL` with no default on a table that already
                # has rows, and the failure happens inside `migrate()` -- before
                # Room ever gets to compare schemas.
                "has_default": "DEFAULT" in decl,
                "line": text[: m.start()].count("\n") + 1,
            }
        )
    return out


def main() -> int:
    entities = parse_entities()
    if not entities:
        print("migrationcheck: no entities parsed -- check SRC", file=sys.stderr)
        return 1

    print("=" * 72)
    print("ROOM MIGRATION DDL VALIDATION")
    print("=" * 72)
    print(f"  entities: {len(entities)}")
    print()

    findings: list[str] = []
    unverifiable: list[str] = []
    checked = {"tables": 0, "indices": 0, "columns": 0}

    for pattern in MIGRATION_GLOBS:
        for path in sorted(ROOT.glob("android/" + pattern)):
            raw = strip_comments(path.read_text(encoding="utf-8"))
            rel = path.relative_to(ROOT)

            steps = parse_migrations(raw)
            if not steps:
                # No `Migration(a, b)` marker anywhere: treat the file as a single
                # step, which is what this check did before migrations chained.
                steps = [{"from": 0, "to": 0, "text": raw, "line": 1}]

            # Replayed state. `schema` accumulates what the chain builds, table by
            # table; `created_by` remembers which tables a migration is responsible
            # for, so the tables Room builds straight from the entity on a fresh
            # install are never held to the migrations.
            schema: dict[str, dict[str, tuple]] = {}
            created_by: dict[str, int] = {}
            line_of: dict[str, int] = {}

            for step, mig in enumerate(sorted(steps, key=lambda m: m["from"])):
                text, ddl_lines = flatten_sql_literals(mig["text"])
                # Line numbers come out relative to the slice; shift them back onto
                # the file. The slice opens on the line holding `Migration(a, b)`.
                off = mig["line"] - 1
                ddl_lines = {k: v + off for k, v in ddl_lines.items()}
                line_of.update(ddl_lines)

                for create in parse_table_creates(text):
                    table = create["table"]
                    entity = entities.get(table)
                    if entity is None:
                        findings.append(
                            f"    {rel}:{ddl_lines.get(table, create['line'] + off)}  CREATE TABLE `{table}` has no "
                            f"@Entity with that tableName"
                        )
                        continue
                    checked["tables"] += 1
                    created_by[table] = step
                    line_of.setdefault(table, create["line"] + off)
                    # Additive on purpose: the same table may be created by a later
                    # migration's rebuild, and then the columns it re-declares are
                    # the ones that count.
                    schema.setdefault(table, {}).update(
                        {c: (a, nn) for c, (a, nn, _) in create["columns"].items()}
                    )
                    expected = entity["columns"]

                    for column, (affinity, not_null, raw_decl) in create["columns"].items():
                        if column not in expected:
                            findings.append(
                                f"    {rel}:{ddl_lines.get(column, create['line'] + off)}  `{table}`.{column} is not on "
                                f"the entity"
                            )
                            continue
                        exp_affinity, exp_not_null = expected[column]
                        checked["columns"] += 1
                        if exp_affinity is None:
                            unverifiable.append(f"{table}.{column} (unmapped Kotlin type)")
                            continue
                        # A column that is the primary key is written as
                        # `INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`; affinity still
                        # has to be INTEGER, and Room's notNull is true for a
                        # non-nullable Long.
                        if affinity != exp_affinity:
                            findings.append(
                                f"    {rel}:{ddl_lines.get(column, create['line'] + off)}  `{table}`.{column} is "
                                f"{affinity}, entity expects {exp_affinity}  [{raw_decl}]"
                            )
                        if not_null != exp_not_null:
                            want = "NOT NULL" if exp_not_null else "nullable"
                            findings.append(
                                f"    {rel}:{ddl_lines.get(column, create['line'] + off)}  `{table}`.{column} should be "
                                f"{want}  [{raw_decl}]"
                            )

                for idx in parse_index_creates(text):
                    table = idx["table"]
                    entity = entities.get(table)
                    if entity is None:
                        findings.append(
                            f"    {rel}:{idx['line'] + off}  index `{idx['name']}` is on unknown "
                            f"table `{table}`"
                        )
                        continue
                    checked["indices"] += 1
                    derived = "index_" + table + "_" + "_".join(idx["columns"])
                    if idx["name"] != derived:
                        findings.append(
                            f"    {rel}:{idx['line'] + off}  index `{idx['name']}` -- Room would "
                            f"name it `{derived}`"
                        )
                    declared = entity["indices"]
                    match = next((d for d in declared if d[0] == idx["columns"]), None)
                    if match is None:
                        findings.append(
                            f"    {rel}:{idx['line'] + off}  index on {idx['columns']} is not "
                            f"declared on @Entity(\"{table}\")"
                        )
                    elif match[1] != idx["unique"]:
                        want = "UNIQUE" if match[1] else "not unique"
                        findings.append(
                            f"    {rel}:{idx['line'] + off}  index `{idx['name']}` should be {want}"
                        )
                    for column in idx["columns"]:
                        if column not in entity["columns"]:
                            findings.append(
                                f"    {rel}:{idx['line'] + off}  index `{idx['name']}` covers "
                                f"`{column}`, which is not on `{table}`"
                            )

                for add in parse_alter_adds(text):
                    table = add["table"]
                    entity = entities.get(table)
                    if entity is None:
                        findings.append(
                            f"    {rel}:{add['line'] + off}  ALTER on unknown table `{table}`"
                        )
                        continue
                    if add["column"] not in entity["columns"]:
                        findings.append(
                            f"    {rel}:{add['line'] + off}  `{table}`.{add['column']} is not on "
                            f"the entity"
                        )
                        continue
                    checked["columns"] += 1
                    if table in created_by:
                        # The chain owns this table, so record the column for the
                        # end-of-chain comparison below.
                        schema.setdefault(table, {})[add["column"]] = (
                            add["affinity"],
                            add["not_null"],
                        )
                    exp_affinity, exp_not_null = entity["columns"][add["column"]]
                    if exp_affinity is None:
                        unverifiable.append(f"{table}.{add['column']} (unmapped Kotlin type)")
                        continue
                    if add["affinity"] != exp_affinity:
                        findings.append(
                            f"    {rel}:{add['line'] + off}  `{table}`.{add['column']} added as "
                            f"{add['affinity']}, entity expects {exp_affinity}"
                        )
                    if add["not_null"] != exp_not_null:
                        want = "NOT NULL" if exp_not_null else "nullable"
                        findings.append(
                            f"    {rel}:{add['line'] + off}  `{table}`.{add['column']} should be "
                            f"{want}"
                        )
                    if add["not_null"] and not add["has_default"]:
                        findings.append(
                            f"    {rel}:{add['line'] + off}  `{table}`.{add['column']} is added "
                            f"NOT NULL with no DEFAULT -- SQLite rejects that on a table with rows"
                        )

            # The end-of-chain comparison: whatever a migration created, replayed
            # through every later ALTER, has to arrive at the entity. Checking this
            # per statement instead would flag MIGRATION_1_2 for not creating a
            # column MIGRATION_2_3 is there to add.
            for table, built in schema.items():
                for column in entities[table]["columns"]:
                    if column not in built:
                        findings.append(
                            f"    {rel}:{line_of.get(table, 0)}  `{table}`.{column} is on the "
                            f"entity but the migration chain never creates it"
                        )

    print(
        f"  checked: {checked['tables']} table(s), {checked['columns']} column(s), "
        f"{checked['indices']} index/indices"
    )
    if unverifiable:
        print(f"  unverifiable: {len(unverifiable)} column(s) -- {', '.join(unverifiable)}")
    print()

    if findings:
        print(f"  {len(findings)} PROBLEM(S) -- Room will reject this schema at open:\n")
        for f in findings:
            print(f)
        print()
        return 1

    print("  no problems found - every migration DDL statement matches its entity")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
