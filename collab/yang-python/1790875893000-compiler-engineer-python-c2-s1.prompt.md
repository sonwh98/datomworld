Created-GMT: 2026-10-01 17:45:00 GMT
Created-Local: 2026-10-02 00:45:00 +07 (+0700)
Coding-Agent: claude
Session-ID: f5c578c3-b4b7-41a3-9e0e-66d8582b073f

# Task: Python phase C2, slice S1 — generators core

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude opus (opus-5.5) | Assigned: 2026-10-02 00:45:00 +07 (+0700) | Status: active | Rationale: the C1 lowering engineer line; fresh seat for a new feature on the same files

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-py-c2gen1 (branch yang-python-c2-s1, based on master 1df123d1).
Do not touch /Users/sto/workspace/datomworld (another orchestrator seat owns that main tree), any other worktree, or any
other branch. Do not stage or commit; leave the finished diff in the working tree.

DESIGN (binding): read collab/1790874900000-architect-python-c2-generators-design.claude-fable-5-1.findings.md — the
C2 generator design — and collab/1790875890000-architect-c2-generators-crossruling.gpt-6-astra.findings.md — the
converged ruling of the architect-pair mob. Decision authority: OWNER, verbatim, "If there are questions you need from
me, then mob between gpt-6-astra and fable-5.1" (2026-10-02, overnight standing orders) — the converged rulings are
binding. Verify the design's file:line citations against the current tree and follow the code where they moved.

S1 SCOPE (design Q8, "S1: core" — nothing beyond it):
- Prelude: py/make-generator, py/gen-switch ([:send v]/[:throw e] -> [:yield v]/[:return v]/[:raise e], never raising
  from the generator's side), yield, gen-exit, gen-fail; StopIteration; next; the py/iter-at :generator arm and the
  py/iterable pass-through (per-generator handler stack and boundary frame per rulings 1-2; clear slots on every
  state transition so a suspended generator retains neither caller nor stale base beyond the design's stated rule).
- Lowering: arms for yield_stmt and yield_expr; a :gen binder in ctx reset in every nested scope; removal of the
  three yield guards (module-level yield and [(yield x) ...] keep producing the single :yang.python.antlr/syntax
  diagnostic per the acceptance table).
- No VM change, no new Universal AST tag, no wire or ledger change (design §UCF).
S1 acceptance table: implement every row of the design's S1 table (the def-g while-loop, lazy body, return 7 via
  e.value, yield-expression send, the two syntax diagnostics, the 3000-item break loop with the JVM continuation-length
  stability check, and the Node prelude-notation gen-switch triple).

MECHANICS:
- Before any JVM lane in this fresh worktree run bb gen:python-antlr, then bb build:yin-repl-node (master's deps.edn
  puts the generated parser classes on :paths; a fresh worktree without them dies on ClassNotFoundException).
- Run test lanes in the FOREGROUND, one simple command per step (a headless run cannot approve compound shell lines).
- Lanes: bb test:clj, bb test:cljs, bb test:cljd. The CLJD lane writes shared generated output: if it fails on
  unrelated namespaces in a way that looks like a cross-worktree collision, report it and rerun once before concluding.
- clj-kondo: 0 errors on every file you touch; cljstyle clean (whitespace-only fixes allowed).
- Do not weaken tests; preserve C1 behaviour except where the design explicitly changes it (the S4 inlining removal is
  NOT in S1). If a design element conflicts with the code, stop that element and report the conflict instead of
  improvising.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>

Report changed files, exact test/check outcomes with counts, unresolved concerns, and any incomplete work. Do not claim
edits or tests that did not occur.
