# Session log

One entry per working session. Template in [ai-workflow.md](ai-workflow.md).

## 2026-10-29 / 2026-10-30 — Phase 0: planning
- Goal: define vision, scope, architecture, API spec, name and AI workflow before writing any code.
- What the agent proposed / what I chose: backend in Python → I chose Kotlin + Ktor to share models with the SDK. Several names → KuotaKit. Separate domain vs personal domain → identifiers under pedrorau.dev (ADR 0008). Postgres + SQLite → Postgres only behind a storage interface (ADR 0009).
- What I had to correct: wording that sounded like avoiding store fees ("revenue", "commission-free") → "Subscriptions without the extra commission".
- Validation done: name conflicts checked (GitHub, npm, pub.dev, domains); identifiers reserved on Maven Central, npm and pub.dev.
- Time spent: ~1 h · Diff accepted as-is: n/a (docs only)

## 2026-10-31 — Phase 0 wrap-up: commit planning docs
- Goal: put the planning docs in the repo, add NOTICE, check docs for inconsistencies.
- What the agent proposed / what I chose: docs were in a `kuotakit-planning/` subfolder → moved to the repo root. LICENSE already existed (identical to the official Apache-2.0 text), so only NOTICE was added.
- What I had to correct: 14 doc inconsistencies found; resolved in this commit: store ids are `play_store`/`app_store`, `/v1` prefix in the roadmap, `current_offering` in entitlements.yaml, ADR 0010 allows `docs/harnesses/`, guard scripts named in AGENTS.md, ADR 0011 date and storage/test-framework wording, layout in README/ADR 0001.
- Validation done: LICENSE diffed against apache.org; no code, so no build or tests.
- Time spent: n/a · Diff accepted as-is: with changes (reviewer findings applied)

## 2026-11-03 — Phase 1, task 1: monorepo skeleton
- Goal: Gradle multi-module build, `contracts/` (KMP) with `HealthResponse`, Ktor backend with `GET /health` and a test, docker-compose with Postgres, ktlint + detekt, CI with path filters.
- What the agent proposed / what I chose: newest Gradle/AGP were newer than Kotlin 2.4.21 officially supports → capped at Gradle 9.7.0 and AGP 9.3.3. detekt has no stable release for Kotlin 2.4 → detekt 2.0.0-alpha.6 pinned. Colima instead of Docker Desktop. JDK 21 through the Gradle toolchain. Kotest kept after a Pitest spike.
- What I had to correct: (1) the version catalog had a `ktlint` / `ktlint-plugin` accessor clash. (2) detekt did nothing in `contracts` (KMP: the plain `detekt` task has no sources). (3) the `!!` rule needs type resolution, so with only the plain `detekt` task in `check` it silently never fired; caught by a negative test (adding `!!` on purpose) and fixed by wiring `detektMain`/`detektMainJvm` into `check`. A guardrail that looks active but is not is the worst kind.
- Validation done: `./gradlew build` green; backend + contracts tests ran; ktlint and detekt fail on deliberately bad code in both modules; backend answers `GET /health` with `{"status":"ok"}`; Pitest spike ran the Kotest specs.
- Public API: `contracts` gets its first public symbol, `HealthResponse(status)`. `explicitApi()` is on so nothing becomes public by accident until the API-dump check lands in task 3.
- Time spent: n/a · Diff accepted as-is: with changes (reviewer findings applied)
