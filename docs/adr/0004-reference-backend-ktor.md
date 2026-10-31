# ADR 0004 — Reference backend in Kotlin (Ktor + Postgres)

**Status:** Accepted · **Date:** 2026-10-29

## Context
The backend is a reference implementation that developers deploy and may read or modify. Most adopters will run it as a Docker image, so ease of operation matters more than language. For the project itself, the biggest risk is contract drift between the SDK and the backend, and mistakes in store signature verification.

## Decision
- Kotlin, Ktor, kotlinx.serialization, Exposed + Flyway for Postgres (behind a storage interface, ADR 0009).
- The API data model (`CustomerInfo`, entitlements, request/response payloads) lives in a shared KMP module, `contracts/`, used by both the SDK core and the backend.
- Google: official Java client for the Android Publisher API.
- Apple: Apple's official App Store Server Library for Java (App Store Server API calls and JWS verification).
- Packaged with `docker-compose` (API + Postgres). Configuration via env vars + `entitlements.yaml`.
- Tooling: ktlint, detekt, Kotest/JUnit, Testcontainers for Postgres.

## Consequences
- One source of truth for the API contract: a field change breaks the build on both sides instead of at runtime.
- Official store libraries for both stores reduce the risk of getting signature verification wrong.
- "Kotlin end to end" is a clear differentiator for the project.
- Fewer potential contributors than TypeScript or Python, and a heavier image (JVM). Mitigated by ADR 0007: the OpenAPI spec and conformance suite let others build backends in other languages.

## Alternatives considered
- Python (FastAPI): simple and widely used, but the data model would be duplicated in two languages.
- TypeScript (Node): largest contributor pool; best second option if adoption becomes the main goal.
- Go: good for small images, but Apple does not publish an official Go library, and signature verification would rely on community code.
