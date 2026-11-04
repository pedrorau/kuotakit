"""Shared helpers for the agent guardrail scripts (ADR 0010, ADR 0011).

Python 3.10+, standard library only. Every harness (Claude Code, OpenCode, Pi, Codex, Cursor...) calls the same
scripts; see docs/harnesses/guard-scripts.md for the contract.

Input (stdin, JSON), either the Claude Code hook payload (recognized by `hook_event_name`) or the neutral one:
    {"event": "pre_tool_use" | "post_tool_use" | "stop",
     "tool": "edit" | "write" | "delete" | "bash",
     "paths": ["relative/or/absolute/path"], "command": "...", "cwd": "...", "session_id": "..."}

Output: exit 0 = allow, exit 2 = block (reason on stderr), exit 3 = ask a human (neutral callers).
Claude Code gets its own dialect: "ask" becomes a `permissionDecision: "ask"` JSON on stdout with exit 0, and a
blocked Stop becomes a `decision: "block"` JSON (Claude Code treats a Stop hook that exits 2 with a "No such file"
message as a non-blocking error, so the JSON is the reliable way to block).
Guards fail closed: unreadable input or an internal error blocks the action.
"""

from __future__ import annotations

import fnmatch
import json
import os
import re
import shlex
import subprocess
import sys
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

ALLOW, ASK, DENY = "allow", "ask", "deny"
EXIT_ALLOW, EXIT_DENY, EXIT_ASK = 0, 2, 3

PRE, POST, STOP, OTHER = "pre_tool_use", "post_tool_use", "stop", "other"
PATH_TOOLS = frozenset({"edit", "write", "delete"})

# The project this script belongs to (scripts/agent/_lib.py -> repo root).
SCRIPT_ROOT = Path(__file__).resolve().parents[2]

_CLAUDE_EVENTS = {"PreToolUse": PRE, "PostToolUse": POST, "Stop": STOP}
# MultiEdit is not in the current Claude Code docs; kept for older builds.
_CLAUDE_FILE_TOOLS = {"Edit": "edit", "MultiEdit": "edit", "NotebookEdit": "edit", "Write": "write"}
_CLAUDE_PATH_KEYS = ("file_path", "notebook_path", "path")
_SECRETS_BLOCK = "secrets and credentials"


class EventError(ValueError):
    """The stdin payload is not a usable event."""


@dataclass(frozen=True)
class Event:
    kind: str
    tool: str
    paths: tuple[str, ...]
    command: str
    cwd: str
    session_id: str
    harness: str  # "claude" or "neutral"


@dataclass(frozen=True)
class Decision:
    action: str = ALLOW
    reason: str = ""


def allow() -> Decision:
    return Decision()


def ask(reason: str) -> Decision:
    return Decision(ASK, reason)


def deny(reason: str) -> Decision:
    return Decision(DENY, reason)


def parse_event(raw: str) -> Event:
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise EventError(f"stdin is not valid JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise EventError("stdin JSON must be an object")
    cwd = str(data.get("cwd") or os.getcwd())
    session_id = str(data.get("session_id") or "")
    if "hook_event_name" in data:
        return _parse_claude(data, cwd, session_id)
    return _parse_neutral(data, cwd, session_id)


def _parse_claude(data: dict, cwd: str, session_id: str) -> Event:
    kind = _CLAUDE_EVENTS.get(str(data["hook_event_name"]), OTHER)
    name = str(data.get("tool_name") or "")
    tool_input = data.get("tool_input") or {}
    if not isinstance(tool_input, dict):
        raise EventError("tool_input must be an object")
    tool, paths, command = OTHER, (), ""
    if name in _CLAUDE_FILE_TOOLS:
        tool = _CLAUDE_FILE_TOOLS[name]
        paths = tuple(str(tool_input[k]) for k in _CLAUDE_PATH_KEYS if tool_input.get(k))[:1]
    elif name == "Bash":
        tool, command = "bash", str(tool_input.get("command") or "")
    return Event(kind, tool, paths, command, cwd, session_id, "claude")


def _parse_neutral(data: dict, cwd: str, session_id: str) -> Event:
    kind = str(data.get("event") or "")
    if kind not in (PRE, POST, STOP):
        raise EventError(f'"event" must be one of {PRE}, {POST}, {STOP}')
    tool = str(data.get("tool") or "")
    paths = data.get("paths")
    if paths is None and data.get("path"):
        paths = [data["path"]]
    if not isinstance(paths, list | type(None)):
        raise EventError('"paths" must be a list')
    return Event(kind, tool, tuple(str(p) for p in paths or ()), str(data.get("command") or ""), cwd, session_id, "neutral")


def emit(decision: Decision, event: Event | None) -> int:
    """Render a decision for the caller's harness and return the exit code."""
    if decision.action == ALLOW:
        return EXIT_ALLOW
    claude = event is not None and event.harness == "claude"
    if claude and event.kind == PRE and decision.action == ASK:
        print(
            json.dumps(
                {
                    "hookSpecificOutput": {
                        "hookEventName": "PreToolUse",
                        "permissionDecision": "ask",
                        "permissionDecisionReason": decision.reason,
                    }
                }
            )
        )
        return EXIT_ALLOW
    if claude and event.kind == STOP:
        print(json.dumps({"decision": "block", "reason": decision.reason}))
        return EXIT_ALLOW
    if not claude:
        print(json.dumps({"decision": decision.action, "reason": decision.reason}))
    sys.stderr.write(decision.reason.rstrip() + "\n")
    neutral_ask = decision.action == ASK and event is not None and not claude
    return EXIT_ASK if neutral_ask else EXIT_DENY


def run(decide: Callable[[Event], Decision], *, error_exit: int = EXIT_DENY, stdin: str | None = None) -> int:
    """Read the event, decide, emit. Any error blocks (fail closed) unless `error_exit` says otherwise."""
    event = None
    try:
        event = parse_event(sys.stdin.read() if stdin is None else stdin)
        decision = decide(event)
    except Exception as exc:  # noqa: BLE001 - a guard must never crash open
        message = f"guardrail error, blocking to be safe: {type(exc).__name__}: {exc}"
        if event is not None and event.harness == "claude" and event.kind == STOP and error_exit == EXIT_DENY:
            print(json.dumps({"decision": "block", "reason": message}))
            return EXIT_ALLOW
        sys.stderr.write(message + "\n")
        return error_exit
    return emit(decision, event)


# --- repository and paths -------------------------------------------------------------------------------


def _has_secrets_block(root: Path) -> bool:
    try:
        return _SECRETS_BLOCK in (root / ".gitignore").read_text(encoding="utf-8").lower()
    except OSError:
        return False


def repo_root(cwd: str) -> Path:
    """The repository the rules come from.

    KUOTAKIT_REPO_ROOT wins. Otherwise the git root of `cwd` (so worktrees work) when it carries the secrets block;
    otherwise the repository this script lives in, so a session that wandered into /tmp or another repo neither
    breaks every command nor applies that other repo's rules.
    """
    override = os.environ.get("KUOTAKIT_REPO_ROOT")
    if override:
        return Path(override).resolve()
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"], cwd=cwd, capture_output=True, text=True, timeout=10, check=True
        )
        top = Path(out.stdout.strip()).resolve()
        if _has_secrets_block(top):
            return top
    except (OSError, subprocess.SubprocessError):
        pass
    return SCRIPT_ROOT


def git_root(cwd: str) -> Path | None:
    """The git repository the session works in, or None when `cwd` is not inside one.

    Unlike `repo_root` it never falls back to this project: the checks must look at the changes of the repository
    the agent is actually working on. A missing `git` or a timeout raises (callers must not treat it as "no repo").
    """
    override = os.environ.get("KUOTAKIT_REPO_ROOT")
    if override:
        return Path(override).resolve()
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"], cwd=cwd, capture_output=True, text=True, timeout=10, check=True
        )
    except subprocess.CalledProcessError:
        return None
    return Path(out.stdout.strip()).resolve()


def resolve(raw: str, cwd: str) -> Path:
    """Absolute path with `~` expanded and symlinks resolved, so a link cannot hide a protected file."""
    path = Path(os.path.expanduser(raw))
    if not path.is_absolute():
        path = Path(cwd) / path
    return Path(os.path.realpath(path))


def rel(path: Path, root: Path) -> str:
    """Posix path relative to the repo root; outside the repo, the absolute path without the leading slash."""
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix().lstrip("/")


def gradle_module(rel_path: str, root: Path) -> str | None:
    """`backend/src/main/App.kt` -> `:backend`, `sdk/core/src/X.kt` -> `:sdk:core` (nearest build.gradle.kts)."""
    parts = rel_path.split("/")[:-1]
    for n in range(len(parts), 0, -1):
        if root.joinpath(*parts[:n], "build.gradle.kts").is_file():
            return ":" + ":".join(parts[:n])
    return None


def glob_to_regex(pattern: str, *, ignore_case: bool = False) -> re.Pattern[str]:
    out: list[str] = []
    i = 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out.append("(?:.*/)?")
            i += 3
            continue
        if pattern.startswith("/**", i) and i + 3 == len(pattern):
            out.append("/.*")
            i += 3
            continue
        if pattern.startswith("**", i):
            out.append(".*")
            i += 2
            continue
        char = pattern[i]
        if char == "*":
            out.append("[^/]*")
        elif char == "?":
            out.append("[^/]")
        elif char == "[" and pattern.find("]", i + 1) != -1:
            end = pattern.find("]", i + 1)
            out.append(pattern[i : end + 1])
            i = end
        else:
            out.append(re.escape(char))
        i += 1
    return re.compile("^" + "".join(out) + "$", re.IGNORECASE if ignore_case else 0)


def gitignore_match(path: str, pattern: str) -> bool:
    """Simplified .gitignore matching (case-insensitive: macOS and Windows file systems are)."""
    dir_only = pattern.endswith("/")
    pat = pattern.rstrip("/")
    anchored = "/" in pat
    pat = pat.lstrip("/")
    parts = path.split("/")
    if not anchored:
        candidates = parts[:-1] if dir_only else parts
        return any(fnmatch.fnmatchcase(part.lower(), pat.lower()) for part in candidates)
    regex = glob_to_regex(pat, ignore_case=True)
    for i in range(1, len(parts) + 1):
        if regex.match("/".join(parts[:i])) and (not dir_only or i < len(parts)):
            return True
    return False


# --- secrets: the single source of truth is the "Secrets and credentials" block of .gitignore -------------


def load_secret_rules(root: Path) -> list[tuple[bool, str]]:
    """Return [(negated, pattern)] in file order. Raises if the block is missing (callers fail closed)."""
    gitignore = root / ".gitignore"
    lines = gitignore.read_text(encoding="utf-8").splitlines()
    rules: list[tuple[bool, str]] = []
    inside = False
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("# ---"):
            inside = _SECRETS_BLOCK in stripped.lower()
            continue
        if not inside or not stripped or stripped.startswith("#"):
            continue
        rules.append((stripped.startswith("!"), stripped.lstrip("!")))
    if not any(not negated for negated, _ in rules):
        raise RuntimeError(f'no "Secrets and credentials" block with patterns found in {gitignore}')
    return rules


def is_secret(path: str, rules: list[tuple[bool, str]]) -> bool:
    secret = False
    for negated, pattern in rules:
        if gitignore_match(path, pattern):
            secret = not negated
    return secret


def secret_hit(raw: str, base: str, root: Path, rules: list[tuple[bool, str]]) -> str | None:
    """The repo-relative name if writing to `raw` (resolved from `base`) would touch a secret, else None.

    A directory (`secrets/`, or an existing one) counts when anything inside it would be a secret.
    """
    path = resolve(raw, base)
    name = rel(path, root)
    if is_secret(name, rules):
        return name
    if (raw.endswith("/") or path.is_dir()) and is_secret(name + "/_", rules):
        return name
    return None


# --- shell commands (best effort: a safety net, not a sandbox) -------------------------------------------

_SEPARATORS = {";", "&&", "||", "|", "&", "|&", "\n", "(", ")"}
_ASSIGNMENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*=.*")
_SHELLS = {"bash", "sh", "zsh", "dash"}
# Wrappers run the command that follows them. Their options that take a value must not be mistaken for it.
_WRAPPER_VALUE_OPTIONS = {
    "sudo": {"-u", "-g", "-h", "-p", "-C", "-r", "-t", "-U", "-D", "-R"},
    "nice": {"-n"},
    "ionice": {"-c", "-n", "-p"},
    "env": {"-u", "-C", "-S"},
    "timeout": {"-s", "-k"},
    "xargs": {"-n", "-I", "-L", "-P", "-s", "-d", "-E", "-a"},
    "command": set(),
    "time": set(),
    "nohup": set(),
    "exec": set(),
    "builtin": set(),
    "setsid": set(),
}
_MAX_DEPTH = 3


@dataclass(frozen=True)
class Segment:
    words: tuple[str, ...]
    redirects: tuple[str, ...]
    dirs: tuple[str, ...] = ()  # the `cd` targets seen earlier in the command, in order

    @property
    def exe(self) -> str:
        return os.path.basename(self.words[0]) if self.words else ""

    @property
    def args(self) -> tuple[str, ...]:
        return self.words[1:]


def _substitutions(command: str) -> list[str]:
    """Bodies of $(...), <(...), >(...) and `...`, which the shell runs even inside double quotes."""
    found: list[str] = []
    for match in re.finditer(r"[$<>]\(", command):
        depth, start = 1, match.end()
        for i in range(start, len(command)):
            depth += {"(": 1, ")": -1}.get(command[i], 0)
            if depth == 0:
                found.append(command[start:i])
                break
    found.extend(m.group(1) for m in re.finditer(r"`([^`]*)`", command))
    return found


def _lex(command: str) -> list[str]:
    lexer = shlex.shlex(command, posix=True, punctuation_chars=";&|<>()\n")
    lexer.whitespace_split = True
    lexer.whitespace = " \t\r"
    return list(lexer)


def short_flags(args: tuple[str, ...]) -> str:
    """The letters of every short-option cluster: `-uf --foo -x` -> `uf` + `x`."""
    return "".join(a[1:] for a in args if a.startswith("-") and not a.startswith("--"))


def segments(command: str, _depth: int = 0, _dirs: tuple[str, ...] = ()) -> list[Segment]:
    """Split a shell command into simple commands, following `bash -lc`, `eval`, `find -exec` and substitutions.

    Raises ValueError when the command cannot be parsed (callers decide how to fail).
    """
    if _depth > _MAX_DEPTH:
        raise ValueError("command nests too deeply to analyze")
    result: list[Segment] = []
    for inner in _substitutions(command):
        result.extend(segments(inner, _depth + 1, _dirs))
    words: list[str] = []
    redirects: list[str] = []
    dirs = list(_dirs)
    expect_target = False

    def flush() -> None:
        nonlocal words, redirects
        seg = _strip_wrappers(Segment(tuple(words), tuple(redirects), tuple(dirs)))
        if seg.words or seg.redirects:
            result.append(seg)
            _follow(seg, result, dirs, _depth)
        words, redirects = [], []

    for token in _lex(command):
        if token in _SEPARATORS:
            flush()
            expect_target = False
        elif re.fullmatch(r"\d*(>>?|>&|&>|<<?<?)", token) or re.fullmatch(r"\d*>\|", token):
            expect_target = token.lstrip("0123456789").startswith(">") or token.startswith("&>")
        elif expect_target:
            if not re.fullmatch(r"\d+|-", token):  # `2>&1` duplicates a descriptor, it does not write a file
                redirects.append(token)
            expect_target = False
        else:
            words.append(token)
    flush()
    return result


def _follow(seg: Segment, result: list[Segment], dirs: list[str], depth: int) -> None:
    """Commands hidden inside this one: `cd`, `bash -c '...'`, `eval ...`, `find ... -exec cmd ... ;`."""
    args = seg.args
    if seg.exe == "cd" and args and args[0] not in {"-", "--"}:
        dirs.append(args[0])
    elif seg.exe in _SHELLS:
        idx = next((i for i, a in enumerate(args) if a.startswith("-") and not a.startswith("--") and "c" in a[1:]), None)
        if idx is not None and idx + 1 < len(args):
            result.extend(segments(args[idx + 1], depth + 1, tuple(dirs)))
    elif seg.exe == "eval" and args:
        result.extend(segments(" ".join(args), depth + 1, tuple(dirs)))
    elif seg.exe == "find":
        for i, arg in enumerate(args):
            if arg in {"-exec", "-execdir", "-ok", "-okdir"}:
                inner = []
                for word in args[i + 1 :]:
                    if word in {";", "+"}:
                        break
                    inner.append(word)
                if inner:
                    result.append(_strip_wrappers(Segment(tuple(inner), (), tuple(dirs))))


def _strip_wrappers(seg: Segment) -> Segment:
    words = list(seg.words)
    while words:
        head = words[0]
        name = os.path.basename(head)
        if _ASSIGNMENT.fullmatch(head):
            words.pop(0)
        elif name in _WRAPPER_VALUE_OPTIONS:
            words.pop(0)
            takes_value = _WRAPPER_VALUE_OPTIONS[name]
            while words:
                word = words[0]
                if name == "env" and _ASSIGNMENT.fullmatch(word):
                    words.pop(0)
                elif word == "--":
                    words.pop(0)
                    break
                elif word.startswith("-"):
                    words.pop(0)
                    if word in takes_value and words:
                        words.pop(0)
                else:
                    break
            if name == "timeout" and words:
                words.pop(0)  # the duration
        else:
            break
    return Segment(tuple(words), seg.redirects, seg.dirs)


def working_dirs(seg: Segment, cwd: str) -> list[str]:
    """Where relative paths of this command may point: the session cwd and every `cd` that came before it."""
    bases, current = [cwd], cwd
    for target in seg.dirs:
        current = str(resolve(target, current))
        bases.append(current)
    return bases


def git_subcommand(seg: Segment) -> tuple[str, tuple[str, ...]]:
    """For `git [-C dir] [-c k=v] <sub> args` return (sub, args)."""
    args = list(seg.args)
    while args and args[0].startswith("-"):
        flag = args.pop(0)
        if flag in {"-C", "-c", "--git-dir", "--work-tree", "--namespace"} and args:
            args.pop(0)
    return (args[0], tuple(args[1:])) if args else ("", ())


def non_options(args: tuple[str, ...]) -> list[str]:
    """Positional arguments: everything after `--`, plus words that do not start with `-`."""
    out: list[str] = []
    after_dashes = False
    for arg in args:
        if after_dashes:
            out.append(arg)
        elif arg == "--":
            after_dashes = True
        elif not arg.startswith("-"):
            out.append(arg)
    return out


def edits_in_place(seg: Segment) -> bool:
    """sed -i / --in-place, perl -pi, awk -i inplace: editors that rewrite the files they are given."""
    if seg.exe in {"sed", "perl"}:
        return "i" in short_flags(seg.args) or any(a.startswith("--in-place") for a in seg.args)
    if seg.exe in {"awk", "gawk"}:
        return any("inplace" in a for a in seg.args)
    return False
