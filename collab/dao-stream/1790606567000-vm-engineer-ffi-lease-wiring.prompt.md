Created-GMT: 2026-09-28 14:42:47 GMT
Created-Local: 2026-09-28 21:42:47 +07 (+0700)
Coding-Agent: claude
Session-ID: 7d111381-2e90-40d2-9519-0a15d8ccca19
# Task: Slice 3b — lease wiring for the FFI export binding (yin.vm.ffi.remote-serve)

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-28 21:42:47 +07 (+0700) | Status: active | Rationale: owner-approved Slice 3 implementer; new session (3a's session context is large; 3a is committed, so this starts from the committed code)

Implement Slice 3b in /Users/sto/workspace/datomworld (master at ada3f200; 3a landed as b34643c0). This is
IMPLEMENTATION: edits authorized in the named files only. Do not stage or commit. A separate worktree
(datomworld-repl-index) is in flight; ignore it.

Read first:
- docs/design/datom.world.md (invariants)
- collab/1790594862000-architect-ffi-serving-slice3.gpt-6-sol.findings.md section 3 "Lease lifecycle" (governs this
  slice) and sections 1-2 for context; its sub-slice "1. Mirror answer retention" and P1 row are RETRACTED
- collab/1790594862000-architect-ffi-serving-slice3-r2.gpt-6-sol.findings.md (authoritative round 2: order is export
  binding -> LEASE WIRING -> responder; policy injected as required options; lease params need not be decided
  architecturally, only validated at assembly)
- docs/design/dao.stream.remote.md section 6 "Lease integration" (~line 507) and the reclaim -> not-found rules
- docs/design/dao.lease.md, src/cljc/dao/lease.cljc (make-judge ~2114, make-holder ~2215, judge-step, stop,
  holding?, due-to-renew?), test/dao/lease_composition_test.cljc (the composition pattern to follow)
- src/cljc/yin/vm/ffi/remote_serve.cljc + its test (3a, committed): open!, serve!, step, retire!, close!, and its
  gate history collab/1790596235000-reviewer-ffi-export-binding-gate*.findings.md
- docs/agents/build-n-test.md

Scope (3b): integrate dao.lease into the export binding, per design section 3:
- The possessing peer grants and judges: wire dao.lease/make-judge with explicit tick and fact media, attribution
  resolver, writer, cadence, tolerance, and an idempotent reclaim procedure whose subject resolves to this binding's
  served identity. Reclaim calls the SAME retire! transition 3a built (removal from the published table precedes
  acknowledging reclaim; later remote ops answer not-found; the identity is never reused).
- Lease parameters and media are REQUIRED options validated in open! (refuse incomplete assembly before publishing,
  like 3a's other options); no defaults that invent policy. The host drive supplies tick DATA and calls the lease
  step at the declared cadence: no clock is read anywhere in the core.
- A detached channel alone does NOT retire the entry or lease; reattachment within the live tenure keeps the same
  identity and source cursors.
- On reclaim, an in-flight request has no delivery guarantee: an unacknowledged append reports the existing
  append-unknown / terminal-loss outcome, never a silent retry against a new tenure. Reclaim stays pending until its
  procedure reports success, as dao.lease requires.
- Holder side: demonstrate the remote holder with dao.lease/make-holder appending renewals and release on its
  attributed medium, in tests at least (a production holder composition may be test-level if the design places it
  on the remote peer; say which you chose and why).
NOT in scope: the apply responder and the real-VM end-to-end / UCF retained round trip (3c).

Allowed files: src/cljc/yin/vm/ffi/remote_serve.cljc, test/yin/vm/ffi/remote_serve_test.cljc; a new
src/cljc/yin/vm/ffi/remote_serve/lease.cljc (+ test) if you factor the lease wiring out. Any other file (dao.lease,
dao.stream.*, ucf.remote) needs authorization: stop and report instead of editing it.

Acceptance tests (each must fail if the behaviour breaks; prove at least the reclaim ones by a temporary mutation,
then revert):
1. Live renewal keeps the export served across many lease ticks.
2. Expiry (no renewal past the bound + tolerance) reclaims: the identity leaves the table before reclaim is
   acknowledged, a fresh remote op answers not-found, and re-serving the handle mints a new identity.
3. Release by the holder reclaims the same way.
4. Detach without expiry keeps the entry and lease; reattach completes an op under the same identity.
5. In-flight reclaim: an unacknowledged remote append at reclaim time surfaces as append-unknown, not retried.
6. open! refuses each missing/malformed lease option before publishing anything.
7. Reclaim is idempotent (a duplicate reclaim or a reclaim after close! is harmless).

Portable CLJC (CLJ/CLJS/CLJD): on CLJD #?(:clj ...) is NOT excluded — use #?(:cljd nil :clj ...) with :cljd first;
no cross-ns #'private access (make helpers public).

Verify and report exactly: clj -M:kondo --lint <changed files>; cljstyle check <changed files> (say if the permission
gate blocks it); clj -M:test -n yin.vm.ffi.remote-serve-test -n dao.lease-test -n dao.lease-composition-test -n
dao.stream.remote-test -n yin.vm.ucf.remote-test (plus any new test ns); bb test:cljs. Do NOT run bb test:cljd (the
orchestrator owns that lane). Known flake: yin.repl.main-test cross-process tests; report, don't fix.

Write the report to collab/1790606567000-vm-engineer-ffi-lease-wiring.claude-opus-5-5.report.md and give it as your final
response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 7d111381-2e90-40d2-9519-0a15d8ccca19
Report changed files, exact test outcomes, design choices where the design left latitude, unresolved concerns, and
incomplete work. Do not claim edits or tests that did not occur.
