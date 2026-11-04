from __future__ import annotations

import json
from pathlib import Path

import pytest
from conftest import claude_payload, neutral_payload, run_script

BLOCKED = [
    ".env",
    ".env.local",
    "local.properties",
    "app/google-services.json",
    "ios/GoogleService-Info.plist",
    "keys/AuthKey_ABC123.p8",
    "certs/dist.p12",
    "release.jks",
    "config/prod-service-account-key.json",
    "secrets/token.txt",
]
ALLOWED = [".env.example", ".env.sample", ".env.template", "README.md", "backend/src/main/kotlin/App.kt", "docs/api-spec.md"]


@pytest.mark.parametrize("name", BLOCKED)
@pytest.mark.parametrize("tool", ["Edit", "Write"])
def test_claude_blocks_secret_files(repo: Path, name: str, tool: str) -> None:
    result = run_script("guard_paths.py", claude_payload(tool, {"file_path": str(repo / name)}, repo))
    assert result.returncode == 2, result.stdout
    assert "secrets pattern" in result.stderr


@pytest.mark.parametrize("name", ALLOWED)
def test_claude_allows_normal_and_template_files(repo: Path, name: str) -> None:
    result = run_script("guard_paths.py", claude_payload("Write", {"file_path": str(repo / name)}, repo))
    assert result.returncode == 0, result.stderr


def test_notebook_edit_uses_notebook_path(repo: Path) -> None:
    result = run_script("guard_paths.py", claude_payload("NotebookEdit", {"notebook_path": str(repo / ".env")}, repo))
    assert result.returncode == 2


def test_relative_paths_resolve_against_cwd(repo: Path) -> None:
    result = run_script("guard_paths.py", claude_payload("Edit", {"file_path": "../.env"}, repo / "backend"))
    assert result.returncode == 2


def test_symlink_cannot_hide_a_secret(repo: Path) -> None:
    (repo / "harmless.txt").symlink_to(repo / ".env")
    result = run_script("guard_paths.py", claude_payload("Write", {"file_path": str(repo / "harmless.txt")}, repo))
    assert result.returncode == 2


def test_a_secret_outside_the_repo_is_blocked_too(repo: Path, tmp_path: Path) -> None:
    result = run_script("guard_paths.py", claude_payload("Write", {"file_path": str(tmp_path / ".env")}, repo))
    assert result.returncode == 2


def test_other_tools_and_events_are_ignored(repo: Path) -> None:
    assert run_script("guard_paths.py", claude_payload("Bash", {"command": "cat .env"}, repo)).returncode == 0
    post = claude_payload("Write", {"file_path": str(repo / ".env")}, repo, event="PostToolUse")
    assert run_script("guard_paths.py", post).returncode == 0


def test_neutral_format_denies_with_json_and_exit_2(repo: Path) -> None:
    result = run_script("guard_paths.py", neutral_payload("pre_tool_use", repo, tool="write", paths=[".env"]))
    assert result.returncode == 2
    assert json.loads(result.stdout)["decision"] == "deny"


def test_neutral_format_allows_a_template(repo: Path) -> None:
    result = run_script("guard_paths.py", neutral_payload("pre_tool_use", repo, tool="edit", paths=[".env.example"]))
    assert result.returncode == 0


def test_edit_without_a_path_fails_closed(repo: Path) -> None:
    assert run_script("guard_paths.py", claude_payload("Edit", {}, repo)).returncode == 2


@pytest.mark.parametrize("garbage", ["", "not json", "[]", '{"event": "nope"}'])
def test_unreadable_input_fails_closed(garbage: str) -> None:
    assert run_script("guard_paths.py", garbage).returncode == 2


def test_missing_secrets_block_fails_closed(repo: Path) -> None:
    (repo / ".gitignore").write_text("build/\n")
    payload = claude_payload("Write", {"file_path": str(repo / "a.txt")}, repo)
    result = run_script("guard_paths.py", payload, {"KUOTAKIT_REPO_ROOT": str(repo)})
    assert result.returncode == 2
    assert "Secrets and credentials" in result.stderr


def test_a_repo_without_the_block_falls_back_to_the_project_rules(repo: Path) -> None:
    """A session that wandered into another repo must not break every command, nor use that repo's rules."""
    (repo / ".gitignore").write_text("build/\n")
    ok = run_script("guard_paths.py", claude_payload("Write", {"file_path": str(repo / "a.txt")}, repo))
    secret = run_script("guard_paths.py", claude_payload("Write", {"file_path": str(repo / ".env")}, repo))
    assert (ok.returncode, secret.returncode) == (0, 2)


def test_a_session_outside_any_repo_still_works(tmp_path: Path) -> None:
    elsewhere = tmp_path / "elsewhere"
    elsewhere.mkdir()
    ok = run_script("guard_paths.py", claude_payload("Write", {"file_path": str(elsewhere / "notes.txt")}, elsewhere))
    secret = run_script("guard_paths.py", claude_payload("Write", {"file_path": str(elsewhere / ".env")}, elsewhere))
    assert (ok.returncode, secret.returncode) == (0, 2)


@pytest.mark.parametrize("name", [".ENV", ".Env.Local", "Local.Properties", "SECRETS/token.txt"])
def test_case_variants_are_blocked(repo: Path, name: str) -> None:
    """macOS and Windows file systems are case-insensitive, so `.ENV` is the same file as `.env`."""
    assert run_script("guard_paths.py", claude_payload("Write", {"file_path": str(repo / name)}, repo)).returncode == 2


def test_writing_into_a_secrets_directory_is_blocked(repo: Path) -> None:
    (repo / "secrets").mkdir()
    result = run_script("guard_paths.py", claude_payload("Write", {"file_path": str(repo / "secrets")}, repo))
    assert result.returncode == 2
