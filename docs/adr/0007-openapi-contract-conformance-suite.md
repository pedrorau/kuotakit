# ADR 0007 — One reference backend, an OpenAPI contract and a conformance suite

**Status:** Accepted · **Date:** 2026-10-29

## Context
Some teams will prefer a backend in their own stack (TypeScript, Python, Go). Maintaining several backends in parallel would multiply every feature, store edge case and bug, including the security-critical validation code.

## Decision
- v0 ships a single reference backend (Ktor, ADR 0004).
- The HTTP API is published as an OpenAPI spec, generated from the shared `contracts/` module and versioned with the project.
- A **conformance suite** (`conformance/`) runs black-box tests against any backend URL and checks behavior, not implementation. Examples:
  - a valid purchase grants the configured entitlement;
  - an invalid or forged purchase grants nothing;
  - a refund or revocation removes access;
  - a store API failure never grants access (fail closed, ADR 0003);
  - the same purchase token cannot be claimed by two users.
- Store APIs are replaced by fakes in the suite, so it runs in CI without real accounts.

## Consequences
- The reference backend must pass the suite in CI from Phase 1.
- A second backend (by the maintainer or the community) is accepted only if it passes the full suite.
- The suite doubles as executable documentation of how a backend must behave.
