# Claude Code

The maintainer's harness. Entry point: `CLAUDE.md`, which imports `AGENTS.md`.

- **Hooks:** `.claude/settings.json` wires `scripts/agent/` into the hook events (the scripts and their contract are in [guard-scripts.md](guard-scripts.md)):

  | Event | Matcher | Scripts |
  |---|---|---|
  | PreToolUse | `Edit\|Write\|MultiEdit\|NotebookEdit` | `guard_paths.py`, `protect_tests.py` |
  | PreToolUse | `Bash` | `guard_commands.py`, `protect_tests.py` |
  | PostToolUse | `Edit\|Write\|MultiEdit\|NotebookEdit` | `check_changed.py` |
  | Stop | none | `check_tests.py` |

  Format verified against the [hooks reference](https://code.claude.com/docs/en/hooks.md) on 2026-11-03 and then **tested live** with real Claude Code sessions.
  - **Shell form on purpose.** The docs recommend exec form (`command` + `args`), but Claude Code 2.1.105 silently never runs a hook that has `args` (no error, nothing is guarded). Shell form (`python3 "${CLAUDE_PROJECT_DIR}"/scripts/agent/<script>.py`) works on that version and on current ones, and a test (`test_settings.py`) fails if `args` comes back.
  - `"onFailure": "block"` on the PreToolUse and Stop hooks makes a hook that cannot start block the action. It needs Claude Code 2.1.295 or later; older versions ignore the field (the scripts themselves already fail closed).
  - "Ask" decisions use `permissionDecision: "ask"`, which Claude Code shows as a permission prompt (in headless `-p` runs nobody can answer, so it counts as a denial).
  - Run `claude update` to get a current version.
- **Hooks load when a session starts in the project.** Open Claude Code inside the repo (not in a parent folder) and check `/hooks`.
- **Subagents:** `.claude/agents/` (`explorer`, `store-docs`, `reviewer`). Hooks also run inside subagents.
- **Model routing:** `opusplan` plans with Opus and implements with Sonnet.
- **Working from the phone:** sessions run on the maintainer's machine with Remote Control on, so plans and permission requests can be approved from the Claude mobile app.
