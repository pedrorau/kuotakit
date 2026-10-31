# AGENTS.md — context for AI coding agents

This is the single source of instructions for any coding agent (Claude Code, OpenCode, Pi, Codex, Cursor…). Harness-specific files only point here and add tool-specific setup.

Read this file at the start of every session. Update the "Current state" section at the end of every session.

## Project
KuotaKit: open-source, self-hosted in-app purchases and subscriptions. KMP core SDK + Play Billing (Kotlin) and StoreKit 2 (Swift) adapters, React Native and Flutter bridges, reference backend in Kotlin (Ktor + Postgres), shared API models in `contracts/`, and a conformance suite in `conformance/`. See README.md and docs/.

## Non-negotiable rules
1. Entitlements come only from the backend. Never add client-side granting (ADR 0003).
2. Fail closed: if a store API call fails, do not grant access.
3. No secrets in the SDK or example apps. Store credentials live only on the server.
4. The public SDK API is a contract. Do not add, rename or remove public symbols without flagging it explicitly in your plan.
5. Bridges (RN, Flutter) contain no business logic.
6. Do not copy code from other projects. If something is inspired by another project, say which, and respect its license.
7. If a requirement is ambiguous, ask. Do not assume.
8. The backend talks to the database only through the storage interface (ADR 0009).

## Conventions
- Kotlin: official style, ktlint + detekt, coroutines and Flow, no `!!` in library code.
- Swift: Swift 5.10+, async/await, SwiftLint.
- Backend: Ktor, kotlinx.serialization, Testcontainers for Postgres. Tests: Kotest if it works with Pitest, otherwise JUnit 5 (ADR 0011; decided in Phase 1, task 1).
- Store identifiers are always `play_store` and `app_store` (API, YAML, SDK).
- API models live only in `contracts/`. Never redefine them in the SDK or the backend.
- Any backend change must keep the conformance suite green (ADR 0007).
- Commits: Conventional Commits (`feat(core): ...`, `fix(backend): ...`).
- Every behavior change comes with a test.

## How to work in a session
1. Restate the task and list what you will change before writing code.
2. Keep changes small: one task per session, one reviewable diff.
3. Run the relevant checks (commands below) and paste the result.
4. If a decision affects architecture, draft an ADR in docs/adr/.
5. Before committing, get a review against [docs/review-checklist.md](docs/review-checklist.md) (in Claude Code: the `reviewer` subagent).
6. At the end, update "Current state" and append to docs/session-log.md.

Questions about Google Play or Apple store APIs follow [docs/store-docs-rules.md](docs/store-docs-rules.md): official sources only, with links.

## Guardrails enforced by scripts (not by this file)
Rules that must always hold are enforced by scripts in `scripts/agent/`, not by asking the model. Every harness calls the same scripts (ADR 0010), and CI runs the same checks for everyone:
- `guard_paths.py`: blocks edits to secrets and credentials: every pattern under "Secrets and credentials" in `.gitignore` (`.env*`, `*.p8`, `*.p12`, `*.pem`, `*.jks`, `*.keystore`, `*.mobileprovision`, `*service-account*.json`, `google-services.json`, `GoogleService-Info.plist`, `local.properties`, `secrets/`). Template files with no secrets (`.env.example`, `.env.sample`, `.env.template`) are allowed.
- `guard_commands.py`: blocks dangerous commands (`git push --force`, `git reset --hard`, `rm -rf` outside build dirs, publishing to Maven/npm/pub.dev).
- `check_changed.py`: formats and lints the files the agent changed.
- `protect_tests.py`: editing or deleting an existing test needs human approval; new test files are allowed. Never weaken a test to make it pass; fix the code or explain why the test is wrong (ADR 0011).
- `check_tests.py`: runs the relevant tests when a task ends; the task cannot be closed while they fail (ADR 0011).
- Architecture tests, the public API dump and mutation testing run in CI (ADR 0011). A green build with a dropped mutation score is a failure.
If a guard blocks you, do not try to work around it. Explain and ask.

## Commands (fill in when code exists)
- Backend tests: `TBD`
- SDK tests: `TBD`
- Lint all: `TBD`

## Current state
- Phase 0 done (2026-10-30): docs closed, identifiers reserved.
- Phase 0 wrap-up done (2026-10-31): planning docs at the repo root, NOTICE added (LICENSE came from GitHub). No code yet.
- Phase 1, task 1 next: monorepo skeleton (Gradle, `contracts/`, backend with `/health`, docker-compose with Postgres, CI).
- Then task 2: all five guardrail scripts in `scripts/agent/` (`guard_paths`, `guard_commands`, `check_changed`, `protect_tests`, `check_tests`) + Claude Code hooks (ADR 0010). Until then, guardrails are not active.
- Then task 3: the rest of the rules as checks (ADR 0011): architecture tests, public API dump, license-header check, mutation testing.
