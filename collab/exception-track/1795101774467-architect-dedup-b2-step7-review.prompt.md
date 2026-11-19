Created-GMT: 2026-11-19 15:16:14 GMT (delta-review dispatch)
Created-Local: 2026-11-19 22:16:14 +07 (Asia/Ho_Chi_Minh)

# Task: yang-dedup-round2-batch2-step7-review

Role: Reviewer (step-7, your round-1/round-2 thread continues)

Batch 2 of the round-2 dedup is implemented (argue sections C4/C5/C6 from the audit you
have seen, plus the conformance amendments and astra's conditions). The batch-1 landing
you approved is on master (c9dbc991); r18 landed after it (aa174a11). This batch:

- **C4**: `src/clj/yang/io.clj` + `src/cljs/yang/io.cljs` deleted → one
  `src/cljc/yang/io.cljc` (NEW, 71 lines): 4 verbatim-unified function bodies +
  reader conditionals on the ns reader requires and the two host lines (read-source,
  write-ast). 5 publics retained.
- **C5**: `own-lexer!`/`own-parser!` extracted in `cst.clj` (private, matching
  cst.cljs/cst.cljd structure); `lexer-span` + `export-tree` added to
  `yang.antlr.export` — signature takes ALREADY-COMPUTED code points and offsets (astra's
  P2 fix), error-first behavior preserved (verified: errors do not invoke tree ops);
  `offset-at`/`export-nodes`/`result` demoted to `defn-` after a zero-external-caller
  search — export public surface 7 → 6. All four hosts (clj/cljs/cljd/jolt) rewired to
  the new helpers; host ops construction stays host-side.
- **C6**: `correlation`/`rejected` added to `eval_transport.cljc` (2→4 publics, out: 0);
  6 rejection-envelope constructions in `eval_admission`/`eval_coordinator` replaced.
  The `eval_coordinator.cljc:46` sanitization and r18's structured-refusal discriminator
  (`:yang.compile/outcome` inside `:php/fatal`) preserved.

## Implementer's evidence (reproducible)

- Baseline vs after captures: SHA-256 IDENTICAL
  (1fe07902a7ded6df0c086d76aeb9df330ea9e5d3a2afdbf411b9ee0a5cf979f0 for both
  /tmp/r2b2-baseline.txt and /tmp/r2b2-after.txt on jupiter — covers eval rejection
  envelopes incl. the r18 discriminator, syntax-error packet, clean Unicode tree+offsets,
  io round-trip).
- Gates: PHP CST 10/196, PHP eval service+bridge 9/52, eval admission/coordinator/
  transport 8/76, Python CST export 16/173, python parser rows 1/31,
  yang.clojure-test 105/1011, include quick 4/20 — all 0 failures. diff --check clean.
- My (orchestrator) independent run: 120 tests/1237 assertions/0 failures
  (cst + eval-service + clojure-test); hashes re-verified identical.
- OPEN lane: Node shadow build compiling now for the cljs leg (the delegate lacked bb);
  Dart/Jolt CST coverage noted as lane-level follow-up.

## Your task

Review the 10-file patch (piped after this prompt): (1) io.cljc — reader conditionals
correct (no :cljd leakage — cljd does NOT load yang.io), bodies truly identical to the
deleted ones; (2) cst.clj own-lexer!/own-parser! extraction — the ATNSimulator
installation blocks preserved exactly incl. array type hints; (3) export.cljc — lexer-span/
export-tree semantics, the demotions safe (no remaining callers), public surface 6; (4) all
four host callers rewired correctly incl. cst.jolt's JPX1 path; (5) C6 — the envelope maps
identical, sanitization and discriminator intact; (6) scope discipline.

Begin exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

End with exactly one line:
APPROVED | CHANGES STILL REQUIRED — <why> | PROOF FAILS — <why>
