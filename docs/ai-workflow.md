# How this project uses AI

This project is built with AI coding agents, on purpose and in the open. The goal is not "AI wrote it", but a repeatable method where the human owns the decisions and the quality.

## The method

### 1. Build context before code
- Write the project context once: README, ADRs, API spec, AGENTS.md.
- Use the agent to read official docs (Play Billing, StoreKit 2, App Store Server API) and produce summaries and sequence diagrams, while reading the same docs yourself. Your understanding plus its summaries becomes the context.
- Include constraints and anti-goals, not only goals (see the rules in AGENTS.md).

### 2. Plan before implementing
- For each phase, ask the agent for 2–3 implementation alternatives with trade-offs. You choose.
- Record significant choices as ADRs.
- Break the phase into tasks small enough to review in one sitting.

### 3. Implement in small slices
- One task per session. Start every session in plan mode: the agent restates the task and the files it will touch, you approve, then it writes code.
- Public API changes are always called out explicitly.

### 4. Validate everything
- Tests run and pass for every change; the agent pastes the output.
- You review the public API surface and the integration points yourself.
- End-to-end checks against real store sandboxes at the end of each phase.
- Cross-check behavior against the store documentation, not against the agent's memory.

### 5. Close the session
- Update "Current state" in AGENTS.md.
- Append an entry to docs/session-log.md (template below).

## Tooling setup (ADR 0010)
- **Instructions:** `AGENTS.md` for every harness; `CLAUDE.md` only imports it and adds Claude Code specifics.
- **Rules vs guardrails:** style and judgment go in `AGENTS.md`. Anything that must *always* happen or *never* happen is a script in `scripts/agent/`, wired as a hook. If a rule was ignored twice, it becomes a guardrail.
- **Subagents by cost and context, not by role-play:** a cheap read-only `explorer`, a `store-docs` researcher that only trusts official docs, and a `reviewer` on the strongest model before each commit.
- **Model routing:** plan with the strongest model, implement with a faster one. How to set that up depends on the harness (see [harnesses/](harnesses/)).
- **Harness-specific setup:** one file per harness in [harnesses/](harnesses/). The maintainer uses Claude Code; contributors can use any harness.
- **Other harnesses:** practice the same task in OpenCode or Pi from time to time and note differences in the session log.
- **Evals (later):** a small set of repeatable tasks in `evals/` with expected results, to compare prompts, models or harnesses with numbers instead of impressions.

## Session log template
```
## YYYY-MM-DD — <task>
- Goal:
- What the agent proposed / what I chose:
- What I had to correct (assumptions, bugs, hallucinated APIs):
- Validation done:
- Time spent: ~X h · Diff accepted as-is: yes / with changes / rejected
```

## Why keep a log
Over a few months the log becomes data: where agents help, where they make things up, how much rework they cause. That is material for blog posts and meetup talks, and it is honest evidence of how to use AI well on a real project.
