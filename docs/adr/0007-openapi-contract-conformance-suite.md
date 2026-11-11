# ADR 0007 — One reference backend, an OpenAPI contract and a conformance suite

**Status:** Accepted · **Date:** 2026-10-29

## Context
Some teams will prefer a backend in their own stack (TypeScript, Python, Go). Maintaining several backends in parallel would multiply every feature, store edge case and bug, including the security-critical validation code.

## Decision
- v0 ships a single reference backend (Ktor, ADR 0004).
- The HTTP API is published as an OpenAPI spec, generated from the shared `contracts/` module and versioned with the project (`docs/api/openapi.json`; how it is generated and checked is in [ADR 0012](0012-openapi-generated-from-contracts.md)).
- A **conformance suite** (`conformance/`) runs black-box tests against any backend URL and checks behavior, not implementation. Examples:
  - a valid purchase grants the configured entitlement;
  - an invalid or forged purchase grants nothing;
  - a refund or revocation removes access;
  - a store API failure never grants access (fail closed, ADR 0003);
  - the same purchase token cannot be claimed by two users.
- Store APIs are replaced by fakes in the suite, so it runs in CI without real accounts.

## Consequences
- The reference backend must pass the suite in CI from Phase 1.
- A second backend (by the maintainer or the community) is accepted only if it is fully conformant: it passes both levels of the suite (see the amendment below).
- The suite doubles as executable documentation of how a backend must behave.

## Amendment (2026-11-11): levels of conformance
The suite has two levels, and what a backend may call itself depends on which it passes:
- **Level 1, the contract.** What every backend does with no store involved: API keys, the error body of every status, request limits, offerings, an empty customer info, validation, and every answer matching `docs/api/openapi.json`. A backend that passes it is **contract conformant**.
- **Level 2, the behaviour of the stores.** Receipts, refunds, the webhook and the failures of Google, against a fake Google that the suite scripts (the profile a backend must offer for this is [ADR 0018](0018-conformance-profile.md)). A backend that passes both levels is **fully conformant**.

Why: a backend that cannot send its Google calls to another address can still prove that it keeps the API contract, and that is worth saying, but it has not proved that it fails closed. Only a fully conformant backend is accepted as an official second backend. The reference backend must be fully conformant in CI. The suite also checks itself: a proxy that corrupts the answers of the backend under test must turn the scenario meant to notice each corruption red. How to run it and what a backend needs: [`docs/conformance.md`](../conformance.md).
