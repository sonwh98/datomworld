Created-GMT: 2026-11-06 07:30:00 GMT
Coding-Agent: muse (muse-wonder-1.0, API lane, session continuation)
# Task: cc1 fix round — C1 must survive the PRODUCTION pipeline (four gate findings)

Your C1 implementation (e6522ceb) was gated: the marker walk, the equality/boundary/validation tests, and the safepoint independence are accepted — but the PRODUCTION paths lose the markers. Four findings, all to fix:

1. **The REPL/expansion pipeline drops markers before the final tree**: repl.cljc ~:1652 passes the compiled AST to encoder/program-batch (canonical rows WITHOUT frontend metadata), and the expander (macro.cljc ~:1255) produces a NEW final tree after tail marking + stand-in lowering with no C1 finalization. Fix: finalize C1 for the POST-EXPANSION final tree (the side table must cover the tree that actually executes — including the stand-in-lowered and tail-marked forms), and route the frontend metadata through encoder/program-batch (or the equivalent publication path) so canonical-row production retains it.
2. **Linked publication drops prelude markers**: prelude.cljc ~:108 annotates module-uast, but publish.cljc ~:117 converts to canonical rows without extracting/retaining frontend metadata. Fix: the published module must carry (or reference) the side table — per the design, published `clj` and `yang.call` coverage is in scope.
3. **The validator**: malformed marker rows must be REJECTED (not silently filtered); dedup must distinguish transport deliveries from duplicate entries in a DECLARED canonical table (dedupe the former, reject the latter).
4. **The frontend catalog**: revise `legacy-1` (yin/repl/frontends.cljc ~:26) — the coverage promise changed, so the catalog needs a revision + the relevant profile entry per the design.

Plus the evidence: the tests must move past projecting compiler-returned maps — add a post-expansion test (the marker set covers the tree that the REPL pipeline actually executes) and a published-module test (the markers retrievable from the published module). The one mislabel to fix: clojure_callsite_test.cljc ~:109 labels `[:expansion (:root tree)]` as an expansion origin — that checks the accepted shape, not a real expansion-record address; either construct a real expansion or relabel honestly.

The attributed cljs red (the dao.stream async test) is OUT of scope — its seat owns it.

Verification unchanged (focused local: the new post-expansion + published-module tests, the battery, kondo; suite-scale remote via the seat). Commit on the branch; push; do NOT merge.
