Created-GMT: 2026-09-29 04:14:08 GMT
Created-Local: 2026-09-29 11:14:08 +07 (+0700)
Coding-Agent: claude
Session-ID: 08d36f7d-b33d-404e-adcb-dc04d5b907ef
# Task: Slice 3d — caller-scoped FFI correlation, FFI response router, caller call-out readiness, portable loss error

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-29 11:14:08 +07 (+0700) | Status: active | Rationale: owner-authorized VM correction; new session on the committed 3c base (c9313ee0)

Implement Slice 3d in /Users/sto/workspace/datomworld (master c9313ee0; 3a b34643c0, 3b c2417899, 3c c9313ee0 landed).
IMPLEMENTATION: edits authorized in the named files only. Do not stage or commit. This slice completes remote FFI.

OWNER DECISIONS (verbatim selected options):
- "Authorize all 8 (Recommended) — Dispatch 3d implementing gpt-6-sol's ruling with glm-5.3's five changes folded in;
  gated by gpt-6-sol with glm-5.3's findings in the gate brief."
- Supplied-pair policy: "Refuse (Recommended) — glm-5.3's recommendation: extend the half-a-pair check so a supplied
  pair without a composition-minted cursor is refused at construction; avoids scanning a shared ring from :oldest and
  spurious gap-loss."

Governing design (read in full; where they differ, glm-5.3's CHANGES amend gpt-6-sol's ruling):
- collab/1790594862000-architect-ffi-serving-slice3-r3.gpt-6-sol.findings.md (the ruling: sections 1-4)
- collab/1790654484000-architect-ffi-correlation-second-opinion.glm-5.3.findings.md (CONCUR WITH CHANGES; its
  "Changes to the ruling" 1-5 are binding)
- the gate finding being fixed: collab/1790611924000-reviewer-ffi-responder-e2e-gate.gpt-6-sol.findings.md (P1, P2
  readiness, Q3)
- collab/1790575143000-architect-one-envelope-ruling.gpt-6-sol.findings.md (the request map travels unchanged;
  apply/correlation-id? stays some?; do not tighten apply's predicates; rpc's allocator untouched)
- docs/design/datom.world.md invariants; docs/design/dao.stream.md (readiness belongs to the driver)
- 3c code: src/cljc/yin/vm/ffi/remote_serve{.cljc,/responder.cljc,/holder.cljc}, its tests; implementer report
  collab/1790610416000-vm-engineer-ffi-responder-e2e.claude-opus-5-5.report.md (Unresolved 1-3 are what you fix)
- docs/agents/build-n-test.md

Scope (summary; the rulings are authoritative):
1. Composite call ids [caller-token local-park-id]: the caller's host composition mints a portable unique token per VM
   caller tenure and passes it as :ffi-caller-id at construction; the composite id is ALSO the parked-map key (one id,
   no second mapping); engine/park-continuation takes an optional explicit id; all four FFI call sites (semantic,
   ast_walker, debruijn/stack, debruijn/register) supply it. Token must satisfy vm/plain-data? and the channel codec.
   Local/legacy behaviour without :ffi-caller-id must keep working (existing tests unchanged).
2. FFI response router in yin.vm.engine beside the link poll (glm change 4): bounded per cursor cell INCLUDING carried
   cells of lowered retained calls; advance the cell on every ok; wake only the live FFI waiter whose :call-id equals
   apply/response-id; keep others parked; discard or diagnose responses for no live waiter; terminal read wakes the
   affected waiters as loss; match both entry shapes (walker nests :call-id in :k); exclude FFI response entries from
   the generic waitset sweep as link entries are; dao.stream.waitset itself unchanged. Envelope interpretation may live
   in yin.vm.ffi with check-wait-set dispatching into it. ffi/call-result stays the final validation.
3. debruijn_register_effects validators (~412, ~432) accept the composite id shape (glm change 1) + their tests.
4. Readiness: a bounded, state-threaded caller-composition step (new ns under src/cljc/yin/vm/ffi/, e.g.
   remote_serve/caller.cljc) that attaches both reflections, obtains a :newest call-out cursor, yields on retry, and
   returns the minted cursor; new trusted :call-out-cursor construction option installed by yin.vm/empty-state; local
   pairs keep their current mint; a SUPPLIED pair without :call-out-cursor is REFUSED at construction (owner decision;
   extend the half-a-pair check ~vm.cljc:2012). Replace the test-only pre-poll with the production step.
5. Loss error: on FFI response-stream end, raise ex-info {:call-id id :error {:dao.stream.apply/code
   :dao.stream.apply/ended :dao.stream.apply/message "..."}}; same for gap with a gap-specific message AND a
   machine-distinguishing extra key in the error map (glm change 3; the error map is open). Replaces the generic
   "FFI response envelope is malformed" for these cases.

Allowed files: src/cljc/yin/vm.cljc, src/cljc/yin/vm/engine.cljc, src/cljc/yin/vm/ffi.cljc,
src/cljc/yin/vm/semantic.cljc, src/cljc/yin/vm/ast_walker.cljc, src/cljc/yin/vm/debruijn/stack.cljc,
src/cljc/yin/vm/debruijn/register.cljc, src/cljc/yin/vm/debruijn_register_effects.cljc; their existing tests;
src/cljc/yin/vm/ffi/remote_serve/** and test/yin/vm/ffi/remote_serve/** (+ remote_serve_test.cljc);
test/yin/vm/ucf/remote_test.cljc (acceptance checks only; ucf/remote.cljc itself must not change). Anything else
(dao.stream.*, dao.lease, yin.vm.ucf.remote source, yang.*): STOP and report.

Acceptance (each must fail if broken; prove the load-bearing ones by temporary mutation, revert, grep):
a. Two VMs on ONE exported pair, responses arriving in the OPPOSITE order to the requests: each VM receives its own
   result (this is the regression test for the gate P1).
b. A VM whose unrelated response arrives first stays parked and is woken later by its own; a response with no live
   waiter is discarded/diagnosed, never delivered.
c. Readiness: the production step yields on retry and returns a real cursor; VM built with it completes a remote call;
   a supplied pair without :call-out-cursor is refused at construction.
d. Loss: response-stream end -> portable :dao.stream.apply/ended error with the call id; gap -> same code, distinct
   machine key; neither raises "malformed envelope".
e. Migrated retained call: lift/lower a parked FFI call carrying a composite id across UCF, resume on the other host,
   correlate correctly (carried cursor cell is routed).
f. All four VM kinds (semantic, ast_walker, debruijn stack, debruijn register) park and resume with composite ids;
   register effects validators accept them; a CLJD-relevant check that a vector parked key round-trips.
g. Every existing yin.vm / ffi / ucf / remote_serve test still passes unchanged except where a test asserted the old
   id shape or the old generic error (update those minimally and list each).

Portable CLJC (CLJ/CLJS/CLJD): on CLJD #?(:clj ...) is NOT excluded — use #?(:cljd nil :clj ...) with :cljd first;
no cross-ns #'private access.

Verify and report exactly: clj -M:kondo --lint <changed files>; cljstyle check (say if blocked); focused JVM over
yin.vm-test, yin.vm.engine-test (if present), yin.vm.ffi-test, yin.vm.semantic-test, yin.vm.semantic-ffi-test,
yin.vm.parity-test, yin.vm.debruijn-register-effects-test, yin.vm.ucf.remote-test, yin.vm.ffi.remote-serve-test,
yin.vm.ffi.remote-serve.responder-test, any new test ns, dao.stream.apply-test; then the FULL clj -M:test and
bb test:cljs (VM-wide change). Do NOT run bb test:cljd. Known flake: yin.repl.main-test cross-process; report, don't fix.

Write the report to collab/1790655248000-vm-engineer-ffi-caller-correlation.claude-opus-5-5.report.md and give it as your
final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 08d36f7d-b33d-404e-adcb-dc04d5b907ef
Report changed files, exact test outcomes, acceptance a-g -> tests, every existing test you modified and why, design
choices where the rulings left latitude, unresolved concerns, and incomplete work.
