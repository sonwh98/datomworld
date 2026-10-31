Created-GMT: 2026-10-18 03:35:00 GMT
Created-Local: 2026-10-18 10:35:00 +07 (+0700)
Coding-Agent: claude-sonnet-5-5

# Task: antlr-multi m012d — implement the strengthened M1 contract the gate requires

WORK TREE: /Users/sto/workspace/datomworld-antlr-multi (branch antlr-multi, HEAD c6187d68). The M0-M2
landing gate (codex gpt-6.1-sol, the same reviewer who granted the plan READY through three rounds)
returned REQUEST CHANGES: M1 does not implement the strengthened contract yang.antlr.md 4.6 promises.

READ FIRST:
1. collab/1792360000000-reviewer-antlr-multi-m012-gate.gpt-6.1-sol.findings.md — the gate's full
   findings (three P1s + one P2, with file:line and the probe results).
2. docs/design/yang.antlr.md 4.6 — the promised contract: source-aware validation, the type-keyed
   synthetic-token policy, strict preorder checks, the adversarial corpus, the error-coordinate
   disagreements. The DESIGN IS THE AUTHORITY — implement to it; do NOT weaken the design to match
   the code.

THE WORK (each finding, in order):

P1-1 The validator (src/cljc/yang/antlr/packet.cljc — validate! at :150, conform at :219): implement
  what 4.6 promises — child-order strictness (DFS/preorder per the doc's wording), duplicate-sibling
  rejection, source-text equality where the packet carries source, UTF-8 boundary alignment for byte
  spans (a span must not end inside a multi-byte sequence — the é𝄞 class), grammar-name validation,
  and the outcome-specific contracts (error packets vs ok packets). Keep it portable (cljc, all three
  hosts). Reject exactly the cases codex's probes showed passing: duplicate child edges, wrong token
  text, a collapsed synthetic NAME, a span ending inside é, non-DFS child ordering.

P1-2 The synthetic-token policy (src/cljc/yang/antlr/export.cljc:55): replace text-mismatch/overlap
  inference with the approved type-keyed policy (DEDENT/NEWLINE/EOF and whatever 4.6 names) with
  canonical helper text, retaining independent exporter evidence. A collapsed synthetic NAME must
  FAIL validation/conform.

P1-3 The adversarial conformance corpus (test/yang/antlr/conformance.cljc + the exporter tests):
  the comparison shape must include child edges and synthetic flags; the error helper must check
  columns; add the twelve mutations and the two pinned helper-token error-coordinate disagreements
  that 4.6 specifies, running on all three hosts (the JVM/Node/Dart quick lanes must exercise them).

P2 Document the three JS compatibility additions in yang.antlr.md 4.6 (the duplicate
  getIndentationCount removal, the :output-patches import rewrite, the antlr4-default runtime
  fallback) — narrow, with the manifest pointers; keep the doc self-contained (no collab references).

VERIFY (one namespace per invocation; DATOM_SLOW_TESTS not needed for these): the conformance +
cst-export + python-antlr namespaces on the JVM; then the Node quick lane (mise exec -- bb test:cljs)
and the Dart quick lane (mise exec -- bb test:cljd) — the new corpus must run on all three. If a
generated-artifact patch fails ("Patch does not apply"), rm -rf build/antlr lib/antlr and rerun (the
generator is not idempotent over already-patched trees — known). kondo 0/0 on changed files; cljstyle
check them (if the bare command is denied, say so — the orchestrator runs it).

CONSTRAINTS: single-turn completion, no interactive approvals. Commit to branch antlr-multi (do NOT
push, do NOT touch master); collab/ stays untracked. End with: the finding-by-finding resolution map,
the new test counts per host, the probe cases now failing as required, and anything left open.
