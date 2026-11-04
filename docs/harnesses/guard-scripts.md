# Guardrail scripts

Rules that must always hold are enforced by the scripts in `scripts/agent/`, not by asking the model (ADR 0010, ADR 0011). Python 3.10+, standard library only. Every harness calls the same scripts; only the glue is harness-specific ([Claude Code](claude-code.md), [OpenCode](opencode.md), [Pi](pi.md)).

## The contract

Each script reads one JSON event on **stdin** and answers with an **exit code**.

Neutral input (what a new harness should send):

```json
{"event": "pre_tool_use", "tool": "edit", "paths": ["backend/src/test/kotlin/FooTest.kt"],
 "command": "", "cwd": "/path/to/repo", "session_id": "abc"}
```

| Field | Values |
|---|---|
| `event` | `pre_tool_use`, `post_tool_use`, `stop` |
| `tool` | `edit`, `write`, `delete` (use `paths`), `bash` (use `command`) |
| `paths` | Absolute, or relative to `cwd` |
| `cwd`, `session_id` | Optional. `session_id` keys the Stop counter |

If the JSON has `hook_event_name` it is read as the **Claude Code** hook payload (`tool_name`, `tool_input.file_path`, `tool_input.command`...), so Claude Code's hooks need no flags.

| Exit code | Meaning |
|---|---|
| `0` | Allow |
| `2` | Block. The reason is on stderr (and `{"decision":"deny","reason":...}` on stdout for neutral callers) |
| `3` | Ask a human (neutral callers only). A caller with nobody to ask must treat it as a block |

Claude Code speaks its own dialect, which the scripts produce automatically:
- "ask" is the JSON `hookSpecificOutput.permissionDecision: "ask"` on stdout with exit 0, because Claude Code can only prompt through that JSON.
- A blocked Stop is the JSON `{"decision": "block", "reason": ...}` with exit 0. Claude Code treats a Stop hook that exits 2 with nothing on stdout and a "No such file or directory" message as a non-blocking error, so a missing `./gradlew` would otherwise let the agent stop in red.

**Fail closed.** Unreadable input, an unknown edit target or an internal error blocks the action (exit 2). The exception is `check_changed.py`, which exits 1 (a warning) when its tooling is missing so a broken toolchain never traps the agent.

## The scripts

| Script | Event | What it does |
|---|---|---|
| `guard_paths.py` | pre, `edit`/`write` | Blocks edits to secrets. Patterns come from the "Secrets and credentials" block of `.gitignore` (single source of truth); `!.env.example`-style exceptions stay editable. Symlinks are resolved |
| `guard_commands.py` | pre, `bash` | Blocks `git push --force` (`-f`, `--force-with-lease`, `+ref`), `--mirror`/`--delete`/`:ref`, `git reset --hard`, `git clean -f`, `git checkout|restore .`, recursive `rm` and `find -delete` outside build directories, publishing (Gradle `publish*` except `publishToMavenLocal`, `mvn deploy`, `npm`/`pnpm`/`yarn`/`bun publish`, `dart`/`flutter pub publish`) and shell writes to secret files (redirections, `tee`, `cp`, `mv`, `touch`, `rsync`, `sed -i`, `perl -pi`, `awk -i inplace`...). Secret matching is case-insensitive |
| `protect_tests.py` | pre, `edit`/`write`/`bash` | Asks for human approval before editing, overwriting or deleting an **existing** test file (also through `rm`, `mv`, `cp`, `git checkout|restore`, in-place editors and redirections). New test files are allowed. Never weaken a test to make it pass (ADR 0011) |
| `check_changed.py` | post, `edit`/`write` | Runs `./gradlew :<module>:ktlintFormat` for the Gradle module of the changed Kotlin file (it reformats the whole module, nested modules included) and returns what ktlint cannot fix; syntax-checks Python. Root-level `.kts` files are not covered (CI is) |
| `check_tests.py` | stop | Runs `:<module>:check` (tests, ktlint, detekt) for the modules with uncommitted changes, `check` for shared build files, pytest for `scripts/agent`. Docs-only changes run nothing. Blocks the stop while anything fails |

`check_tests.py` blocks up to **3 times in a row** per session. The fourth time it lets the agent stop and prints a loud warning, so a test that cannot be fixed does not trap a session forever. All its commands share one 800 s budget (the hook timeout is 900 s), so the counter is always written before the harness could kill it. CI and the branch ruleset remain the final gate.

**Which repository the rules come from:** `KUOTAKIT_REPO_ROOT` if set; otherwise the git root of the session's `cwd` when it has the secrets block in `.gitignore` (so worktrees work); otherwise the repository the scripts live in. A session that wandered into `/tmp` or another repo therefore neither breaks every command nor applies foreign rules. `check_changed.py` and `check_tests.py` are different: they always look at the git repository the session works in (never at this project), do nothing outside a git repository, and block if `git` itself is missing.

Other override: `KUOTAKIT_STATE_DIR` (where the Stop counter lives; default: the system temp dir; the small state files are not cleaned up).

## Known limits

These scripts are a safety net, not a sandbox.

- Shell analysis is best effort. It follows `;`, `&&`, `|`, `$(...)`, backticks, `bash -c`/`-lc`, `eval`, `find -exec`, `cd` and the wrappers `sudo`, `env`, `nice`, `timeout`, `xargs`, `nohup`... It does **not** follow indirect or dynamic execution: `python -c "os.remove(...)"`, a script that deletes files, `$(echo rm) -rf x`, `x=rm; $x -rf y`, `patch`, `git apply`, `git stash`.
- Gradle lets you abbreviate task names (`pAPTMCR`); any task starting with `p` + a capital letter is blocked as a possible `publish*` abbreviation.
- Heredoc bodies are scanned like commands, so a heredoc that merely mentions `git push --force` is blocked.
- Windows-style backslash paths are not normalized.
- An agent could edit `.claude/settings.json` or the scripts themselves. Treat changes to them as a review item.
- A harness without a stop hook cannot enforce `check_tests.py`; the same checks run in CI for everyone.
- In Claude Code, subagents fire `SubagentStop`, not `Stop`, so `check_tests.py` only guards the main agent. PreToolUse and PostToolUse hooks do run inside subagents.

## Tests

```
python3 -m venv .venv && .venv/bin/pip install -r scripts/agent/requirements-dev.txt
.venv/bin/python -m pytest scripts/agent
```

CI runs them in the `agent-scripts` job. The tests feed the scripts the exact payloads a harness would send and assert on exit codes and output. The `tests/` folder is itself protected by `protect_tests.py`.

## Adding a harness

1. Map the harness' "before tool" event to `{"event": "pre_tool_use", "tool": ..., "paths"/"command": ...}` and pipe it to the three pre scripts.
2. Exit 2 → stop the tool call and show stderr to the model. Exit 3 → ask the human, or block if nobody can answer.
3. Optionally wire `check_changed.py` after edits and `check_tests.py` when the agent finishes.
4. Add a `docs/harnesses/<name>.md` with the glue and its limits.
