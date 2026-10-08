Created-GMT: 2026-09-10 06:12:10 GMT
Created-Local: 2026-09-10 13:12:11 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e09ea11a-33c4-45e7-a673-f4f23681d703
# Task: revise yin.vm-consumers.implementation-plan.md for missed live consumers
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-10 13:12:11 +0700 | Status: active | Rationale: architecture-level revision to a destructive deletion plan; needs the rigor this repo's team.md reserves for Architect (security sign-offs, architecture definition, critical high-risk boundaries)

## Context

`docs/design/yin.vm-consumers.implementation-plan.md` (51 lines) plans
deleting five experimental v1 VM implementations
(`yin.vm.{space,wasm,semantic,stack,register}`) and the macro engine
(`yin.vm.macro`), because `docs/design/yin.vm.divergence-register.md`
scopes `yin.vm` to the `ast-walker` slice only and excludes these models
by design. The plan's stated completion criteria for Phase 1 only name
`test/yin/vm/parity_test.cljc` and `src/clj/yin/vm/bytecode_bench.clj` as
places to check for stale references.

That is wrong, verified by a local grep sweep just now (do not re-run it,
trust and extend it):

- `src/cljc/yin/vm/wasm.cljc` **does not exist** — already gone from the
  tree (check when/how; it may have been deleted in an earlier, undocumented
  pass, or the plan may simply be wrong about the filename/path). The plan's
  Phase 1 delete-list and completion criteria need correcting either way.
- `yin.vm.semantic`, `yin.vm.register`, and `yin.vm.stack` are **live
  `:require`d outside any test or benchmark file**, in:
  - `src/cljc/yin/repl.cljc` (the v1 REPL) — all three, wired into a
    user-facing `(vm :semantic | :register | :stack | :ast-walker)` backend
    switch command (lines ~19-21, ~35-37, ~42-44, ~65, ~74, ~183 as of this
    writing).
  - `src/cljc/datomworld/demo/continuation_handoff.cljc` — `register` and
    `stack`.
  - `src/clj/yin/demo.clj` — `register`.
- `yin.vm.macro` is required by `semantic.cljc`, `register.cljc`,
  `space.cljc`, and `stack.cljc` themselves (each experimental VM depends on
  the macro engine), plus `test/yang/macro_test.clj` and
  `test/yin/vm/macro_test.cljc`.

None of these three live consumers (`yin.repl.cljc`,
`continuation_handoff.cljc`, `yin/demo.clj`) are named anywhere in the
current plan. Deleting the Phase 1/2 files as written would break the build
for all three.

For scope orientation (do not treat as this task's boundary without
verifying it still holds): `docs/design/dao.jing.remote.implementation-plan.md`
§8 says `yin.vm.*` deletion, `dao.runtime`, `yin.io`, and "the demo surfaces"
are each their own separate migration, explicitly not planned by that
document. `docs/design/dao.stream.md`'s "remaining v1 consumers" section
lists "the demo and server surfaces" as migrating under their own plan(s),
separately from the v1 VM lineage's deletion (which is this plan). Whether
`continuation_handoff.cljc` counts as one of those separately-owed "demo
surfaces," or whether it's squarely this plan's own problem to resolve
because it directly requires the files this plan deletes, is exactly the
kind of boundary call this task needs you to make and record — read both
documents yourself rather than trusting this characterization.

The plan is also thin compared to this repo's other design-plan documents
(e.g. `docs/design/dao.jing.remote.implementation-plan.md`, 1152 lines,
five architect rounds against two independent reviewers): no invariants
section, no test-pinning method, no revision history. That rigor is not
necessarily owed here — a deletion plan is a different shape of problem
than a new-code plan — but the missing-consumer gap above is a correctness
defect regardless of format, and it is the one thing this task must fix.

## Task

Revise `docs/design/yin.vm-consumers.implementation-plan.md` (read it
first; it may have drifted further since this brief was written) so that:

1. Every live, non-test, non-benchmark consumer of a file the plan deletes
   is named and given an explicit disposition — deleted alongside its
   dependency, migrated to keep working without the deleted VM, or the plan
   states why it is out of scope and where that work is actually owed
   (citing the real document, not asserted).
2. `yin.vm.wasm.cljc`'s absence is resolved: either the plan's file list
   drops it with a note explaining it's already gone, or you find where it
   actually lives if the grep missed a moved/renamed file — check before
   assuming.
3. `yin.vm.macro`'s cross-dependencies (four VM files requiring it, plus
   its two test files) are sequenced correctly relative to the VM deletions
   — the current phase order (VMs first, then macro) should be checked
   against what actually requires what, not assumed correct.
4. The `(vm :semantic | :register | :stack | :ast-walker)` REPL command in
   `yin.repl.cljc` gets a real disposition: if `:ast-walker` alone survives,
   say what happens to the command surface and to any test that exercises
   the other backends through the REPL. If `yin.repl.cljc` itself is fully
   superseded by `yin.repl` and slated for its own deletion under a
   different plan, verify that claim against the actual codebase (do not
   assume it from the `dao.jing.remote` plan's `v1 yin.repl` deletion note,
   which is about `dao.stream.rpc`-era transport plumbing, not necessarily
   this backend-switch surface) and say so explicitly with the evidence.
5. Completion criteria are corrected to actually name every file and
   reference this revision found, not just the two the current version
   names.

Produce the complete revised plan as your deliverable — do not wait for
approval or produce only a diff description. You may restructure the
document (e.g. add a short "consumers" table) if it makes the disposition
clearer, but do not inflate it with process ceremony this deletion-shaped
task doesn't need. State plainly wherever you are making a judgment call
versus reporting a verified fact.

Read-only: you have Read and read-only Bash (git diff/status, and you may
run additional greps/finds to verify anything above). Do not edit any file.
Produce your revised plan as the complete text of your final response.
