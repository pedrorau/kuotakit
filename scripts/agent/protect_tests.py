#!/usr/bin/env python3
"""PreToolUse guard: an existing test file may only be edited, overwritten or deleted with human approval.

New test files are allowed. Weakening a test to make it pass is not (ADR 0011): fix the code, or explain why
the test is wrong and let the maintainer decide. Shell commands are covered on a best-effort basis (rm, mv, cp,
sed -i, tee, truncate and redirections).
"""

from __future__ import annotations

import glob
import sys
from pathlib import Path

from _lib import (
    PATH_TOOLS,
    PRE,
    Decision,
    Event,
    allow,
    ask,
    edits_in_place,
    git_subcommand,
    glob_to_regex,
    non_options,
    rel,
    repo_root,
    resolve,
    run,
    segments,
    working_dirs,
)

# Paths are relative to the repo root, in posix form. Kotlin Multiplatform keeps tests in src/<target>Test/.
TEST_GLOBS = (
    "**/src/test/**",
    "**/src/*Test/**",
    "**/*Test.kt",
    "**/*Tests.kt",
    "**/*Spec.kt",
    "**/*Test.java",
    "**/*Test.swift",
    "**/*Tests.swift",
    "**/test_*.py",
    "**/*_test.py",
    "**/tests/**",
    "**/__tests__/**",
    "**/*.test.[jt]s",
    "**/*.test.[jt]sx",
    "**/*.spec.[jt]s",
    "**/*.spec.[jt]sx",
    "**/*_test.dart",
    "test/**",
)
_TEST_REGEXES = tuple(glob_to_regex(g) for g in TEST_GLOBS)
_MUTATING = {"rm", "mv", "tee", "truncate"}
_COPIERS = {"cp", "install"}  # only the destination (the last argument) is written


def is_test_path(path: str) -> bool:
    return any(rx.match(path) for rx in _TEST_REGEXES)


def _existing_test(raw: str, cwd: str, root: Path) -> str | None:
    """The repo-relative path if `raw` names an existing test file (or a directory holding tests)."""
    matches = glob.glob(raw if Path(raw).is_absolute() else str(Path(cwd) / raw)) if any(c in raw for c in "*?[") else [raw]
    for candidate in matches:
        path = resolve(candidate, cwd)
        if not path.exists():
            continue
        name = rel(path, root)
        if is_test_path(name) or (path.is_dir() and is_test_path(name + "/x")):
            return name
    return None


def _shell_targets(event: Event) -> list[tuple[str, str]]:
    """(path, base directory) pairs the command may change; every `cd` seen so far is a possible base."""
    targets: list[tuple[str, str]] = []
    for seg in segments(event.command):
        raw = list(seg.redirects)
        args = seg.args
        if seg.exe == "git" and git_subcommand(seg)[0] in {"rm", "mv", "checkout", "restore"}:
            raw += non_options(git_subcommand(seg)[1])  # checkout/restore overwrite the file from the index or a commit
        elif seg.exe in _MUTATING or edits_in_place(seg):
            raw += non_options(args)
        elif seg.exe in _COPIERS:
            raw += non_options(args)[-1:]
        elif seg.exe == "dd":
            raw += [a.removeprefix("of=") for a in args if a.startswith("of=")]
        targets += [(path, base) for path in raw for base in working_dirs(seg, event.cwd)]
    return targets


def decide(event: Event) -> Decision:
    if event.kind != PRE:
        return allow()
    root = repo_root(event.cwd)
    if event.tool in PATH_TOOLS:
        targets = [(path, event.cwd) for path in event.paths]
    elif event.tool == "bash":
        targets = _shell_targets(event)
    else:
        return allow()
    for raw, base in targets:
        name = _existing_test(raw, base, root)
        if name:
            return ask(
                f"'{name}' is an existing test. Editing or deleting it needs the maintainer's approval: "
                "never weaken a test to make it pass (ADR 0011). Fix the code, or explain why the test is wrong."
            )
    return allow()


def main() -> int:
    return run(decide)


if __name__ == "__main__":
    sys.exit(main())
