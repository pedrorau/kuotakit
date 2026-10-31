# Roadmap

Estimates assume ~10 hours per week. Each phase ends with an exit criterion that can be checked, not a feeling.

## Phase 0 — Planning ✅ (done 2026-10-30)
- Vision, scope, ADRs, API spec, AI workflow, AGENTS.md.
- Name: **KuotaKit**. Identifiers (ADR 0008): site `kuotakit.pedrorau.dev`, Maven group `dev.pedrorau.kuotakit`, npm org `@kuotakit`, pub.dev publisher `pedrorau.dev`, GitHub repo `kuotakit`.
- **Exit:** docs reviewed, identifiers reserved.

## Phase 1 — Backend + Android (4–6 weeks) ← current
- Task 1: monorepo skeleton (Gradle, `contracts/`, backend with `/health`, docker-compose with Postgres, CI). LICENSE and NOTICE already added (Phase 0 wrap-up).
- Task 2: agent guardrail scripts in `scripts/agent/` + Claude Code hooks (ADR 0010), including the protected-tests and tests-must-pass guards (they only need the Gradle tests from task 1, so there is no reason to wait).
- Task 3: rules as checks (ADR 0011): Konsist architecture tests, Binary Compatibility Validator, license-header check; Pitest mutation testing enabled with the first real domain logic (entitlement computation).
- `contracts/` module with the API models; OpenAPI spec generated from it.
- Backend (Ktor): `POST /v1/subscribers/{id}/receipts` (Google), `GET /v1/subscribers/{id}`, `GET /v1/offerings`, Google RTDN webhook, docker-compose.
- Conformance suite: first scenarios (valid purchase, invalid purchase, refund, store failure, token reuse), running in CI.
- SDK core (KMP) + Android adapter: configure, getOfferings, purchase, restore, getCustomerInfo, customer info listener.
- Example Android app against a Play Console internal-testing track.
- **Exit:** a test subscription bought on a real device unlocks `premium`; a cancellation from Play Console shows up as not renewing; tests green in CI.

## Phase 2 — iOS (4–6 weeks)
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
