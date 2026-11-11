# Review checklist

Used before every commit, by a human or by whatever review agent the harness provides (in Claude Code: the `reviewer` subagent). Review the current diff (`git diff --staged`, or `git diff` if nothing is staged). Read-only: do not edit files, only run read-only commands.

Check, in this order:
1. Non-negotiable rules in [AGENTS.md](../AGENTS.md): entitlements only from the backend, fail closed, no secrets, public API changes flagged, no logic in bridges, storage only through the interface.
2. The task's plan in [plans/](plans/): the request for the review names it (it may be untracked and so absent from the diff, so read the file). Every acceptance criterion must be true, nothing outside its scope may have changed, and if it has a State model the code must match it.
3. Consistency with the ADRs in [adr/](adr/) and [api-spec.md](api-spec.md).
4. Tests: every behavior change has a test, and no existing test was weakened.
5. State: no implicit state machine in new code (several fields that must agree to mean one state, or a flag that takes part in a state without a justification in the plan).
6. Readability and naming.

Report findings with `file:line`, severity (blocker / should fix / nit) and why.
