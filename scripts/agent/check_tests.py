#!/usr/bin/env python3
"""Stop check: a task cannot be closed while the checks of the code it changed fail (ADR 0011).

Looks at the uncommitted changes, runs `./gradlew :<module>:check` (tests, ktlint, detekt) for each Gradle module
touched, `./gradlew check` when shared build files changed, and pytest when the agent scripts changed. Docs-only
changes run nothing. If something fails (or cannot run) the stop is blocked and the output goes back to the agent.

To avoid an endless loop, it blocks up to MAX_BLOCKS times in a row per session and then lets the agent stop with
a loud warning: CI and the branch ruleset are the final gate. All commands share one time budget that is smaller
than the hook timeout, so the counter is always written before the harness could kill this script.
"""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from _lib import STOP, Decision, Event, allow, deny, git_root, gradle_module, run

MAX_BLOCKS = 3
TIME_BUDGET_SECONDS = 800  # the Stop hook in .claude/settings.json has a 900 s timeout
TAIL_LINES = 60
SHARED_BUILD_FILES = ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", ".editorconfig")
SHARED_BUILD_DIRS = ("gradle/", "config/")


def changed_files(root: Path) -> list[str]:
    out = subprocess.run(
        ["git", "status", "--porcelain=v1", "-z", "--untracked-files=all"],
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
        timeout=30,
    ).stdout
    entries, files = out.split("\0"), []
    i = 0
    while i < len(entries):
        entry = entries[i]
        if len(entry) > 3:
            files.append(entry[3:])
            if entry[0] in "RC":  # a rename lists the old path as the next entry
                i += 1
        i += 1
    return files


def python_for_tests(root: Path) -> str:
    """The project's virtualenv when there is one: the interpreter that runs the hook may not have pytest."""
    venv = root / ".venv" / "bin" / "python"
    return str(venv) if venv.is_file() else sys.executable


def plan(files: list[str], root: Path) -> list[list[str]]:
    """The commands to run for these changed files."""
    commands: list[list[str]] = []
    gradle = str(root / "gradlew")
    if any(f in SHARED_BUILD_FILES or f.startswith(SHARED_BUILD_DIRS) for f in files):
        commands.append([gradle, "check", "--console=plain"])
    else:
        modules = sorted({m for f in files if (m := gradle_module(f, root))})
        commands += [[gradle, f"{m}:check", "--console=plain"] for m in modules]
    if any(f.startswith("scripts/agent/") and f.endswith(".py") for f in files):
        commands.append([python_for_tests(root), "-m", "pytest", "scripts/agent", "-q"])
    return commands


def _state_file(event: Event, root: Path) -> Path:
    key = hashlib.sha256((event.session_id or str(root)).encode()).hexdigest()[:16]
    return Path(os.environ.get("KUOTAKIT_STATE_DIR") or tempfile.gettempdir()) / f"kuotakit-stop-{key}.json"


def _blocked_so_far(state: Path) -> int:
    try:
        return int(json.loads(state.read_text())["blocked"])
    except (OSError, ValueError, KeyError, TypeError):
        return 0


def _failures(commands: list[list[str]], root: Path) -> list[str]:
    failures = []
    started = time.monotonic()
    for cmd in commands:
        label = " ".join(Path(cmd[0]).name if i == 0 else part for i, part in enumerate(cmd))
        remaining = TIME_BUDGET_SECONDS - (time.monotonic() - started)
        if remaining < 5:
            failures.append(f"$ {label}\nnot run: the {TIME_BUDGET_SECONDS} s time budget is used up")
            continue
        try:
            result = subprocess.run(cmd, cwd=root, capture_output=True, text=True, timeout=remaining, check=False)
        except subprocess.TimeoutExpired:
            failures.append(f"$ {label}\ntimed out after the {TIME_BUDGET_SECONDS} s time budget")
            continue
        except (OSError, subprocess.SubprocessError) as exc:
            failures.append(f"$ {label}\ncould not run: {exc}")
            continue
        if result.returncode != 0:
            tail = "\n".join((result.stdout + result.stderr).strip().splitlines()[-TAIL_LINES:])
            failures.append(f"$ {label}\n{tail}")
    return failures


def decide(event: Event) -> Decision:
    if event.kind != STOP:
        return allow()
    root: Path | None = None
    try:
        root = git_root(event.cwd)
        if root is None:  # not inside a git repository: nothing to check
            return allow()
        commands = plan(changed_files(root), root)
    except (OSError, subprocess.SubprocessError) as exc:
        failures = [f"could not list the changed files with git: {exc}"]
    else:
        failures = _failures(commands, root) if commands else []
    state = _state_file(event, root or Path(event.cwd))
    if not failures:
        state.unlink(missing_ok=True)
        return allow()
    blocked = _blocked_so_far(state) + 1
    report = "\n\n".join(failures)
    if blocked <= MAX_BLOCKS:
        state.write_text(json.dumps({"blocked": blocked}))
        return deny(
            f"Checks are failing, so this task cannot be closed yet (attempt {blocked} of {MAX_BLOCKS}). "
            f"Fix the code (never weaken a test) and try again.\n\n{report}"
        )
    state.unlink(missing_ok=True)
    warning = (
        f"WARNING: stopping with FAILING checks after {MAX_BLOCKS} blocked attempts. "
        f"Do not merge until they pass (CI will enforce it).\n\n{report}"
    )
    # Exit 0 hides stderr from Claude Code, so it gets the warning as a `systemMessage` shown to the user.
    if event.harness == "claude":
        print(json.dumps({"systemMessage": warning}))
    else:
        sys.stderr.write(warning + "\n")
    return allow()


def main() -> int:
    return run(decide)


if __name__ == "__main__":
    sys.exit(main())
