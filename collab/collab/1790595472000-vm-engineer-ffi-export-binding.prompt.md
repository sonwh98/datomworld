Created-GMT: 2026-09-28 11:37:52 GMT
Created-Local: 2026-09-28 18:37:52 +07 (+0700)
Coding-Agent: claude
Session-ID: 2fd38cae-ba0d-4d01-9d12-b9ee9af137e2
# Task: Slice 3a — FFI export binding (yin.vm.ffi.remote-serve): stable per-handle serve!, retirement, refusal cleanup

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-28 18:37:52 +07 (+0700) | Status: active | Rationale: owner-approved default implementer for Slice 3; claude CLI route worked for slice 1 (GLM subagents hit concurrency limits)

Implement the first sub-slice of Slice 3 in /Users/sto/workspace/datomworld. This is IMPLEMENTATION — edits authorized
in the named files only. Do not stage or commit.

Read first:
- docs/design/datom.world.md (invariants: no hidden global state, no shared mutable state, no implicit control flow)
- collab/1790594862000-architect-ffi-serving-slice3.gpt-6-sol.findings.md — the design. Sections 1 (placement/API) and
  2 (registry) govern this sub-slice. IGNORE its sub-slice "1. Mirror answer retention" and the P1 defect row: both
  were RETRACTED in round 2.
- collab/1790594862000-architect-ffi-serving-slice3-r2.gpt-6-sol.findings.md — round-2 ruling (authoritative where it
  differs from round 1): no mirror retention, no remote spec change; order is export binding -> lease wiring ->
  responder + VM/UCF acceptance; policy is injected as required options.
- docs/design/dao.stream.remote.md sections 2, 2.3 (table), 2.5 (loss/resend), 6 (lease) — do NOT change remote semantics
- src/cljc/dao/stream/remote.cljc (table shape, mirror-step, attach, close/loss -> append-unknown)
- src/cljc/yin/vm/ucf/remote.cljc (the serve! callback contract at ~line 37; lift-marker; lift-frame calls serve!
  from mint-cell AND from lift-one for the same handle)
- src/cljc/yin/vm/ffi.cljc, src/cljc/dao/stream/apply.cljc (context only; not edited in this sub-slice)
- test/yin/vm/ucf/remote_test.cljc, test/dao/stream/remote_test.cljc (fixtures to reuse)
- docs/agents/build-n-test.md (commands)

Scope of THIS sub-slice (3a): the export binding only. NOT in scope: dao.lease wiring (3b), the apply responder and
real-VM end-to-end (3c). Design the binding so 3b's reclaim calls the same retire! transition.

Allowed files (create): src/cljc/yin/vm/ffi/remote_serve.cljc, test/yin/vm/ffi/remote_serve_test.cljc.
Any other file needs authorization: stop and report instead of editing it.

Acceptance criteria:
1. Public API per design section 1: open! (validates required options, refuses incomplete assembly BEFORE publishing
   any descriptor), serve! (the UCF callback: handle -> {:dao.stream/identity :dao.stream/channel} | nil),
   step (one bounded drive pass over the binding's mirror traffic, returning new state/outcomes), retire!, close!.
   Policy is injected, not decided: channel end/descriptor, per-handle surface policy, capacities, and a handler
   authority gate are options (the gate may be a required predicate; no default that silently admits everything
   unless the option says so explicitly).
2. Registry: keyed by live handle reference identity (not descriptor equality); entry records handle, minted served
   identity, channel descriptor, declared surface, retirement status. The mirror is handed a plain table snapshot
   {served-identity {:handle h :surface S}} (remote.cljc table shape). State is owned by the binding value/one drive
   owner — no namespace-level atom, no process-wide registry.
3. serve! idempotence: repeated calls for the same live handle within one binding return the identical identity and
   channel and yield exactly one table entry. Test by running ucf.remote/lift-frame over a frame where the same handle
   is reached from both mint-cell and lift-one, asserting identical markers and one entry.
4. Retirement: retire! is idempotent; it removes the identity from the published table before releasing anything;
   afterwards a remote op on that identity through the mirror answers not-found, and the handle re-served later gets
   a NEW identity (never reuse the old one). close! retires every entry. Unresolved remote appends on the binding end
   through the existing close/loss path (append-unknown), not silently.
5. Refusal: serve! returns nil for a handle the binding cannot serve (surface policy/gate refuses, binding closed);
   lift-frame then refuses :yin.k/unsatisfied with no published lift and no lingering provisional entry (retire any
   entries provisionally made during a whole-frame lift that refuses).
6. Portable CLJC (CLJ/CLJS/CLJD). Remember: on CLJD, #?(:clj ...) is NOT excluded (use #?(:cljd nil :clj ...) with
   :cljd first) and cross-ns #'private-var access fails at runtime — make helpers public instead.

Verification you must run and report exactly (commands + counts):
- clj -M:kondo --lint src/cljc/yin/vm/ffi/remote_serve.cljc test/yin/vm/ffi/remote_serve_test.cljc
- focused JVM run of the new test ns plus yin.vm.ucf.remote-test and dao.stream.remote-test
- cljstyle check on the two new files
The orchestrator will run the full JVM, Node, and CLJD lanes itself; you may run them, but do not start bb test:cljd
if another process could be using it.

Work only in named files. If a required dependency demands expansion, stop and request authorization before editing it.
Preserve unrelated changes, do not weaken tests, and preserve CESK and execution-parity invariants. Inspect the diff.

Write your report to collab/1790595472000-vm-engineer-ffi-export-binding.claude-opus-5-5.report.md and also give it as your
final response. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 2fd38cae-ba0d-4d01-9d12-b9ee9af137e2

Report changed files, exact test/check outcomes, design choices you made where the design left latitude, unresolved
concerns, and any incomplete work. Do not claim edits or tests that did not occur.
