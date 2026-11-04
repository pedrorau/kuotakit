# Pi

**Status: documented, not configured or tested in this repo.** Verified against the official extension docs on 2026-11-03: [extensions.md](https://raw.githubusercontent.com/badlogic/pi-mono/main/packages/coding-agent/docs/extensions.md). Pi reads `AGENTS.md`. The package is now `@earendil-works/pi-coding-agent` (the legacy `@mariozechner/*` names map to the same modules).

The scripts and their contract are in [guard-scripts.md](guard-scripts.md). This page is only the glue.

## What the official docs confirm

- Project extensions live in `.pi/extensions/` and load only after the project is trusted. An extension is `export default function (pi: ExtensionAPI) { pi.on("<event>", async (event, ctx) => {...}) }`.
- `pi.on("tool_call", ...)` runs before a tool. `event.toolName` is `"bash"`, `"write"`, `"edit"`...; `event.input` holds the arguments. **Returning `{ block: true, reason }` blocks the call.**
- `ctx.ui.confirm(title, message)` asks the human. `ctx.hasUI` is `false` in print (`-p`) and JSON modes, so check it first.
- `agent_before_settle` is "the final actionable boundary": it can request **one continuation** with `{ entries: [...event.entries, <custom_message entry>], continue: true }`. An unconditional `continue: true` can loop forever, so guard it. `agent_end` is not a final-idle signal and `agent_settled` is notification-only.
- `pi.exec(cmd, args, opts)` has **no stdin option**, so pipe the JSON with Node's `child_process.spawnSync(..., { input })` instead (our suggestion, not from the docs).

## Sketch of the glue

Untested. Log a real payload first: the docs do not list the argument names of every tool.

```ts
// .pi/extensions/kuotakit-guards.ts
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent"
import { spawnSync } from "node:child_process"

const PRE = ["guard_paths.py", "guard_commands.py", "protect_tests.py"]

function runGuard(script: string, payload: object) {
  const r = spawnSync("python3", [`${process.cwd()}/scripts/agent/${script}`], {
    input: JSON.stringify(payload),
    encoding: "utf8",
  })
  return { code: r.status ?? 2, message: r.stderr }
}

export default function (pi: ExtensionAPI) {
  pi.on("tool_call", async (event, ctx) => {
    const isFile = event.toolName === "edit" || event.toolName === "write"
    if (!isFile && event.toolName !== "bash") return
    const payload = {
      event: "pre_tool_use",
      tool: event.toolName,
      paths: isFile ? [(event.input as any).path] : [], // confirm the real argument name
      command: isFile ? "" : (event.input as any).command,
      cwd: process.cwd(),
    }
    for (const script of PRE) {
      const { code, message } = runGuard(script, payload)
      if (code === 2) return { block: true, reason: message }
      if (code === 3) {
        const approved = ctx.hasUI && (await ctx.ui.confirm("Protected test", message))
        if (!approved) return { block: true, reason: message }
      }
    }
  })
}
```

## Stop guard

Wire `check_tests.py` to `agent_before_settle`: run it with `{ "event": "stop" }`; on exit 2 request one continuation whose message is the script's stderr, and only when the previous run was not already a continuation. Take the exact entry shape from the official example, because the docs show it only by example. The script's own 3-attempt counter keeps it from looping.

## Limits

- Extensions run inside the Pi process with the same operating-system permissions: they are as trustworthy as the repo they come from.
- Argument names of Pi's file tools were not confirmed; verify them before relying on `guard_paths.py` and `protect_tests.py`.
