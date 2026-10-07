Created-GMT: 2026-10-02 20:10:00 GMT
Created-Local: 2026-10-03 03:10:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Gate Review — Python C2-S2 (generators: send/throw/close, GeneratorExit)

Role: Lead System Architect (gate review)

Scope: the uncommitted C2-S2 delta in the worktree
/Users/sto/workspace/datomworld-py-c2gen1 (branch yang-python-c2-s2 @
cf6ed9ad, based on safepoint-s1): 3 files, +475/-5 —
- src/cljc/yang/python/antlr/prelude.cljc (generator machinery:
  GeneratorExit, generator send/throw/close/__next__/__iter__ via
  py/gen-attr, per the implementing brief)
- test/yang/python/antlr/e2e_c2_test.clj (9 e2e tests + 1 parity test)
- test/yang/python/antlr/prelude_parity_test.cljc

Context: the delegate (claude-opus-5-5, session aa90648c) reported
targeted JVM e2e-c2 16/103/0 and Node 2703/91884/0, kondo/cljstyle
clean on 3 files; the full JVM lane, Dart lane, and ASCII/80-col
checks were outstanding and have since been run by the orchestrator:
JVM 2,906/227,036/0; Node 2,703/91,884/0; CLJD 2,658 passed, all
green on this exact tree. The delegate also left duplicate background
test runs whose results were lost — disregard that history; the
orchestrator's lanes above are the evidence.

Read first:
- The implementing brief:
  collab/1790958000000-compiler-engineer-python-c2-s2.prompt.md
- docs/design/yang.antlr.md (the generator rulings: yield as a
  crossing with per-generator handler stacks, next/StopIteration and
  send; the C2 slice scope at ~1858)
- src/cljc/yang/python/antlr/prelude.cljc (the landed generator
  machinery, including the D6/D7 host-typed closures interaction)
- The safepoint-s1 landing (cf6ed9ad) this builds on
- docs/design/datom.world.md (invariants)

Adversarial focus:
1. Generator semantics vs CPython: yield-as-crossing (no VM-native
   generator type leaking), send/throw/close state machine (including
   GeneratorExit propagation and the close-on-GC question if in
   scope), StopIteration vs the host's return-value handling.
2. The py/gen-attr path: does it leak generator machinery across the
   host-typed closure boundary (D6/D7 interaction), or does the
   closure/continuation discipline hold?
3. Per-generator handler stacks: isolation between generators, nested
   generator interactions, and the safepoint slice 1 marks
   interaction.
4. Parity: the prelude parity test coverage on all four VMs; the
   CLJD lane green evidence.
5. Hygiene on added lines; no scope creep beyond the 3 files; the
   float-literal gate (C2-S5 restrictions) not violated — generators
   here are float-free or the float handling follows the recorded
   restrictions.

Do not edit files. Cite file:line evidence for every finding.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly two lines:
Verdict: READY
Sign-off: GRANTED
or
Verdict: REQUEST CHANGES
Sign-off: DENIED
