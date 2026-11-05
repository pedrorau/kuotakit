# ADR 0009 — Storage behind an interface, Postgres only in v0

**Status:** Accepted · **Date:** 2026-10-30

## Context
Small deployments might prefer SQLite; others may want MySQL or a managed database. Supporting several databases from day one multiplies migrations and tests. ADR 0007 already solves "many backends" with a contract and a conformance suite; storage can use the same idea one level down.

## Decision
- The backend defines a storage interface (repositories for subscribers, purchases and events) in its domain layer. Business logic depends only on that interface. `PurchaseRepository` is the first one: it creates the subscriber row with the first purchase, so there is no separate subscribers repository yet; the events repository arrives with the store notifications.
- v0 ships one implementation: Postgres, with Exposed + Flyway migrations.
- Storage tests are written against the interface (a shared contract test suite), and run against Postgres with Testcontainers.
- The HTTP conformance suite (ADR 0007) does not know which database is used.

## Consequences
- Adding SQLite or another database later means one new implementation that passes the storage contract tests, with no changes to business logic.
- Slightly more structure than calling the database directly, accepted for testability.
- docker-compose keeps the default setup to one command.

## Alternatives considered
- Postgres and SQLite from v0: doubles migration and test work before there are users asking for it.

## Implementation notes (backend core)
- The interface and its `Purchase` model live in `backend.domain`; the Postgres implementation, the Exposed tables, Flyway and the connection pool live in `backend.storage` (`PostgresStorage.open` does all the wiring). Konsist rules keep it that way: only `storage` uses the persistence libraries, and `domain` knows neither Ktor nor `storage`.
- `PurchaseRepositoryContract` is the shared test suite. The in-memory fake and the Postgres implementation (a real Postgres 18 with Testcontainers) both pass it, including a race of several users for the same token: the unique constraint on `(store, purchase_token)` and a single upsert decide, so a token can never be linked to two users (HTTP 409 for the loser).
- Migrations are Flyway files in `backend/src/main/resources/db/migration/` and run at start-up.
