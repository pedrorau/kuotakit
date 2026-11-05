#!/usr/bin/env python3
"""Fail when a tracked source file lacks the Apache-2.0 license header (ADR 0006, ADR 0011).

Checks the application source code: Kotlin, Java, Swift, Dart, TypeScript and JavaScript. Gradle scripts (`.kts`),
Python scripts and generated files are out of scope. Only files tracked by git are looked at, so build output and
`node_modules` never matter. Standard library only; run it from anywhere inside the repository.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

SOURCE_SUFFIXES = {".kt", ".java", ".swift", ".dart", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".mts", ".cts", ".m", ".mm", ".h"}
# Only vendored or generated-by-convention files. Directories called `build` or `generated` are NOT skipped: a package
# can have that name, and build output is not tracked by git anyway.
GENERATED = re.compile(r"(^|/)(node_modules|Pods|\.dart_tool)/|\.g\.dart$|\.freezed\.dart$|\.d\.ts$")
HEADER_LINES = 30
COPYRIGHT = re.compile(r"Copyright (\(c\) )?20\d\d(-20\d\d)? Pedro Rau")
LICENSE = re.compile(r"Licensed under the Apache License, Version 2\.0")

TEMPLATE = """/*
 * Copyright 2026 Pedro Rau
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
"""


def has_header(text: str) -> bool:
    head = "\n".join(text.splitlines()[:HEADER_LINES])
    return bool(COPYRIGHT.search(head) and LICENSE.search(head))


def source_files(root: Path) -> list[str]:
    """Tracked, regular (not symlinked, not deleted) source files in scope."""
    out = subprocess.run(["git", "ls-files", "-z"], cwd=root, capture_output=True, text=True, check=True).stdout
    names = [f for f in out.split("\0") if f and Path(f).suffix in SOURCE_SUFFIXES and not GENERATED.search(f)]
    return [f for f in names if (root / f).is_file() and not (root / f).is_symlink()]


def missing_headers(root: Path) -> list[str]:
    return [f for f in source_files(root) if not has_header((root / f).read_text(encoding="utf-8", errors="replace"))]


def git_root(start: Path) -> Path | None:
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"], cwd=start, capture_output=True, text=True, check=True
        ).stdout
    except (subprocess.CalledProcessError, OSError):
        return None
    return Path(out.strip())


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="a directory inside the git repository")
    parser.add_argument(
        "--min-files",
        type=int,
        default=1,
        help="fail when fewer source files are found: zero checked files would mean the check silently covers nothing",
    )
    args = parser.parse_args(argv)
    root = git_root(args.root)
    if root is None:
        print(f"{args.root} is not inside a git repository.", file=sys.stderr)
        return 2
    checked = source_files(root)
    if len(checked) < args.min_files:
        print(f"Only {len(checked)} source files found (expected at least {args.min_files}): is the check pointing at the right place?", file=sys.stderr)
        return 1
    missing = missing_headers(root)
    if not missing:
        print(f"License headers OK ({len(checked)} source files checked).")
        return 0
    print("These files lack the Apache-2.0 license header (ADR 0006):", file=sys.stderr)
    for name in missing:
        print(f"  {name}", file=sys.stderr)
    print("\nAdd this at the top of each file (use the file's own comment style):\n", file=sys.stderr)
    print(TEMPLATE, file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
