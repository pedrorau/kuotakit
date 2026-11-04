"""Fixtures: a throwaway git repo shaped like KuotaKit, and helpers to call the scripts like a harness would."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

AGENT_DIR = Path(__file__).resolve().parents[1]
REAL_ROOT = AGENT_DIR.parents[1]
sys.path.insert(0, str(AGENT_DIR))

FAKE_GRADLEW = """#!/bin/sh
echo "$@" >> "$KUOTAKIT_FAKE_LOG"
if [ -n "$KUOTAKIT_FAKE_FAIL" ]; then echo "FAILED: boom in $*" >&2; exit 1; fi
exit 0
"""

FILES = {
    "backend/build.gradle.kts": "// module",
    "backend/src/main/kotlin/App.kt": "fun main() {}\n",
    "backend/src/test/kotlin/FooTest.kt": "class FooTest\n",
    "contracts/build.gradle.kts": "// module",
    "contracts/src/commonTest/kotlin/BarTest.kt": "class BarTest\n",
    "scripts/agent/tests/test_x.py": "def test_x():\n    pass\n",
    "docs/readme.md": "# docs\n",
    "build.gradle.kts": "// root",
}


def git(root: Path, *args: str) -> None:
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *args], cwd=root, check=True, capture_output=True)


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    root = (tmp_path / "repo").resolve()
    root.mkdir()
    git(root, "init", "-q")
    shutil.copy(REAL_ROOT / ".gitignore", root / ".gitignore")  # the real secret patterns
    for name, content in FILES.items():
        (root / name).parent.mkdir(parents=True, exist_ok=True)
        (root / name).write_text(content)
    gradlew = root / "gradlew"
    gradlew.write_text(FAKE_GRADLEW)
    gradlew.chmod(0o755)
    git(root, "add", "-A")
    git(root, "commit", "-q", "-m", "init")
    return root


def claude_payload(tool: str, tool_input: dict, cwd: Path, event: str = "PreToolUse") -> dict:
    """The JSON Claude Code sends to a hook (field names from the official hooks reference)."""
    return {
        "session_id": "s1",
        "cwd": str(cwd),
        "hook_event_name": event,
        "tool_name": tool,
        "tool_input": tool_input,
    }


def neutral_payload(event: str, cwd: Path, **fields: object) -> dict:
    return {"event": event, "cwd": str(cwd), "session_id": "s1", **fields}


def run_script(script: str, payload: dict | str, env: dict | None = None) -> subprocess.CompletedProcess[str]:
    stdin = payload if isinstance(payload, str) else json.dumps(payload)
    return subprocess.run(
        [sys.executable, str(AGENT_DIR / script)],
        input=stdin,
        capture_output=True,
        text=True,
        env={**os.environ, **(env or {})},
        timeout=120,
        check=False,
    )
