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
- Backend: Ktor, kotlinx.serialization, Exposed + Flyway on Postgres, Testcontainers for the storage tests. Layers: `domain` (pure Kotlin, owns the `PurchaseRepository` and `PlayStoreApi` interfaces), `storage` (the only place that touches the database), `google` (the only place that imports `com.google.*`), `config`, `web`. Tests: Kotest (works with Pitest, see ADR 0011).
- Gradle: the root `settings.gradle.kts` includes only `:contracts`, `:backend`, `:openapi` and `:architecture-tests`. Future bridges (`bridges/react-native`, `bridges/flutter`) keep their own build tools and are NOT included in it. Versions live in `gradle/libs.versions.toml`.
- Tests that need Docker (the Postgres storage tests): on macOS with Colima run `colima start --network-address` and export `DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` and `TESTCONTAINERS_HOST_OVERRIDE=$(colima ls -j | jq -r .address)`; without Docker those tests fail with a message saying so.
- JDK 21 comes from the Gradle toolchain (downloaded automatically); do not rely on the JDK installed on the machine.
- Every Kotlin, Java, Swift, Dart, TypeScript, JavaScript and Objective-C source file carries the Apache-2.0 license header near the top (ADR 0006); `scripts/ci/check_license_headers.py` enforces it in CI.
- The public API of `contracts` is dumped in `contracts/api/`. After an intentional change run `./gradlew :contracts:updateKotlinAbi` and commit the diff; it must be called out in the plan (rule 4).
- Architecture rules live in `architecture-tests/` (Konsist). The rule "SDK core does not depend on platform billing libraries" (ADR 0002) is added with the `sdk/core` module.
- Store identifiers are always `play_store` and `app_store` (API, YAML, SDK).
- API models live only in `contracts/`. Never redefine them in the SDK or the backend. Wire names are `snake_case` via `@SerialName`; a nullable field has no default, so `null` is always sent explicitly. Backends and SDKs use `kuotaKitJson` for these models; every enum has an `UNKNOWN` fallback (`FallbackEnumSerializer`).
- The OpenAPI document `docs/api/openapi.json` is generated from the models (`:openapi`, ADR 0012), never edited by hand. After changing a model or adding an endpoint (also add it to `ENDPOINTS` in `openapi/`), run `./gradlew :openapi:updateOpenApi` and `:contracts:updateKotlinAbi`, and commit the diffs; `check` fails otherwise.
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
Rules that must always hold are enforced by scripts in `scripts/agent/`, not by asking the model. Every harness calls the same scripts (ADR 0010); the contract, the limits and how to wire a harness are in [docs/harnesses/guard-scripts.md](docs/harnesses/guard-scripts.md). The hooks are active in Claude Code (`.claude/settings.json`); OpenCode and Pi are documented in `docs/harnesses/` but not configured. CI runs the same checks for everyone:
- `guard_paths.py`: blocks edits to secrets and credentials: every pattern under "Secrets and credentials" in `.gitignore` (`.env*`, `*.p8`, `*.p12`, `*.pem`, `*.jks`, `*.keystore`, `*.mobileprovision`, `*service-account*.json`, `google-services.json`, `GoogleService-Info.plist`, `local.properties`, `secrets/`). Template files with no secrets (`.env.example`, `.env.sample`, `.env.template`) are allowed.
- `guard_commands.py`: blocks dangerous commands (`git push --force`, `git reset --hard`, `rm -rf` outside build dirs, publishing to Maven/npm/pub.dev).
- `check_changed.py`: after each edit, formats the changed Kotlin file with ktlint and returns what it cannot fix.
- `protect_tests.py`: editing or deleting an existing test needs human approval; new test files are allowed. Never weaken a test to make it pass; fix the code or explain why the test is wrong (ADR 0011).
- `check_tests.py`: when a task ends, runs the checks (tests, ktlint, detekt) of the modules with uncommitted changes; the task cannot be closed while they fail. After 3 blocked attempts in a row it lets the agent stop with a loud warning, so an unfixable failure does not trap a session (ADR 0011).
- Architecture tests, the public API dump and mutation testing run in CI (ADR 0011). Mutation testing keeps a 70 % score on the backend `domain` package (`mutation.yml`); a score below it fails that workflow.
If a guard blocks you, do not try to work around it. Explain and ask.

## Commands
- Build, tests and lint (ktlint + detekt, all modules): `./gradlew build`
- Backend tests: `./gradlew :backend:test`
- Contracts tests (JVM): `./gradlew :contracts:jvmTest` (iOS simulator tests only run on Apple Silicon or in CI: `:contracts:iosSimulatorArm64Test`)
- Auto-format: `./gradlew ktlintFormat`
- Run the backend: `./gradlew :backend:run`, then `curl localhost:8080/health`
- Backend + Postgres: `./gradlew :backend:installDist && cp .env.example .env && cp entitlements.example.yaml entitlements.yaml && docker compose up --build`
- Architecture tests: `./gradlew :architecture-tests:test`
- OpenAPI document: `./gradlew :openapi:checkOpenApi` (in `check`) / `./gradlew :openapi:updateOpenApi` after an intentional API change
- Public API check / update the dump: `./gradlew :contracts:checkKotlinAbi` / `./gradlew :contracts:updateKotlinAbi`
- License headers: `python3 scripts/ci/check_license_headers.py`
- Mutation testing (70 % threshold on the backend `domain` package, CI runs it in `mutation.yml`): `./gradlew :backend:pitest`; only some classes: `-PpitestTargets='pkg.Foo*,pkg.Bar*'`
- Script tests (`scripts/agent`, `scripts/ci`): `python3 -m venv .venv && .venv/bin/pip install -r scripts/agent/requirements-dev.txt && .venv/bin/python -m pytest scripts`
- SDK tests: `TBD` (no SDK yet)

## Current state
- Phase 0 done (2026-10-30): docs closed, identifiers reserved.
- Phase 0 wrap-up done (2026-10-31): planning docs at the repo root, NOTICE added (LICENSE came from GitHub). No code yet.
- Phase 1, task 1 done (2026-11-03, branch `feat/project-skeleton`): Gradle multi-module (`contracts` KMP with jvm/android/iOS targets, `backend` Ktor with `GET /health`), docker-compose with Postgres, ktlint + detekt, GitHub Actions CI. Kotest confirmed to work with Pitest. **Public API:** first public symbol in `contracts`, `HealthResponse(status)`; `explicitApi()` is enabled.
- Phase 1, task 2 done (2026-11-03, branch `feat/agent-guardrails`): the five guardrail scripts in `scripts/agent/` with 242 pytest cases (also in CI, job `agent-scripts`), Claude Code hooks in `.claude/settings.json`, and the OpenCode and Pi wiring documented in `docs/harnesses/`. Guardrails are active in Claude Code sessions opened inside the repo.
- Known gap: an agent could edit `.claude/settings.json` or `scripts/agent/` itself; treat changes to them as a review item.
- Phase 1, task 3 done (2026-11-04, branch `feat/rules-as-checks`): Konsist architecture tests with canary tests (`architecture-tests/`), public API dump for `contracts` (Kotlin ABI validation, `contracts/api/`), license-header check (`scripts/ci/`), and Pitest reporting in CI (`mutation.yml`, informative until the first domain logic). **Public API:** none added; the first dump records `HealthResponse`.
- API contracts task done (2026-11-04, branch `feat/api-contracts`): the `contracts/` models of the whole API (`CustomerInfo`, entitlements, offerings, receipts, errors, enums), the `:openapi` module that generates `docs/api/openapi.json` from them with a drift check (ADR 0012), and a Konsist rule that keeps `contracts/` platform independent. **Public API:** 3 enums (each with an `UNKNOWN` fallback), 11 data classes, the sealed interface `ReceiptRequest` and the shared JSON configuration `kuotaKitJson` added to `contracts` (see `contracts/api/`).
- Backend core done (2026-11-05, branch `feat/backend-core`): `domain` (purchases, `PurchaseRepository`, the pure `EntitlementCalculator`), `entitlements.yaml` and environment configuration, `storage` (Postgres with Exposed, Flyway and Hikari, tested with Testcontainers), API-key Bearer auth, `GET /v1/offerings` and `GET /v1/subscribers/{id}` with errors as `ErrorResponse`, docker-compose with a CI smoke test, and the mutation threshold (70 %) on `domain`. **Public API:** `ErrorCode` lost `FORBIDDEN` and `NOT_FOUND` and gained `INTERNAL_ERROR`; `contracts` gained `APP_USER_ID_PATTERN`. An app user id is the secret: any valid key reads any id, and an unknown id is an empty customer info.
- Backend part 2a done (2026-11-04, branch `feat/google-receipts`): `POST /v1/subscribers/{id}/receipts` for Google Play subscriptions (ADR 0013): pure state mapping and `ReceiptService` in `domain`, the official client only in `backend.google`, migration V2 (`on_hold`, `paused`), Google config by env (`docker-compose.google.yml`, `docs/google-play-setup.md`) and a Konsist rule for the package. **Public API:** none. Known limit: no notifications yet, so a refund before the stored end date is not seen. Only tested against a simulated Google.
- Backend part 2b done (2026-11-05, branch `feat/google-rtdn`): `POST /v1/webhooks/google` (ADR 0014): Pub/Sub OIDC token check, refunds and revocations applied from the verified message, other changes re-read from Google, duplicate pushes skipped (`store_events`, migration V3), config `KUOTAKIT_GOOGLE_PUBSUB_*`. **Public API:** none (the OpenAPI document gained a `502` on the webhook). Only tested with test-signed tokens and a simulated Google.
- Backend part 2c done (2026-11-05, branch `feat/google-one-time`): non-consumable one-time products (`purchases.productsv2`, `products.acknowledge`) chosen by the new optional `one_time_products` in `entitlements.yaml`, their notifications and refunds, and `linkedPurchaseToken` (the replaced subscription ends once the new one is saved) (ADR 0015). Pitest now runs all `domain` tests against any mutated class. **Public API:** none (`contracts/` unchanged). Not run against the real Google.
- Backend part 2d done (2026-11-06, branch `feat/rate-limit`): request limits per client address and, for receipts, per app user id (`ktor-server-rate-limit`, `429 rate_limited` with `Retry-After`, `KUOTAKIT_RATE_LIMIT_*`, `KUOTAKIT_TRUST_FORWARDED_FOR` only behind a proxy), and refunds or revocations that arrive before the receipt now wait in `pending_endings` (migration V4, 60 days) and are applied when it arrives (ADR 0016). **Public API:** `ErrorCode` gained `RATE_LIMITED` (`rate_limited`); `contracts/api/` and the OpenAPI document (a `429` on the three API-key endpoints) were regenerated. One process only; not measured against real traffic.
- Account id required (2026-11-06, branch `feat/require-account-id`): a Google Play receipt is accepted only if the purchase carries `playObfuscatedAccountId(appUserId)`, the SHA-256 of the app user id in lowercase hex (ADR 0017); without it `402`, with another user's `409`. SHA-256 is plain Kotlin in `contracts/` with the FIPS vectors and a JVM cross-check. **Public API:** `playObfuscatedAccountId(String): String` added to `contracts` (see `contracts/api/`). The SDK must attach it with `setObfuscatedAccountId`. Not run against the real Google.
- Next: the conformance suite (ADR 0007), the SDK core with the Android adapter (which attaches the account id) and the example app, then validate against the real Google with an app on an internal test track.
