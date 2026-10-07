Created-GMT: 2026-09-19 17:40:35 GMT
Created-Local: 2026-09-20 00:40:35 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: 7544db48-801f-4328-a095-e2174a746b28
# Task: dao.stream.waitset Phase W0 — the census

Role: Scoped / Subagent (bounded search and documentation)

You are running on glm-5.3-flash within a bounded credit window: keep the
work tight, read only what the task names, and produce exactly the
deliverable.

Implement Phase W0 of
`docs/design/dao.stream.waitset.implementation-plan.md` (read it first —
your task is its "Phase W0 — Census" section verbatim). Read also
`docs/design/dao.stream.md` (the stream contract: outcomes, retention, the
non-blocking rule) and then inspect each consumer named in the plan's
expected rows:

- `src/cljc/yin/vm/engine.cljc` — `augment-wait-entry`, `poll-wait-entry`,
  `check-wait-set` (the inline multiplexed sweep)
- `src/cljc/dao/stream/rpc.cljc` — `poll!`
- `src/cljc/dao/stream/apply.cljc` — `serve-once!`
- `src/cljc/dao/stream/observer.cljc` — `run-on-stream`
- `src/cljc/dao/stream/serving.cljc` — the forward loops over `forward-step`
- `src/cljc/dao/jing.cljc` — the intake materializer loop
- the `dao.gui.event` pump (find it under `src/`)
- `src/cljc/yin/repl.cljc` — the host shell's poll loop

Deliverable — edit ONLY
`docs/design/dao.stream.waitset.implementation-plan.md`:

1. In the Phase W0 section, add the census table: one row per polling loop
   found — caller, file (with line references), what it waits on,
   single-stream or multiplexed, and what a waitset adoption would change
   or why nothing should change. Verify each row against the actual source
   you read; cite the function and line. If you find polling loops beyond
   the expected rows, add them. If an expected row does not exist in the
   source, say so plainly rather than inventing it.
2. Update the plan's front-matter status line to record that W0 is
   complete (leave the rest of the status line intact).

Touch nothing else — no source files, no other documents, no staging, no
commit. One single simple command per step if you run any (no chaining,
no pipes).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: the rows you recorded (one line each), any expected row you
could not find, and any loop you discovered beyond the expected rows.
