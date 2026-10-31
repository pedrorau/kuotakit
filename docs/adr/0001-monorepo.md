# ADR 0001 — Single monorepo

**Status:** Accepted · **Date:** 2026-10-29

## Context
The project has a backend, a KMP core, two native adapters, two cross-platform bridges and example apps. They change together: a new field in CustomerInfo touches the backend, the core and every bridge.

## Decision
One monorepo with top-level `contracts/`, `backend/`, `conformance/`, `sdk/`, `bridges/`, `examples/`, `docs/`, `scripts/` (agent guardrails and CI helpers) and `evals/` (layout in README). Each package keeps its own build tool (Gradle, SPM, npm, pub) but shares CI, versioning and docs.

## Consequences
- One PR can change the contract end to end, and reviewers see it all.
- CI must only build what changed (path filters) to stay fast.
- Releases are versioned together (all packages share `0.x.y`) to keep compatibility simple.

## Alternatives considered
- One repo per platform (like most hosted SDK vendors): better isolation, but cross-cutting changes need coordinated PRs. Too much overhead for a solo maintainer.
