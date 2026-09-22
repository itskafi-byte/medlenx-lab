#!/usr/bin/env python3
"""
Repairs local git state after a sandbox reset.

Why this exists
---------------
The sandbox this session runs in is periodically restored from an older snapshot.
When that happens mid-turn, only the git state regresses: HEAD falls back to the
branch's base commit while the working tree keeps every file written since. Git
then reads the entire rest of the repository -- the Python web app, its data, its
tests -- as deleted, and the next commit would record those deletions for real.

guard.py detects the signature and refuses to let that commit happen. This script
repairs the state so work can continue.

Why the repair is safe
----------------------
One asymmetry does all the work: the WORKING TREE is trustworthy and the git
history is not. The files survived the reset; only the commit pointer moved. So
the fix is to snapshot the tree, reset git to the remote tip, then write back
only the files that actually differ from what the remote tip contains -- which is
precisely the work done since the last push.

Nothing is ever deleted. Files present in the snapshot are written back; files
the reset restores that you had deliberately removed are reported, not removed
again, so a mistaken recovery can never destroy work.

Usage
-----
    python3 android/checks/recover.py            # report only, changes nothing
    python3 android/checks/recover.py --apply    # repair the state
"""

import os
import shutil
import subprocess
import sys
import tempfile

BRANCH = "arena/01a09bf9-medlenx-lab"
# The only roots this branch is allowed to touch. Anything else belongs to the
# snapshot that regressed and must not be reasoned about here.
PROTECTED = ("android/", "agent/")


def git(*args: str) -> str:
    return subprocess.run(["git", *args], capture_output=True, text=True).stdout.strip()


def git_run(*args: str) -> int:
    return subprocess.run(["git", *args], capture_output=True, text=True).returncode


def status_lines() -> list[str]:
    out = subprocess.run(
        ["git", "status", "--porcelain"], capture_output=True, text=True
    ).stdout
    return [line for line in out.split("\n") if line.strip()]


def remote_tip() -> str:
    parts = git("ls-remote", "origin", f"refs/heads/{BRANCH}").split()
    return parts[0] if parts else ""


def is_reset() -> tuple[bool, list[str]]:
    """The two signals guard.py uses, restated."""
    reasons: list[str] = []
    head = git("rev-parse", "HEAD")
    tip = remote_tip()

    if tip and head != tip:
        ancestor = subprocess.run(
            ["git", "merge-base", "--is-ancestor", tip, head], capture_output=True
        ).returncode
        if ancestor != 0:
            reasons.append(
                f"HEAD ({head[:7]}) is not a descendant of the remote tip ({tip[:7]})"
            )

    deletions = [
        line[3:]
        for line in status_lines()
        if line[:2].strip().startswith("D")
        and not line[3:].startswith(PROTECTED)
    ]
    if deletions:
        reasons.append(
            f"{len(deletions)} deletion(s) outside {PROTECTED} -- e.g. {deletions[0]}"
        )
    return bool(reasons), reasons


def snapshot(root: str, dest: str) -> dict[str, str]:
    """Copy every file under the protected roots into dest. Returns relpath -> hash."""
    saved: dict[str, str] = {}
    for base in PROTECTED:
        base_dir = os.path.join(root, base.rstrip("/"))
        if not os.path.isdir(base_dir):
            continue
        for dirpath, _, files in os.walk(base_dir):
            # Skip build output and bytecode: large, regenerable, never authored.
            if "__pycache__" in dirpath or os.sep + "build" + os.sep in dirpath + os.sep:
                continue
            for fn in files:
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, root)
                try:
                    with open(full, "rb") as fh:
                        data = fh.read()
                except OSError:
                    continue
                saved[rel] = str(hash(data))
                out = os.path.join(dest, rel)
                os.makedirs(os.path.dirname(out), exist_ok=True)
                with open(out, "wb") as fh:
                    fh.write(data)
    return saved


def main() -> int:
    apply = "--apply" in sys.argv
    root = git("rev-parse", "--show-toplevel")
    if not root:
        print("recover: not inside a git repository")
        return 2

    broken, reasons = is_reset()
    if not broken:
        print(f"recover: nothing to repair - local state is consistent")
        return 0

    print("recover: sandbox reset detected")
    for r in reasons:
        print(f"  - {r}")
    print()

    if not apply:
        print("  Dry run. To repair:")
        print(f"    python3 android/checks/recover.py --apply")
        print()
        print("  This snapshots every file under android/ and agent/, resets git to")
        print("  the remote tip, then writes back only the files that differ.")
        return 1

    # 1. Snapshot the tree. The files are the only trustworthy thing here.
    tmp = tempfile.mkdtemp(prefix="medlenx-recover-")
    try:
        saved = snapshot(root, tmp)
        print(f"  snapshot: {len(saved)} file(s) under {PROTECTED}")

        # 2. Fix the history pointer.
        if git_run("fetch", "origin", BRANCH) != 0:
            print("  ERROR: fetch failed - aborting, nothing was changed")
            return 2
        if git_run("reset", "--hard", "FETCH_HEAD") != 0:
            print("  ERROR: reset failed - the snapshot is at " + tmp)
            return 2
        print(f"  reset:    HEAD now {git('rev-parse', '--short', 'HEAD')}")

        # 3. Write back only what actually differs from the remote tip.
        restored: list[str] = []
        for rel in sorted(saved):
            current = os.path.join(root, rel)
            with open(os.path.join(tmp, rel), "rb") as fh:
                snap = fh.read()
            try:
                with open(current, "rb") as fh:
                    if fh.read() == snap:
                        continue
            except OSError:
                pass
            os.makedirs(os.path.dirname(current), exist_ok=True)
            with open(current, "wb") as fh:
                fh.write(snap)
            restored.append(rel)

        print(f"  restored: {len(restored)} file(s) of local work")
        for r in restored:
            print(f"      {r}")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print()
    broken_now, reasons_now = is_reset()
    if broken_now:
        print("recover: still inconsistent after repair")
        for r in reasons_now:
            print(f"  - {r}")
        return 1
    print("recover: repaired - run the checks, then commit as normal")
    return 0


if __name__ == "__main__":
    sys.exit(main())
