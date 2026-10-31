# ADR 0008 — Project identifiers under the maintainer's personal domain

**Status:** Accepted · **Date:** 2026-10-30

## Context
The project needs a website, a Maven Central namespace, a pub.dev publisher and an npm scope. A dedicated domain (e.g. `kuotakit.dev`) costs a yearly fee; the maintainer already owns `pedrorau.dev`. The project is also a way for the maintainer to be visible to developers and companies.

## Decision
- Website and docs: `kuotakit.pedrorau.dev`.
- Maven Central group: `dev.pedrorau.kuotakit` (verified with a DNS TXT record on `pedrorau.dev`). Artifacts: `kuotakit-core`, `kuotakit-android`, etc.
- pub.dev verified publisher: `pedrorau.dev`.
- npm: organization `@kuotakit` (does not depend on a domain).
- iOS: Swift Package Manager from the GitHub repo.

## Consequences
- No extra cost, and the project is clearly linked to its maintainer.
- The website can move to its own domain later with a redirect.
- **The Maven group is permanent.** Changing it later breaks every user's dependency. If the project ever moves to an organization, new artifacts would be published under a new group and the old ones relocated (Maven relocation POMs).
