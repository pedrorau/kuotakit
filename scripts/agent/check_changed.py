#!/usr/bin/env python3
"""PostToolUse check: format and lint the file the agent just changed.

Kotlin (`.kt`, `.kts` inside a Gradle module): `./gradlew :<module>:ktlintFormat`, which reformats the whole module;
violations ktlint cannot fix by itself are returned to the agent. Root-level `.kts` files are not covered (CI is). Python: a syntax check. Anything else is ignored. When the tooling is not
available the script only warns (exit 1) and never blocks the agent.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

from _lib import PATH_TOOLS, POST, Decision, Event, allow, deny, git_root, gradle_module, rel, resolve, run

GRADLE_TIMEOUT_SECONDS = 280
TAIL_LINES = 40


def _tail(text: str) -> str:
    return "\n".join(text.strip().splitlines()[-TAIL_LINES:])


def _run(cmd: list[str], cwd: Path, timeout: int) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout, check=False)


def decide(event: Event) -> Decision:
    if event.kind != POST or event.tool not in PATH_TOOLS or not event.paths:
        return allow()
    root = git_root(event.cwd)
    path = resolve(event.paths[0], event.cwd)
    if root is None or not path.is_file():
        return allow()
    name = rel(path, root)
    if path.suffix in {".kt", ".kts"}:
        module = gradle_module(name, root)
        if module is None:
            return allow()
        result = _run([str(root / "gradlew"), f"{module}:ktlintFormat", "--console=plain", "-q"], root, GRADLE_TIMEOUT_SECONDS)
        if result.returncode != 0:
            return deny(f"ktlint found violations in {name} that it cannot fix by itself:\n{_tail(result.stdout + result.stderr)}")
    elif path.suffix == ".py":
        result = _run([sys.executable, "-m", "py_compile", str(path)], root, 30)
        if result.returncode != 0:
            return deny(f"Python syntax error in {name}:\n{_tail(result.stderr)}")
    return allow()


def main() -> int:
    # A broken toolchain must not trap the agent: infrastructure errors exit 1 (a warning), only real findings block.
    return run(decide, error_exit=1)


if __name__ == "__main__":
    sys.exit(main())
