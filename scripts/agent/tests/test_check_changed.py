from __future__ import annotations

from pathlib import Path

from conftest import claude_payload, neutral_payload, run_script


def post(repo: Path, name: str) -> dict:
    return claude_payload("Edit", {"file_path": str(repo / name)}, repo, event="PostToolUse")


def env(tmp_path: Path, **extra: str) -> dict:
    return {"KUOTAKIT_FAKE_LOG": str(tmp_path / "gradle.log"), **extra}


def calls(tmp_path: Path) -> list[str]:
    log = tmp_path / "gradle.log"
    return log.read_text().splitlines() if log.exists() else []


def test_formats_a_kotlin_file_with_its_module_task(repo: Path, tmp_path: Path) -> None:
    result = run_script("check_changed.py", post(repo, "backend/src/main/kotlin/App.kt"), env(tmp_path))
    assert result.returncode == 0, result.stderr
    assert calls(tmp_path) == [":backend:ktlintFormat --console=plain -q"]


def test_ktlint_violations_that_cannot_be_fixed_go_back_to_the_agent(repo: Path, tmp_path: Path) -> None:
    result = run_script("check_changed.py", post(repo, "contracts/src/commonTest/kotlin/BarTest.kt"), env(tmp_path, KUOTAKIT_FAKE_FAIL="1"))
    assert result.returncode == 2
    assert "cannot fix by itself" in result.stderr
    assert "FAILED: boom" in result.stderr


def test_files_outside_a_gradle_module_are_ignored(repo: Path, tmp_path: Path) -> None:
    run_script("check_changed.py", post(repo, "build.gradle.kts"), env(tmp_path))
    run_script("check_changed.py", post(repo, "docs/readme.md"), env(tmp_path))
    assert calls(tmp_path) == []


def test_python_syntax_errors_are_reported(repo: Path, tmp_path: Path) -> None:
    (repo / "scripts/agent/broken.py").write_text("def oops(:\n")
    (repo / "scripts/agent/fine.py").write_text("x = 1\n")
    bad = run_script("check_changed.py", post(repo, "scripts/agent/broken.py"), env(tmp_path))
    good = run_script("check_changed.py", post(repo, "scripts/agent/fine.py"), env(tmp_path))
    assert (bad.returncode, good.returncode) == (2, 0)
    assert "syntax error" in bad.stderr


def test_a_missing_file_is_ignored(repo: Path, tmp_path: Path) -> None:
    assert run_script("check_changed.py", post(repo, "backend/src/main/kotlin/Gone.kt"), env(tmp_path)).returncode == 0


def test_missing_tooling_warns_without_blocking(repo: Path, tmp_path: Path) -> None:
    (repo / "gradlew").unlink()
    result = run_script("check_changed.py", post(repo, "backend/src/main/kotlin/App.kt"), env(tmp_path))
    assert result.returncode == 1  # a non-blocking warning for the harness, never a trap for the agent
    assert "blocking to be safe" in result.stderr


def test_pre_tool_use_events_are_ignored(repo: Path, tmp_path: Path) -> None:
    pre = claude_payload("Edit", {"file_path": str(repo / "backend/src/main/kotlin/App.kt")}, repo)
    assert run_script("check_changed.py", pre, env(tmp_path)).returncode == 0
    assert calls(tmp_path) == []


def test_neutral_format(repo: Path, tmp_path: Path) -> None:
    payload = neutral_payload("post_tool_use", repo, tool="write", paths=["backend/src/main/kotlin/App.kt"])
    assert run_script("check_changed.py", payload, env(tmp_path)).returncode == 0
    assert calls(tmp_path) == [":backend:ktlintFormat --console=plain -q"]
