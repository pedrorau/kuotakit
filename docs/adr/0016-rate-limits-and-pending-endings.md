# ADR 0016 — Request limits and refunds that arrive before the receipt

**Status:** Accepted · **Date:** 2026-11-06

## Context
Two gaps were left open on purpose by ADR 0013 and ADR 0014.
1. The API key in the app is public and the same for every user, so anyone who has it can make the backend ask Google about random purchase tokens and use up the Play Developer API quota; honest users then get `429` from Google, which the backend answers as `502`.
2. A refund or a revocation that reaches the webhook before the purchase has been submitted was acknowledged and dropped. If the receipt arrived later while Google still described the purchase as active, the user kept the access.

## Decision
### Request limits
- **Counted per client address, and for receipts also per app user id** (Ktor `ktor-server-rate-limit`, token bucket refilled every minute). A limit by API key was rejected: the key is public and shared, so one client could use up everybody's share.
  - every authenticated call: `KUOTAKIT_RATE_LIMIT_PER_MINUTE` per client address (default 300). It is counted *before* the key is checked, so a flood of wrong keys is limited too. **Calls with a wrong or missing key have a bucket of their own** (the same address, but apart) and cost 10 calls of it each, so a flood of them can never use up the budget of the calls that carry a good key, even when every client looks like the same address.
  - `POST /v1/subscribers/{id}/receipts`, the only call that makes the backend ask Google: `KUOTAKIT_RATE_LIMIT_RECEIPTS_PER_MINUTE` per client address (default 30) and `KUOTAKIT_RATE_LIMIT_RECEIPTS_PER_USER_PER_MINUTE` per app user id (default 10). A real purchase is rare, so these are generous.
  - `/health` and the Google webhook are not limited: the first costs nothing and the second already carries a token signed by Google.
- **The client address** is the connection's. Behind a reverse proxy every connection comes from the proxy, so `KUOTAKIT_TRUST_FORWARDED_FOR=true` makes the **last value of the last `X-Forwarded-For` line** the address (what the proxy appended; earlier values and lines can be written by the client). A value that is not an IP address is ignored and the connection is used. An IPv6 address counts as its /64 network, because one host owns billions of addresses in it. Without the setting the header is ignored, because anyone could forge it to dodge the limit.
- **The answer** is `429` with `Retry-After` and the usual error body; the new code is `rate_limited`. Adding it to `ErrorCode` is compatible (an older client reads it as `UNKNOWN` and the 429 says what happened) but it is a change of the public API of `contracts/`, recorded in `contracts/api/` and in the OpenAPI document.
- The counters are in memory, in one process. The library drops a key when its bucket refills (after a minute), so memory is bounded by the number of distinct keys seen in a minute. Addresses are limited by the /64 grouping and by the limit itself; the per-user key of an id that is not valid is one shared bucket (`invalid-id`), so garbage ids cannot make buckets.

### Refunds before the receipt
- A refund (voided purchase) or a revocation for a token nobody has submitted is written down in `pending_endings` (migration V4) instead of being dropped; the first reason is kept, and entries older than 60 days are deleted when a new one is written.
- When the receipt for that token arrives, the purchase is stored already `REFUNDED` or `REVOKED`, without being acknowledged, and the entry is removed. Google's current description does not override it. The purchase it replaced (`linkedPurchaseToken`) is not ended, because the replacement never gave access.
- **Both orders are covered, each with a second look.** After writing the entry the webhook looks for the purchase again, so a receipt stored between its first look and the write is not missed. The receipt looks for an entry before it acknowledges with Google and **again after the purchase is stored**, because the acknowledgement is a network round trip during which a refund can arrive when the purchase does not exist yet. (The first version looked only before, and a review found the window; there is a test that puts the refund in the middle of the acknowledgement.) The look-ups also ignore entries older than the retention, whether or not they were deleted yet.
- Only refunds and revocations wait. A change that could give access (a renewal, a recovery) is not kept: the receipt reads fresh state anyway.

## Known limits
- **Measured on 2026-11-06 (Colima on macOS, the setup of the maintainer): the backend sees every client of a published port as the same address.** A container behind `-p 8080:8080` saw `172.17.0.1`, the bridge gateway, for calls through `localhost` and through the LAN address; only a call straight to the VM's address kept its own source. With the real stack and a limit of 3 a minute, calls by `127.0.0.1` and by the LAN address shared one budget (the fourth was `429`) and a wrong key was refused too. Docker Desktop is likely to behave the same; a Linux server with Docker's default port forwarding normally keeps the client address, but behind a reverse proxy the problem is the same. For a developer alone on a laptop it is harmless; for a deployment it is not.
- **Behind a proxy, or wherever the backend sees one address for everybody, the defaults still lock people out of a shared budget.** If every connection looks like the proxy (or like the Docker gateway, measured above), all users share one budget of 300 calls and 30 receipts a minute. A flood of wrong keys no longer touches it (they have their own bucket), but the app's API key is public, so someone with it can still use the shared budget up from that one address. Only telling clients apart fixes that: `KUOTAKIT_TRUST_FORWARDED_FOR=true` behind a proxy, or the behaviour-based protection listed below. The backend writes one warning in the log the first time a request carries `X-Forwarded-For` while the setting is off (the notice itself is not tested; its decision is). Set `KUOTAKIT_TRUST_FORWARDED_FOR=true` behind a proxy, and only then. With two proxies in a row (a CDN and a server) the last value is the first proxy, so everyone behind it shares one budget again.
- **The app user id is the secret** (ADR 0009), and it also names the receipts budget. Anyone who knows an id and has the public API key can send junk to that user's receipts and keep their budget (10 a minute) empty, so the real receipt gets `429`. The same person could already read that user's data, which is why the id must not leak. The app should retry after `Retry-After`.
- **Header spoofing with the setting on and no proxy** also lets one client make many buckets (memory grows with the rate of requests for a minute). Do not turn it on without a proxy.
- **The OpenAPI document declares the `429` but not its headers** (`Retry-After`, `X-RateLimit-*`): the generator does not model response headers yet. `Retry-After` is tested.
- **A voided-purchase notification is assumed to mean the whole purchase** (ADR 0014). Before this change a wrong assumption only affected purchases already stored; now it can also affect the first receipt of a purchase, and `REFUNDED` is final.
- **One process.** With several instances each has its own counters, so the real limit is the sum. The documented deployment is one instance.
- **Shared addresses.** Many users behind one address (an office, a mobile operator's NAT) share the per-address budgets. The defaults are meant to be far above normal use; raise them with the variables if needed.
- **A forged address** is possible if `KUOTAKIT_TRUST_FORWARDED_FOR=true` is set without a proxy that overwrites the header.
- **Retention.** A refund older than 60 days that still has no receipt is ignored (and deleted when the next one is written); the same user could then store the purchase as Google describes it. The table also keeps refunds of purchases that never go through this backend for those 60 days.
- A pending refund applies to whoever submits the token first. The webhook that recorded it carried a token signed by Google, so the refund is genuine; the usual ownership rules of ADR 0013 still apply to the receipt.
- Not measured against real traffic or against the real Google.

## Ideas not done here (maintainer, 2026-11-06)
- Combine behaviour patterns with the counts (many distinct ids or wrong keys from one address, repeated `token_already_linked`, bursts of invalid receipts) and apply progressive penalties, instead of fixed limits only.
- Let the owner cancel an app user id that leaked. The id is the user's own secret and a leak is their responsibility, but the backend can help them replace it.
- Redis (or similar) as an optional shared store for counters, bans and any state that must be shared by several backend instances; today the counters are in one process.

## Alternatives considered
- **A limit by API key:** shared and public, so a single client could deny service to every user.
- **A global cap on Google calls instead of per client:** protects the quota but lets one client lock everybody out of purchasing.
- **Reading Google again to find out about a refund at receipt time:** the docs do not say how soon or in what form a refund shows in `subscriptionsv2`; a verified notification is the reliable signal.
- **Refusing a receipt for a purchase with a pending refund (402):** simpler, but the app would retry for ever; storing the purchase as refunded answers the question once and shows the user their real state.
