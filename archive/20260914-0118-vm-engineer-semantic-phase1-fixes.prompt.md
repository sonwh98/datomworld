Created-GMT: 2026-09-13 18:18:00 GMT
Created-Local: 2026-09-14 01:18:00 +07:00

# Task: Phase 1 Fixes (Core VM Interpreter)

Role: VM Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-14 01:18:00 +07:00 | Status: active

The Lead System Architect (gpt-6-astra) reviewed your implementation of `src/cljc/yin/vm/semantic.cljc` and issued a "Verdict: request changes" due to two P1 architecture defects:

1. [P1] Stable segment identity: Loading silently replaces an existing segment image with the same ID, causing existing parked continuations to resume into the wrong code. 
   - Correction: Reject conflicting reuse of a segment id (throw if code is different); allow identical reloads. Add a collision regression test.
2. [P1] Pure-data continuations; no callbacks: Passing `:restore-fn` into effect handling causes the shared engine to attach a host stream handle and a live `:resume` closure to blocked wait entries via `runtime-adapter/vm-task`. As a result, a blocked entry cannot survive an EDN round-trip.
   - Correction: Keep wait/ready entries as pure data containing registers and resource ids. Do not pass `:restore-fn` closures to the wait set. Resolve handles during polling and dispatch restoration explicitly. Test serialization of blocked entries (EDN round-trip).

Modify `src/cljc/yin/vm/semantic.cljc` and `test/yin/vm/semantic_test.cljc` to fix these 2 issues.
