Created-GMT: 2026-10-18 09:25:00 GMT
Created-Local: 2026-10-18 16:25:00 +07 (+0700)
Coding-Agent: claude-sonnet-5-5

# Task: antlr-multi m012g — regenerate the e2e packets to the canonical-text contract (undo the test weakening)

WORK TREE: /Users/sto/workspace/datomworld-antlr-multi (branch antlr-multi, tip fefec6d4 — the m012f
merge). The merged head is green EXCEPT one decision the m012f engineer flagged: master's stored e2e
packets (the portable + import packet sets in test/yang/python/antlr/e2e_test.cljc's fixtures) carry
the runtime's DEDENT text "<EOF>", while the m012d synthetic-token policy (codex-approved) canonicalizes
DEDENT text to "" in the exported packet v1. m012f accommodated by making node-shape IGNORE a DEDENT's
text — that weakens the golden: a real exporter regression (e.g. emitting the runtime text again, or
losing synthetic text) would pass unnoticed.

ORCHESTRATOR RULING: the packet v1 is the TARGET-NEUTRAL IR; canonical synthetic text is what
guarantees cross-host packet identity (each runtime invents its own synthetic text). The stored
packets follow the contract, not the other way round. REGENERATE, don't weaken.

DO:
1. REVERT the node-shape change from fefec6d4 (the DEDENT-text ignoring) — the comparison compares
   text again.
2. REGENERATE the stored portable + import packets through the CURRENT pipeline (parse + export with
   the canonical policy), however the existing fixtures were produced (find the generator/probe —
   likely the same path that built them originally; if there is no checked-in generator, write the
   regeneration as a one-off in collab/ and record the exact invocation in the commit message). The
   only intended delta: DEDENT tokens' text "<EOF>" → "" (and any other synthetic text the policy
   canonicalizes — NEWLINE "\n", EOF "<EOF>").
3. VERIFY: e2e-test green at the regenerated fixtures (the comparison strict again);
   cst-export-test still 16/173; python_antlr_test green; the Node + Dart quick lanes
   (mise exec -- bb test:cljs / test:cljd) — the regenerated fixtures must be IDENTICAL cross-host
   (that is the point: run the e2e namespace scoped on Node/Dart too if cheap —
   clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "e2e"}' and
   bb src/dev/cljd_agg.clj --only yang.python.antlr.e2e-test).
4. kondo 0/0 + cljstyle on the touched files.

CONSTRAINTS: single turn, no approvals. One commit to antlr-multi. Do NOT push, do NOT touch master.
End with: the regeneration method, the fixture delta summary (how many DEDENT/NEWLINE/EOF texts
changed), the lane results incl. the scoped runs, and confirmation the comparison is strict again.
