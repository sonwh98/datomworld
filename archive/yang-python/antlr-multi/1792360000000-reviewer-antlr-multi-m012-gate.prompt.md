Created-GMT: 2026-10-18 03:05:00 GMT
Created-Local: 2026-10-18 10:05:00 +07 (+0700)
Coding-Agent: codex gpt-6.1-sol

# Task: M0-M2 landing gate — the ANTLR multi-target build, IR and three-host Python parser

Role: adversarial landing gate. You reviewed the PLAN through three rounds and granted READY (sign-off)
on the v3 plan (the six P1s and two late P1s resolved). This gate verifies the IMPLEMENTATION matches
that plan and is safe to land.

WORK TREE (read-only): /Users/sto/workspace/datomworld-antlr-multi, branch antlr-multi, HEAD c6187d68.
THE DIFF: `git diff a8b729ef 4685a8bd` (the feature; one commit holding M0+M1+M2+docs) plus the merge
c6187d68 (origin/master: the :pysp scanner-golden regen + docs — conflict-free, master's pins kept).

READ:
1. docs/design/yang.antlr.md section 4.6 — the multi-target pipeline, the CST packet v1, the
   conformance oracle (the plan's landed design hub).
2. collab/m012b-report.md — the implementer's M0/M1/M2 report (three additions beyond the plan, the
   Dart fixes, the CST-parity evidence including the deliberate [4 99] mutation that only Dart failed,
   the declared incompletes).
3. collab/m012c-report.log — the completion round (commits, the clean master merge, the in-slice
   scanner-golden run with deferred #{} — NOTHING moved, the py manifest held, the slow JVM namespaces
   green, cljstyle/Node-smoke left to the orchestrator).

GATE QUESTIONS:
1. Does the implementation match the READY'd plan's M0-M2 scope (the vendored grammar revision f4,
   the digest pinning, the packet v1 shape, the conformance oracle, the three-host parser split)?
   Any invented scope beyond the plan's declarations?
2. The three M0 additions (the duplicate getIndentationCount patch for Closure, the :output-patches
   mechanism rewriting the generated JS import line, the antlr4-default fallback in cst.cljs): each
   justified, minimal, and recorded in the design doc — or silent scope creep?
3. The M1 IR: packet.cljc's v1 shape + conform; cst.cljs/cst.cljd as siblings of cst.clj with the
   shared export.cljc. Is the packet schema target-neutral as designed (the language-specific CST
   lowered into the AST downstream)? The UTF-16/code-point span contract honored (the é𝄞 fixture)?
4. The M2 semantics: the frontend installed on every host (the JVM-only catalog test rewritten),
   worker isolation via fresh DFA/prediction-context caches per worker (declared UNTESTED on JS/Dart —
   acceptable to land with the test debt recorded, or a blocker?), the cljd defquoted prelude change
   (JVM/Node keep the plain def — the in-slice scanner-golden run confirmed nothing moved).
5. The declared state: the four helper licences UNRESOLVED with :redistribution :blocked-until
   unchanged — the plan declared this; confirm the landing doesn't silently widen redistribution.
6. Anything in the diff that would break the legacy frontends, the linker, or the other seat's
   surfaces (the diff touches prelude.cljc — the scanner-golden evidence says nothing moved; verify
   the claim's plausibility from the diff itself).

ORCHESTRATOR VERIFICATION ALREADY DONE (do not redo): JVM quick + slow namespaces green (m012c's
table), Node quick lane green at the m012b head and re-run from clean generated state post-merge
(orchestrator), Dart quick lane at the m012b head green (4022 tests / 0 failures; post-merge rerun in
flight at gate time), cljstyle rc=0, kondo 0/0 on changed files, the Node REPL smoke: frontend switch
+ comprehension + clean EOF exit (the value print is expected absent — the value-display feature is a
separate unlanded round).

Verdict: end with exactly one line "Verdict: READY (sign-off granted)" or "Verdict: REQUEST CHANGES"
with findings. Begin the final response exactly with Completed-GMT / Completed-Local.
