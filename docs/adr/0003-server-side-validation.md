# ADR 0003 — Server-side validation is mandatory

**Status:** Accepted · **Date:** 2026-10-29

## Context
Purchases verified only on the device can be faked on rooted/jailbroken devices or with modified clients. A "client-only mode" would be tempting for quick demos but would make insecure setups the default.

## Decision
- The SDK never grants an entitlement by itself. Entitlements always come from the backend.
- The backend validates every purchase with the store API before granting access.
- On store API failure, the backend returns an error and does **not** grant access (fail closed). The SDK may serve the last known CustomerInfo from cache, marked as stale.
- Android purchases are acknowledged by the backend after successful validation (Play requires acknowledgement within 3 days or refunds the purchase).
- Store notifications are treated as triggers to re-fetch state from the store, never as the source of truth.

## Consequences
- Running the backend is required, even for a demo. The quickstart must make that one command.
- Store credentials live only on the server.
