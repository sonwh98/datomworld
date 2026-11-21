Created-GMT: 2026-11-19 21:59:38 GMT
Created-Local: 2026-11-20 04:59:38 +07 (Asia/Ho_Chi_Minh)
# Task: yang-compilers-post-dry-graph-malleability-check
Role: Reviewer (step-7, your round-1/round-2 thread continues)

The round-2 deduplication is FULLY LANDED (batch 1 c9dbc991 + batch 2 0d1ab55d, your
APPROVEDs; argon architect sign-off GRANTED). I drew the dependency graph of the landed
yang.* compilers from the analyzer (`bin/analyze-deps.clj`, current master) and my own
verified require extractions. Judge: **does this graph ALIGN with docs/agents/malleability.md**
(bounded complexity, loose coupling, high cohesion, the architectural-vs-accidental
coupling distinction, the red-flag rules)? Read malleability.md and
docs/design/datom.world.md in your cwd (the laptop checkout, synced to the landed master —
the graph below IS this tree's state, so file reads here are authoritative).

## The measured graph (analyzer output, current master)

Node degrees (in / out, internal yang edges + foundation edges as the analyzer counts them):

| Node | in | out | Notes |
|---|---|---|---|
| yang.core.uast | 14 | 2 | the hub |
| yang.antlr.packet | 11 | 1 | 15 publics post-demotion |
| yang.core.prelude | 10 | 1 | 11 publics, pure emission post-split |
| yang.core.prelude-admission | 8 | 2 | 3 publics (batch-1) |
| yang.clojure | 7 | 5 | |
| yang.stage | 5 | 1 | |
| yang.python.antlr.prelude | 4 | 6 | |
| yang.php.antlr.lower | 4 | 14 | heaviest out in the surface |
| yang.typescript.antlr.prelude | 3 | 6 | |
| yang.python.antlr.lower | 3 | 10 | |
| yang.php.antlr.siteset | 3 | 9 | wrappers post-C1 |
| yang.php.antlr.prelude | 3 | 9 | |
| yang.python.antlr.repl | 2 | 5 | |
| yang.core.siteset-publication | 2 | 5 | |
| yang.python.antlr.siteset | 1 | 8 | |
| yang.php.antlr.eval-publication | 1 | 5 | |
| yang.typescript.antlr.lower | 0 | 8 | |
| yang.python.antlr.publication | 0 | 5 | |
| yang.php.antlr.eval-coordinator | 0 | 9 | |
| yang.clojure.prelude | 0 | 6 | |
| yang.io | — | — | NEW: 5 publics in one cljc (post-C4) |
| yang.antlr.export | — | — | 6 publics (7→6 post-C5 demotions) |
| yang.core.siteset-store | — | — | 5 publics (batch-1) |
| yang.php.antlr.eval-transport | — | — | 4 publics, out: 0 (C6) |

Known graph facts: analyzer reports 3 CIRCULAR entries — all SELF-loops (A→A) in
yang.php.antlr.quoted, yang.python.antlr.prelude, yang.typescript.antlr.prelude (macro
self-reference); no multi-node SCCs. No yang.core namespace requires any target namespace.
The 3 per-host CST files + cst.jolt remain separate per-host files by ruling.

## Questions

1. Bounded complexity: are the post-landing public counts (prelude 11, packet 15, export 6,
   io 5, siteset-store 5, siteset-publication 5, prelude-admission 3, eval-transport 4,
   module 8, lower 12) acceptable per the doc — or do any now REQUIRE action?
2. Coupling: which of the high-out nodes are architectural vs accidental coupling per the
   doc's own distinction? Is php lower's out:14 defensible (stable foundation deps) or a
   flag?
3. The 3 self-loops: do they matter, or are they analyzer noise for macro namespaces?
4. High-degree nodes (119 ns with 6+ connections repo-wide): is the yang surface's
   contribution proportionate and healthy?
5. Overall: does the landed state ALIGN with malleability.md, and is there ONE residual
   action (if any) most worth taking next?

Begin exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

End with exactly one line:
GRAPH VERDICT: ALIGNS | ALIGNS WITH ONE RESIDUAL ACTION — <it> | MISALIGNS — <why>
