Created-GMT: 2026-09-28 16:13:42 GMT
Created-Local: 2026-09-28 23:13:42 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0e7c5-6b35-7e01-9081-541da3b78e92 (resumed, pinned -m gpt-6-sol)
# Task: Architect ruling — Slice 3c: VM call correlation across callers, call-out readiness, loss error
Role: Lead System Architect
Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-28 23:13:42 +07 (+0700) | Status: active | Rationale: same Architect thread that designed Slice 3; the gate routed P1 to the Architect

Read-only; no edits. Slices 3a (b34643c0) and 3b (c2417899) are committed. Slice 3c is implemented but uncommitted:
src/cljc/yin/vm/ffi/remote_serve{.cljc,/responder.cljc,/holder.cljc} and tests (report:
collab/1790610416000-vm-engineer-ffi-responder-e2e.claude-opus-5-5.report.md). Gate
(collab/1790611924000-reviewer-ffi-responder-e2e-gate.gpt-6-sol.findings.md) returned REQUEST CHANGES; it routed these
to you. Rule on each precisely enough to implement without a further design round:

1. P1 (blocking): yin.vm.cljc ~2045 / ffi.cljc ~94 — a fresh VM reads call-out from :oldest, mints call ids from zero,
   and accepts a response by id alone; a second VM on the same exported pair can accept the first VM's result. The
   endpoint does not enforce one-caller-per-pair. Choose: (a) a correlation scheme distinguishing calls across callers
   (e.g. a caller-unique id component — where minted, by whom, and how it stays within the one-envelope ruling:
   apply/correlation-id? is some?, rpc owns its own allocation, the VM's request map must travel unchanged), or (b) an
   enforced exclusive caller tenure in the composition (how enforced, what a second caller observes), or another
   option. Weigh against the one-envelope ruling, UCF migration (a migrated VM resumes on another host and must still
   correlate its retained call), datom.world invariants, and "derive, don't persist". Name the files the fix touches;
   if it touches yin.vm / yin.vm.semantic / yin.vm.ffi / debruijn / ast_walker, say so explicitly (that needs owner
   authorization) and give the minimal shape.
2. Readiness (gate P2): VM construction needs a synchronous call-out cursor, but a reflection's first cursor answer
   can be retry; only the test helper pre-polls. Choose: a production readiness step in the composition that obtains
   and files the cursor before VM construction, or VM construction handling retry. Prefer the option that keeps
   yin.vm ignorant of remote reflections if sound.
3. Q3: after a call-in gap the parked VM raises the generic "FFI response envelope is malformed". The gate asks for a
   specific portable loss result in the same VM error-path change. Specify the error word/shape (apply-qualified,
   consistent with the ruled terminal words) and who emits it.
4. Ordering: can the holder-gap P2 (holder.cljc only, being fixed now) and these land as one 3c commit, or should the
   VM-side change be its own slice?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then answer 1-4 with file:line evidence, and list anything that is an OWNER decision rather than architecture.
