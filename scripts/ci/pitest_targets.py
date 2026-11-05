#!/usr/bin/env python3
"""Print the Pitest `targetClasses` globs for the Kotlin classes a branch changed.

CI mutates only the code of the pull request (the free alternative to the commercial Arcmutate git plugin):
`backend/src/main/kotlin/dev/acme/Foo.kt` becomes `dev.acme.Foo*`, which also covers `FooKt` (top-level functions),
nested and generated classes. Prints nothing when no production code of the module changed.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import PurePosixPath

# File names come from pull requests (possibly from forks) and end up in a workflow's shell and GITHUB_OUTPUT:
# only plain Java/Kotlin identifiers are accepted, anything else is skipped.
SAFE_PART = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def changed_files(base: str) -> list[str]:
    out = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=AMR", "-z", f"{base}...HEAD"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    return [f for f in out.split("\0") if f]


def targets(files: list[str], module: str) -> list[str]:
    prefix = PurePosixPath(module, "src", "main", "kotlin")
    found = set()
    for name in files:
        path = PurePosixPath(name)
        if path.suffix == ".kt" and path.is_relative_to(prefix):
            parts = path.relative_to(prefix).with_suffix("").parts
            if all(SAFE_PART.fullmatch(part) for part in parts):
                found.add(".".join(parts) + "*")
    return sorted(found)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", required=True, help="the ref the branch is compared with, e.g. origin/main")
    parser.add_argument("--module", default="backend", help="Gradle module directory")
    args = parser.parse_args(argv)
    print(",".join(targets(changed_files(args.base), args.module)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
