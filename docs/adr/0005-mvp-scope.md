# ADR 0005 — v0 scope

**Status:** Accepted · **Date:** 2026-10-29

## Decision
v0 includes only:
- Auto-renewable subscriptions and non-consumable one-time purchases.
- `configure`, `getOfferings`, `purchase`, `restore`, `getCustomerInfo`, customer info listener.
- Anonymous app user IDs generated and stored on device.
- Google RTDN and App Store Server Notifications v2.

Explicitly out of v0: consumables, logIn/logOut and aliasing, promotional offers, paywalls, experiments, analytics, admin dashboard, web billing, outbound webhooks.

## Why
A small surface that works end to end on all four platforms is more useful (and more credible) than a wide surface that half works. Every "out" item is in the Later section of the roadmap.
