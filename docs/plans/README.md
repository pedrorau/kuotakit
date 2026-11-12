# Plans

A plan is the written agreement for one task, made before any code is written. It lives in the repository, not in a chat window, so that any person or any agent harness (ADR 0010) can read what was decided, what "done" means and what was left out.

## How to use them
- **One plan per task that changes behaviour or structure** (a typo or a pure rename needs none), named `YYYY-MM-<slug>.md`, written from [`_template.md`](_template.md) *before* implementing. It replaces the plan an agent would otherwise keep in its own tool: if a harness has a plan mode (read-only in some), the file is saved right after the approval.
- **The maintainer approves it.** Until then it is a draft and nothing is implemented.
- **It is checked off as the work advances**: acceptance criteria and steps are checkboxes, and the `Status` section says where the task is.
- **The reviewer reads the diff against it** (see [../review-checklist.md](../review-checklist.md)). The request for the review names the plan, which may still be untracked and so absent from `git diff`: every acceptance criterion must be true, and nothing outside the scope may have changed. The last criterion of a plan is that review, and it says what the reviewer checks.
- **It is closed with the task:** all criteria checked (or dropped, with the reason written), `Status` set to `Done`, and the branch or PR written in the header.
- It does not replace the ADRs (a lasting decision about the architecture) or the [session log](../session-log.md) (what happened and what went wrong). It says what we are going to do and how we will know it is done.

## Acceptance criteria
Each one must be something that can be checked: a test that exists, a command with its expected result, or a concrete review ("the diff of `contracts/api/` adds only X"). "Works well" is not a criterion.

## State of a plan
Plans have a small state machine of their own, written once here:

| From | To | When |
|---|---|---|
| Draft | Approved | the maintainer approves it explicitly |
| Approved | In progress | the first step starts |
| In progress | Done | every criterion is checked or dropped with a written reason |
| Draft, Approved, In progress | Abandoned | the task is not going to be done; the reason is written |
| Approved, In progress | Superseded | another plan replaces it; the new one is linked |

Only the maintainer moves a plan from Draft to Approved. A plan that changes while in progress is edited in place and the change is written in its `Status` section.

## The origin of a plan
Every plan says where its text came from: `context` (written and approved in the conversation, then copied here), `transcript` (taken word for word from a saved conversation) or `reconstructed` (rebuilt from the ADRs, the session log, the PRs and the commits, and marked as such). A reconstructed plan must not invent criteria, steps or times that its sources do not support.

## Earlier work
Plans were not kept in the repository before 2026-11-11, and they are not backfilled: the earlier tasks are described by [../roadmap.md](../roadmap.md), the [ADRs](../adr/), the [session log](../session-log.md) and their pull requests.

## Index
- [2026-11-plans-in-the-repo.md](2026-11-plans-in-the-repo.md): this method.
- [2026-11-conformance-profile.md](2026-11-conformance-profile.md): the backend can talk to a Google at another address, and the conformance profile is defined.
- [2026-11-conformance-suite.md](2026-11-conformance-suite.md): the black-box suite that any backend must pass (superseded, split in two).
- [2026-11-conformance-suite-base.md](2026-11-conformance-suite-base.md): part 2a, the base: runner, fake Google, Level 1, mutant proxy, CI.
- [2026-11-conformance-suite-behaviour.md](2026-11-conformance-suite-behaviour.md): part 2b, Level 2, the behaviour of the stores.
- [2026-11-sdk-android-1a.md](2026-11-sdk-android-1a.md): the SDK core (KMP) and the Android adapter, subphase 1a (the basics).
- [2026-11-sdk-android-1b-i.md](2026-11-sdk-android-1b-i.md): the SDK for Android, subphase 1b-i: restore and recovery of purchases.
- [2026-11-backend-deferred-plan-changes.md](2026-11-backend-deferred-plan-changes.md): the backend accepts deferred plan changes (`ReplacementMode.DEFERRED`).
- [2026-11-sdk-android-1b-ii.md](2026-11-sdk-android-1b-ii.md): the SDK for Android, subphase 1b-ii: plan changes, offline cache, end-to-end test, closed API.
- [2026-11-example-app.md](2026-11-example-app.md): the example Android app (the sandbox).
- [2026-11-example-app-privacy-link.md](2026-11-example-app-privacy-link.md): the example app links to its privacy policy and says "not set up yet" kindly.
