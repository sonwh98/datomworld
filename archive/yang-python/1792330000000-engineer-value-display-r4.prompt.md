Created-GMT: 2026-10-18 01:05:00 GMT
Created-Local: 2026-10-18 08:05:00 +07 (+0700)
Coding-Agent: claude-sonnet-5-5

# Task: value-display r4 — resolve the v3 gate's REQUEST CHANGES (1 P1 + 3 P3s)

WORK TREE: /Users/sto/workspace/datomworld-c4fix (branch value-display-r2). The uncommitted diff
(8 files) implements the loop-level value display ruled by the owner ("print the last expression
evaluated", the r3-restricted round). The gate verdict is REQUEST CHANGES.

READ FIRST, in order:
1. collab/1792315000000-reviewer-value-display-gate-v3.deepseek-flash.findings.md — the gate's full
   findings (the P1 + three P3s, with line numbers). Your work is to resolve every finding.
2. collab/1792312000000-engineer-value-display-r3-restricted.prompt.md — the r3 scope (what NOT to
   expand: no displayhook-every semantics, no out-of-band marker redesign — the owner ruled the
   Clojure-style last-expression display; the marker stays as is, the P3 marker-hardening is
   explicitly out of scope for this round).

STEP 0 — reconcile with master: commit the current WIP to branch value-display-r2 (a WIP commit is
fine), then merge origin/master (it moved to ba197c96: the :pysp scanner-golden regen 37db44da +
docs). No file overlap is expected; if scanner_goldens.cljc conflicts, take master's pins.

THE P1 (must fix — the default JVM lane is red until this lands): the prelude change
(py/result! in core-definitions at prelude.cljc:2518, py.rt/value in init-form at :3518) moved the
py manifest address; the diff re-pinned linked_prelude_test + dht-import-v1.edn but NOT:
  a. test/yang/python/antlr/float_address_test.cljc:227-274 — the bundled prelude root (2bc76b3c…),
     A (e0a762bf…), A′ (2de8098a…), the derivation record (8728d356…), prelude-id (231cbc80…).
     NOT ^:slow — bare `clj -M:test` fails. Re-derive mechanically from the new py manifest
     (093fc404…), do not hand-invent values.
  b. test/yang/python/antlr/linked_safepoint_test.cljc:243-253 — the pysp-manifest-golden
     (cf017bd4…): pysp's requires pin the py manifest address, so the pysp manifest address moves.
     Recompute the same way the test suite does (or by running the linked build) — mechanically.
  c. test/yin/vm/semantic/scanner_goldens.cljc — the :c4 :py entry moves (:definitions grows by
     result!/value additions, :application-count 3957, requirements). Regenerate with the CHECKED-IN
     GENERATOR: `clojure -Sdeps '{:aliases {:t {:extra-paths ["test"]}}}' -M:t
     test/resources/yin/vm/semantic/scanner-golden-c4.generate.clj` — first set its `deferred` to
     exactly `#{:py}` (the landed rule: the set names exactly the groups expected to move; :pysp is
     NOT expected to move — its body is unchanged, only the py manifest ADDRESS moves, and the
     scanner scans bodies, not addresses — if the gate aborts on :pysp, STOP and report, that is a
     finding, not a pin move). Double-run: byte-identical. Then set `deferred` back to #{:pysp} in
     your commit (the committed state stays #{:pysp}; do not commit #{:py}).
  d. Confirm dht-import-test is the only reader of test/resources/yang/python/dht-import-v1.edn and
     run it green.

THE P3s (fix all):
  e. Stale docstrings: test/yin/repl/python_antlr_test.cljc:3-5 (still says "shows what a submission
     printed and not its value"); src/cljc/yin/vm/docs/yin.repl.md:72-73 (same claim);
     src/cljc/yin/repl.cljc:1888 ("the display policy" — now :render); prelude.cljc:71, :3504,
     :3610-3611 ("three runtime cells" — there are now four: ctx, limit, out, value);
     docs/design/yang.typescript.md:63, :1108 (specify the removed `:display :output` binding key —
     align with the ruled loop-level display; keep the doc self-contained, no collab references).
  f. Coverage: add acceptance rows to the shared `packets` map in test/yin/repl/python_antlr_test.cljc
     (:44-51, covers cljs/cljd via langs): the owner's own example (the even_squares comprehension
     printing [4, 16, 36, 64, 100]), None alone (prints nothing), 1+2, a string, a semicolon
     submission (`x = 1; x`), and a later assignment after a displayed expression.
  g. Semantic edges — record, don't change behavior (document in yin.repl.md next to the display
     policy): bare expressions inside top-level compound statements (if/for bodies) do NOT display
     (the r3 mapping scans top-level simple_stmts only; CPython's interactive mode would); a value
     captured before a later statement raises is printed AFTER the error text (CPython prints it
     before the traceback); render/string-repr escapes only code units < 32 and 127, so a raw C1
     control (e.g. '\x80') reaches the prompt (pre-existing, now load-bearing — if there is a
     known-gaps-worthy edge, say so in your report rather than expanding scope).

VERIFY (each namespace as a separate invocation — the comma form selects zero tests; use
DATOM_SLOW_TESTS=1 for the slow ones): float_address_test, linked_prelude_test,
linked_safepoint_test, python_antlr_test, yang.python.antlr.e2e-test, yin.repl.frontend-catalog-test,
dht-import-test, yin.repl.main-test, yin.vm.semantic.scan-test (slow), lower-portable-test. Plus:
kondo 0/0 on every changed file (clj -M:kondo --lint <file>), cljstyle check on the changed files,
the generator double-run byte-identical. If the Node (cljs) lane is cheap, run the shared-packet
test under it; if not, say so in the report.

CONSTRAINTS: single-turn completion, no interactive approvals. Commit your final state to branch
value-display-r2 (one or more commits; do NOT push, do NOT touch master). Leave collab/ untracked.
End your final output with: files changed, the namespace results (tests/assertions/failures each),
the new pins (float_address + pysp manifest + py manifest), the generator double-run proof, and
anything you could not verify.
