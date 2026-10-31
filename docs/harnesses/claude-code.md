# Claude Code

The maintainer's harness. Entry point: `CLAUDE.md`, which imports `AGENTS.md`.

- **Hooks:** `.claude/settings.json` wires `scripts/agent/` into PreToolUse, PostToolUse and Stop (Phase 1, task 2).
- **Subagents:** `.claude/agents/` (`explorer`, `store-docs`, `reviewer`).
- **Model routing:** `opusplan` plans with Opus and implements with Sonnet.
- **Working from the phone:** sessions run on the maintainer's machine with Remote Control on, so plans and permission requests can be approved from the Claude mobile app.
