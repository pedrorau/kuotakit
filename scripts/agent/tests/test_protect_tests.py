from __future__ import annotations

import json
from pathlib import Path

import pytest
from conftest import claude_payload, neutral_payload, run_script
from protect_tests import is_test_path

TEST = "backend/src/test/kotlin/FooTest.kt"


@pytest.mark.parametrize(
    "path",
    [
        "backend/src/test/kotlin/FooTest.kt",
        "contracts/src/commonTest/kotlin/BarTest.kt",
        "contracts/src/jvmTest/kotlin/Anything.kt",
        "app/src/androidTest/java/Foo.kt",
        "sdk/core/src/commonMain/kotlin/FooSpec.kt",
        "Tests/KuotaKitTests/PurchaseTests.swift",
        "scripts/agent/tests/test_x.py",
        "tools/check_test.py".replace("check_test", "x_test"),
        "bridges/react-native/src/__tests__/a.ts",
        "bridges/react-native/src/purchase.test.ts",
        "bridges/flutter/test/purchase_test.dart",
    ],
)
def test_recognizes_test_files(path: str) -> None:
    assert is_test_path(path)


@pytest.mark.parametrize(
    "path",
    ["backend/src/main/kotlin/App.kt", "contracts/src/commonMain/kotlin/HealthResponse.kt", "docs/testing.md", "README.md"],
)
def test_recognizes_production_files(path: str) -> None:
    assert not is_test_path(path)


@pytest.mark.parametrize("tool", ["Edit", "Write"])
def test_claude_asks_before_touching_an_existing_test(repo: Path, tool: str) -> None:
    result = run_script("protect_tests.py", claude_payload(tool, {"file_path": str(repo / TEST)}, repo))
    assert result.returncode == 0  # Claude Code gets its "ask" as JSON, not as an exit code
    output = json.loads(result.stdout)["hookSpecificOutput"]
    assert output["hookEventName"] == "PreToolUse"
    assert output["permissionDecision"] == "ask"
    assert "never weaken a test" in output["permissionDecisionReason"]


def test_a_new_test_file_is_allowed(repo: Path) -> None:
    new = repo / "backend/src/test/kotlin/BrandNewTest.kt"
    result = run_script("protect_tests.py", claude_payload("Write", {"file_path": str(new)}, repo))
    assert (result.returncode, result.stdout) == (0, "")


def test_production_code_is_allowed(repo: Path) -> None:
    result = run_script("protect_tests.py", claude_payload("Edit", {"file_path": str(repo / "backend/src/main/kotlin/App.kt")}, repo))
    assert (result.returncode, result.stdout) == (0, "")


@pytest.mark.parametrize(
    "command",
    [
        f"rm {TEST}",
        f"rm -f {TEST}",
        f"git rm {TEST}",
        f"mv {TEST} backend/src/test/kotlin/Renamed.kt",
        f"mv backend/src/main/kotlin/App.kt {TEST}",
        f"sed -i 's/Foo/Bar/' {TEST}",
        f"echo 'class X' > {TEST}",
        f"echo 'extra' >> {TEST}",
        f"truncate -s 0 {TEST}",
        f"cp backend/src/main/kotlin/App.kt {TEST}",
        "rm -rf backend/src/test",
        "rm backend/src/test/kotlin/*.kt",
        "cd backend && rm src/test/kotlin/FooTest.kt",
        f"git checkout -- {TEST}",
        f"git restore {TEST}",
        f"git checkout HEAD~3 -- {TEST}",
        f"perl -pi -e 's/Foo/Bar/' {TEST}",
        f"sed --in-place s/Foo/Bar/ {TEST}",
        f"awk -i inplace '{{print}}' {TEST}",
        f"cp /tmp/x {TEST}",
    ],
)
def test_shell_commands_that_change_an_existing_test_ask(repo: Path, command: str) -> None:
    result = run_script("protect_tests.py", claude_payload("Bash", {"command": command}, repo))
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout)["hookSpecificOutput"]["permissionDecision"] == "ask", command


@pytest.mark.parametrize(
    "command",
    [
        f"cat {TEST}",
        "./gradlew :backend:test",
        "rm -rf backend/build",
        "echo hi > backend/src/test/kotlin/NewTest.kt",
        "git status",
        f"cp {TEST} /tmp/copy-of-test",
        f"git checkout main -- backend/src/main/kotlin/App.kt",
    ],
)
def test_other_shell_commands_are_allowed(repo: Path, command: str) -> None:
    result = run_script("protect_tests.py", claude_payload("Bash", {"command": command}, repo))
    assert (result.returncode, result.stdout) == (0, ""), command


def test_neutral_format_uses_exit_3_for_ask(repo: Path) -> None:
    result = run_script("protect_tests.py", neutral_payload("pre_tool_use", repo, tool="delete", paths=[TEST]))
    assert result.returncode == 3
    assert json.loads(result.stdout)["decision"] == "ask"


def test_unreadable_input_fails_closed() -> None:
    assert run_script("protect_tests.py", "oops").returncode == 2
