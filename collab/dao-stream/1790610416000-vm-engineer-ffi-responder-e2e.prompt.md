Created-GMT: 2026-09-28 15:46:56 GMT
Created-Local: 2026-09-28 22:46:56 +07 (+0700)
Coding-Agent: claude
Session-ID: 715a2230-3f6e-48b5-9260-4e2225debf68
# Task: Slice 3c — FFI apply responder, production lease holder + grant delivery, real-VM end-to-end remote FFI

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-28 22:46:56 +07 (+0700) | Status: active | Rationale: owner-approved Slice 3 implementer; new session on the committed 3b base (c2417899)

Implement Slice 3c in /Users/sto/workspace/datomworld (master c2417899; 3a b34643c0 and 3b c2417899 landed). This is
IMPLEMENTATION: edits authorized in the named files only. Do not stage or commit. OWNER INSTRUCTION (verbatim):
"dispatch 3c". This slice completes "remote FFI" per the governing ruling.

Read first:
- docs/design/datom.world.md (invariants)
- governing one-envelope ruling: collab/1790575143000-architect-one-envelope-ruling.gpt-6-sol.findings.md,
  section "Serving and end-to-end acceptance" (the acceptance definition of remote FFI)
- collab/1790594862000-architect-ffi-serving-slice3.gpt-6-sol.findings.md sections 1-4 and "Acceptance tests" 1-10
  (its "1. Mirror answer retention" sub-slice and P1 row are RETRACTED) and round 2
  collab/1790594862000-architect-ffi-serving-slice3-r2.gpt-6-sol.findings.md (authoritative where they differ)
- src/cljc/yin/vm/ffi/remote_serve.cljc (3a+3b, committed) and its test; gate histories
  collab/1790596235000-reviewer-ffi-export-binding-gate*.findings.md, collab/1790608971000-reviewer-ffi-lease-wiring-gate*.findings.md
- src/cljc/dao/stream/apply.cljc (serve-once!, server-state, success/error responses), src/cljc/yin/vm/ffi.cljc
  (call-result, dispatch-request), src/cljc/yin/vm/semantic.cljc (~467 request construction), src/cljc/yin/vm/ucf/remote.cljc
  (lift-frame/lower-frame, retained :yin.k/request-envelope), src/cljc/dao/stream/remote.cljc (attach!, reflections),
  src/cljc/dao/lease.cljc (make-holder, observe-grant, due-to-renew?, stop), docs/design/dao.stream.remote.md section 6
  (lease-grants / lease-proposals entries)
- test/yin/vm/ucf/remote_test.cljc, test/yin/vm/ffi_test.cljc, test/dao/lease_composition_test.cljc (fixtures)
- docs/agents/build-n-test.md

Scope (design section 4 + the 3b deferrals):
A. Responder: at the possessing peer, apply/serve-once! reads the VM's call-in request with a cursor minted from
   call-in, dispatches one handler, and appends apply success/error with the request id to call-out, retaining the
   computed response and successor across full (caller-owned server-state). Export call-in as a remote WRITER and
   call-out as a remote READER through the 3a/3b binding. The request travels as the WHOLE ORIGINAL MAP (extra keys
   preserved); never rebuild from op/args; never reissue the VM call through rpc/request! (it mints a new id).
   Handler authority is the injected gate from 3a (owner policy); no default that admits everything silently.
B. Production lease holder + grant delivery (3b deferral): serve lease grants as a lease-grants entry per remote
   spec S6 so a real remote holder (dao.lease/make-holder) observes its grant and renews/releases over reflections,
   replacing the test-only inbox copy.
C. The VM side resumes through its ordinary response wait and yin.vm.ffi/call-result (checks the id; value incl. nil;
   portable apply error raised).
Place new code in remote_serve.cljc or new namespaces under src/cljc/yin/vm/ffi/ (e.g. remote_serve/responder.cljc)
as fits; justify the choice.

Acceptance (design "Acceptance tests", each must fail if broken; prove the load-bearing ones by temporary mutation,
then revert and grep):
1. Real VM end to end: run an AST :dao.stream.apply/call with NO local bridge; the call-in value is carried unchanged
   through remote append; answer success AND error through the production composition; the VM halts with the value /
   raises the error via call-result. Include an extra request key and assert identical id and map in transit.
2. Pending request append: force full, retain the exact request and id, retry when capacity returns; exactly one
   accepted append and one handler invocation.
3. Response full: force call-out full after handler evaluation; repeat steps; retained response and successor, no
   second handler invocation, then one response append.
4. (Retracted mirror-retention case: instead assert the spec'd behaviour — a remote answer refused full leaves the
   remote append! outcome unknown, surfaced via append-unknown on close/loss, while the VM still resumes from its own
   apply response.)
5. Gap: gap the call-in request cursor -> the binding reports loss rather than leaving a parked VM forever; gap a pair
   channel -> its link ends.
6. Detach and rebind: end the channel, observe detached, served entry and lease stay live, reattach!, and a subsequent
   call completes under the same served identity.
7. Terminal not-found: reclaim the lease; removal precedes acknowledgement; a fresh op answers not-found and cannot
   rebind that identity.
8. Retained UCF call: force the VM's call-in append full; lift and lower the parked :ffi-request; envelope, extra key,
   response cell and cursor survive verbatim; retry over reflections and resume the VM.
9. serve! idempotence across both lift-frame call sites (already pinned in 3a — keep it passing).
10. Refusal: unservable endpoint or unportable cursor -> :yin.k/unsatisfied, no published lift, no provisional entry.
11. Holder: a real make-holder on the remote peer receives its grant via the lease-grants entry, renews over a
    reflection, and its release reclaims (reclaim path = 3b's).

Allowed files: src/cljc/yin/vm/ffi/remote_serve.cljc and test; new files under src/cljc/yin/vm/ffi/ and test/yin/vm/ffi/.
Any other file (yin.vm.ffi, yin.vm.semantic, dao.stream.*, dao.lease, ucf.remote) needs authorization: STOP and report
the exact change needed instead of editing it. If the slice proves too large for one round, deliver A+C with
acceptance 1-3, 5-7 first, report what remains, and say so plainly.

Portable CLJC (CLJ/CLJS/CLJD): on CLJD #?(:clj ...) is NOT excluded — use #?(:cljd nil :clj ...) with :cljd first; no
cross-ns #'private access (make helpers public).

Verify and report exactly: clj -M:kondo --lint <changed files>; cljstyle check (say if blocked); focused JVM over
yin.vm.ffi.remote-serve-test, any new test ns, yin.vm.ffi-test, yin.vm.ucf.remote-test, dao.stream.apply-test (if it
exists), dao.stream.remote-test, dao.lease-composition-test; bb test:cljs. Do NOT run bb test:cljd. Known flake:
yin.repl.main-test cross-process tests; report, don't fix.

Write the report to collab/1790610416000-vm-engineer-ffi-responder-e2e.claude-opus-5-5.report.md and give it as your final
response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 715a2230-3f6e-48b5-9260-4e2225debf68
Report changed files, exact test outcomes, which acceptance items are covered by which tests, design choices where
the design left latitude, any authorization you need, unresolved concerns, and incomplete work.
