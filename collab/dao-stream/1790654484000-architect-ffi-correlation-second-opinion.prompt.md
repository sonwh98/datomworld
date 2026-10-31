Created-GMT: 2026-09-29 04:01:24 GMT
Created-Local: 2026-09-29 11:01:24 +07 (+0700)
Coding-Agent: glm
Session-ID: d56323dc-e84f-4304-a382-8fdddf13ac85
# Task: Architect second opinion — cross-caller FFI correlation, call-out readiness, loss error (Slice 3d)

Role: Lead System Architect

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-29 11:01:24 +07 (+0700) | Status: active | Rationale: OWNER DECISION (verbatim selected option) "Architect review first — Get a second Architect opinion (glm-5.3) on the ruling before touching the VM."; glm-5.3 is the authorized Architect route independent of gpt-6-sol (routing-status.md)

Perform a read-only architecture review. Do not edit files. You are headless; your final response is the deliverable.

Background: remote FFI (a VM's dao.stream.apply calls answered by a remote peer over dao.stream.remote reflections) is
being built in slices. 3a (b34643c0) and 3b (c2417899) are committed; 3c (apply responder + lease holder) is
implemented, uncommitted, in src/cljc/yin/vm/ffi/remote_serve{.cljc,/responder.cljc,/holder.cljc}. Its gate found a
blocking P1, and gpt-6-sol (the slice's Architect) ruled on the fix. The owner wants an independent second opinion
BEFORE authorizing edits to 7 VM files.

Read first:
- docs/design/datom.world.md (axioms, six invariants), docs/design/dao.stream.md
- THE RULING UNDER REVIEW: collab/1790594862000-architect-ffi-serving-slice3-r3.gpt-6-sol.findings.md
- the gate finding it answers: collab/1790611924000-reviewer-ffi-responder-e2e-gate.gpt-6-sol.findings.md
- the governing one-envelope ruling: collab/1790575143000-architect-one-envelope-ruling.gpt-6-sol.findings.md
- 3c implementer report: collab/1790610416000-vm-engineer-ffi-responder-e2e.claude-opus-5-5.report.md
- code: src/cljc/yin/vm.cljc (~2040-2050 call pair construction), src/cljc/yin/vm/engine.cljc (park-continuation,
  ~1343 wait polling, link-specific poll), src/cljc/yin/vm/ffi.cljc (~88-100 call-result), src/cljc/yin/vm/semantic.cljc
  (~462-470 request construction), src/cljc/yin/vm/ast_walker.cljc, src/cljc/yin/vm/debruijn/{stack,register}.cljc
  (FFI call sites), src/cljc/dao/stream/waitset.cljc (~118, ~202), src/cljc/dao/stream/apply.cljc (~24
  correlation-id?), src/cljc/yin/vm/ucf/remote.cljc (~328 retained envelope, :yin.k/call-id),
  src/cljc/yin/vm/ffi/remote_serve/responder.cljc, test/yin/vm/ffi/remote_serve/responder_test.cljc (~278 pre-poll)

The ruling proposes (summary; read it in full):
(1) caller-scoped composite call ids [caller-token local-park-id], the token minted per VM caller tenure by the host
composition and passed as :ffi-caller-id; the composite id is also the parked map key; plus a bounded FFI-specific
response poll in yin.vm.engine that advances each response cursor cell and wakes only the waiter whose :call-id matches
apply/response-id (generic waitset unchanged); (2) a state-threaded caller-composition readiness step that obtains a
:newest call-out cursor before VM construction, installed via a new trusted :call-out-cursor construction option;
(3) on response-stream end/gap, the VM raises {:call-id id :error {:dao.stream.apply/code :dao.stream.apply/ended ...}};
(4) these land as their own slice 3d, separate from committing 3c's server/holder composition.

Evaluate independently:
A. Is the P1 real and is the ruling's diagnosis right (in particular: that id uniqueness alone is insufficient because
   the shared waitset sweep advances the cursor before call-result checks the id)? Verify in code.
B. Is the composite-id + FFI router the right fix? Compare at least: the ruling's scheme; a per-caller call pair (each
   caller tenure gets its own exported call-in/call-out, so no sharing and no router); cursor-at-:newest; and anything
   better. Weigh the six invariants (no hidden global state, no implicit control flow, no callbacks, no shared mutable
   state, no layer collapse, no assumed graphs), the peer-observer principle (yin.vm stays ignorant of remote
   reflections), UCF migration (a migrated VM must still correlate its retained call on another host), CLJ/CLJS/CLJD
   portability, blast radius (7 VM files), and "derive, don't persist".
C. Readiness: agree with a composition readiness step + :call-out-cursor option, or propose otherwise.
D. Loss error: agree with :dao.stream.apply/ended for both end and gap, or propose otherwise.
E. Slicing: agree that 3c commits as server/holder composition first and 3d lands the VM correction?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then: Verdict on the ruling: CONCUR / CONCUR WITH CHANGES / DISSENT. Answer A-E with file:line evidence. If you
dissent or change anything, state the alternative precisely enough to implement, the files it touches, and why it is
better. List any OWNER decision separately.
