# CLAUDE.md

@AGENTS.md

## Claude Code specifics
- Hooks in `.claude/settings.json` (added in Phase 1, task 2) call the scripts in `scripts/agent/` (see AGENTS.md → Guardrails). Do not edit `.claude/settings.json` unless the task is about it.
- Subagents live in `.claude/agents/`. Delegate to them instead of doing that work in the main context:
  - `explorer`: find files, usages, read logs. Read-only, fast model.
  - `store-docs`: answer questions about Google Play Billing, StoreKit 2 and store server APIs from official docs only, with links.
  - `reviewer`: review a diff against AGENTS.md rules and the ADRs before committing. Read-only.
- Start every coding task in plan mode.
