Created-GMT: 2026-09-10 07:42:09 GMT
Coding-Agent: claude
Session-ID: e09ea11a-33c4-45e7-a673-f4f23681d703
# Task: revise yin.vm-consumers.implementation-plan.md — r3, two more findings
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-10 14:42:09 +0700 | Status: active | Rationale: same as r1/r2

## Context

r2 was promoted and given a confirmation review. Five of your six r1 fixes
were confirmed clean; the reviewer found two more concrete issues plus one
prose nit. Read the current plan file, then fix:

1. **[P1] `demo.cljs`'s toolbar still references the deleted pipeline
   namespace outside the render branch the plan already names.** The
   reviewer cites `demo.cljs:277`: a toolbar block calling
   `pipeline/show-explainer-video!`, `pipeline/layout-controls`, and
   `pipeline/app-state` — separate from whatever render branch/case the
   current plan text already covers for `compilation_pipeline`. Verify this
   yourself (re-read `demo.cljs` in full; don't trust only the cited line),
   find every remaining reference to the `pipeline` alias (and, while
   there, double check the `continuation`/`plotter` aliases for the same
   pattern — the reviewer only checked pipeline), and give the migration
   row a complete disposition: either the toolbar block is deleted with the
   rest of the v1 branch, or its retained UI features move to
   `compilation_pipeline.cljs` alongside the Python/PHP port from D3 if
   they're worth keeping. State which and why.

2. **[P2] The Flutter startup-smoke criterion names the wrong entry
   point.** `dao_gui.md:41`'s command compiles a nonexistent
   `datomworld.main`; the real entry point is `datomworld.demo.main`
   (`main.cljd:40`), which opens a picker and only reaches
   `dao-gui/start!` (and therefore `flutter.cljd`'s `start-server!`) when
   the "dao.gui Prototype" entry is selected. Correct Phase 1's criteria to
   name the actual command and the selection step needed to reach the
   startup path being tested.

3. **[Prose]** The reviewer notes: opening an old `#pipeline`/`#plotter`/
   `#continuation` URL retains that hash in the address bar rather than
   `demo->hash` auto-canonicalizing it to the `-v2` id on arrival. If D5's
   text implied otherwise, correct it to describe the actual behavior.

Everything else was confirmed clean: the runtime-regression-test migration,
the Flutter argument fix itself (`(create-state {})`), the five-alias
count and Dart launcher deletion, the Python/PHP port's technical soundness
(with one clarification worth folding in: both compilers produce `:type`
AST maps accepted by `vm/ast->datoms`, not `:yin/*` datoms directly — correct
D3's language if it overstated that), the hash-alias mappings themselves,
and the Boundary gate split. Do not re-litigate any of these.

## Task

Produce the complete r3 of the plan (not a diff). Update the Revision
history with a short r3 entry in the same format as r1/r2's.

Read-only, same tool grant as before. Do not edit the plan file — the
orchestrator promotes your output.
