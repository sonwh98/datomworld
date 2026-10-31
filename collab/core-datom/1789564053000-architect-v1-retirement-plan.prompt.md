Created-GMT: 2026-09-16 06:07:33 GMT
Created-Local: 2026-09-16 13:07:33 +07 (Asia/Ho_Chi_Minh)

# Task: Draft an implementation plan for retiring yin.vm/yin.repl v1

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-16 13:07:33 +07 | Status: active | Rationale: cross-cutting architecture/migration planning, matching how the two prior v1-related plans in this repo were produced
Session-ID: dac99eba-dac3-4517-a34b-2cac30a7df20

The owner has decided: v1 (`yin.vm` ast-walker lineage, `yin.repl`,
`dao.await`) is deprecated and should be fully retired — build v2
replacements for every real consumer first, then delete v1 entirely.
This is a planning task: produce a reviewed implementation plan
document, matching the format and rigor of the two existing plans in
this repo for adjacent v1 work. Do not write any implementation code.

## Context already established (read-only investigation already done
tonight, verify rather than re-derive from scratch, but don't just trust
it blindly either)

- `dao.await` v1 (`src/cljc/dao/await.cljc`) has no real consumers —
  only its own test file (`test/dao/await_test.cljc`) requires it. v2
  sibling `src/cljc/dao/await/v2.cljc` already exists and is presumably
  a full replacement. This should be the simplest, first-deletable piece.
- `yin.vm` v1's OTHER models (semantic/register/stack/space/macro) were
  already deleted per a prior plan
  (`docs/design/yin.vm.v2-consumers.implementation-plan.md`). Only the
  ast-walker slice remains: `src/cljc/yin/vm/{ast_walker,engine,ffi,
  runtime_adapter,stream_driver,telemetry}.cljc` plus `src/cljc/yin/vm.cljc`.
  v2 sibling: `src/cljc/yin/vm/v2/*` (8 files, already complete and
  actively developed all session).
- `yin.repl` v1 (`src/cljc/yin/repl.cljc`) has THREE real, live
  consumers with no v2 replacement built yet:
  1. `src/cljd/yin/repl/flutter.cljd` — a Flutter REPL widget, used by
     `src/cljd/datomworld/demo/dao_gui.cljd` and `solar_system.cljd`
     (Flutter GUI demo apps).
  2. `src/clj/yin/vm/telemetry_server/jvm.clj` — a JVM telemetry server.
  3. `src/cljs/yin/vm/telemetry_server/node.cljs` — a Node telemetry
     server.
- Two existing plans explicitly and deliberately deferred v1 `yin.repl`'s
  deletion until "each consumer migrates under its own plan"
  (`docs/design/yin.repl.v2.implementation-plan.md`) — that migration
  plan does not exist yet; this task is to write it.
- v2 infrastructure that ALREADY EXISTS and should be leveraged, not
  rebuilt: a complete v2 REPL stack
  (`src/cljc/yin/repl/v2/{core,driver,connect,serve,host,host/common}.cljc`),
  including a ClojureDart host WebSocket adapter already at
  `src/cljd/yin/repl/v2/host.cljd` (read it — the v1 `flutter.cljd`
  widget may mostly need porting its UI/interaction layer onto this
  existing v2 wire-level stack, not building connection logic from
  scratch). v2 telemetry emission already exists at
  `src/cljc/yin/vm/v2/telemetry.cljc` — the gap is specifically the
  SERVER processes that consume/serve that telemetry (presumably to
  `src/cljs/yin/vm/telemetry_viewer.cljs`), not the emission side.
- `docs/design/dao.stream.md:803-812` lists a separate, larger, UNRELATED
  v1-transport-deletion item (`dao.stream.{apply,file,http,ws,...}`,
  `dao.stream.rpc.*`) — that is explicitly OUT OF SCOPE for this plan;
  do not fold it in.
- `remotes/origin/mr-clean` branch exists and hasn't been inspected —
  check it; it may contain relevant prior work or be irrelevant noise.

## Task

Read the actual v1 consumer implementations
(`src/cljd/yin/repl/flutter.cljd`, `src/clj/yin/vm/telemetry_server/jvm.clj`,
`src/cljs/yin/vm/telemetry_server/node.cljs`) and the existing v2
infrastructure they'd sit on
(`src/cljc/yin/repl/v2/*`, `src/cljd/yin/repl/v2/host.cljd`,
`src/cljc/yin/vm/v2/telemetry.cljc`, `src/cljs/yin/vm/telemetry_viewer.cljs`).
Check `remotes/origin/mr-clean` for relevant prior work.

Produce `docs/design/yin.vm.v1-retirement.implementation-plan.md`
(matching the structure/rigor of the two existing adjacent plans —
read them first as templates) covering:

1. **dao.await v1 deletion** — confirm it's genuinely a no-op deletion
   (no hidden consumers you find that the prior investigation missed),
   list the exact files to delete (source + test).
2. **A v2 Flutter REPL widget** — what exactly `flutter.cljd` does today
   (UI surface, what v1 REPL APIs it calls), what of that can port
   directly onto the existing v2 REPL stack + `host.cljd` adapter versus
   what's genuinely new work, and a concrete file/function-level plan.
3. **A v2 JVM telemetry server** and **a v2 Node telemetry server** —
   same treatment: what the v1 servers actually do, what v2's
   `telemetry.cljc` already provides, what's the gap, concrete plan for
   each.
4. **Migration and deletion order** — which pieces can be built/verified
   independently and in parallel, which have dependencies, and the final
   safe-deletion checklist (what must be true before each v1 file can be
   deleted: no remaining `:require`, tests ported or deleted, `deps.edn`/
   `shadow-cljs.edn` build aliases updated).
5. **Acceptance criteria per piece** — how each v2 replacement's
   correctness gets verified (tests, or if genuinely untestable — e.g.
   requires a real Flutter device/emulator or Node process — say so
   explicitly and propose the closest feasible verification).
6. **Risk and scope boundaries** — call out anything you find that makes
   this bigger or smaller than currently believed, and explicitly
   exclude the unrelated `dao.stream` v1 transport item.

This is planning only. Follow this repo's `docs/agents/roles/architect.md`
delegation template conventions for a design document, but the
deliverable here is the plan document itself, not a review of someone
else's diff — adapt the format accordingly (a plan document, with
sections, not a findings list).

## Deliverable

Write the plan to `docs/design/yin.vm.v1-retirement.implementation-plan.md`.
Report back a summary of the plan's shape, the proposed unit breakdown,
and any scope surprises found. Do not implement anything — planning
document only. Do not stage or commit.
