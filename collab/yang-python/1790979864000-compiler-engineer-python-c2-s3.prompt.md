Created-GMT: 2026-10-02 22:25:00 GMT
Created-Local: 2026-10-03 05:25:00 +07 (+0700)
Coding-Agent: claude
Session-ID: 8a064c70-5a78-4fc8-a2d8-1bcd34e27a0f

# Task: Python phase C2, slice S3 — yield from, iter, sequence iterators

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude opus (opus-5.5) | Assigned: 2026-10-03 05:25:00 +07 (+0700) | Status: active | Rationale: continuation of the C2 generator line; S1 (7654c2d0) and S2 (c3f2da8f) are landed on master

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-py-c2gen3 (branch yang-python-c2-s3, based on master
c3f2da8f, which contains S1+S2). Do not touch /Users/sto/workspace/datomworld, the datomworld-linker-* worktrees
(another seat owns them), or any other worktree. Do not stage or commit.

DESIGN (binding): read collab/1790874900000-architect-python-c2-generators-design.claude-fable-5-1.findings.md
(the C2 design; S3 = its "S3: yield from, iter, sequence iterators" slice and acceptance table) and
collab/1790875890000-architect-c2-generators-crossruling.gpt-6-astra.findings.md (binding converged rulings).
The S2 machinery (send/throw/close/GeneratorExit, py/gen-attr) is landed — build on it, verify the design's
file:line citations against the current tree.

S3 SCOPE (the design's S3 table — nothing beyond it):
1. yield from delegation: the inner generator receives send values, exceptions thrown at the outer reach the
   inner's handlers, values returned by the inner become the delegation expression's value; nested cleanup runs
   inner before outer (the table's close row); the return value of `yield from range(3000)` consumed in a for.
2. iter and sequence iterators: iter([1,2]) with next/next(it, default), iter(it) is it for iterators; the
   py/iter-at / py/iterable arms for sequence-backed iterators per the design (ruling 5: keep py/iter-at's
   index signature).
3. The design's S3 acceptance table rows, each on all four evaluators (JVM) with the portable parity forms for
   Node/Dart: inner send-through, throw-through, double-finally close ordering, the 3000-item delegation sum,
   the iter protocol rows.
4. PEP 380 semantics per the design's S2/S3 rulings (return-value transport via StopIteration.value internally,
   never guest-visible at protocol boundaries — ruling 3).

MECHANICS:
- Before any JVM lane: mise exec -- bb gen:python-antlr, then mise exec -- bb build:yin-repl-node. Run lanes in
  the FOREGROUND one at a time and wait for each (a full JVM lane can take ~45 min under load; that is expected).
  One command per step; NO background runs, NO watchers; complete the turn. mise is trusted; node_modules
  installed.
- Lanes: mise exec -- bb test:clj, bb test:cljs, bb test:cljd; clj -M:kondo and cljstyle check on touched files.
- JS-safe literal discipline for any new prelude numeric literal; no float-bearing address goldens; no new AST
  tag; do not weaken tests; if a design element conflicts with the landed S1/S2 code, stop and report.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>

Report changed files, exact counts per lane, unresolved concerns, incomplete work. Do not claim edits or tests
that did not occur.
