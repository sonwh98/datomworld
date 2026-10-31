Created-GMT: 2026-09-30 11:13:35 GMT
Created-Local: 2026-09-30 18:13:35 +07 (+0700)
Coding-Agent: claude
Session-ID: 0dd55e3e-bfc9-48ba-ba2a-5e307baeb120
# Task: Captured continuations are invocable (apply a :reified-continuation)

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-30 18:13:35 +07 (+0700) | Status: active | Rationale: four-VM parity change in CESK dispatch

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-k-invoke (branch vm-continuation-invoke from master dac64b41;
mise trusted, npm ci done). Do not touch /Users/sto/workspace/datomworld or any other worktree (another orchestrator's
DHT and REPL work is in flight there). Do not stage or commit.

Owner instruction (verbatim): "yes, wire up continuation invocation with a test if it does not cause conflict with the
current work with the DHT."
Context: the owner is planning ANTLR frontends; early return, break/continue and try/raise should lower to escaping
through a captured continuation, which needs this.

Today: `:vm/current-continuation` reifies the continuation as a value, but applying it throws "Cannot apply
non-function" in every VM. The repo's own design already names the fix: src/cljc/yin/vm/docs/co-routines.md:116 and :159
("Add a :reified-continuation clause in application dispatch"). No new AST tag, no grammar change.

Sites (verify; line numbers approximate):
- AST walker: src/cljc/yin/vm/ast_walker.cljc application arms ~:617-640 and ~:659-684 (and apply helper ~:251-261);
  capture at ~:523-528 ({:type :reified-continuation :k k :env env}).
- Semantic VM: src/cljc/yin/vm/semantic.cljc apply ~:223-249; capture opcode 19 ~:345 ({:segment :pc :env :stack :k}).
- De Bruijn stack VM: src/cljc/yin/vm/debruijn/stack.cljc apply ~:567-597; capture ~:687.
- De Bruijn register VM: src/cljc/yin/vm/debruijn/register.cljc apply ~:564-606; capture ~:677.

Required semantics (abortive, Scheme-style, multi-shot):
1. Applying a :reified-continuation to exactly one argument discards the current continuation and delivers the argument
   as the value of the original capture point, restoring the captured control context (k/env; for the bytecode VMs
   segment/pc/env/stack/K as captured).
2. The store is NOT rolled back: the current store is kept (standard CESK: S is not part of the continuation).
3. Multi-shot: a continuation can be invoked more than once, including after the expression that captured it returned.
4. Arity other than 1 fails with an ex-info whose message is identical across all four VMs.
5. Works in tail and non-tail operator position.
If any VM's machine state makes one of these ill-defined or impossible, STOP and report rather than choosing a
different semantics.

Acceptance (tests; each must fail if the change is reverted — prove by temporary mutation, then revert):
- Escape: a continuation captured outside a nested computation is invoked from inside it (pending frames, e.g. an
  enclosing (+ 1000 ...) application, must be discarded); result is the delivered value.
- Re-entry: a continuation stored (e.g. via a store key or closure) and invoked after its capturing expression has
  returned re-enters the capture point; show it with a bounded counter in the store so the test terminates.
- Store not rolled back: a store write made before the invocation is visible after it.
- Arity error message identical across VMs.
- All of the above run against ALL FOUR VMs through the existing parity harness if test/yin/vm_test.cljc (or a sibling)
  has one; otherwise one test per VM with the same expectations.

Allowed files: the four VM files above; src/cljc/yin/vm/engine.cljc only for a shared helper; src/cljc/yin/vm/completion.cljc
only if its abstract analysis must learn the new application case (say why); tests under test/yin/ for yin.vm; docs
src/cljc/yin/vm/docs/co-routines.md and src/cljc/yin/vm/docs/ast.md (update the "Currently ... throws" text).
Anything else: STOP and report. Do not change the AST grammar, add tags, or touch yin.repl/*, dao.jing/*, dao.stream/*.

Verify and report (commands per docs/agents/build-n-test.md): clj -M:kondo --lint on changed files; cljstyle check (say
if blocked); focused JVM over the yin.vm test namespaces you touched; full `clj -M:test`; `bb test:cljs` (confirm your
test namespaces print "Testing <ns>"). NOT bb test:cljd (the orchestrator owns that lane). Give exact counts.

Write the report to /Users/sto/workspace/datomworld/collab/1790766815406-vm-engineer-continuation-invocation.claude-opus-5-5.report.md
and give it as your final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 0dd55e3e-bfc9-48ba-ba2a-5e307baeb120
Report changed files, the diff summary, exact test/check outcomes, unresolved concerns, and incomplete work. Do not
claim edits or tests that did not occur.

## Round 1 addendum (2026-09-30, orchestrator)
- Status-Event: r1 | Model: claude-opus-5-5 | Status: blocked | Rationale: harness denied reading the main-tree collab/ from the worktree; no work done.
The brief is now copied to /Users/sto/workspace/datomworld-k-invoke/collab/. Write the report to
/Users/sto/workspace/datomworld-k-invoke/collab/1790766815406-vm-engineer-continuation-invocation.claude-opus-5-5.report.md
instead (the orchestrator copies it back). All other instructions stand.
