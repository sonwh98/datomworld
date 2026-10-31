Created-GMT: 2026-09-17 07:26:36 GMT
Created-Local: 2026-09-17 14:26:36 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Role: Lead System Architect

# Task: Draft docs/design/dao.stream.v1-retirement.implementation-plan.md

Legacy `dao.stream` (v1) is the last blocker on the rename wave already
agreed in three separate docs: `dao.stream.md` (\"The v2 namespace is
transient\" section), `yin.vm.v1-retirement.implementation-plan.md` D5, and
`dao.runtime.implementation-plan.md`'s R4 status note. All three say the
same thing: `dao.stream`/`dao.runtime`/`dao.await`/`yin.vm`/
`yin.repl` rename together, gated on `dao.stream.v1`'s own retirement
trigger — "when the last consumer has migrated." No plan for that migration
exists yet. Read all three of those documents in full first, then
`docs/design/dao.stream.md` in full (it's the authority on what v2 is and
the invariants any v1 replacement must uphold), and the two prior retirement
plans as your template for structure and tone: `yin.vm.v1-retirement.
implementation-plan.md` and `dao.runtime.implementation-plan.md`.

## Starting census (orchestrator's research, verify before relying on it)

v1's own implementation (retires wholesale when the last consumer is gone,
not migrated file-by-file — this matches how the v1 VM and v1 telemetry
retired):
`src/cljc/dao/stream.cljc` and its siblings `apply`, `file`,
`file_input_stream`, `file_output_stream`, `http`, `link`, `ringbuffer`,
`udp`, `ws`, `rpc/{client,dedup,retry,server,udp,ws}` (all `.cljc`), plus
host-specific `src/clj/dao/stream/transit.clj`, `src/cljd/dao/stream/
{transit,udp,ws}.cljd`, `src/cljs/dao/stream/transit.cljs`.

External consumers (require a v1 `dao.stream*` namespace; re-verify this
list yourself with a fresh grep for `[dao\.stream(\.[a-z.]+)?[\s\]]` excluding
`v2`, since the orchestrator's pass may have missed aliasing forms):

1. `src/cljc/yin/io/{file,file_input_stream,file_output_stream}.cljc` — no
   v2 twin exists yet.
2. `src/cljc/dao/gui/event.cljc` (design doc: `dao.gui.event.md`) — no v2
   twin exists yet.
3. `src/cljc/dao/postgraphics/terminal.cljc` (design doc: `dao.postgraphics.
   terminal.md`) — no v2 twin exists yet. Note `dao.postgraphics.{v2,v3,v4}.
   md` exist but describe a different postgraphics evolution, not a stream
   migration — check whether they're actually relevant or a false lead.
4. `src/cljc/agent/tools.cljc` — no v2 twin exists yet, no design doc found
   under that name.
5. `src/cljc/agent/tzu.cljc` (+ `agent.tzu.dao.stream.md`, `agent.tzu.md`,
   `agent.tzu.yin.vm.md`) — the orchestrator's memory flags this module as
   **unused/dead code**, not to be included in consumer/impact analysis
   despite looking active. Verify this yourself (check for real callers
   beyond its own test) before deciding whether it's a delete-candidate
   rather than a migrate-candidate.
6. Demo/server surfaces across all three hosts: `src/clj/datomworld/
   ws_demo_server.clj`, `src/cljs/datomworld/ws_client_demo.cljs`,
   `src/cljc/datomworld/continuation_transport.cljc`, `src/cljc/datomworld/
   demo/{earth_moon_runner,voxel_runner}.cljc`, `src/cljd/datomworld/demo/
   {artifact,dao_gui,postgraphics,solar_system}.cljd`, `src/cljs/datomworld/
   demo/{artifact,solar_system}.cljs`. Some of these may be supersedable by
   already-built v2 demo surfaces (e.g. `src/cljs/dao/stream/ws/browser.
   cljs` and `src/cljs/datomworld/demo/yin_repl.cljs` from U4 of the VM
   retirement) rather than needing fresh migration — check.
7. `src/cljs/yin/vm/telemetry_viewer.cljs` (+ its own test) — the
   orchestrator noticed this consumes v1 `dao.stream.ws` to talk to a v1
   telemetry *server*, and the v1 telemetry servers were deleted in last
   night's `yin.vm.v1-retirement.implementation-plan.md` U6. If true, this
   file may already be dead/orphaned (broken since U6, not merely
   unmigrated) — verify and, if confirmed, treat it as a delete-candidate,
   not a migration target.
8. Roughly two dozen test files exercising the v1 implementation directly
   (`test/dao/stream*`, `test/dao/stream/**`) — these retire with the
   implementation, not separately.

`dao.space` (`index`, `query`, `schema`, `transactor`) and `dao.jing`'s
family are **already done** — confirmed by the orchestrator: their apparent
`dao.stream` references are all `:dao.stream/...` keywords (the v2
protocol's own outcome vocabulary, which is host-agnostic and not a v1
namespace reference), not v1 requires. Do not schedule these.

## Task

Produce `docs/design/dao.stream.v1-retirement.implementation-plan.md`
following the structure of the two prior retirement plans (design decisions
section, phased units, a consumer census table, an explicit "what deletes
wholesale vs. what migrates" split, owner-decision points flagged for
AskUserQuestion where you can't decide unilaterally). Concretely:

1. Verify and correct the census above.
2. For each live (non-dead) consumer, determine: does a v2 replacement
   primitive already exist that it can move to directly, or does new v2
   surface need to be built first (and if so, roughly what)? This is real
   design work, not a rename — say so plainly where it applies, and don't
   understate the size of e.g. `dao.gui.event`'s or `agent.tools`'s
   migration if it isn't a small one.
3. Decide and record an explicit position on `agent.tzu` and
   `telemetry_viewer.cljs`: delete outright (dead code) vs. migrate,
   with your reasoning.
4. Phase the work into units analogous to the prior plans' U1/U2/... or
   R0/R1/..., ordered by dependency (what must move before what), noting
   which units are independently parallelizable.
5. State plainly whether this plan can begin execution immediately or
   whether it should wait — this is the biggest retirement plan in the
   series so far (real per-consumer design work, not just deletion), so an
   honest scope/effort estimate matters more than in the prior two.
6. Do NOT implement anything. Do NOT touch any file except the new plan
   doc. This is a design task only.

Write the file directly. When done, report back a short summary of the
plan's shape (number of units, which units carry real design risk, and
your one-line recommendation on how to sequence the actual work).
