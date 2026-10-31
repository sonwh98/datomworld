Created-GMT: 2026-10-18 05:35:00 GMT
Created-Local: 2026-10-18 12:35:00 +07 (+0700)
Coding-Agent: codex gpt-6.1-sol

# Task: M0-M2 RE-GATE — verify the resolution of your three P1s and the P2

You are the same reviewer family that gated the ANTLR multi-target M0-M2 implementation and returned
REQUEST CHANGES with three P1s and one P2 (the strengthened validator, the synthetic-token policy, the
adversarial corpus, the design-doc recording). A fresh review of the resolution; your previous findings
file is in the tree.

WORK TREE (read-only): /Users/sto/workspace/datomworld-antlr-multi, branch antlr-multi. THE RESOLUTION
DIFF: `git diff c6187d68 25a908fb` (one commit). Your original findings:
collab/1792360000000-reviewer-antlr-multi-m012-gate.gpt-6.1-sol.findings.md. The implementer's report:
collab/m012d-report.log.

VERIFY, finding by finding, with your own probes where cheap:
1. P1-1 the validator: validate! structural by default, source-aware under {:source …}; conform
   [packet source opts]; the single preorder walk (duplicate edges, out-of-order children, non-forward
   edges, unreachable nodes); metadata checks (version, unit, grammar name, completion); outcome
   contracts (ok vs error packets; error records checked for kind/message/line/column/span); source
   checks (token text == source, spans in range + UTF-8 boundaries, terminal order, rule extents,
   synthetic policy). Re-run your probes: duplicate edges, wrong text, collapsed synthetic NAME, span
   ending inside é, non-DFS ordering — each must now be REFUSED.
2. P1-2 the synthetic policy: packet/synthetic-policy keyed on token type (DEDENT/EOF always synthetic;
   NEWLINE only at end-of-source; others never); canonical text (DEDENT "", NEWLINE "\n", EOF "<EOF>");
   zero-width spans; the exporter decides by type with independent evidence retained. Is this the
   approved policy from the READY'd plan?
3. P1-3 the corpus: the comparison shape includes child edges + synthetic flags; check-error validates
   columns + the error packet; 14 mutations (the required twelve + a real EOF + a mid-file synthetic
   NEWLINE), each refused by BOTH validate! and conform, running on all three hosts; the two
   helper-token error-coordinate disagreement pins; the golden asserts children + flags.
4. P2: §4.6 now documents the three JS compatibility additions + the per-host error-coordinate pinning;
   self-contained, no collab references.
5. NEW information from the round: the implementer pinned per-host helper-token error coordinates
   (JVM/Node column 2, Dart 0; EOF [1 0]/[2 0]/[1 7]; byte spans agree) under §4.6's "not required to
   agree". Rule on whether pinning-per-host is the right recording vs a defect to fix.
6. The declared residue: the token-ordering check is untested against arbitrary sources — acceptable
   debt or blocker?

ORCHESTRATOR VERIFICATION ALREADY DONE (do not redo): JVM focused namespaces green (cst-export
15/151, python-antlr, lower-portable, scope), Node lane no failures (the corpus namespace ran), Dart
4025 all passed, kondo 0/0, cljstyle clean (engineer + orchestrator runs).

Verdict: end with exactly one line "Verdict: READY (sign-off granted)" or "Verdict: REQUEST CHANGES"
with findings. Begin the final response exactly with Completed-GMT / Completed-Local.
