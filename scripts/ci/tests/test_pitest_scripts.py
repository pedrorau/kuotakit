from __future__ import annotations

from pathlib import Path

import pytest
from conftest import add, git, run_script
from pitest_summary import summarize
from pitest_targets import targets


@pytest.mark.parametrize(
    ("files", "expected"),
    [
        (["backend/src/main/kotlin/dev/acme/Foo.kt"], ["dev.acme.Foo*"]),
        (["backend/src/main/kotlin/dev/acme/Foo.kt", "backend/src/main/kotlin/dev/acme/Bar.kt"], ["dev.acme.Bar*", "dev.acme.Foo*"]),
        (["backend/src/main/kotlin/dev/acme/Foo.kt", "backend/src/main/kotlin/dev/acme/Foo.kt"], ["dev.acme.Foo*"]),
        (["backend/src/test/kotlin/dev/acme/FooTest.kt"], []),
        (["contracts/src/commonMain/kotlin/dev/acme/Model.kt"], []),
        (["backend/src/main/resources/logback.xml", "docs/readme.md", "backend/build.gradle.kts"], []),
        (["backend/src/main/kotlin/Top.kt"], ["Top*"]),
        ([], []),
        (["backend/src/main/kotlin/dev/acme/Evil\nname=x.kt"], []),
        (["backend/src/main/kotlin/dev/acme/$(touch pwned).kt"], []),
        (["backend/src/main/kotlin/dev/acme/a;b.kt", "backend/src/main/kotlin/dev/acme/Ok.kt"], ["dev.acme.Ok*"]),
    ],
)
def test_targets_from_changed_files(files: list[str], expected: list[str]) -> None:
    assert targets(files, "backend") == expected


def test_the_module_is_configurable() -> None:
    assert targets(["sdk/src/main/kotlin/dev/acme/Core.kt"], "sdk") == ["dev.acme.Core*"]


def test_targets_come_from_the_git_diff(repo: Path) -> None:
    add(repo, "README.md", "x\n")
    git(repo, "commit", "-q", "-m", "base")
    git(repo, "branch", "-M", "main")
    git(repo, "checkout", "-q", "-b", "feature")
    add(repo, "backend/src/main/kotlin/dev/acme/Foo.kt", "class Foo\n")
    add(repo, "backend/src/test/kotlin/dev/acme/FooTest.kt", "class FooTest\n")
    git(repo, "commit", "-q", "-m", "feature")
    result = run_script("pitest_targets.py", "--base", "main", cwd=repo)
    assert (result.returncode, result.stdout.strip()) == (0, "dev.acme.Foo*")


def test_no_production_change_prints_nothing(repo: Path) -> None:
    add(repo, "README.md", "x\n")
    git(repo, "commit", "-q", "-m", "base")
    git(repo, "branch", "-M", "main")
    git(repo, "checkout", "-q", "-b", "docs")
    add(repo, "docs/a.md", "x\n")
    git(repo, "commit", "-q", "-m", "docs")
    result = run_script("pitest_targets.py", "--base", "main", cwd=repo)
    assert (result.returncode, result.stdout.strip()) == (0, "")


MUTATIONS = """<mutations>
  <mutation detected='true' status='KILLED'/><mutation detected='true' status='KILLED'/>
  <mutation detected='false' status='SURVIVED'/><mutation detected='false' status='NO_COVERAGE'/>
  <mutation detected='true' status='TIMED_OUT'/><mutation detected='true' status='NON_VIABLE'/>
  <mutation detected='true' status='RUN_ERROR'/>
</mutations>"""


def test_summary_counts_and_scores(tmp_path: Path) -> None:
    xml = tmp_path / "mutations.xml"
    xml.write_text(MUTATIONS)
    text = summarize(xml)
    assert "Mutations: **7**" in text
    assert "mutation score: **71 %**" in text  # 5 detected of 7, truncated like Pitest (71.4)
    assert "test strength: **83 %**" in text  # 5 detected of 6 covered, truncated (83.3)
    assert "| KILLED | 2 |" in text
    assert "| NON_VIABLE | 1 |" in text
    assert "informative, no threshold yet" in text


def test_summary_without_mutations(tmp_path: Path) -> None:
    xml = tmp_path / "mutations.xml"
    xml.write_text("<mutations/>")
    assert "n/a" in summarize(xml)


def test_summary_script_handles_a_missing_report(tmp_path: Path) -> None:
    result = run_script("pitest_summary.py", str(tmp_path), cwd=tmp_path)
    assert result.returncode == 0
    assert "No mutation report" in result.stdout
