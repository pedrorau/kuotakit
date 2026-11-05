from __future__ import annotations

from pathlib import Path

import pytest
from check_license_headers import has_header
from conftest import HEADER, add, git, run_script


def check(repo: Path, *args: str):
    return run_script("check_license_headers.py", *args, cwd=repo)


def test_a_file_with_the_header_passes(repo: Path) -> None:
    add(repo, "backend/src/main/kotlin/App.kt", HEADER + "\nfun main() {}\n")
    result = check(repo)
    assert result.returncode == 0, result.stderr
    assert "1 source files" in result.stdout


def test_a_file_without_the_header_fails_and_is_named(repo: Path) -> None:
    add(repo, "backend/src/main/kotlin/App.kt", "fun main() {}\n")
    result = check(repo)
    assert result.returncode == 1
    assert "backend/src/main/kotlin/App.kt" in result.stderr
    assert "Licensed under the Apache License" in result.stderr  # the template to paste


@pytest.mark.parametrize(
    "name", ["A.kt", "A.java", "A.swift", "a.dart", "a.ts", "a.tsx", "a.js", "a.mjs", "a.cjs", "a.mts", "a.cts", "A.m", "A.mm", "A.h"]
)
def test_every_application_language_is_covered(repo: Path, name: str) -> None:
    add(repo, f"src/{name}", "no header here\n")
    assert check(repo).returncode == 1


@pytest.mark.parametrize(
    "name",
    ["build.gradle.kts", "scripts/agent/guard.py", "README.md", "docs/a.md", "config.json", "x.yml"],
)
def test_other_files_are_out_of_scope(repo: Path, name: str) -> None:
    add(repo, name, "no header here\n")
    assert check(repo, "--min-files", "0").returncode == 0


@pytest.mark.parametrize(
    "name",
    ["app/node_modules/x/index.js", "ios/Pods/Y.swift", "lib/model.g.dart", "lib/model.freezed.dart", "types/a.d.ts"],
)
def test_generated_and_vendored_files_are_skipped(repo: Path, name: str) -> None:
    add(repo, name, "no header here\n")
    assert check(repo, "--min-files", "0").returncode == 0


@pytest.mark.parametrize("name", ["src/main/kotlin/dev/x/build/A.kt", "src/main/kotlin/dev/x/generated/B.kt"])
def test_a_package_called_build_or_generated_is_still_checked(repo: Path, name: str) -> None:
    add(repo, name, "no header here\n")
    assert check(repo).returncode == 1


def test_checking_zero_files_fails_instead_of_passing_silently(repo: Path) -> None:
    add(repo, "README.md", "x\n")
    result = check(repo)
    assert result.returncode == 1
    assert "Only 0 source files" in result.stderr


def test_symlinks_and_deleted_files_do_not_crash(repo: Path) -> None:
    add(repo, "Real.kt", HEADER + "\nclass Real\n")
    (repo / "dir").mkdir()
    (repo / "Link.kt").symlink_to(repo / "dir")
    add(repo, "Gone.kt", "x\n")
    (repo / "Gone.kt").unlink()
    git(repo, "add", "Link.kt")
    result = check(repo)
    assert result.returncode == 0, result.stderr
    assert "Traceback" not in result.stderr


def test_outside_a_git_repository_is_a_clear_error(tmp_path: Path) -> None:
    result = run_script("check_license_headers.py", cwd=tmp_path)
    assert result.returncode == 2
    assert "not inside a git repository" in result.stderr


def test_untracked_files_are_ignored(repo: Path) -> None:
    add(repo, "Local.kt", "no header\n", track=False)
    result = check(repo, "--min-files", "0")
    assert result.returncode == 0
    assert "0 source files checked" in result.stdout


def test_the_header_must_be_near_the_top(repo: Path) -> None:
    add(repo, "Late.kt", "// filler\n" * 40 + HEADER)
    assert check(repo).returncode == 1


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        (HEADER, True),
        (HEADER.replace("2026", "2024-2026"), True),
        (HEADER.replace("Copyright 2026", "Copyright (c) 2026"), True),
        (HEADER.replace("Pedro Rau", "Someone Else"), False),
        (HEADER.replace("Apache License, Version 2.0", "MIT License"), False),
        ("", False),
    ],
)
def test_header_detection(text: str, expected: bool) -> None:
    assert has_header(text) is expected


def test_it_works_from_a_subdirectory(repo: Path) -> None:
    add(repo, "a/B.kt", "no header\n")
    assert run_script("check_license_headers.py", cwd=repo / "a").returncode == 1


def test_this_repository_passes() -> None:
    """The real project must satisfy its own rule."""
    result = run_script("check_license_headers.py", cwd=Path(__file__).resolve().parents[3])
    assert result.returncode == 0, result.stderr
