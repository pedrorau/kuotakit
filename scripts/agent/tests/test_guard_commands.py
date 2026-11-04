from __future__ import annotations

from pathlib import Path

import pytest
from conftest import claude_payload, neutral_payload, run_script

BLOCKED = [
    "git push --force",
    "git push -f origin main",
    "git push -uf origin feat/x",
    "git push --force-with-lease",
    "git push origin +main",
    "git -C . push --force",
    "git reset --hard",
    "git reset --hard HEAD~3",
    "rm -rf /",
    "rm -rf ~",
    "rm -rf .",
    "rm -rf *",
    "rm -rf ../other",
    "rm -r src",
    "rm -rf build/../src",
    "rm -rf",
    "npm publish",
    "pnpm publish --access public",
    "yarn npm publish",
    "bun publish",
    "./gradlew publish",
    "./gradlew :contracts:publishAllPublicationsToMavenCentralRepository",
    "gradle closeAndReleaseSonatypeStagingRepository",
    "mvn deploy",
    "./mvnw release:perform",
    "dart pub publish",
    "flutter pub publish --dry-run=false",
    "echo TOKEN=x > .env",
    "cd backend && echo TOKEN=x > ../.env",
    "(cd backend; cp ../.env.example ../.env)",
    "echo x >> local.properties",
    "cp .env.example .env",
    "mv key.p8 AuthKey.p8",
    "tee .env <<< SECRET=1",
    "sed -i s/a/b/ .env",
    "rm .env",
    "FOO=1 sudo git push --force",
    "bash -c 'git push --force'",
    "sh -c \"rm -rf /\"",
    "echo $(git reset --hard)",
    "echo `git push -f`",
    "ls && git push --force",
    "git status; rm -rf /",
    "ls | xargs echo; git reset --hard",
    "/usr/bin/git push --force",
    "eval 'npm publish'",
    # combined shell flags
    "bash -lc 'git push -f'",
    "sh -ec \"rm -rf src\"",
    # wrapper options that take a value
    "sudo -u root git push -f",
    "nice -n 5 git push -f",
    "env -u X git push -f",
    "timeout 5 git push -f",
    "timeout -s KILL 5 git reset --hard",
    "xargs git push -f",
    "ls | xargs rm -rf",
    "ionice -c 3 git reset --hard",
    # remote refs and lost work
    "git push --mirror",
    "git push origin --delete feature",
    "git push origin :main",
    "git push -d origin feature",
    "git checkout -- .",
    "git restore .",
    "git clean -fdx",
    "git checkout --force main",
    # find
    "find . -delete",
    "find src -name '*.kt' -delete",
    "find . -exec rm -rf {} +",
    "find . -type f -exec git push -f \\;",
    # secrets through other writers and case variants
    "cp x secrets/",
    "touch .env",
    "rsync x .env",
    "echo x | sponge .env",
    "sed --in-place s/a/b/ .env",
    "perl -pi -e s/a/b/ .env",
    "echo x > .ENV",
    # abbreviated gradle publishing tasks
    "./gradlew pAPTMCR",
]
ALLOWED = [
    "git push origin feature",
    "git push -u origin feat/x",
    "git status",
    "git reset --soft HEAD~1",
    "git reset HEAD file.txt",
    "git commit -m 'never run git push --force or rm -rf /'",
    "rm -rf backend/build",
    "rm -rf build/",
    "rm -rf ./node_modules",
    "rm -rf contracts/build/tmp",
    "rm file.txt",
    "rm -f a.txt",
    "./gradlew build",
    "./gradlew :backend:test --tests 'Foo*'",
    "./gradlew publishToMavenLocal",
    "npm install",
    "npm run build",
    "dart pub get",
    "cat .env",
    "cp .env.example docs/env-sample.txt",
    "echo hi 2>&1",
    "docker compose up --build",
    "python3 -m pytest scripts/agent",
    "git checkout feat/x",
    "git checkout -b feat/y",
    "git checkout -- backend/src/main/kotlin/App.kt",
    "git clean -n",
    "find . -name '*.kt'",
    "find backend/build -delete",
    "find . -exec grep -l foo {} +",
    "sudo -u root ls",
    "timeout 5 ./gradlew build",
    "xargs echo",
    "bash -lc 'git status'",
    "./gradlew :backend:pitest",
]


@pytest.mark.parametrize("command", BLOCKED)
def test_blocks_dangerous_commands(repo: Path, command: str) -> None:
    result = run_script("guard_commands.py", claude_payload("Bash", {"command": command}, repo))
    assert result.returncode == 2, f"should block: {command}"
    assert "Blocked" in result.stderr


@pytest.mark.parametrize("command", ALLOWED)
def test_allows_everyday_commands(repo: Path, command: str) -> None:
    result = run_script("guard_commands.py", claude_payload("Bash", {"command": command}, repo))
    assert result.returncode == 0, f"should allow: {command}\n{result.stderr}"


def test_a_command_it_cannot_parse_is_blocked(repo: Path) -> None:
    result = run_script("guard_commands.py", claude_payload("Bash", {"command": "echo 'unterminated"}, repo))
    assert result.returncode == 2
    assert "cannot analyze" in result.stderr


def test_neutral_format(repo: Path) -> None:
    bad = run_script("guard_commands.py", neutral_payload("pre_tool_use", repo, tool="bash", command="git reset --hard"))
    good = run_script("guard_commands.py", neutral_payload("pre_tool_use", repo, tool="bash", command="git status"))
    assert (bad.returncode, good.returncode) == (2, 0)


def test_non_bash_tools_are_ignored(repo: Path) -> None:
    result = run_script("guard_commands.py", claude_payload("Write", {"file_path": str(repo / "a.txt")}, repo))
    assert result.returncode == 0


def test_a_session_in_an_unrelated_directory_does_not_break_every_command(tmp_path: Path) -> None:
    elsewhere = tmp_path / "elsewhere"
    elsewhere.mkdir()
    ok = run_script("guard_commands.py", claude_payload("Bash", {"command": "ls -la"}, elsewhere))
    bad = run_script("guard_commands.py", claude_payload("Bash", {"command": "git push --force"}, elsewhere))
    assert (ok.returncode, bad.returncode) == (0, 2)
