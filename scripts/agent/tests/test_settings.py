"""`.claude/settings.json` is the glue between Claude Code and the scripts; if it is wrong nothing is guarded and
nothing complains, so its shape is tested."""

from __future__ import annotations

import json
import re

from conftest import REAL_ROOT

SETTINGS = REAL_ROOT / ".claude" / "settings.json"
EXPECTED = {
    ("PreToolUse", "Edit|Write|MultiEdit|NotebookEdit"): {"guard_paths.py", "protect_tests.py"},
    ("PreToolUse", "Bash"): {"guard_commands.py", "protect_tests.py"},
    ("PostToolUse", "Edit|Write|MultiEdit|NotebookEdit"): {"check_changed.py"},
    ("Stop", None): {"check_tests.py"},
}


def handlers() -> list[tuple[str, str | None, dict]]:
    hooks = json.loads(SETTINGS.read_text())["hooks"]
    return [(event, group.get("matcher"), h) for event, groups in hooks.items() for group in groups for h in group["hooks"]]


def test_every_expected_script_is_wired_to_the_right_event_and_matcher() -> None:
    found: dict[tuple[str, str | None], set[str]] = {}
    for event, matcher, handler in handlers():
        script = re.search(r"scripts/agent/(\w+\.py)", handler["command"])
        assert script, handler
        found.setdefault((event, matcher), set()).add(script.group(1))
    assert found == EXPECTED


def test_hooks_use_shell_form_because_older_claude_code_silently_ignores_exec_form() -> None:
    """Claude Code 2.1.105 runs a shell-form hook but never runs one with `args` (and says nothing about it)."""
    for _, _, handler in handlers():
        assert handler["type"] == "command"
        assert "args" not in handler, "exec form is ignored by older Claude Code versions"
        assert handler["command"].startswith('python3 "${CLAUDE_PROJECT_DIR}"/scripts/agent/'), handler["command"]


def test_every_referenced_script_exists() -> None:
    for _, _, handler in handlers():
        script = handler["command"].split("/scripts/agent/")[1]
        assert (REAL_ROOT / "scripts" / "agent" / script).is_file(), script


def test_policy_hooks_fail_closed_where_supported() -> None:
    for event, _, handler in handlers():
        if event in {"PreToolUse", "Stop"}:
            assert handler.get("onFailure") == "block", handler
