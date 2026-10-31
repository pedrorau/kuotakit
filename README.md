# KuotaKit

> **Subscriptions without the extra commission.**

> Open-source, self-hosted in-app purchases and subscriptions for mobile apps.
> One SDK for Android, iOS, React Native and Flutter, plus a reference backend you run yourself.

**Status:** early development (Phase 1). Not ready for production use.

## Why

Selling subscriptions in a mobile app means dealing with Google Play Billing and StoreKit 2: two different APIs, server-side receipt validation, renewal and refund notifications, grace periods, plan changes. Hosted services solve this well, but you send them your sales data and usually pay a percentage of your sales.

KuotaKit is for teams that want to:
- **own their data** (subscriber and purchase data stays in their database),
- **pay no third-party commission** (store fees still apply),
- **self-host** on their own infrastructure,
- use a **Kotlin-first** stack, end to end: KMP SDK with first-class Android support and a Kotlin backend.

## What it is (and isn't)

| KuotaKit is | KuotaKit is not |
|---|---|
| A client SDK + a reference backend you deploy | A hosted SaaS |
| Server-side validated purchases and entitlements | A payment processor (stores still charge the user) |
| Configured with a simple YAML file | A paywall builder, A/B testing or analytics platform (not in v0) |

## Architecture

```
┌────────────────────────── Your app ──────────────────────────┐
│  React Native / Flutter bridge  ──►  KuotaKit SDK             │
│                                       ├─ core (KMP): API client, cache, entitlement model
│                                       ├─ Android adapter: Play Billing (Kotlin)
│                                       └─ iOS adapter: StoreKit 2 (Swift)
└──────────────────────────────┬───────────────────────────────┘
                               │ HTTPS (app key)
┌──────────────────────────────▼───────────────────────────────┐
│  KuotaKit backend (you host it): Kotlin + Ktor + Postgres      │
│   ├─ validates purchases with Google Play / App Store APIs    │
│   ├─ computes entitlements from entitlements.yaml             │
│   └─ receives store notifications (RTDN, ASSN v2)             │
└───────────────────────────────────────────────────────────────┘
```

## Monorepo layout (planned)

```
contracts/            Shared KMP module: API models, used by the SDK and the backend
backend/              Reference backend (Ktor, Postgres, docker-compose)
conformance/          Black-box test suite any backend must pass
sdk/core/             Kotlin Multiplatform core
sdk/android/          Play Billing adapter
sdk/ios/              StoreKit 2 adapter (Swift) + KMP framework integration
bridges/react-native/ React Native package
bridges/flutter/      Flutter plugin
examples/             Sample apps (Android, iOS, RN, Flutter)
docs/                 Architecture decisions, API spec, guides
scripts/agent/        Guardrail scripts shared by every AI harness (ADR 0010)
evals/                Repeatable agent tasks to compare prompts, models and harnesses (later)
.github/workflows/    CI
```

## Planned SDK API (v0)

```kotlin
// Illustrative: the final API may differ. The HTTP contract is in docs/api-spec.md.
KuotaKit.configure(KuotaKitConfig(apiUrl = "https://billing.myapp.com", appKey = "pk_..."))

val offerings    = KuotaKit.getOfferings()
val result       = KuotaKit.purchase(activity, offerings.current.monthly)
val customerInfo = KuotaKit.getCustomerInfo()
val restored     = KuotaKit.restore()

if (customerInfo.entitlements["premium"]?.isActive == true) unlockPremium()
```

## Roadmap

See [docs/roadmap.md](docs/roadmap.md). In short: backend + Android → iOS → React Native + Flutter → docs and launch.

## Docs

- [Roadmap](docs/roadmap.md)
- [Backend API spec](docs/api-spec.md)
- [Architecture decisions](docs/adr/)
- [How this project uses AI](docs/ai-workflow.md) · [review checklist](docs/review-checklist.md) · [store docs rules](docs/store-docs-rules.md) · [harness setup](docs/harnesses/)
- [Session log](docs/session-log.md)
- [Security policy](SECURITY.md)

## License

Apache-2.0 (see [LICENSE](LICENSE) and [ADR 0006](docs/adr/0006-license-apache-2.md)).

*Google Play and App Store are trademarks of Google LLC and Apple Inc. KuotaKit is not affiliated with them or with any subscription platform.*
