# ADR 0010 — Harness-agnostic AI setup

**Status:** Accepted · **Date:** 2026-10-30

## Context
The maintainer uses Claude Code, wants to practice with other harnesses (OpenCode, Pi), and contributors will use whatever tool they prefer. Instructions in a markdown file are suggestions that a model can ignore; rules that must always hold need to be enforced by code.

## Decision
- **One instruction file:** `AGENTS.md` is the source of truth. Harness files only point to it and add tool-specific setup (`CLAUDE.md` imports it with `@AGENTS.md`; other harnesses read `AGENTS.md` directly or are configured to).
- **Guardrails as scripts:** hard rules live in `scripts/agent/*.py` (plain Python, stdin JSON / exit codes). Each harness wires them with its own mechanism: Claude Code hooks (`.claude/settings.json`), OpenCode plugins, Pi extensions. The logic is written once.
- **CI is the final gate:** the same checks run in GitHub Actions on every PR, whatever tool (or no tool) produced the change.
- **Subagents:** defined for Claude Code in `.claude/agents/` (explorer, store-docs, reviewer). Equivalents for other harnesses are added when someone uses them; their prompts stay the same.
- **Harness-specific knowledge stays out of the code and the shared docs.** It lives in the harness config folders and in `docs/harnesses/`, one file per harness (`claude-code.md`, `opencode.md`, `pi.md`…). No harness is privileged: the contributor picks the one they like, and anyone can add a file for theirs. Everything else in `docs/` must make sense without knowing any harness.

## Consequences
- Switching harness changes only config, not rules.
- Guard scripts need their own small tests.
- Some harness features (e.g. a model per subagent) may not map one to one; that is acceptable.
