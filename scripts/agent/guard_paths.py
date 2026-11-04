#!/usr/bin/env python3
"""PreToolUse guard: block edits to secrets and credentials.

The patterns are read from the "Secrets and credentials" block of `.gitignore` (single source of truth), so what
git refuses to track is also what an agent may not touch. Template files (`!.env.example`...) stay editable.
"""

from __future__ import annotations

import sys

from _lib import PATH_TOOLS, PRE, Decision, Event, allow, deny, load_secret_rules, repo_root, run, secret_hit


def decide(event: Event) -> Decision:
    if event.kind != PRE or event.tool not in PATH_TOOLS:
        return allow()
    if not event.paths:
        return deny("Cannot tell which file this edit targets, so it is blocked to be safe.")
    root = repo_root(event.cwd)
    rules = load_secret_rules(root)
    for raw in event.paths:
        name = secret_hit(raw, event.cwd, root, rules)
        if name:
            return deny(
                f"Blocked: '{name}' matches a secrets pattern in .gitignore (credentials never go through an agent). "
                "Ask the maintainer to change it; do not try to work around this guard."
            )
    return allow()


def main() -> int:
    return run(decide)


if __name__ == "__main__":
    sys.exit(main())
