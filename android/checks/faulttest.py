#!/usr/bin/env python3
"""
Prove the checks fire, and put the tree back afterwards.

Why this exists
---------------
`agent/README.md` rule 6: a clean run proves nothing until a fault proves it
fires. Every check in this directory has been fault-tested, but the testing was
done by hand, in a throwaway script, one fault at a time -- and one of those
scripts crashed partway through and left `Migrations.kt` mutated until it was
noticed by eye. A verification step that can silently leave the tree broken is
worse than no verification step, because the next run reports on a tree nobody
meant to write.

This harness is the one place faults are injected. It:

  1. Hashes every file under `android/` and `agent/` before touching anything.
  2. Asserts each fault's anchor text occurs exactly once, so a fault that has
     drifted out of sync with the code fails loudly instead of passing as
     "nothing to do".
  3. Runs the named check and asserts the expected finding appears AND that the
     check exits non-zero.
  4. Restores from the in-memory snapshot in a `finally`, then re-runs the check
     and asserts it is clean again.
  5. Re-hashes the tree and fails if anything does not match the hash taken in
     step 1 -- including files this harness never intended to touch.

Step 5 is the point of the whole exercise. Without it, "restored" is a claim
about the code path that ran, and the failure mode being guarded against is
exactly the code path that did not.

Usage
-----
    python3 android/checks/faulttest.py            # every fault
    python3 android/checks/faulttest.py roomcheck  # faults for one check
"""

from __future__ import annotations

import hashlib
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SRC = os.path.join(ROOT, "android/app/src/main/java/com/medlenx/lab")
DAOS = "android/app/src/main/java/com/medlenx/lab/data/local/Daos.kt"
MIGR = "android/app/src/main/java/com/medlenx/lab/data/local/Migrations.kt"
FILTERS = "android/app/src/main/java/com/medlenx/lab/data/local/Filters.kt"
SCAN_REPO = "android/app/src/main/java/com/medlenx/lab/data/repo/ScanRepository.kt"
ANALYTICS = "android/app/src/main/java/com/medlenx/lab/ui/screens/analytics/AnalyticsScreen.kt"
HUB_SCREEN = "android/app/src/main/java/com/medlenx/lab/ui/screens/hub/HubScreen.kt"
MEDLEN_VL = "android/app/src/main/java/com/medlenx/lab/data/remote/MedLenXVlClient.kt"
DOCTOR_IDENTITY = "android/app/src/main/java/com/medlenx/lab/data/local/DoctorIdentity.kt"
SCAN_SCREEN = "android/app/src/main/java/com/medlenx/lab/ui/screens/scan/ScanScreen.kt"

# The join every query appending RX_FILTER_SQL must carry.
JOIN = '            "LEFT JOIN doctors d ON p.doctor_id = d.id " +\n'

# (name, check, file, old, new, expected fragment in the check's output)
FAULTS: list[tuple[str, str, str, str, str, str]] = [
    # ── imports.py ───────────────────────────────────────────────────────────
    (
        "a project type used without its import",
        "imports.py", SCAN_REPO,
        "import com.medlenx.lab.data.local.DoctorDao\n",
        "",
        "DoctorDao",
    ),
    (
        "a doc block whose declaration was pushed away",
        "imports.py", DOCTOR_IDENTITY,
        "fun displayName(name: String?): String =",
        "/** A doc that describes nothing. */\n"
        "fun displayName(name: String?): String =",
        "ORPHANED KDoc",
    ),
    (
        "a theme token that does not exist",
        "imports.py", HUB_SCREEN,
        "Mlx.Accent100",
        "MlxShape.Medium2",
        "UNKNOWN THEME TOKEN",
    ),
    (
        "an icon used without its import",
        "imports.py", HUB_SCREEN,
        "Mlx.Accent100",
        "Icons.Filled.Sailing2",
        "ICON",
    ),
    (
        "the same member declared twice",
        "imports.py", DAOS,
        "    @Query(\"SELECT COUNT(*) FROM doctors\")\n    suspend fun count(): Int",
        "    @Query(\"SELECT COUNT(*) FROM doctors\")\n    suspend fun count(): Int\n\n"
        "    @Query(\"SELECT COUNT(*) FROM doctors\")\n    suspend fun count(): Int",
        "DUPLICATE",
    ),
    # ── roomcheck.py ─────────────────────────────────────────────────────────
    (
        "a bare column that exists on two joined tables",
        "roomcheck.py", DAOS,
        """        "SELECT COUNT(DISTINCT p.id) FROM prescriptions p " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE p.created_at >= :from AND p.created_at < :to" + RX_FILTER_SQL""",
        """        "SELECT COUNT(DISTINCT id) FROM prescriptions p " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE p.created_at >= :from AND p.created_at < :to" + RX_FILTER_SQL""",
        "is not qualified",
    ),
    (
        "a query using RX_FILTER_SQL with no doctors join",
        "roomcheck.py", DAOS,
        '            "LEFT JOIN doctors d ON p.doctor_id = d.id " +\n'
        '            "WHERE p.created_at >= :since AND IFNULL(p.doctor_name, '
        "''" ') != ' + "''" + '" + RX_FILTER_SQL',
        '"WHERE p.created_at >= :since AND IFNULL(p.doctor_name, '
        "''" ') != ' + "''" + '" + RX_FILTER_SQL',
        "declares no table",
    ),
    (
        "a qualifier pointing at the wrong table",
        "roomcheck.py", DAOS,
        "        SELECT IFNULL(d.specialty, '') AS specialty,\n"
        "               sm.generic AS generic,\n"
        "               COUNT(*) AS count",
        "        SELECT IFNULL(d.rx_no, '') AS specialty,\n"
        "               sm.generic AS generic,\n"
        "               COUNT(*) AS count",
        "is not on table 'doctors'",
    ),
    (
        "a column that is on no table at all, written bare",
        "roomcheck.py", DAOS,
        """            "WHERE p.created_at >= :since AND IFNULL(p.doctor_name, '') != ''" + RX_FILTER_SQL""",
        """            "WHERE p.created_at >= :since AND IFNULL(doctor_nam, '') != ''" + RX_FILTER_SQL""",
        "not on [",
    ),
    (
        "a declared parameter the SQL never binds",
        "roomcheck.py", DAOS,
        """        since: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): TopBrandRow?""",
        """        since: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
        neverBound: String?,
    ): TopBrandRow?""",
        "never binds :neverBound",
    ),
    (
        "an inlined filter fragment with one clause changed",
        "roomcheck.py", DAOS,
        """        WHERE sm.generic != '' AND IFNULL(d.specialty, '') != ''
          AND (:district IS NULL OR :district = '' OR p.district = :district)""",
        """        WHERE sm.generic != '' AND IFNULL(d.specialty, '') != ''
          AND (:district IS NULL OR :district = '' OR p.upazila = :district)""",
        # The reported `found:` line has to carry the drift itself, not just a count.
        "p.upazila = :district",
    ),
    (
        "an inlined filter fragment that lost its last clause",
        "roomcheck.py", DAOS,
        "          AND (:source IS NULL OR :source = '' OR p.prescription_source = :source)\n"
        "        GROUP BY sm.brand_name, sm.company_name",
        "        GROUP BY sm.brand_name, sm.company_name",
        "filter fragment matches no known variant",
    ),
    # ── migrationcheck.py ────────────────────────────────────────────────────
    (
        "an ALTER dropped from the chain",
        "migrationcheck.py", MIGR,
        '                "ALTER TABLE `doctors` ADD COLUMN `territory` TEXT NOT NULL '
        """DEFAULT ''\",""",
        "",
        "never creates",
    ),
    (
        "an ALTER with the wrong affinity",
        "migrationcheck.py", MIGR,
        "ADD COLUMN `territory` TEXT NOT NULL DEFAULT ''",
        "ADD COLUMN `territory` INTEGER NOT NULL DEFAULT ''",
        "entity expects TEXT",
    ),
    (
        "an ALTER that drops NOT NULL",
        "migrationcheck.py", MIGR,
        "ADD COLUMN `territory` TEXT NOT NULL DEFAULT ''",
        "ADD COLUMN `territory` TEXT DEFAULT ''",
        "should be NOT NULL",
    ),
    (
        "an ALTER adding NOT NULL with no DEFAULT",
        "migrationcheck.py", MIGR,
        "ADD COLUMN `territory` TEXT NOT NULL DEFAULT ''",
        "ADD COLUMN `territory` TEXT NOT NULL",
        "no DEFAULT",
    ),
    (
        "a column created that no entity declares",
        "migrationcheck.py", MIGR,
        "`upazila` TEXT NOT NULL, ",
        "`upazila` TEXT NOT NULL, `stray` TEXT NOT NULL, ",
        "is not on the entity",
    ),
    (
        "an index Room would name differently",
        "migrationcheck.py", MIGR,
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_doctors_identity_key`",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_doctors_identitykey`",
        "Room would name it",
    ),
    (
        "a UNIQUE index declared non-unique",
        "migrationcheck.py", MIGR,
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_doctors_identity_key`",
        "CREATE INDEX IF NOT EXISTS `index_doctors_identity_key`",
        "should be UNIQUE",
    ),
    # ── deadparams.py ────────────────────────────────────────────────────────
    (
        # The fault is the one from this project's own history: a control's
        # callback declared, typed, defaulted to `{}`, and never passed. Here the
        # wiring is deleted from the screen instead of never written, which is the
        # same fact about the tree.
        "a callback that no call site supplies",
        "deadparams.py", SCAN_SCREEN,
        "            onMarkWon = vm::markConversionWon,\n",
        "",
        "onMarkWon",
    ),
]


def tree_hashes() -> dict[str, str]:
    """sha256 of every file under android/ and agent/, keyed by relative path."""
    out: dict[str, str] = {}
    for prefix in ("android", "agent"):
        for dirpath, dirnames, files in os.walk(os.path.join(ROOT, prefix)):
            dirnames[:] = [d for d in dirnames if d != "__pycache__"]
            for fn in files:
                path = os.path.join(dirpath, fn)
                rel = os.path.relpath(path, ROOT)
                with open(path, "rb") as fh:
                    out[rel] = hashlib.sha256(fh.read()).hexdigest()
    return out


def run_check(check: str) -> tuple[int, str]:
    path = os.path.join(HERE, check)
    if not os.path.exists(path):
        path = os.path.join(ROOT, "agent", check)
    proc = subprocess.run(
        [sys.executable, path], capture_output=True, text=True, cwd=ROOT
    )
    return proc.returncode, proc.stdout + proc.stderr


def main() -> int:
    wanted = sys.argv[1] if len(sys.argv) > 1 else ""
    chosen = [f for f in FAULTS if not wanted or f[1].startswith(wanted)]
    if not chosen:
        print(f"faulttest: no faults match {wanted!r}", file=sys.stderr)
        return 1

    before = tree_hashes()
    failures: list[str] = []
    print("=" * 72)
    print(f"FAULT INJECTION - {len(chosen)} fault(s)")
    print("=" * 72)

    for name, check, rel, old, new, expected in chosen:
        path = os.path.join(ROOT, rel)
        original = open(path, encoding="utf-8").read()

        if original.count(old) != 1:
            # Not a pass: a fault whose anchor no longer matches is a fault that
            # silently stopped testing anything.
            failures.append(
                f"{name}: anchor appears {original.count(old)}x in {rel}, expected 1 "
                f"-- this fault no longer tests anything"
            )
            print(f"  SKIP  {name}\n          anchor count {original.count(old)}")
            continue

        try:
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(original.replace(old, new, 1))
            code, output = run_check(check)
            fired = expected.lower() in output.lower()
            nonzero = code != 0
            status = "FIRED" if (fired and nonzero) else "MISSED"
            print(f"  {status}  {name}   [{check}]")
            if not fired:
                failures.append(f"{name}: {check} did not report {expected!r}")
            elif not nonzero:
                failures.append(f"{name}: {check} reported it but exited 0")
        finally:
            # Even a KeyboardInterrupt lands here. The file goes back before the
            # next fault is attempted, not at the end of the run.
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(original)

        # And prove the restore took, rather than trusting the write.
        code, output = run_check(check)
        if code != 0:
            failures.append(f"{name}: {check} still failing after restore -- {output.strip()[:160]}")

    after = tree_hashes()
    changed = sorted(k for k in set(before) | set(after) if before.get(k) != after.get(k))
    print()
    if changed:
        # The guard the hand-rolled scripts did not have.
        failures.append(
            "the tree does not match its pre-run hash for: " + ", ".join(changed)
        )
        for k in changed:
            print(f"  UNRESTORED  {k}")

    if failures:
        print(f"\n  {len(failures)} PROBLEM(S):")
        for f in failures:
            print(f"    - {f}")
        print()
        return 1

    print(f"  all {len(chosen)} fault(s) fired, and the tree is byte-identical to before")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
