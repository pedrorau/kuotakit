#!/usr/bin/env python3
"""PreToolUse guard for shell commands.

Blocks: force pushes and remote ref deletion, `git reset --hard` and other ways to throw away uncommitted work,
recursive `rm` / `find -delete` outside build directories, publishing to Maven, npm and pub.dev, and writes to
secret files. It is a best-effort safety net, not a sandbox: GitHub's ruleset and CI remain the final gate (see
docs/harnesses/guard-scripts.md for the known limits).
"""

from __future__ import annotations

import re
import sys

from _lib import (
    PRE,
    Decision,
    Event,
    Segment,
    allow,
    deny,
    edits_in_place,
    git_subcommand,
    load_secret_rules,
    non_options,
    repo_root,
    run,
    secret_hit,
    segments,
    short_flags,
    working_dirs,
)

BUILD_DIRS = frozenset(
    {"build", ".gradle", ".kotlin", "node_modules", ".dart_tool", "dist", "DerivedData", "Pods", "__pycache__", ".pytest_cache"}
)
GRADLE = frozenset({"gradle", "gradlew"})
NODE_PUBLISHERS = frozenset({"npm", "pnpm", "yarn", "bun"})
# Commands that write to the paths they receive; used to keep secrets out of reach through the shell.
WRITERS = frozenset({"tee", "cp", "mv", "install", "dd", "truncate", "rm", "ln", "touch", "rsync", "sponge"})
_SONATYPE = re.compile(r"closeandrelease|releasesonatype|sonatype", re.IGNORECASE)
# Gradle lets you abbreviate a task by its camel-case initials (`pAPTMCR` = publishAllPublicationsToMavenCentral...).
_PUBLISH_ABBREVIATION = re.compile(r"^p[A-Z]")


def _unsafe_target(target: str) -> bool:
    """True unless the path is relative and sits inside a build directory."""
    parts = [p for p in target.split("/") if p not in ("", ".")]
    return target.startswith(("/", "~", "$")) or ".." in parts or not any(p in BUILD_DIRS for p in parts)


def _git_reason(seg: Segment) -> str | None:
    sub, args = git_subcommand(seg)
    flags, positional = short_flags(args), non_options(args)
    if sub == "push":
        forced = any(a in {"--force", "--force-if-includes"} or a.startswith("--force-with-lease") for a in args)
        if forced or "f" in flags or any(a.startswith("+") for a in positional):
            return "git push --force rewrites remote history"
        if "--mirror" in args or "--delete" in args or "d" in flags or any(a.startswith(":") for a in positional):
            return "git push --mirror/--delete/:ref rewrites or deletes remote refs"
    if sub == "reset" and "--hard" in args:
        return "git reset --hard throws away uncommitted work"
    if sub == "clean" and ("f" in flags or "--force" in args):
        return "git clean -f deletes untracked files for good"
    if sub in {"checkout", "restore"} and any(p in {".", "*", ":/"} for p in positional):
        return f"git {sub} . throws away every uncommitted change"
    if sub in {"checkout", "switch"} and ("--force" in args or "--discard-changes" in args or "f" in flags):
        return f"git {sub} --force throws away uncommitted work"
    return None


def _rm_reason(seg: Segment) -> str | None:
    recursive = "--recursive" in seg.args or bool(set(short_flags(seg.args)) & {"r", "R"})
    if not recursive:
        return None
    for target in non_options(seg.args) or ["(nothing)"]:
        if _unsafe_target(target):
            return f"recursive rm of '{target}' is only allowed inside build directories ({', '.join(sorted(BUILD_DIRS))})"
    return None


def _find_reason(seg: Segment) -> str | None:
    if "-delete" not in seg.args:
        return None
    starts: list[str] = []
    for arg in seg.args:
        if arg.startswith(("-", "(", "!")):
            break
        starts.append(arg)
    for target in starts or ["."]:
        if _unsafe_target(target):
            return f"find -delete under '{target}' is only allowed inside build directories"
    return None


def _publish_reason(seg: Segment) -> str | None:
    exe, args = seg.exe, seg.args
    if exe in GRADLE:
        for task in non_options(args):
            name = task.rsplit(":", 1)[-1]
            if (
                (name.lower().startswith("publish") and name.lower() != "publishtomavenlocal")
                or _SONATYPE.search(name)
                or _PUBLISH_ABBREVIATION.match(name)
            ):
                return f"Gradle task '{task}' publishes (or may be an abbreviation of a task that publishes) to a remote repository"
    if exe in {"mvn", "mvnw"} and any(a in {"deploy", "release:perform"} for a in args):
        return "Maven deploy publishes to a remote repository"
    if exe in NODE_PUBLISHERS and "publish" in non_options(args)[:3]:
        return f"{exe} publish releases a package to npm"
    if exe in {"dart", "flutter"} and "pub" in args and "publish" in args:
        return f"{exe} pub publish releases a package to pub.dev"
    return None


def _secret_write_reason(seg: Segment, event: Event, rules: list[tuple[bool, str]]) -> str | None:
    candidates = list(seg.redirects)
    if seg.exe in WRITERS or edits_in_place(seg) or (seg.exe == "git" and git_subcommand(seg)[0] in {"rm", "mv"}):
        candidates += [a.removeprefix("of=") for a in seg.args if not a.startswith("-") or a.startswith("of=")]
    root = repo_root(event.cwd)
    for raw in candidates:
        for base in working_dirs(seg, event.cwd):
            name = secret_hit(raw, base, root, rules)
            if name:
                return f"'{name}' matches a secrets pattern in .gitignore"
    return None


def decide(event: Event) -> Decision:
    if event.kind != PRE or event.tool != "bash":
        return allow()
    try:
        parsed = segments(event.command)
    except ValueError as exc:
        return deny(f"Blocked: cannot analyze this command ({exc}). Simplify it into separate steps.")
    rules = load_secret_rules(repo_root(event.cwd))
    for seg in parsed:
        reason = None
        if seg.exe == "git":
            reason = _git_reason(seg)
        elif seg.exe == "rm":
            reason = _rm_reason(seg)
        elif seg.exe == "find":
            reason = _find_reason(seg)
        reason = reason or _publish_reason(seg) or _secret_write_reason(seg, event, rules)
        if reason:
            return deny(
                f"Blocked: {reason}. If this is really needed, explain why and ask the maintainer to run it; "
                "do not try to work around this guard."
            )
    return allow()


def main() -> int:
    return run(decide)


if __name__ == "__main__":
    sys.exit(main())
