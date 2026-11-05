from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest
from check_tests import MAX_BLOCKS, changed_files, plan
from conftest import REAL_ROOT, claude_payload, git, neutral_payload, run_script


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


@pytest.mark.parametrize("script", ["scripts/agent/guard_paths.py", "scripts/ci/check_license_headers.py"])
def test_changed_scripts_trigger_pytest_with_the_project_venv(repo: Path, script: str) -> None:
    commands = plan([script], repo)
    assert [c[1:] for c in commands] == [["-m", "pytest", "scripts", "-q"]]
    (repo / ".venv/bin").mkdir(parents=True)
    (repo / ".venv/bin/python").write_text("")
    assert plan([script], repo)[0][0] == str(repo / ".venv/bin/python")


def test_kotlin_changes_also_run_the_architecture_tests(repo: Path) -> None:
    (repo / "architecture-tests").mkdir()
    (repo / "architecture-tests/build.gradle.kts").write_text("// module")
    commands = plan(["backend/src/main/kotlin/App.kt"], repo)
    assert [c[1:] for c in commands] == [[":backend:check", "--console=plain"], [":architecture-tests:test", "--console=plain"]]
    only_arch = plan(["architecture-tests/src/test/kotlin/RulesTest.kt"], repo)
    assert [c[1] for c in only_arch if c[0].endswith("gradlew")] == [":architecture-tests:check"]  # not run twice


def test_source_changes_run_the_license_header_check(repo: Path) -> None:
    (repo / "scripts/ci").mkdir(parents=True)
    (repo / "scripts/ci/check_license_headers.py").write_text("")
    commands = plan(["docs/readme.md", "backend/src/main/kotlin/App.kt"], repo)
    assert str(repo / "scripts/ci/check_license_headers.py") in [c[1] for c in commands if len(c) == 2]
    assert not [c for c in plan(["docs/readme.md"], repo) if len(c) == 2]


def test_license_suffixes_match_the_license_check() -> None:
    sys.path.insert(0, str(REAL_ROOT / "scripts" / "ci"))
    from check_license_headers import SOURCE_SUFFIXES
    from check_tests import LICENSE_SUFFIXES

    assert set(LICENSE_SUFFIXES) == SOURCE_SUFFIXES
