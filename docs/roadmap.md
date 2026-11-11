# Roadmap

Estimates assume ~10 hours per week. Each phase ends with an exit criterion that can be checked, not a feeling.

## Phase 0 — Planning ✅ (done 2026-10-30)
- Vision, scope, ADRs, API spec, AI workflow, AGENTS.md.
- Name: **KuotaKit**. Identifiers (ADR 0008): site `kuotakit.pedrorau.dev`, Maven group `dev.pedrorau.kuotakit`, npm org `@kuotakit`, pub.dev publisher `pedrorau.dev`, GitHub repo `kuotakit`.
- **Exit:** docs reviewed, identifiers reserved.

## Phase 1 — Backend + Android (4–6 weeks) ← current
- Task 1: monorepo skeleton (Gradle, `contracts/`, backend with `/health`, docker-compose with Postgres, CI). LICENSE and NOTICE already added (Phase 0 wrap-up).
- Task 2: agent guardrail scripts in `scripts/agent/` + Claude Code hooks (ADR 0010), including the protected-tests and tests-must-pass guards (they only need the Gradle tests from task 1, so there is no reason to wait).
- Task 3: rules as checks (ADR 0011): Konsist architecture tests, public API dump (Kotlin ABI validation), license-header check, and Pitest reporting in CI. The mutation score threshold is switched on with the first real domain logic (entitlement computation).
- `contracts/` module with the API models; OpenAPI spec generated from it (`docs/api/openapi.json`, ADR 0012). ✅
- Backend (Ktor), part 1 ✅: storage interface with Postgres, entitlement computation, `entitlements.yaml`, API keys, `GET /v1/offerings` and `GET /v1/subscribers/{id}`, docker-compose.
- Backend (Ktor), part 2a (done): `POST /v1/subscribers/{id}/receipts` for Google subscriptions (ADR 0013).
- Backend (Ktor), part 2b (done): the Google RTDN webhook (ADR 0014).
- Backend (Ktor), part 2c (done): one-time products and `linkedPurchaseToken` (ADR 0015).
- Backend (Ktor), part 2d (done): request limits and refunds that arrive before the first receipt (ADR 0016).
- Require the account id on Google receipts (done, ADR 0017). The SDK must attach `playObfuscatedAccountId(appUserId)` when it starts a purchase.
- Plans in the repository (`docs/plans/`) and the design rule "no implicit state machines" (in progress, `docs/plans/2026-11-plans-in-the-repo.md`).
- Conformance suite, in three pull requests: the backend can talk to a Google at another address when explicitly allowed (ADR 0018, done); then the base of the black-box suite (2a: runner, fake Google, Level 1, a check of the suite itself, CI); then the behaviour of the stores (2b: Level 2). Plans: `docs/plans/2026-11-conformance-profile.md`, `docs/plans/2026-11-conformance-suite-base.md` and `docs/plans/2026-11-conformance-suite-behaviour.md`.
- Deferred plan changes in the backend (`docs/plans/2026-11-backend-deferred-plan-changes.md`, ADR 0020): in progress; the SDK offers the mode in 1b-ii.
- SDK core (KMP) + Android adapter, in subphases: 1a (in progress, `docs/plans/2026-11-sdk-android-1a.md`, ADR 0019): configure, getOfferings, purchase, getCustomerInfo, customerInfo flow; 1b-i (in progress, `docs/plans/2026-11-sdk-android-1b-i.md`): restore, recovery of unsent purchases, `ITEM_ALREADY_OWNED`; 1b-ii: plan changes, offline cache, an end-to-end test against the reference backend, the public API closed. Phase 1 closes only with the complete Android SDK.
- Example Android app against a Play Console internal-testing track.
- **Exit:** a test subscription bought on a real device unlocks `premium`; a cancellation from Play Console shows up as not renewing; tests green in CI.

## Phase 2 — iOS (4–6 weeks)
- **Before starting:** audit of the implicit state machines of `backend/` and `contracts/` (report in `docs/audits/`, findings verified against the code, no change applied by the audit itself). Apple adds its own mapping on top of `Purchase`, so the audit is better done first. It also assesses whether the design rule can become an automatic check (ADR 0011) and what it would cost.
- Backend: App Store Server API validation + App Store Server Notifications v2.
- StoreKit 2 adapter in Swift, consuming the KMP core.
- Example iOS app (StoreKit configuration file for local tests, then sandbox).
- **Exit:** same scenarios as Phase 1 pass on iOS sandbox.

## Phase 3 — React Native + Flutter (3–4 weeks)
- Thin bridges over the native SDKs; no business logic in the bridges.
- Example apps for both.
- **Exit:** purchase → entitlement works from RN and Flutter on Android and iOS.

## Phase 4 — Docs and launch (2–3 weeks)
- Quickstart: from `git clone` to first sandbox purchase in under 30 minutes.
- Guides: store credentials setup (Google service account, App Store Server API key), deploying the backend, entitlements.yaml.
- Launch: blog post, talk at a meetup, post on communities.
- **Exit:** v0.1.0 published to Maven Central, Swift Package Manager, npm and pub.dev.
- iOS ships via SPM only: CocoaPods trunk becomes read-only on 2026-12-02.

## Later (not v0)
- User identity: logIn / logOut / aliasing.
- Promotional offers, introductory offers UI helpers.
- Webhooks out to the customer's own systems.
- Minimal admin dashboard.
- A second backend (e.g. TypeScript), only if there is real demand, accepted once it passes the conformance suite.
- Paywalls.
- Abuse protection beyond fixed limits (ideas of the maintainer, 2026-11-06, see ADR 0016): combine behaviour patterns instead of counting only (many distinct app user ids or wrong keys from one address, repeated `token_already_linked`, bursts of invalid receipts) with progressive penalties; a way to cancel an app user id that leaked (the id is the user's secret and a leak is the owner's responsibility, but the backend can help to replace it); and **Redis** (or similar) as an optional shared store for counters, bans and other state that must live across several backend instances.
