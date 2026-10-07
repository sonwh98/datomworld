Created-GMT: 2026-09-10 07:33:01 GMT
Created-Local: 2026-09-10 14:33:01 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e09ea11a-33c4-45e7-a673-f4f23681d703
# Task: revise yin.vm-consumers.implementation-plan.md — r2, fold in adversarial findings
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-10 14:33:01 +0700 | Status: active | Rationale: same as r1

## Context

Your r1 revision was promoted to
`docs/design/yin.vm-consumers.implementation-plan.md` and then given an
independent adversarial review (gpt-6-astra, different model family). Read
the current plan file (it is your r1 text, unmodified since) and the
findings below. Two are P1 (would break the build/runtime as the plan
stands); four are P2.

## Findings to address

1. **[P1] `test/yin/vm/runtime_regression_test.cljc` is listed as
   "Unchanged" but directly requires `register`, `semantic`, and `stack`**
   (verify the exact line yourself — the reviewer cited `:8`). The plan's
   own Phase 0 sweep command would have caught this; it wasn't run against
   the final census, or the hit was missed. Move this file into the
   "Migrated" table (drop the three requires; per your own note it already
   iterates `vtu/vm-factories`, which r1 already shrinks to `{:ast-walker
   ...}` for `test_utils.cljc`, so this file may need no further change
   once those requires are dropped — verify).

2. **[P1] `flutter.cljd` explicitly requests `:semantic`, not the REPL's
   default.** D1 migrates `yin.repl.cljc`'s *default* `vm-type` from
   `:semantic` to `:ast-walker`, but `src/cljd/yin/repl/flutter.cljd`
   (reviewer cites line 60) calls `(repl/create-state {:vm-type
   :semantic})` explicitly — an explicit argument is not touched by a
   default change, and `make-vm` throws "Unknown Yin REPL VM type" once
   `:semantic` no longer exists. Both Flutter demo consumers
   (`dao_gui.cljd`, `solar_system.cljd`) go through this startup path. Fix
   D1/the migration table: `flutter.cljd`'s explicit `:vm-type` must change
   too, and state what test or startup check would actually catch this
   class of defect (the reviewer notes compilation alone would not).

3. **[P2] The census undercounts `deps.edn` aliases and misses a Dart
   launcher.** `deps.edn` has five `bytecode-bench*`-targeting aliases
   (`:bench`, `:profile`, `:profile-fast`, `:profile-cesk-space`,
   `:profile-ast-walker`), not four. `bin/register_bench_cljd.dart` imports
   the generated output of `register_bench_cljd.cljd` (which you already
   delete) and has no disposition in the plan — give it one. Broaden the
   plan's own sweep/completion-criteria language to explicitly cover
   `deps.edn` alias counts and Dart launcher files under `bin/`, not just
   `.clj*` source.

4. **[P2] D3's "assumption, not verified" is already false for one demo.**
   The reviewer found the v1 `compilation_pipeline.cljs` offers Clojure,
   Python, and PHP frontends feeding the walker; the v2 twin
   (`compilation_pipeline.cljs`) accepts Clojure only. This is a real,
   already-known feature gap, not a hypothetical for "Phase 0 to check" —
   name it explicitly in D3 with an actual disposition (restore the
   frontends in the v2 twin before deleting v1, or state plainly that
   Python/PHP input support is an intentional casualty of this deletion and
   who signed off on that, since it's a user-visible product regression,
   not an internal VM-model comparison).

5. **[P2] A public-facing link breaks.** `public/chp/yin.chp:18` advertises
   `/demo.html#pipeline` as "Try the Live Demo," but the plan removes the
   `#pipeline` hash route. Either keep `#pipeline` as an alias route to the
   v2 picker entry, or update the linking document, and add it to
   completion criteria/verification.

6. **[P2] The Boundary section conflates two different gates.** The
   successor-plan row bundles the `dao.stream` rename into "written when
   the three preceding rows are clear," but `dao.stream.md:792`'s actual
   rename gate is broader than this plan's three VM-deletion rows — it
   requires every remaining v1 stream consumer (`yin.io`, GUI/terminal
   consumers, `agent.tools`, etc., not just the VM-lineage ones) to have
   migrated and legacy `dao.stream` to be deleted. Separate the VM/R4 gate
   this plan actually clears from the global stream-deletion-and-rename
   gate, which is much larger and not this plan's to imply is nearly done.

The reviewer confirmed as sound: the core dependency/leaf-ordering argument,
D1's cited Flutter/telemetry consumers being real (aside from finding #2
above), D2/D3's named twins existing and being wired into their builds/pickers,
and the single-commit approach (no bisectability defect, since an atomic
commit exposes no partially-edited state).

## Task

Produce the complete r2 of the plan (not a diff description) with all six
findings resolved, and a short addition to the Revision history section
recording this round and what it fixed — same format as your r1 entry. Keep
everything from r1 that the reviewer did not dispute. State plainly wherever
you're making a new judgment call versus applying a reviewer-verified fact.

Read-only, same tool grant as r1: Read, read-only Bash (grep/find/git
diff/git status). Do not edit `docs/design/yin.vm-consumers.implementation-plan.md`
or any other file — the orchestrator promotes your output.
