#!/usr/bin/env python3
"""Static checks for the MedLenX Lab Android app.

There is no JVM in the review sandbox, so nothing here compiles the project. These
checks exist to catch the classes of error that a first build would otherwise report
in bulk, and the logic bugs that a build would not report at all.

Run it from anywhere - paths are resolved from this file's own location:

    python3 android/checks/audit.py

Exits non-zero when any check reports a finding, so it can gate a commit.

Every check in here was mutation-tested: a defect was planted in the source and the
check was confirmed to report it. A check that has never caught anything is not
evidence of anything.
"""

import os
import re
import sys
import collections

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)                                    # .../android
SRC = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'medlenx', 'lab')

findings = []


def report(check, path, line, message):
    findings.append((check, os.path.relpath(path, ROOT), line, message))


# --------------------------------------------------------------------------
# Kotlin source, lightly normalised
# --------------------------------------------------------------------------

def strip_comments(text):
    """Drop comments, preserving line numbers and the shape of the code."""
    out = []
    i = 0
    n = len(text)
    in_str = None            # None | '"' | '"""' | "'"
    while i < n:
        c = text[i]
        if in_str:
            if in_str == "'":
                if c == '\\':
                    out.append(text[i:i + 2]); i += 2; continue
                if c == "'":
                    in_str = None
                out.append(c); i += 1; continue
            if in_str == '"""':
                if text.startswith('"""', i):
                    in_str = None
                    out.append('"""'); i += 3; continue
                out.append(c); i += 1; continue
            # ordinary "..." string
            if c == '\\':
                out.append(text[i:i + 2]); i += 2; continue
            if c == '"':
                in_str = None
            out.append(c); i += 1; continue

        if text.startswith('"""', i):
            in_str = '"""'; out.append('"""'); i += 3; continue
        if c == '"':
            in_str = '"'; out.append('"'); i += 1; continue
        if c == "'":
            in_str = "'"; out.append(c); i += 1; continue
        if text.startswith('/*', i):
            end = text.find('*/', i + 2)
            end = len(text) if end < 0 else end + 2
            # keep the newlines so reported line numbers stay correct
            out.append('\n' * text.count('\n', i, end))
            i = end; continue
        if text.startswith('//', i):
            end = text.find('\n', i)
            i = len(text) if end < 0 else end
            continue
        out.append(c); i += 1
    return ''.join(out)


def blank_strings(text):
    """Empty out string/char literal bodies.

    They must keep their quotes: deleting them outright merges neighbouring tokens
    and silently changes argument counts, which is a bug this script once had.
    """
    def repl(m):
        body = m.group(0)
        return body[0] * len(body) if len(body) < 3 else body[:3] + body[-3:]

    text = re.sub(r'"""(?:[^"]|"(?!""))*"""', lambda m: '""""""', text)
    text = re.sub(r'"(?:\\.|[^"\\\n])*"', lambda m: '""', text)
    text = re.sub(r"'(?:\\.|[^'\\\n])*'", lambda m: "''", text)
    return text


def normalise(text):
    return blank_strings(strip_comments(text))


def load_sources():
    files = {}
    for dirpath, _dirs, names in os.walk(SRC):
        for name in names:
            if name.endswith('.kt'):
                path = os.path.join(dirpath, name)
                with open(path, encoding='utf-8') as fh:
                    files[path] = normalise(fh.read())
    return files


def match_paren(text, open_index):
    """Index of the bracket matching the one at open_index, or -1."""
    depth = 0
    i = open_index
    while i < len(text):
        if text[i] in '([{':
            depth += 1
        elif text[i] in ')]}':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def split_top(text):
    """Split on commas at nesting depth zero.

    `<` and `>` are NOT nesting brackets here. Treating them as such makes
    `all.count { it.abxItems > 0 }` split at the wrong depth - a bug this
    script once had.
    """
    out = []
    cur = []
    depth = 0
    for ch in text:
        if ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
        if ch == ',' and depth == 0:
            out.append(''.join(cur)); cur = []
        else:
            cur.append(ch)
    if ''.join(cur).strip():
        out.append(''.join(cur))
    return out


def line_of(text, index):
    return text.count('\n', 0, index) + 1


SOURCES = load_sources()


# --------------------------------------------------------------------------
# 1. Duplicate top-level declarations
# --------------------------------------------------------------------------

def check_duplicate_declarations():
    """Two top-level declarations with the same name make the wrong one win
    resolution, and every downstream error then points somewhere misleading."""
    seen = collections.defaultdict(list)
    decl_re = re.compile(
        r'\b(?:data\s+|value\s+|sealed\s+|enum\s+|annotation\s+)*'
        r'(class|interface|object)\s+(\w+)'
    )
    for path, text in SOURCES.items():
        for m in decl_re.finditer(text):
            kind, name = m.group(1), m.group(2)
            # only top level: nothing but whitespace/annotations before it on the line
            if m.start() > 0 and text[:m.start()].count('{') != text[:m.start()].count('}'):
                continue
            # `private` at top level is file-scoped, so two files may each have one
            if text[:m.start()].rstrip().endswith('private'):
                continue
            seen[name].append((path, line_of(text, m.start()), kind))

    # extension functions: same name, different receiver - legal overloads
    ext_re = re.compile(r'\bfun\s+(?:[\w<>.]+\.)?(\w+)\s*\.?\s*(\w*)\s*\(')
    fun_seen = collections.defaultdict(list)
    for path, text in SOURCES.items():
        for m in re.finditer(r'\bfun\s+(?:<[^>]*>\s*)?([\w<>.?]+\.)?(\w+)\s*\(', text):
            receiver, name = m.group(1), m.group(2)
            if text[:m.start()].count('{') != text[:m.start()].count('}'):
                continue
            if text[:m.start()].rstrip().endswith('private'):
                continue
            fun_seen[(name, (receiver or '').rstrip('.'))].append(
                (path, line_of(text, m.start()))
            )

    for name, places in sorted(seen.items()):
        if len(places) > 1:
            report('duplicate-declaration', places[0][0], places[0][1],
                   "'%s' declared %d times: %s" % (
                       name, len(places),
                       ', '.join('%s:%d' % (os.path.relpath(p, ROOT), l) for p, l, _ in places)))

    for (name, receiver), places in sorted(fun_seen.items()):
        if len(places) > 1:
            report('duplicate-declaration', places[0][0], places[0][1],
                   "top-level fun '%s' (receiver %r) declared %d times: %s" % (
                       name, receiver or '<none>', len(places),
                       ', '.join('%s:%d' % (os.path.relpath(p, ROOT), l) for p, l in places)))


# --------------------------------------------------------------------------
# 2. JVM signature clashes
# --------------------------------------------------------------------------

def check_jvm_clashes():
    """`var x by mutableStateOf(..) private set` plus `fun setX()` collide on the
    JVM: the property's generated setter and the function share a signature."""
    cls_re = re.compile(r'\b(?:class|object)\s+(\w+)')
    for path, text in SOURCES.items():
        # collect class spans crudely: from the opening brace to end of file
        spans = []
        for m in cls_re.finditer(text):
            brace = text.find('{', m.end())
            if brace < 0:
                continue
            spans.append((m.group(1), brace))
        for name, start in spans:
            body = text[start:]
            props = set(re.findall(r'\b(?:var)\s+(\w+)\s+by\s+(?:mutable|snapshotFlow)', body))
            props |= set(re.findall(r'\bvar\s+(\w+)\s*[:=]', body))
            for prop in props:
                cap = prop[0].upper() + prop[1:]
                for verb in ('set', 'get'):
                    if re.search(r'\bfun\s+%s%s\s*\(' % (verb, cap), body):
                        report('jvm-clash', path, line_of(text, start),
                               "class %s: 'var %s' and 'fun %s%s()' share a JVM signature"
                               % (name, prop, verb, cap))


# --------------------------------------------------------------------------
# 3. Named arguments
# --------------------------------------------------------------------------

def collect_declarations():
    """name -> [(params, path, line, enclosing)] for functions and data classes."""
    decls = collections.defaultdict(list)
    decl_re = re.compile(
        r'\b(?:fun|(?:data\s+|value\s+|sealed\s+)?class|object)\s+(?:[\w<>.]+\.)?(\w+)\s*\('
    )
    for path, text in SOURCES.items():
        for m in decl_re.finditer(text):
            name = m.group(1)
            open_idx = text.index('(', m.end() - 1)
            close_idx = match_paren(text, open_idx)
            if close_idx < 0:
                continue
            params = []
            for raw in split_top(text[open_idx + 1:close_idx]):
                raw = raw.strip()
                if not raw:
                    continue
                pm = re.search(r'([A-Za-z_]\w*)\s*:\s*[^=]+', raw)
                if not pm:
                    continue
                params.append((pm.group(1), '=' in raw.split(':', 1)[1]))
            if not params:
                continue
            head = text[:m.start()]
            enclosing = None
            for em in re.finditer(r'\b(?:object|class|interface)\s+(\w+)', head):
                enclosing = em.group(1)
            decls[name].append((params, path, line_of(text, m.start()), enclosing))
    return decls


def check_named_arguments(decls):
    call_re = re.compile(r'(?<![\w>])([A-Z]\w*|[a-z]\w*)\s*\(')
    for path, text in SOURCES.items():
        for m in call_re.finditer(text):
            name = m.group(1)
            if name not in decls:
                continue
            prev = text[:m.start()].rstrip()
            if prev.endswith('fun'):
                continue
            open_idx = m.end() - 1
            close_idx = match_paren(text, open_idx)
            if close_idx < 0:
                continue
            given, positional = [], 0
            for arg in split_top(text[open_idx + 1:close_idx]):
                arg = arg.strip()
                if not arg:
                    continue
                am = re.match(r'^([A-Za-z_]\w*)\s*=(?!=)', arg)
                if am:
                    given.append(am.group(1))
                else:
                    positional += 1
            if not given:
                continue

            # Resolve overloads by receiver where one is written (Object.method(..)).
            # A camelCase receiver such as `scanRepository.` is an instance, not a
            # type, so it must not be treated as a receiver qualifier.
            receiver = None
            rm = re.match(r'.*\b([A-Z]\w*)\.$', prev)
            if rm and not re.search(r'[a-z][A-Z]', rm.group(1)):
                receiver = rm.group(1)
            cands = decls[name]
            pick = [c for c in cands if c[3] == receiver] if receiver else \
                   [c for c in cands if c[3] is None]
            if not pick:
                pick = cands
            if len({tuple(c[0]) for c in pick}) > 1:
                continue                                   # genuinely ambiguous
            params = pick[0][0]
            pnames = [p[0] for p in params]
            for g in given:
                if g not in pnames:
                    report('named-argument', path, line_of(text, m.start()),
                           "%s(): unknown named argument '%s' (parameters: %s)"
                           % (name, g, ', '.join(pnames)))
            required = [p[0] for p in params if not p[1]]
            trailing = text[close_idx + 1:close_idx + 8].lstrip()[:1]
            if trailing == '{' and required:
                required = required[:-1]
            missing = [r for r in required if r not in given]
            if missing and positional < len(missing):
                report('named-argument', path, line_of(text, m.start()),
                       "%s(): missing required argument(s) %s (positional given=%d)"
                       % (name, ', '.join(missing), positional))


# --------------------------------------------------------------------------
# 4. ViewModel member access from the UI
# --------------------------------------------------------------------------

def check_viewmodel_members():
    """`vm.somethingThatDoesNotExist` is the single most common error a first
    build reports, and it is cheap to catch statically."""
    members = collections.defaultdict(set)
    for path, text in SOURCES.items():
        for m in re.finditer(r'\b(?:class|object)\s+(\w+)', text):
            brace = text.find('{', m.end())
            if brace < 0:
                continue
            body = text[brace:]
            for dm in re.finditer(r'\b(?:fun|val|var)\s+(?:[\w<>.]+\.)?(\w+)', body):
                members[m.group(1)].add(dm.group(1))

    for path, text in SOURCES.items():
        typing = {}
        for m in re.finditer(r'\b(\w+)\s*:\s*(\w+ViewModel)\b', text):
            typing[m.group(1)] = m.group(2)
        for m in re.finditer(r'val\s+(\w+)\s*:\s*(\w+ViewModel)\s*=\s*viewModel', text):
            typing[m.group(1)] = m.group(2)
        for var, cls in typing.items():
            known = members.get(cls)
            if not known:
                continue
            for m in re.finditer(r'\b' + re.escape(var) + r'(?:\.|::)(\w+)', text):
                member = m.group(1)
                if member in known:
                    continue
                if member in ('viewModelScope', 'getApplication', 'clear',
                              'onCleared', 'addCloseable'):
                    continue
                report('viewmodel-member', path, line_of(text, m.start()),
                       "%s has no member '%s'" % (cls, member))


# --------------------------------------------------------------------------
# 5. Room @Query tables and columns
# --------------------------------------------------------------------------

def read(path):
    with open(path, encoding='utf-8') as fh:
        return fh.read()


def entity_tables():
    """tableName -> set of column names, parsed out of the @Entity classes."""
    path = os.path.join(SRC, 'data', 'local', 'Entities.kt')
    if not os.path.exists(path):
        return {}
    text = read(path)
    tables = {}
    for m in re.finditer(r'@Entity\(', text):
        open_idx = text.index('(', m.end() - 1)
        close_idx = match_paren(text, open_idx)
        if close_idx < 0:
            continue
        annotation = text[open_idx + 1:close_idx]
        named = re.search(r'tableName\s*=\s*"(\w+)"', annotation)
        cls = re.search(r'data class (\w+)\(', text[close_idx:])
        if not cls:
            continue
        body_open = text.index('(', close_idx + cls.start())
        body_close = match_paren(text, body_open)
        body = text[body_open + 1:body_close]
        table = named.group(1) if named else cls.group(1)
        cols = set()
        for pm in re.finditer(r'@ColumnInfo\(name\s*=\s*"(\w+)"\)|val\s+(\w+)\s*:', body):
            explicit, inferred = pm.group(1), pm.group(2)
            if explicit:
                cols.add(explicit)
            elif inferred:
                cols.add(re.sub(r'([A-Z])', lambda x: '_' + x.group(1).lower(), inferred))
        tables[table] = cols
    return tables


def string_literals(expression):
    """Concatenate the string literals of a Kotlin expression, in order."""
    return ''.join(re.findall(r'"((?:\\.|[^"\\])*)"', expression))


SQL_KEYWORDS = {
    'and', 'or', 'not', 'null', 'is', 'in', 'like', 'between', 'case', 'when',
    'then', 'else', 'end', 'asc', 'desc', 'distinct', 'count', 'sum', 'avg',
    'max', 'min', 'ifnull', 'coalesce', 'cast', 'as', 'on', 'by', 'order',
    'group', 'limit', 'offset', 'select', 'from', 'true', 'false', 'exists',
    'escape', 'collate', 'nocase', 'where', 'inner', 'left', 'outer', 'join',
    'union', 'all', 'having', 'using', 'values', 'set', 'update', 'insert',
    'into', 'delete', 'nulls', 'first', 'last', 'current_date', 'date',
}


def check_room_queries():
    """Room validates its @Query strings at KSP time, which is the one surface
    no other check here reaches. A typo'd column is a build failure the developer
    only sees after a full Gradle run."""
    daos = os.path.join(SRC, 'data', 'local', 'Daos.kt')
    filters = os.path.join(SRC, 'data', 'local', 'Filters.kt')
    if not os.path.exists(daos):
        return
    tables = entity_tables()
    if not tables:
        report('room-query', daos, 1, 'no @Entity tables parsed - check cannot run')
        return

    rx_filter_sql = ''
    if os.path.exists(filters):
        ftext = read(filters)
        i = ftext.find('const val RX_FILTER_SQL')
        if i >= 0:
            j = ftext.find('\n\n', i)
            rx_filter_sql = string_literals(ftext[i:j if j > 0 else len(ftext)])

    text = read(daos)
    checked = 0
    for m in re.finditer(r'@Query\(', text):
        open_idx = text.index('(', m.end() - 1)
        close_idx = match_paren(text, open_idx)
        if close_idx < 0:
            continue
        expression = text[open_idx + 1:close_idx]
        sql = string_literals(expression)
        if 'RX_FILTER_SQL' in expression:
            sql += rx_filter_sql
        checked += 1
        line = line_of(text, m.start())
        low = sql.lower()

        # Quoted literals are data, not identifiers: CASE WHEN x = 'live' would
        # otherwise report 'live' as an unresolved column.
        low = re.sub(r"'[^']*'", "''", low)
        # `:from` is a Room bind parameter, not the FROM keyword. Replace it with
        # the positional placeholder so no keyword survives inside a parameter name.
        low = re.sub(r':\w+', ' ? ', low)
        sql = re.sub(r':\w+', ' ? ', sql)
        aliases = set(re.findall(r'\bas\s+(\w+)', low))

        scope = {}
        for tm in re.finditer(r'\b(?:from|join|update|into)\s+(\w+)(?:\s+(?:as\s+)?(\w+))?', low):
            table = tm.group(1)
            if table in ('select', 'where', 'set', 'values', 'dual'):
                continue
            if table not in tables:
                report('room-query', daos, line, "unknown table '%s'" % table)
                continue
            scope[tm.group(2) or table] = table
        if not scope:
            continue
        available = set().union(*(tables[t] for t in scope.values()))

        for cm in re.finditer(r'\b(\w+)\.(\w+)\b', sql):
            alias, column = cm.group(1), cm.group(2)
            if alias in scope and column not in tables[scope[alias]]:
                report('room-query', daos, line,
                       "no column '%s' on table '%s'" % (column, scope[alias]))

        for kw in ('where', 'order by', 'group by', 'on', 'set'):
            for seg in re.finditer(
                    r'\b' + kw + r'\b(.*?)(?=\bwhere\b|\border by\b|\bgroup by\b'
                    r'|\blimit\b|\boffset\b|$)', low, re.S):
                for cm in re.finditer(r'(?<![\w.:])([a-z_][a-z0-9_]*)(?![\w(])',
                                      seg.group(1)):
                    word = cm.group(1)
                    if (word in available or word in SQL_KEYWORDS or word in scope
                            or word in aliases or word in tables):
                        continue
                    report('room-query', daos, line,
                           "unresolved identifier '%s'" % word)
    return checked


# --------------------------------------------------------------------------

def main():
    decls = collect_declarations()
    check_duplicate_declarations()
    check_jvm_clashes()
    check_named_arguments(decls)
    check_viewmodel_members()
    queries = check_room_queries()

    print('MedLenX Lab static audit')
    print('  kotlin files : %d' % len(SOURCES))
    print('  declarations : %d' % sum(len(v) for v in decls.values()))
    print('  @Query       : %s' % queries)
    print()
    if not findings:
        print('no findings')
        return 0
    by_check = collections.defaultdict(list)
    for check, path, line, message in findings:
        by_check[check].append((path, line, message))
    for check in sorted(by_check):
        print('%s (%d)' % (check, len(by_check[check])))
        for path, line, message in by_check[check]:
            print('  %s:%d  %s' % (path, line, message))
    print()
    print('%d finding(s)' % len(findings))
    return 1


if __name__ == '__main__':
    sys.exit(main())
