Created-GMT: 2026-10-01 17:50:00 GMT
Created-Local: 2026-10-02 00:50:00 +07 (+0700)
Coding-Agent: claude
Session-ID: aca6eb75-a02d-4556-b7e9-d69ccb513811

# Task: Python phase C3, slice S1 — exact-integer module and carrier recognition

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude opus (opus-5.5) | Assigned: 2026-10-02 00:50:00 +07 (+0700) | Status: active | Rationale: bounded module-level slice; VM-side recognition work adjacent to the landed heap/reclamation line

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-py-c3int1 (branch yang-python-c3-s1, based on master 1df123d1).
Do not touch /Users/sto/workspace/datomworld (another orchestrator seat owns that main tree), any other worktree, or any
other branch. Do not stage or commit; leave the finished diff in the working tree.

DESIGN (binding): read collab/1790874940000-architect-python-c3-bignum-design.gpt-6-astra.findings.md — the C3
big-integer design — and collab/1790875860000-architect-c3-bignum-crossruling.claude-fable-5-1.findings.md — the
converged ruling of the architect-pair mob. Decision authority: OWNER, verbatim, "If there are questions you need from
me, then mob between gpt-6-astra and fable-5.1" (2026-10-02, overnight standing orders) — the converged rulings are
binding. Verify the design's file:line citations against the current tree.

S1 SCOPE (ruling 14 sequencing: module slices run alongside C2; NO prelude or lowering changes in this slice):
1. Carrier rule (rulings 1-2): each integer value has one carrier per host — native iff within signed 64-bit (JVM
   long, Dart int) or ±(2^53-1) (JS); otherwise the host BigInt (clojure.lang.BigInt or host BigInt). Promotion
   before every operation; demotion after it is mandatory.
2. The versioned :pure integer module (ruling 5): a new module namespace carrying the exact kernels — add, subtract,
   multiply, quotient/remainder and floor div/mod with Python sign rules, compare, bit operations on negatives,
   exact non-negative integer power, exact decimal parse and format — written once over a per-host shim that does
   not depend on Jing's privates. Guest integers go only through the module; internal VM counters stay VM primitives.
3. Carrier recognition as scalars (ruling 4): the encoder, the heap trace, pin-refs, values/kind-of and data/number?
   must recognize exact-integer carriers as scalars — tested on Node and Dart BEFORE any bignum reaches a cell. No
   UCF marker, no change to the cell-lift refusal, no new payload kind or AST tag (rulings 3-4).
4. Numeric limits as explicit Python-profile data in bits and digits (ruling 11): a bit-length breach is guest
   MemoryError; a digit-limit breach is guest ValueError; neither is OverflowError. No implicit default; composition
   supplies values.
OUT OF SCOPE: prelude numeric-tower rewiring, literal lowering beyond ±(2^53-1), dict-key normalization (C3-S2+
), stream/remote transport (ruling 12: no widening in C3), hash() (ruling 7, S2+).

ACCEPTANCE (slice level; the full ruling-14 phase gate is later slices):
- Kernel unit tests across JVM, Node, Dart: exact results at and beyond the carrier boundaries (2^53, 2^63, negative
  operands, bit ops on negatives, big powers), promotion/demotion asserted in both directions.
- Recognition tests on Node and Dart: a bignum-carrying value passes encoder, heap trace, pin-refs, kind-of and
  data/number? as a scalar without a cell wrapper.
- Unchanged Jing fixtures (ruling 14): the existing suite output on the exact-integer path is untouched.
- The full existing lanes stay green.

MECHANICS:
- Before any JVM lane in this fresh worktree run bb gen:python-antlr, then bb build:yin-repl-node (master's deps.edn
  puts the generated parser classes on :paths; a fresh worktree without them dies on ClassNotFoundException).
- Run test lanes in the FOREGROUND, one simple command per step (a headless run cannot approve compound shell lines).
- Lanes: bb test:clj, bb test:cljs, bb test:cljd. The CLJD lane writes shared generated output: if it fails on
  unrelated namespaces in a way that looks like a cross-worktree collision, report it and rerun once before concluding.
- clj-kondo: 0 errors on every file you touch; cljstyle clean (whitespace-only fixes allowed).
- Do not weaken tests; preserve C1 behaviour. If a design element conflicts with the code, stop that element and
  report the conflict instead of improvising.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>

Report changed files, exact test/check outcomes with counts, unresolved concerns, and any incomplete work. Do not claim
edits or tests that did not occur.
