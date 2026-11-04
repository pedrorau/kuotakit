from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest
from check_tests import MAX_BLOCKS, changed_files, plan
from conftest import claude_payload, git, neutral_payload, run_script


def stop(repo: Path) -> dict:
    return claude_payload("", {}, repo, event="Stop") | {"stop_hook_active": False}


def env(tmp_path: Path, **extra: str) -> dict:
    return {"KUOTAKIT_FAKE_LOG": str(tmp_path / "gradle.log"), "KUOTAKIT_STATE_DIR": str(tmp_path), **extra}


def calls(tmp_path: Path) -> list[str]:
    log = tmp_path / "gradle.log"
    return log.read_text().splitlines() if log.exists() else []


def output(result: subprocess.CompletedProcess[str]) -> dict:
    return json.loads(result.stdout) if result.stdout.strip() else {}


def blocked(result: subprocess.CompletedProcess[str]) -> bool:
    """Claude Code blocks a Stop with a `decision: "block"` JSON; the exit code stays 0."""
    return result.returncode == 0 and output(result).get("decision") == "block"


def test_a_clean_tree_runs_nothing(repo: Path, tmp_path: Path) -> None:
    result = run_script("check_tests.py", stop(repo), env(tmp_path))
    assert (result.returncode, result.stdout) == (0, "")
    assert calls(tmp_path) == []


def test_docs_only_changes_run_nothing(repo: Path, tmp_path: Path) -> None:
    (repo / "docs/readme.md").write_text("# changed\n")
    assert run_script("check_tests.py", stop(repo), env(tmp_path)).stdout == ""
    assert calls(tmp_path) == []


def test_changed_module_runs_its_checks_and_passes(repo: Path, tmp_path: Path) -> None:
    (repo / "backend/src/main/kotlin/App.kt").write_text("fun main() { println() }\n")
    result = run_script("check_tests.py", stop(repo), env(tmp_path))
    assert (result.returncode, result.stdout) == (0, "")
    assert calls(tmp_path) == [":backend:check --console=plain"]


def test_only_touched_modules_are_checked(repo: Path, tmp_path: Path) -> None:
    (repo / "backend/src/main/kotlin/App.kt").write_text("// 1\n")
    (repo / "contracts/src/commonTest/kotlin/NewTest.kt").write_text("class NewTest\n")  # untracked counts too
    run_script("check_tests.py", stop(repo), env(tmp_path))
    assert calls(tmp_path) == [":backend:check --console=plain", ":contracts:check --console=plain"]


def test_nested_modules_are_found(repo: Path, tmp_path: Path) -> None:
    (repo / "sdk/core/src").mkdir(parents=True)
    (repo / "sdk/core/build.gradle.kts").write_text("// module")
    (repo / "sdk/core/src/Core.kt").write_text("class Core\n")
    run_script("check_tests.py", stop(repo), env(tmp_path))
    assert calls(tmp_path) == [":sdk:core:check --console=plain"]


def test_failing_checks_block_the_stop_and_report_the_output(repo: Path, tmp_path: Path) -> None:
    (repo / "backend/src/main/kotlin/App.kt").write_text("// broken\n")
    result = run_script("check_tests.py", stop(repo), env(tmp_path, KUOTAKIT_FAKE_FAIL="1"))
    assert blocked(result), result.stdout
    reason = output(result)["reason"]
    assert f"attempt 1 of {MAX_BLOCKS}" in reason
    assert "FAILED: boom" in reason
    assert "never weaken a test" in reason


def test_it_blocks_three_times_then_lets_the_agent_stop_with_a_warning(repo: Path, tmp_path: Path) -> None:
    (repo / "backend/src/main/kotlin/App.kt").write_text("// broken\n")
    failing = env(tmp_path, KUOTAKIT_FAKE_FAIL="1")
    assert [blocked(run_script("check_tests.py", stop(repo), failing)) for _ in range(MAX_BLOCKS)] == [True] * MAX_BLOCKS
    last = run_script("check_tests.py", stop(repo), failing)
    assert not blocked(last)
    assert "FAILING checks" in output(last)["systemMessage"]
    # the counter restarts after the escape hatch
    assert blocked(run_script("check_tests.py", stop(repo), failing))


def test_a_green_run_resets_the_counter(repo: Path, tmp_path: Path) -> None:
    (repo / "backend/src/main/kotlin/App.kt").write_text("// broken\n")
    assert blocked(run_script("check_tests.py", stop(repo), env(tmp_path, KUOTAKIT_FAKE_FAIL="1")))
    assert not blocked(run_script("check_tests.py", stop(repo), env(tmp_path)))
    again = run_script("check_tests.py", stop(repo), env(tmp_path, KUOTAKIT_FAKE_FAIL="1"))
    assert f"attempt 1 of {MAX_BLOCKS}" in output(again)["reason"]


def test_a_corrupt_state_file_does_not_break_the_guard(repo: Path, tmp_path: Path) -> None:
    (repo / "backend/src/main/kotlin/App.kt").write_text("// broken\n")
    for state in tmp_path.glob("kuotakit-stop-*.json"):
        state.unlink()
    failing = env(tmp_path, KUOTAKIT_FAKE_FAIL="1")
    run_script("check_tests.py", stop(repo), failing)
    next(tmp_path.glob("kuotakit-stop-*.json")).write_text("not json")
    assert blocked(run_script("check_tests.py", stop(repo), failing))


def test_a_missing_runner_still_blocks_through_json(repo: Path, tmp_path: Path) -> None:
    """Claude Code ignores a Stop hook that exits 2 with a 'No such file' message, so the block must be JSON."""
    (repo / "gradlew").unlink()
    (repo / "backend/src/main/kotlin/App.kt").write_text("// 2\n")
    result = run_script("check_tests.py", stop(repo), env(tmp_path))
    assert blocked(result), (result.returncode, result.stdout, result.stderr)
    assert "could not run" in output(result)["reason"]
    assert result.stderr == ""


def test_missing_git_blocks_and_counts(repo: Path, tmp_path: Path) -> None:
    path_without_git = str(tmp_path / "empty-bin")
    (tmp_path / "empty-bin").mkdir()
    result = run_script("check_tests.py", stop(repo), env(tmp_path, PATH=path_without_git))
    assert blocked(result), (result.returncode, result.stdout, result.stderr)
    assert f"attempt 1 of {MAX_BLOCKS}" in output(result)["reason"]


def test_neutral_format_blocks_with_exit_2(repo: Path, tmp_path: Path) -> None:
    (repo / "backend/src/main/kotlin/App.kt").write_text("// broken\n")
    result = run_script("check_tests.py", neutral_payload("stop", repo), env(tmp_path, KUOTAKIT_FAKE_FAIL="1"))
    assert result.returncode == 2
    assert json.loads(result.stdout)["decision"] == "deny"


def test_other_events_are_ignored(repo: Path, tmp_path: Path) -> None:
    (repo / "backend/src/main/kotlin/App.kt").write_text("// broken\n")
    payload = claude_payload("Edit", {"file_path": "x"}, repo, event="PostToolUse")
    assert run_script("check_tests.py", payload, env(tmp_path, KUOTAKIT_FAKE_FAIL="1")).returncode == 0


def test_changed_files_handles_untracked_and_renamed_files(repo: Path) -> None:
    (repo / "docs/new.md").write_text("x")
    git(repo, "mv", "docs/readme.md", "docs/renamed.md")
    files = changed_files(repo)
    assert "docs/new.md" in files
    assert "docs/renamed.md" in files
    assert "docs/readme.md" not in files


@pytest.mark.parametrize("shared", ["build.gradle.kts", "settings.gradle.kts", "gradle/libs.versions.toml", "config/detekt/detekt.yml"])
def test_shared_build_files_trigger_the_whole_check(repo: Path, shared: str) -> None:
    commands = plan([shared, "backend/src/main/kotlin/App.kt"], repo)
    assert [c[1:] for c in commands] == [["check", "--console=plain"]]


def test_changed_agent_scripts_trigger_pytest_with_the_project_venv(repo: Path) -> None:
    commands = plan(["scripts/agent/guard_paths.py"], repo)
    assert [c[1:] for c in commands] == [["-m", "pytest", "scripts/agent", "-q"]]
    (repo / ".venv/bin").mkdir(parents=True)
    (repo / ".venv/bin/python").write_text("")
    assert plan(["scripts/agent/guard_paths.py"], repo)[0][0] == str(repo / ".venv/bin/python")


def test_a_repo_without_the_secrets_block_is_still_the_one_checked(tmp_path: Path) -> None:
    """The checks look at the repository the agent works in, never at the project the scripts live in."""
    other = tmp_path / "other"
    (other / "backend").mkdir(parents=True)
    (other / "backend/build.gradle.kts").write_text("// module")
    (other / "backend/App.kt").write_text("a\n")
    (other / "gradlew").write_text("#!/bin/sh\necho \"$@\" >> \"$KUOTAKIT_FAKE_LOG\"\nexit 1\n")
    (other / "gradlew").chmod(0o755)
    git(other, "init", "-q")
    git(other, "add", "-A")
    git(other, "commit", "-q", "-m", "init")
    (other / "backend/App.kt").write_text("b\n")
    result = run_script("check_tests.py", stop(other), env(tmp_path))
    assert blocked(result), result.stdout
    assert calls(tmp_path) == [":backend:check --console=plain"]


def test_outside_a_git_repository_nothing_is_checked(tmp_path: Path) -> None:
    elsewhere = tmp_path / "elsewhere"
    elsewhere.mkdir()
    result = run_script("check_tests.py", stop(elsewhere), env(tmp_path))
    assert (result.returncode, result.stdout) == (0, "")
