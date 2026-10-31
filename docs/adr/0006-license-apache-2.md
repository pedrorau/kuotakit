# ADR 0006 — License: Apache-2.0

**Status:** Accepted · **Date:** 2026-10-30

## Context
The project wants companies to adopt it, including in commercial apps, and wants contributions.

## Decision
Apache-2.0 for every package in the monorepo.

## Why
- Permissive, like MIT, so commercial use is easy.
- Includes an explicit patent grant and patent-retaliation clause, which legal teams at larger companies tend to prefer.
- It is the default in the Android and Kotlin ecosystem.

## Consequences
- Each source file carries a short license header; a NOTICE file lists attributions.
- When reading other open-source projects for reference, respect their licenses and do not copy code without attribution.
