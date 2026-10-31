Created-GMT: 2026-09-28 11:27:42 GMT
Created-Local: 2026-09-28 18:27:42 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0e7c5-6b35-7e01-9081-541da3b78e92 (captured from thread.started; was pending)

# Task: Architect design — Slice 3 production FFI serving composition (remote FFI end-to-end)

Role: Lead System Architect

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-28 18:27:42 +07 (+0700) | Status: active | Rationale: Architect route per routing-status.md (fable reserved by owner); author of the governing ruling, so best placed to make it implementable

Perform a read-only architecture design of Slice 3 of the one-envelope ruling. Do not edit files.

Read first:
- docs/design/datom.world.md
- collab/1790575143000-architect-one-envelope-ruling.gpt-6-sol.findings.md  (governing ruling; esp. "Serving and end-to-end acceptance" and "Migration order" step 3)
- docs/orchestrator-log.md (tail: slices 1 and 2 landed as afa01710 and 84bfb74d)
- src/cljc/dao/stream/apply.cljc, remote.cljc, remote_pair.cljc, rpc.cljc
- src/cljc/yin/vm/ffi.cljc, src/cljc/yin/vm/ucf/remote.cljc, src/cljc/yin/repl/serve.cljc
- dao.lease (find its namespace; per docs, fetch stays clock-free and the drive owns liveness via dao.lease)
- test/dao/stream/remote_test.cljc and the ucf/ffi tests

Design question (the ruling states requirements; you must now make them implementable without a further design round):
1. Placement: which namespace owns the production composition (new ns vs existing), its public API, and its CLJ/CLJS/CLJD portability.
2. Registry: per-handle serve! registry shape, key (handle identity), what a table entry holds (identity, channel, cursors, reflection keys, lease), idempotence within one export binding, and how retirement/lease reclaim ends the binding explicitly. Where does mutable state live under the no-hidden-global-state / no-shared-mutable-state invariants?
3. Lease lifecycle: use of dao.lease, who drives renewal/expiry (no clock in the core), what happens to in-flight retained calls on reclaim.
4. Responder: how an rpc-driven responder reads the UNCHANGED apply request from the call-out surface and appends a valid apply success/error, and how the VM resumes via yin.vm.ffi/call-result.
5. Acceptance tests: enumerate concrete tests (end-to-end VM case, pending append retries, response full, gap, detach+rebind, terminal not-found, UCF lift/lower of a retained call, serve! idempotence across lift-frame's two call sites, refusal returning nil before lift).
6. Slicing: split into the smallest independently committable sub-slices if it is too large for one implementer round; state order and per-slice acceptance.
7. Must-not-change list and open risks. Flag anything that needs an OWNER decision rather than an architecture call.

Evaluate foundational invariants, ownership boundaries, explicit state and control flow, concurrency and linearization, dynamic extension, host isolation, CLJ/CLJS/CLJD portability, migration risk, completion criteria, and design contradictions. Distinguish architectural defects from implementation gaps or intentionally deferred work. There is no backward-compat requirement (dev-only repo).

Your final response is the deliverable (you are headless); make it complete enough to hand to an implementer.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: verdict, the design (items 1-4), acceptance tests (5), slicing (6), and severity | file:line | invariant/evidence | recommended correction for any defects found (7).
