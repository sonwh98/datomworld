Created-GMT: 2026-09-30 14:30:58 GMT
Created-Local: 2026-09-30 21:30:58 +07 (+0700)
Coding-Agent: claude
Session-ID: 34a43a41-4291-4c0a-ba31-296bdadeab7a
# Task: D4 — effects are an unforgeable host type (fix F1: pure primitives forging engine effects)

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-30 21:30:58 +07 (+0700) | Status: active | Rationale: four-VM runtime change; mob decision D4

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-host-effects (branch vm-host-typed-effects from master e3cf971b;
mise trusted, npm ci done). This brief is at collab/ inside that worktree. Do not touch /Users/sto/workspace/datomworld
or other worktrees (another orchestrator's DHT/REPL work is in flight). Do not stage or commit.

OWNER (verbatim): "3. yes" — approving dispatch of D4 as decided by the Architect mob (fable + gpt-6-astra).
Governing decision (read in full): /Users/sto/workspace/datomworld/collab/1790776815400-architect-mob-outstanding-decisions.claude-fable-5-1.findings-r2.md
and .gpt-6-astra.findings-r2.md (D4), plus the cell ruling's F1:
/Users/sto/workspace/datomworld/collab/1790773810605-architect-cell-primitive.claude-fable-5-1.findings.md
(If those absolute paths are unreadable from your seat, STOP and say so; the orchestrator will copy them in.)

Defect (F1, confirmed by execution on the AST walker): effect detection is by result SHAPE
(src/cljc/yin/vm/module.cljc:231 effect?, vm.cljc:~373-377, engine.cljc handle-effect ~1755-1775). A :pure primitive can
return guest data carrying :effect and the engine executes it: (assoc {:key 'k :val 99} :effect :vm/store-put) performs
a runtime-keyed store write; (get {:d {:effect :vm/store-put :key 'k2 :val 7}} :d) executes instead of returning the map.

Required (mob-converged):
1. An effect is a host type minted ONLY by module/make-effect (or one trusted constructor). module/effect? becomes a type
   test. Ordinary maps are always data, including maps with an :effect key.
2. The constructor is never exposed as a guest primitive or module export.
3. The host type is an in-machine value only: portable descriptors and anything emitted onto compilation or effect
   STREAMS remain plain data (astra: the host wrapper must not become the stream representation). Identify every
   boundary where an effect is written to or read from a stream/wire and keep plain data there; convert at the trusted
   boundary only.
4. Migrate every {:effect ...} producer that must remain an effect (about 37 literal sites across ~14 files in
   src/cljc; plus tests) to the constructor. Keep behaviour identical on all four VMs (ast-walker, semantic, debruijn
   stack, register) and on CLJ, CLJS and CLJD (a deftype/record per host; check CLJD reader-conditional traps: use
   #?(:cljd ... :clj ...) with :cljd FIRST).
5. Layered check (fable's D4; astra did not object): when a callee's result IS an effect, check its kind against the
   callee's declared effect set via an identity-keyed callable->profile map built at registration
   (module.cljc ~179-187, ~210). If this cannot be done without a per-call reverse lookup/registry scan on the hot path,
   implement 1-4 only and STOP-report on 5 rather than choosing another mechanism.

Acceptance tests (each must fail if its part is reverted; prove by temporary mutation, then revert):
- The two F1 programs above return data (the map / value) and perform no store write, on all four VMs.
- A literal/assoc-built map with :effect :stream/make (or any engine effect kind) is data, not executed.
- Every existing effect path still works (existing suites green) — streams, FFI/dao.stream.apply calls, park/resume,
  module effects, the new continuation invocation.
- (If 5 lands) a host fn profiled :pure that returns an effect is refused with a qualified error; a declared effect passes.

Verify (docs/agents/build-n-test.md): clj -M:kondo --lint on changed files; cljstyle check; focused JVM on touched
namespaces; full clj -M:test; bb test:cljs (confirm new test ns prints "Testing <ns>"). NOT bb test:cljd (orchestrator lane).
Exact counts.

Write the report to /Users/sto/workspace/datomworld-host-effects/collab/1790778658842-vm-engineer-host-typed-effects.claude-opus-5-5.report.md
and give it as your final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 34a43a41-4291-4c0a-ba31-296bdadeab7a
Report changed files, every stream/wire boundary you identified and how it is handled, exact test outcomes, the
mutation proof, unresolved concerns, and incomplete work. Do not claim edits or tests that did not occur.

NOTE (orchestrator, at dispatch): copies of the three governing decision files are already in this worktree's collab/
under the same basenames; read those. Do not stop over the absolute main-tree paths.

## Round 1 addendum (orchestrator)
- Status-Event: r1 | Model: claude-opus-5-5 | Status: incomplete | Rationale: session ended after launching test lanes in
  the background ("I'll pick up when they finish"); in -p mode the session exits with the turn, so the lanes died and no
  report was written. Code changes are present in the worktree.
Round 2: run every verification step in the FOREGROUND (never background a command; use long timeouts), then write the
report file and give it as your final response. Do not change code unless a check fails; if one does, fix and re-run.

## Round 3 (orchestrator) — gate REQUEST CHANGES; fix both P2s
Gate: collab/1790779949348-reviewer-host-typed-effects-gate.gpt-6-sol.findings.md (copied into this worktree's collab/),
thread 01a0f2cd-a004-7443-ab55-20b50eaba7ab. Orchestrator lanes on your round-2 code: JVM 2420/185018/0, Node
2325/51462/0, CLJD +2287 All tests passed (Effect deftype and identity keys work on Dart).
1. P2 engine.cljc:~1766: the check uses the primitive declaration OR the module declaration. Merge both declared sets
   (union) before checking the kind. Test: one callable published both as a profiled primitive and a module export;
   each declared kind passes, an undeclared kind is refused.
2. P2 vm.cljc:~2139: :callable-effects is built once, so a profiled primitive added later via assoc :primitives escapes
   the check. OWNER DECISION (verbatim option chosen): "Rebuild on change (Recommended)" — "Remember which :primitives
   map the index was built from; when an effect is checked and the map is no longer the same one (identical?), rebuild
   the index first. Registry stays a normal value you may extend; no wrong refusals, no escapes; cost only on effect
   results." Apply the same rule to the module registry's :callable-effects if it can change after registration. Test:
   assoc a new profiled primitive onto a live VM, have it return an undeclared effect -> refused; a declared one -> runs;
   on all four VMs.
Mutation-prove both new tests (revert, see failure, restore). Run EVERY check in the FOREGROUND: kondo on changed files,
cljstyle check (or say blocked), focused JVM, full clj -M:test, bb test:cljs. Not bb test:cljd. Append a "Round 3"
section to the same report file and give the full updated report as your final response (same header).
