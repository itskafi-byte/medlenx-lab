#!/usr/bin/env python3
"""Exercise the actual 16->17 FTS DDL in SQLite and Room-style sync triggers.

This does not replace Room/KSP migration tests or Android-device testing.
"""
from pathlib import Path
import re, sqlite3
root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab/data/local'
migrations = (root / 'Migrations.kt').read_text()
block = migrations.split('val MIGRATION_16_17', 1)[1].split('\n    }', 1)[0]
statements = re.findall(r'db.execSQL\("([^"\n]+)"\)', block)
assert len(statements) == 2
entities = (root / 'Entities.kt').read_text()
assert '@Fts4(contentEntity = MedexEntity::class, tokenizer = FtsOptions.TOKENIZER_PORTER)' in entities
dao = (root / 'Daos.kt').read_text()
assert 'medex_fts MATCH :expression' in dao and '@Upsert\n    suspend fun insertAll(rows: List<MedexEntity>)' in dao
assert '"\\\"${it.value}*\\\""' in dao
assert 'Migrations.MIGRATION_16_17' in (root / 'MedLenXDatabase.kt').read_text()
c = sqlite3.connect(':memory:')
c.execute('CREATE TABLE medex_products(id TEXT PRIMARY KEY, brand_name TEXT NOT NULL, generic TEXT NOT NULL, company TEXT NOT NULL, type TEXT NOT NULL)')
c.execute("INSERT INTO medex_products VALUES ('id1', 'Napa Extra', 'Paracetamol', 'Beximco', 'Tablet')")
for sql in statements: c.execute(sql)
def search(expression):
    return c.execute('SELECT p.id FROM medex_products p JOIN medex_fts ON p.rowid=medex_fts.rowid WHERE medex_fts MATCH ?', (expression,)).fetchall()
assert search('"nap*"') == [('id1',)]
assert search('"para*" "tab*"') == [('id1',)]
assert search('"apa*"') == []  # substring is not a token prefix
# Mirror the Room FTS4 external-content trigger shape. Room generates these;
# this tests the semantics, not that KSP actually generated them.
for op in ('UPDATE', 'DELETE'):
    c.execute(f'CREATE TRIGGER before_{op} BEFORE {op} ON medex_products BEGIN DELETE FROM medex_fts WHERE docid=OLD.rowid; END')
for op in ('UPDATE', 'INSERT'):
    c.execute(f'CREATE TRIGGER after_{op} AFTER {op} ON medex_products BEGIN INSERT INTO medex_fts(docid,brand_name,generic,company,type) VALUES(NEW.rowid, NEW.brand_name, NEW.generic, NEW.company, NEW.type); END')
c.execute("UPDATE medex_products SET brand_name='Ace Plus' WHERE id='id1'")
assert search('"nap*"') == [] and search('"ace*"') == [('id1',)]
c.execute("INSERT INTO medex_products VALUES ('id2','Seclo','Omeprazole','Square','Capsule')")
assert search('"secl*"') == [('id2',)]
c.execute("DELETE FROM medex_products WHERE id='id2'")
assert search('"secl*"') == []
print('medexftscheck: migration rebuild, token prefixes, update/insert/delete PASS (host SQLite only)')
