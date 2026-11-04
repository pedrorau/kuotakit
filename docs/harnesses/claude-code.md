# Claude Code

The maintainer's harness. Entry point: `CLAUDE.md`, which imports `AGENTS.md`.

- **Hooks:** `.claude/settings.json` wires `scripts/agent/` into the hook events (the scripts and their contract are in [guard-scripts.md](guard-scripts.md)):

  | Event | Matcher | Scripts |
  |---|---|---|
  | PreToolUse | `Edit\|Write\|MultiEdit\|NotebookEdit` | `guard_paths.py`, `protect_tests.py` |
  | PreToolUse | `Bash` | `guard_commands.py`, `protect_tests.py` |
  | PostToolUse | `Edit\|Write\|MultiEdit\|NotebookEdit` | `check_changed.py` |
  | Stop | none | `check_tests.py` |

  Format verified against the [hooks reference](https://code.claude.com/docs/en/hooks.md) on 2026-11-03. The hooks use exec form (`command: "python3"` + `args`) with `${CLAUDE_PROJECT_DIR}`. The PreToolUse and Stop hooks set `"onFailure": "block"` (Claude Code 2.1.295 or later), so a hook that cannot start blocks the action instead of letting it through. "Ask" decisions use `permissionDecision: "ask"`, which Claude Code shows as a permission prompt.
- **Hooks load when a session starts in the project.** Open Claude Code inside the repo (not in a parent folder) and check `/hooks`.
- **Subagents:** `.claude/agents/` (`explorer`, `store-docs`, `reviewer`). Hooks also run inside subagents.
- **Model routing:** `opusplan` plans with Opus and implements with Sonnet.
- **Working from the phone:** sessions run on the maintainer's machine with Remote Control on, so plans and permission requests can be approved from the Claude mobile app.
