# Plan: plans in the repository

- **Origin:** context (agreed in the conversation of 2026-11-11, copied here)
- **Approved by the maintainer on:** 2026-11-11
- **Branch / PR:** `docs/plans-method` / pending

## Goal
Stop losing the plans of each task: until now they lived only in the chat of the agent session and what survived was the result (ADR, session log, PR). Put them in the repository, in a form any harness can use (ADR 0010), and give the reviewer something concrete to review the diff against.

## Scope
- `docs/plans/` with a README (how plans are used, the state of a plan, the origin of a plan) and a template.
- The plans already approved for the next tasks, as the first real files (this one and the two of the conformance suite).
- A design rule in `AGENTS.md`, the changes to `docs/ai-workflow.md` and to the review checklist, a line in the roadmap, a session-log entry.

## Out of scope
- Backfilling plans for the tasks already done: they would duplicate the session log, the ADRs and the PRs, and rebuilding them risks inventing what was not written. The README says where to find them.
- The audit of implicit state machines: it is scheduled before Phase 2 (see the roadmap), not now.
- An automatic check for the design rule (Konsist): to be assessed by the audit; false positives are likely.
- Any code change.

## Acceptance criteria
- [x] `docs/plans/README.md` and `docs/plans/_template.md` exist, and the template has the sections Goal, Scope, Out of scope, Acceptance criteria, Steps, Validation, State model, Decisions taken and Status — checked by: reading the template.
- [x] Each plan file follows the template and states its origin — checked by: reading the three plan files.
- [x] `AGENTS.md` has the design rule and a step that says to write the plan first — checked by: `grep -n "implicit state" AGENTS.md` (the rule) and `grep -n "docs/plans" AGENTS.md` (the step).
- [x] `docs/ai-workflow.md` (its sections 2, 3, 4 and 5) and `docs/review-checklist.md` each mention the plan in the way this plan describes — checked by: `grep -c "docs/plans" docs/ai-workflow.md` is at least 2 (sections 2 and 3), `grep -ci "acceptance criteri" docs/ai-workflow.md docs/review-checklist.md` shows at least one match in each file, and `grep -n "Status" docs/ai-workflow.md` finds the closing step.
- [x] The roadmap lists the audit before Phase 2 — checked by: `grep -n "implicit state" docs/roadmap.md`.
- [x] The diff touches only Markdown files and every relative link in them resolves — checked by: `(git diff --name-only main; git ls-files -o --exclude-standard)` lists only `.md` files, and a script that resolves every relative link of the new and changed files reports none broken.
- [ ] The checks of the pull request are green — checked by: the CI run of the PR (it cannot be checked before the PR exists).
- [x] An independent read-only review of the diff against the criteria above found nothing that blocks — checked by: the review report, which goes through each criterion (two rounds: the first found problems that were fixed; the second found no blocker, and its medium findings were applied).

## Steps
- [x] Write the README, the template and the three plans.
- [x] Add the rule to `AGENTS.md` and update the "How to work in a session" steps.
- [x] Update `docs/ai-workflow.md`, `docs/review-checklist.md` and `docs/roadmap.md`.
- [x] Add the session-log entry (with "Time spent" and "Diff accepted as-is" left for the maintainer).
- [x] Fix what the review found (done once; the findings are in the session log).
- [ ] Commit, push, PR, merge (the maintainer merges).

## Validation
- `git diff --stat main` lists only `.md` files.
- `python3 scripts/ci/check_license_headers.py` still passes (no source files changed).
- Negative check: none needed; there is no behaviour.

## State model
None. (The state of a plan itself is a small table in the README, written once.)

## Decisions taken
- Plans only going forward, no backfill: the maintainer agreed with the agent's recommendation.
- The rule is worded as the agent proposed it, not in the stricter wording of the prompt that inspired it: "Boolean or nullable flags that combine into a state are not allowed" would forbid a value such as `Purchase.expiresAt = null` ("never expires"), which is a single field with one clear meaning. What causes bugs is several fields that must agree (`status` with `expiresAt`), and that is what the rule covers. Chosen by the maintainer.
- The first real plans are the two approved for the conformance suite, so that they stop living only in the chat.

## Status
In progress
- 2026-11-11: approved by the maintainer; files written. The criteria are ticked only after each one is verified with its command.
- 2026-11-11: a second read-only review found no blocker; its medium findings (scenarios not observable from outside, a manifest with no machine-readable source, a temporary behaviour listed as contract, a criterion that depended on a later pull request) were applied to the conformance plans.
- 2026-11-11: a read-only review found that one box claimed CI green before a PR existed, a wrong count of earlier tasks, weak criteria in the conformance plans, and a rule without a scope; all corrected, and the affected boxes unticked until re-verified.
