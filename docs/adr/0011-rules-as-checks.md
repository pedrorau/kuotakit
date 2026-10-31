# ADR 0011 — Rules as checks: architecture tests, protected tests and mutation testing

**Status:** Accepted · **Date:** 2026-10-30

## Context
Coding agents sometimes ignore written rules, or make a failing test pass by weakening the test instead of fixing the code. A rule written only in AGENTS.md is a suggestion; a hook can block actions but cannot judge natural-language instructions. Code coverage does not show whether tests actually verify behavior.

## Decision
Every rule that can be broken silently is turned into an automated check:
- **Static rules → linters.** detekt rules (e.g. no `!!` in library code).
- **Architecture rules → architecture tests** (Konsist), for example:
  - only the `storage` package of the `backend` module may depend on Exposed (ADR 0009);
  - API models (`@Serializable` request/response types) exist only in `contracts/` (ADR 0004);
  - SDK core does not depend on platform billing libraries (ADR 0002).
- **License headers → a CI check** that every source file has the Apache-2.0 header (ADR 0006).
- **Public API → Binary Compatibility Validator.** Public API changes fail the build until the `.api` dump is updated, so they are always visible in review.
- **Protected tests.** A guard script (`scripts/agent/protect_tests.py`) asks for human approval before an agent edits or deletes an existing test file. New test files are allowed.
- **No "done" in red.** At the end of an agent task, a guard (`scripts/agent/check_tests.py`) runs the relevant tests; the task cannot be closed while they fail.
- **Mutation testing (Pitest, JVM).** Runs on changed code in every PR and on the whole backend and core weekly. Critical modules (purchase validation, entitlement computation) must keep a minimum mutation score, starting at 70% and raised over time.
- The two guard scripts (`protect_tests.py`, `check_tests.py`) ship in Phase 1, task 2 with the other agent guardrails; the remaining checks above ship in task 3.
- All of these run in CI as well, so they apply to any harness or to humans.

## Consequences
- Weakened or empty tests lower the mutation score and fail CI, even when tests are green.
- Rules that cannot be automated (e.g. "bridges contain no business logic") stay in AGENTS.md and are checked by the reviewer.
- Mutation testing is slow; it is scoped to changed code in PRs. Kotlin/Native-only code is not covered; critical logic lives in the backend and `commonMain`, which run on the JVM.
- Test framework: Kotest if it works with Pitest (Kotest runs on the JUnit Platform and has a Pitest extension, but the versions must be verified); otherwise JUnit 5. The choice is settled with a small spike in Phase 1, task 1, before the first real tests.
- Mutation testing is enabled once there is real domain logic to mutate (Phase 1, task 3), not on the skeleton.
