#!/usr/bin/env python3
"""Summarize Pitest's `mutations.xml` as a Markdown table (for the GitHub job summary)."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


def summarize(xml_path: Path) -> str:
    mutations = ET.parse(xml_path).getroot().findall("mutation")
    status = Counter(m.get("status", "UNKNOWN") for m in mutations)
    # Pitest itself says which mutants count as detected (killed, timed out, non-viable, errors...).
    detected = sum(1 for m in mutations if m.get("detected") == "true")
    covered = len(mutations) - status["NO_COVERAGE"]
    # Truncated, like Pitest's own report, so the numbers match it.
    score = f"{100 * detected // len(mutations)} %" if mutations else "n/a"
    strength = f"{100 * detected // covered} %" if covered else "n/a"
    rows = "\n".join(f"| {name} | {count} |" for name, count in sorted(status.items()))
    return (
        f"### Mutation testing (informative, no threshold yet)\n\n"
        f"Mutations: **{len(mutations)}** · mutation score: **{score}** · test strength: **{strength}**\n\n"
        f"| Status | Count |\n|---|---|\n{rows}\n"
    )


def main(argv: list[str] | None = None) -> int:
    args = argv if argv is not None else sys.argv[1:]
    reports = sorted(Path(args[0] if args else "backend/build/reports/pitest").rglob("mutations.xml"))
    if not reports:
        print("### Mutation testing\n\nNo mutation report was produced (nothing to mutate).")
        return 0
    print(summarize(reports[-1]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
