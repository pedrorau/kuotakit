# ADR 0012 — The OpenAPI document is generated from `contracts/` and checked in CI

**Status:** Accepted · **Date:** 2026-11-04

## Context
ADR 0007 says the HTTP API is published as an OpenAPI spec generated from the shared `contracts/` models. The models say what the bodies look like; the paths, methods and status codes are not in the models. A hand-written spec next to the models would give two sources of truth that drift.

## Decision
- **Schemas come from the models.** The `:openapi` module (JVM only, no `@Serializable` of its own) walks the `SerialDescriptor` of each `contracts/` model and writes OpenAPI 3.1 schemas (`SchemaBuilder`). Nullable properties become `["type", "null"]`, `Instant` becomes `format: date-time`, sealed hierarchies become `oneOf` with a `discriminator` and its `mapping`, and nothing sets `additionalProperties: false`, so a newer backend stays compatible with older clients.
- **Paths come from a typed table.** `ENDPOINTS` lists every endpoint with its parameters, its auth, its request and response models and the error cases. Examples are built from the models themselves, so they cannot disagree with the wire format.
- **The result is committed** as `docs/api/openapi.json` (JSON, no extra YAML dependency, stable key order). `./gradlew :openapi:checkOpenApi`, part of `check`, fails when the committed file differs from what the code produces; `:openapi:updateOpenApi` rewrites it. Like the public API dump (ADR 0011), a change is always visible in review.
- **Tests** parse the document with the Atlassian `swagger-request-validator` (the library the conformance suite will use), check that every `$ref` resolves and that no schema is orphaned, and validate every example, in both directions, against the schemas. Negative cases prove the schemas reject what the contract forbids.
- The Konsist rule "the common code of `contracts/` imports only Kotlin and kotlinx" keeps the models usable from every platform.

- **One wire configuration.** `kuotaKitJson` (in `contracts/`) is the `Json` that every backend and SDK must use for these models: it ignores unknown keys, writes nullable fields as explicit `null` and encodes defaults. The wire format depends on these settings, so they live next to the models. A receipt must be encoded as a `ReceiptRequest`; encoding it with the serializer of its own class leaves out the `store` field.
- **Newer backends, older clients.** Every enum has an `UNKNOWN` member and its own serializer: a value the client does not know decodes as `UNKNOWN` instead of failing the whole body, and `UNKNOWN` is written back as `unknown` (the SDK caches customer info). The OpenAPI document lists only the values a backend sends. Adding a field or an enum value is therefore compatible at the wire level; it still changes the public API dump (the generated `copy`/`componentN` of a data class), so binary compatibility for SDK consumers is a post-1.0 promise.

## Alternatives considered
- **kotlinx-schema 0.6.0** (JetBrains, experimental). A spike with our models showed it omitted `nullable` for our nullable properties, left `Instant` without a format, and used `$defs` with fully qualified names and `additionalProperties: false`; fixing all of that after the fact would be more code and more fragile than walking the descriptors ourselves.
- **Ktor's OpenAPI support** (`ktor-server-routing-openapi`, `smiley4/ktor-openapi`). It documents the routes that exist and serves the result at runtime; its APIs are experimental and there is no file export. The backend has one endpoint today, and the spec must describe all of them from the start.
- **Spec first** (a hand-written YAML plus OpenAPI Generator for the models). Contradicts ADR 0007 and gives two sources of truth.

## Consequences
- The conformance suite validates responses with the Atlassian validator, which by default reports properties not declared in a schema (it treats a missing `additionalProperties` as "not allowed"); the suite turns that check off so a backend that adds a field is not rejected.
- Adding an endpoint means adding it to `ENDPOINTS` (and the route to the backend); the document, its examples and the checks follow.
- Webhook request bodies are defined by Google and Apple, so they are described as opaque objects until the webhook endpoints bring their models.
- The walker supports the kinds the API uses (classes, enums, sealed, lists, maps, primitives, `Instant`) and fails loudly on anything else, instead of producing a vague schema.
