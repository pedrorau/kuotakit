# ADR 0002 — KMP core with thin native billing adapters

**Status:** Accepted · **Date:** 2026-10-29

## Context
Logic shared across platforms: HTTP client, caching, anonymous user ID, entitlement model, retry rules. Store integration differs: Play Billing is a Java/Kotlin library; StoreKit 2 is a Swift-only, async API that Kotlin/Native cannot call directly.

## Decision
- `sdk/core` in Kotlin Multiplatform owns all shared logic and the public data model.
- `sdk/android` implements the billing interface with Play Billing, in Kotlin.
- `sdk/ios` implements the same interface in Swift with StoreKit 2 and plugs it into the KMP core.
- React Native and Flutter bridges wrap the native SDKs and contain no business logic.

## Consequences
- Business rules are written and tested once.
- iOS needs a small Swift layer and a clear interop boundary (a protocol the Swift adapter implements).
- KMP iOS framework size and Swift interop ergonomics must be watched (consider SKIE).

## Alternatives considered
- Fully native SDKs per platform: duplicated logic, harder to keep in sync for one maintainer.
- KMP calling StoreKit 1 through Objective-C: StoreKit 1 is the legacy API; building new on it is a dead end.
