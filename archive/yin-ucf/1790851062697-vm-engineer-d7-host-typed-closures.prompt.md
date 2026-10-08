Created-GMT: 2026-10-01 10:37:42 GMT
Created-Local: 2026-10-01 17:37:42 +07 (+0700)
Coding-Agent: claude
Session-ID: a991361e-3716-4327-84f0-1a35a3a5a57b
# Task: D6/D7 slice A — host-typed Closure and Continuation, owner tags, qualified refusals, non-symbol parameter refusal

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-01 17:37:42 +07 (+0700) | Status: active | Rationale: four-VM runtime change per Architect design

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-d7 (branch vm-host-typed-closures from master 60b60898). A Python
phase C1 engineer is editing src/cljc/yang/python/antlr/* in another worktree: in yang.python you may change ONLY the
function py/numeric? in src/cljc/yang/python/antlr/prelude.cljc (see 6). Do not touch other worktrees. Do not stage or commit.

OWNER (verbatim): "go ahead with 4 and 5" (plan item 5 = D6/D7); on the design's decisions, verbatim: "accept all
recommendations" — structural equality on Closure/Continuation (kind, owner, payload); cell/stream/cursor refs stay
sealed plain data; refuse non-symbol parameters now (store-context move is a later slice C); add data/number? and
data/callable? to the data module.
Design (this worktree's collab/; read in full): 1790849347441-architect-d7-host-typed-closures-continuations.claude-fable-5-1.findings.md
Also: the gemini reclamation gate's accepted concerns 1-2, and the reclamation design (gc-children seam).

Build (design "Slice A"):
1. New namespace yin.vm.value: deftype Closure [owner payload], deftype Continuation [owner payload], trusted
   constructors, closure?/continuation? type tests, payload/owner accessors. NO ILookup (guest get must not read :env).
   Structural equality and hash agreeing with it (per-host protocol blocks; :cljd branch FIRST; no duplicate protocol
   parameter names — use [_ _x]). Neither implements IFn.
2. Owner tag: derived once per task from the capability secret, held in VM state; tasks without a secret use nil.
3. Every kernel (AST walker, semantic, de Bruijn stack, register): construction wraps the existing payload; application
   and invocation use the type tests and check the owner; lift encoder refuses foreign-owned values as
   :yin.k/non-portable and handles the types before the map? arm (a plain {:type :closure} map is now a literal);
   lower-closure mints with the receiver's owner; completion's typed? checks become type tests over the payload;
   printing renders from the payload, never the owner tag. :parked-continuation stays plain data.
4. One qualified refusal vocabulary (in the existing :reason shape): :not-applicable (operator is not a host fn, Closure
   or Continuation; carries a coarse :kind, never the value), :foreign-value, :foreign-format (today's register
   check-format!), :continuation-arity (rename). One engine classifier used by all four kernels.
5. Refuse non-symbol lambda parameters at the transition on every kernel (closes the :yin.k/store-of keyword-parameter
   entrance; like Rule R refuses a reserved binder).
6. data module: add data/number? and data/callable? (pure, :pure profile, identical across hosts; callable? true for
   host fns, Closure, Continuation). Rewrite ONLY py/numeric? in the Python prelude on data/number? (it classified by
   elimination via (nil? (get x :type)), which an opaque Closure would now pass).
7. Two-mode heap trace: kernel-shape pruning only when arriving from a kernel root, an engine table or a host-typed
   payload; once in a value position (env value, operand, register, cell content) every map is walked whole.
8. Docs: yin vm docs (ast.md/state.md) for the new types and refusals.

Acceptance tests (four VMs; each must fail when its part is reverted — prove by temporary mutation, then restore):
- forged {:type :closure ...} and {:type :reified-continuation ...} maps refuse :not-applicable;
- (get <closure> :env) and (get <continuation> :env) answer nil; assoc on a closure does not yield an applicable value;
- a :yin.k/store-of keyword parameter refuses;
- a closure handed raw from one task to another (in-memory stream) refuses :foreign-value; the same closure through
  lift and lower works;
- = on two closures from one lambda with equal envs is true; closure vs look-alike map false; hash agrees;
- two runs of one program give equal VM states;
- a guest frame-shaped map holding a cell ref keeps that cell alive across a collection (two-mode trace);
- applying 5 refuses :not-applicable with a :kind and without the value;
- the existing continuation-invoke, cell, heap-reclamation, data and yang.python.antlr e2e suites pass (e2e needs bb
  build:yin-repl-node and bb gen:python-antlr first in a fresh worktree).

Verify — EVERY command in the FOREGROUND: kondo (files as separate args); cljstyle check per file (say if blocked);
bb build:yin-repl-node; bb gen:python-antlr; focused JVM; full clj -M:test; bb test:cljs. NOT bb test:cljd.

Write the report to /Users/sto/workspace/datomworld-d7/collab/1790851062697-vm-engineer-d7-host-typed-closures.claude-opus-5-5.report.md
and give it as your final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: a991361e-3716-4327-84f0-1a35a3a5a57b
Report changed files, exact test outcomes, the mutation proof, unresolved concerns, incomplete work.

## Round 2 (orchestrator) — gate REQUEST CHANGES (gpt-6.1-sol)
Gate (copy in this worktree's collab/): 1790853691435-reviewer-d7-host-typed-closures-gate.gpt-6.1-sol.findings.md,
thread 01a0f732-d45c-7302-8cf2-744f7958d3d1. Orchestrator: cljstyle reformatted 4 of your files (whitespace, keep);
kondo 0 errors; the lane run was stopped for this round (no CLJD evidence yet).
Fix:
1. P1 printing leak (repl.cljc:398 quote-symbols; values.cljc:42,69 toString): closures and continuations render as
   OPAQUE kind markers everywhere — guest print/println/prn, nested inside collections, str/toString on every host, and
   the REPL's own display — never the payload, env, frames or owner. Use a marker consistent with the existing
   {:type :host-fn :name ...} rendering (e.g. {:type :closure} / {:type :continuation}; a name only if it is not
   captured data). No "trusted detailed view" in this slice. (Orchestrator correction: the design's "render from the
   payload" line conflicted with D7's opacity goal; the reviewer is right.) Tests: printing a closure/continuation, nested
   in a vector/map, via str, on all four VMs, contains no captured value.
2. P1 lowering bypass (ast_walker.cljc:1223, semantic.cljc:1036): validate the wire marker's :yin.k/params with
   vm/check-params! before minting, and require them to equal the attached lambda's params; refuse otherwise with the
   qualified vocabulary. Check the positional kernels' lower-closure paths for the same gap. Test: a marker with
   [:yin.k/store-of] (and a mismatched param vector) refuses on every kernel that lowers.
3. P2 vm.cljc:150: nil and false parameters escape `some`. Return a truthy wrapper naming the bad parameter; tests for
   both falsy binders on every VM path.
Mutation-prove each new test. Run EVERY check in the FOREGROUND: kondo (separate args), cljstyle check per file (say if
blocked), bb build:yin-repl-node, bb gen:python-antlr, focused JVM, full clj -M:test, bb test:cljs. Not bb test:cljd.
Append a "Round 2" section to the report; give the full report as your final response (same header).
