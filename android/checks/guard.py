#!/usr/bin/env python3
"""
Pre-commit sanity guard for this Android-only branch.

Why this exists
---------------
The sandbox this session runs in is periodically restored from an older
snapshot. When that happens mid-session, git HEAD can fall back to the branch's
base commit while the working tree still holds the files written since. The very
next commit then records the entire rest of the repository -- the Python web
app, its data files, its tests -- as deleted.

That happened once: a commit was created on top of the base commit with several
hundred deletions. It was caught by the push rejection and never forced, but the
guard below is what stops it from being created in the first place.

Usage
-----
    python3 android/checks/guard.py

Run it at the start of a turn (to detect a reset before doing work) and again
immediately before committing. Exit code 0 means the tree is safe to commit.
"""

import subprocess
import sys

BRANCH = "arena/01a09bf9-medlenx-lab"
# This branch is contractually Android-only.
ALLOWED_PREFIX = "android/"


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], capture_output=True, text=True
    ).stdout.strip()


def git_status_lines() -> list[str]:
    """`git status --porcelain` lines with leading whitespace intact.

    Do NOT strip() the whole output: porcelain puts two status columns before the
    path, so a worktree-only change starts with a space (' M path'). Stripping the
    result shifts the first line one character left and the path is then sliced
    wrong -- which turned 'android/...' into 'ndroid/...' and falsely reported a
    change outside android/.
    """
    out = subprocess.run(
        ["git", "status", "--porcelain"], capture_output=True, text=True
    ).stdout
    return [line for line in out.split("\n") if line.strip()]


def main() -> int:
    problems: list[str] = []

    branch = git("branch", "--show-current")
    if branch != BRANCH:
        problems.append(f"on branch {branch!r}, expected {BRANCH!r}")

    # 1. History must be a descendant of the remote tip.
    remote = git("ls-remote", "origin", f"refs/heads/{BRANCH}").split()
    remote_tip = remote[0] if remote else ""
    head = git("rev-parse", "HEAD")

    if remote_tip and head != remote_tip:
        ancestor = subprocess.run(
            ["git", "merge-base", "--is-ancestor", remote_tip, head],
            capture_output=True,
        ).returncode
        if ancestor != 0:
            problems.append(
                f"HEAD ({head[:7]}) is NOT a descendant of the remote tip "
                f"({remote_tip[:7]}).\n"
                f"        This is the sandbox-reset signature: local history fell "
                f"back to the\n        branch base. Recover before committing:\n"
                f"          git fetch origin {BRANCH} && git reset --hard FETCH_HEAD\n"
                f"        then re-apply this turn's edits."
            )

    # 2. Nothing outside android/ may be deleted, staged or not.
    status_lines = git_status_lines()
    deletions = [
        line
        for line in status_lines
        if line[:2].strip().startswith("D") and not line[3:].startswith(ALLOWED_PREFIX)
    ]
    if deletions:
        problems.append(
            f"{len(deletions)} deletion(s) outside {ALLOWED_PREFIX} -- e.g. "
            f"{deletions[0][3:]}\n"
            f"        This branch may only add or modify Android app files."
        )

    # 3. Everything staged must live under android/ too.
    # Column 0 is the index status: a space or '?' means nothing is staged.
    staged = [
        line for line in status_lines
        if line[0] != " " and line[0] != "?" and not line[3:].startswith(ALLOWED_PREFIX)
    ]
    if staged:
        problems.append(
            f"{len(staged)} staged change(s) outside {ALLOWED_PREFIX} -- e.g. "
            f"{staged[0][3:]}"
        )

    if problems:
        print("guard: REFUSING - the working tree is not safe to commit\n")
        for p in problems:
            print(f"  - {p}")
        return 1

    print(f"guard: ok - on {branch}, HEAD {head[:7]}, all changes under {ALLOWED_PREFIX}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
