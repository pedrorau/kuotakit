# OpenCode

**Status: documented, not configured or tested in this repo.** Verified against the official docs on 2026-11-03: [Plugins](https://opencode.ai/docs/plugins/) and [Permissions](https://opencode.ai/docs/permissions/). OpenCode reads `AGENTS.md` natively.

The scripts and their contract are in [guard-scripts.md](guard-scripts.md). This page is only the glue.

## What the official docs confirm

- Project plugins live in `.opencode/plugins/` (the docs only use the plural).
- A plugin is `export const MyPlugin: Plugin = async ({ project, client, $, directory, worktree }) => ({ ...hooks })`, with `import type { Plugin } from "@opencode-ai/plugin"`. `$` is Bun's shell API.
- `"tool.execute.before": async (input, output) => {...}` runs before a tool. `input.tool` is the tool name and `output.args` its arguments (the docs use `output.args.filePath`). **Throwing an `Error` blocks the call.**
- Human approval is configuration, not code: `"permission": { "edit": "ask", "bash": { "*": "ask", "rm *": "deny" } }` in `opencode.json`. The official page does not document a plugin hook that asks the user (one appears only in third-party tutorials).
- `session.idle` is an event a plugin can react to. The docs describe no way to stop a session from finishing.
- Bun's shell can feed stdin and read the exit code: `` await $`python3 script.py < ${new Response(json)}`.nothrow().quiet() `` then `.exitCode`, `.stderr`.

## Sketch of the glue

Untested. Log a real payload first: the docs do not list the full `input`/`output` types or the exact tool names.

```ts
// .opencode/plugins/kuotakit-guards.ts
import type { Plugin } from "@opencode-ai/plugin"

const PRE = ["guard_paths.py", "guard_commands.py", "protect_tests.py"]

export const KuotaKitGuards: Plugin = async ({ $, directory }) => ({
  "tool.execute.before": async (input, output) => {
    const isFile = input.tool === "edit" || input.tool === "write"
    if (!isFile && input.tool !== "bash") return
    const payload = JSON.stringify({
      event: "pre_tool_use",
      tool: input.tool,
      paths: isFile ? [output.args.filePath] : [],
      command: isFile ? "" : output.args.command,
      cwd: directory,
    })
    for (const script of PRE) {
      const r = await $`python3 ${directory}/scripts/agent/${script} < ${new Response(payload)}`.nothrow().quiet()
      // 2 = block, 3 = needs a human. A plugin cannot ask, so both stop the call.
      if (r.exitCode === 2 || r.exitCode === 3) throw new Error(r.stderr.toString())
    }
  },
})
```

## Limits

- **Protected tests:** a plugin cannot prompt, so "ask" becomes a block ("ask the maintainer"). For a prompt instead, set `"permission": { "edit": "ask" }` (asks on every edit, not only tests).
- **No stop guard:** OpenCode cannot be prevented from finishing, so `check_tests.py` cannot be enforced. Run the checks yourself (`./gradlew check`) and rely on CI.
- **Formatting after edits:** `tool.execute.after` exists as an event; wiring `check_changed.py` there is possible but not documented here.
