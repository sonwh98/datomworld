Created-GMT: 2026-10-01 17:14:30 GMT
Created-Local: 2026-10-02 00:14:30 +07 (+0700)
Coding-Agent: claude
Session-ID: bfaf5e35-70a5-4e4b-9686-bf5717d46bd8 (provenance: 720bbffc rejected the model string claude-opus-5.5; 71532d55 started in the main tree, could not reach the worktree, did nothing; the dispatch runs from the worktree with this brief staged in the worktree's own collab/)

# Task: Python safepoint interpreter — slice 1 (mechanism + signals)

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-opus-5.5 | Assigned: 2026-10-02 00:14:30 +07 (+0700) | Status: active | Rationale: author of the Python spike (A, B) and phase C1; lowering continuity

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-py-safepoint1 (branch yang-python-safepoint-s1, based on master
111a9823, which contains C1 as 24cdf535). Do not touch /Users/sto/workspace/datomworld (another orchestrator seat owns
that main tree and its uncommitted changes), any other worktree, or any other branch. Do not stage or commit; leave the
finished diff in the working tree.

DESIGN (binding): read
/Users/sto/workspace/datomworld/collab/1790849347715-architect-safepoint-interpreter.claude-fable-5-1.findings.md
first — the safepoint-interpreter design this slice implements. Its file:line citations predate C1; verify each against
the current tree and follow the code where C1 moved things. Owner decisions, verbatim (recorded 2026-10-01 17:37 +07,
all accepted): "(5) a generic :stream/poll effect; (6) sites from frontend marks; (7) identity = canonical tree,
evaluator runs the derived tree; (8) no signal-delivery journalling in slice 1; (9) count-based green-thread switches;
(10) sys.settrace without a tracing profile raises an explicit unsupported error; (11) slice order signals, recursion,
tracing, threads."

SLICE 1 SCOPE (design Q5, "the mechanism plus signals, end to end"; nothing beyond it):
1. Engine: a stream/poll! export and a :stream/poll arm in the shared handle-effect, with a declared profile
   (non-parking read: blocked is a value, not a park).
2. Lowering: emit :yang/site marks for :loop and :call, and emit an encoder/source-envelope with the map AST as its
   member, so marks reach the frontend-metadata side table. The naive lowering rows stay unchanged; the prelude and
   module body carry no marks, so they are untouched automatically.
3. yang.safepoint (language-neutral namespace, not under yang.python): pure insert over the row medium, tail marks
   stripped and recomputed over the whole derived tree (move mark-tails to a shared namespace per the findings), the
   :derive ledger record (input A, output A', function :yang.safepoint/insert, profile pinning the hook map and the
   sorted site set), deterministic re-runs. Names, ledger, publication and identity refer to the canonical tree A
   (decision 7); the evaluator reads exactly one stream, chosen by the composition.
4. Python hook prelude: py.sp/loop and py.sp/call polling a signal cursor via :stream/poll; on an event, run the
   registered Python handler or by default raise KeyboardInterrupt (a new class under BaseException, in the hook
   prelude, not the naive prelude). The raise is an explicit continuation invoke at an explicit program point.
NOT in slice 1: recursion depth, tracing/:line marks, threads, signal journalling (decisions 8 and 11).

ACCEPTANCE (design Q5 acceptance tests, on all four VMs across the JVM, Node and CLJD hosts):
- Transparency: the e2e corpus through the stage with no-op hooks gives the naive output.
- Identity: an empty profile yields the same root and rows.
- Canonical untouched: the input batch is unchanged; every derived row validates; prelude rows keep their ids.
- Insertion determinism: the same input gives the same A' and record address on CLJ, CLJS and CLJD.
- Interrupt: while True: pass with one pre-appended signal ends with KeyboardInterrupt; wrapped in
  try/except KeyboardInterrupt it prints from the handler.
- No park: with an empty signal stream the derived program finishes without ever blocking, same output as naive.
- Tail preservation: a 100,000-iteration safepointed loop grows no continuation on the VMs that honour tail marks.
- Atomicity: no hook application appears under any prelude definition.
- Fail closed: the derived program without the hook prelude reports the unresolved hook name.
- stream/poll!: on an empty stream it returns :dao.stream/blocked without parking; ok advances the cursor; a callee
  without the declared effect is refused.

MECHANICS:
- Before any JVM lane in this fresh worktree run bb gen:python-antlr, then bb build:yin-repl-node (master's deps.edn
  puts the generated parser classes on :paths; a fresh worktree without them dies on ClassNotFoundException).
- Run test lanes in the FOREGROUND, one simple command per step (a headless run cannot approve compound shell lines).
- Lanes: bb test:clj, bb test:cljs, bb test:cljd. The CLJD lane writes shared generated output: if it fails on
  unrelated namespaces in a way that looks like a cross-worktree collision, report it and rerun once before concluding.
- clj-kondo: 0 errors on every file you touch; cljstyle clean (run cljstyle fix on your files if needed, whitespace
  only).
- No new AST tag; ordinary :application rows only; the canonical program is never modified; do not weaken existing
  tests. If a design element conflicts with the post-C1 code, stop that element and report the conflict instead of
  improvising.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>

Report changed files, exact test/check outcomes with counts, unresolved concerns, and any incomplete work. Do not claim
edits or tests that did not occur.
