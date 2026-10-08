Created-GMT: 2026-09-05 10:15:00 GMT
Created-Local: 2026-09-05 17:15:00 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: f4e3fed9-73ca-4ea5-a485-a45263d8eff0

# Task: Review the stream-observer separation plan

Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-05 17:15:00 Asia/Ho_Chi_Minh | Status: active | Rationale: Architect primary in team.md; independent Claude review of a GPT-authored plan.

Perform a complete read-only architecture review of the proposed plan below in /Users/sto/workspace/datomworld. The user explicitly requested the Architect role review through the Orchestrator. Review the plan, not an implemented refactor. The working tree contains only the preceding stream-driver-to-stream-observer rename, plus two unrelated untracked blog files which are outside scope. No implementation, staging, commits, further delegation, or unrelated inspection is authorized. Read-only git diff/status and searches in the listed scope are authorized. Do not run tests: this is a static plan review. Return the full review now without waiting for another approval.

Read first:
- docs/agents/roles/architect.md
- docs/agents/team.md
- docs/design/datom.world.md
- docs/agents/malleability.md
- docs/design/dao.stream.md
- src/cljc/yin/vm.cljc
- src/cljc/yin/vm/stream_observer.cljc
- src/cljc/yin/vm/engine.cljc
- src/cljc/yin/vm/ast_walker.cljc
- src/cljc/yin/vm/ffi.cljc
- src/cljc/yin/vm/runtime_adapter.cljc
- src/cljc/yin/repl/core.cljc
- test/yin/vm/stream_observer_test.cljc
- test/yin/vm/engine_test.cljc
- test/yin/vm/ast_walker_test.cljc
- test/yin/vm/ffi_test.cljc
- test/yin/vm/parity_test.cljc
- test/yin/vm/test_utils.cljc
- test/yin/repl_core_test.cljc
- docs/design/yin.vm.implementation-plan.md
- docs/design/yin.vm.divergence-register.md

Optional read scope, only if needed: src/cljc/dao/stream.cljc and src/cljc/dao/stream/ringbuffer.cljc; src/cljc/yin/vm/{semantic,stack,register}.cljc for comparison with existing loader mechanisms; docs/agents/build-n-test.md, deps.edn, bb.edn, shadow-cljs.edn for test feasibility. Do not inspect private configuration, credentials, unrelated collab artifacts, or unrelated repository content.

User constraints:
- Observation and execution are separate responsibilities. The existing stream-observer is the starting point, not a reason to invent a parallel mechanism.
- Reuse existing loading, readiness and execution mechanisms. The user rejected gratuitous accept-datoms and ready-for-program? APIs.
- The user explicitly selected SEPARATION ONLY: keep direct eval, current gap handling, and REPL reset behavior. Earlier stream-only/fault-on-gap/reset-on-same-stream proposals are superseded.
- Fan-out and porting other evaluators are deferred. The design should permit later reuse without implementing those features now.

Local evidence: the preceding mechanical rename passed `clj -M:test -n yin.vm.stream-observer-test -n yin.vm.engine-test -n yin.vm.ast-walker-test`: 40 tests, 138 assertions, zero failures/errors. Focused lint had zero warnings/errors. These results cover the rename only, not this unimplemented proposal. The review should spend its effort on static analysis and concrete failure traces, not rerunning that suite.

Evaluate foundational invariants, ownership boundaries, explicit state and control flow, concurrency and linearization, host isolation, CLJ/CLJS/CLJD portability, compatibility, bounded interface size, and whether the plan is decision-complete. Distinguish architectural defects, behavior changes accidentally introduced by extraction, implementation gaps, and intentionally deferred work. Pay particular attention to the semantics of run/step, FFI bridge orchestration, readiness and suspension, cursor advancement versus exceptions, and observer/VM state persistence in the REPL. Verify findings against actual code. Do not manufacture findings to satisfy a quota.

Begin the final report with actual completion timestamps and these exact fields:
Completed-GMT: <actual timestamp>
Completed-Local: <actual timestamp and Asia/Ho_Chi_Minh>
Coding-Agent: claude
Session-ID: f4e3fed9-73ca-4ea5-a485-a45263d8eff0

Then provide:
1. Verdict: approve, approve with nonblocking notes, or changes required.
2. Findings in severity | file:line or plan clause | invariant/evidence | recommended correction form, including concrete behavior traces where applicable.
3. Properties that passed review.
4. Minimum changes needed to make the plan implementation-ready, separating blockers from optional extensions.

# Proposed plan (exact technical content presented to the user)

## Separate stream observation from VM execution

### Summary

Move program observation state out of ASTWalkerVM and let composition coordinate the observer and VM. Reuse ready-for-ingress?, vm-load-program, step, run, and eval.

This is an ownership refactor: preserve program-batch format, direct evaluation, gap handling, REPL reset behavior, and execution semantics. Build on the completed stream-driver to stream-observer rename.

### State and existing operations

- Store :in-stream, :in-cursor, and :ingress-gaps in a separate observer map. Composition holds {:observer observer :vm vm}; neither state value contains the other.
- Add a small observer constructor taking an existing reader handle. Mint its cursor at :dao.stream/oldest, as today. Creation and descriptor attachment remain host responsibilities.
- Remove those three fields from the walker record, its positional constructor calls, and VM construction. Reject the obsolete :in-stream VM option with a migration message before allocating resources.
- Change stream-observer/ingest-next-program to accept observer state alone and return {:status ... :state updated-observer}, plus :value for a successfully observed batch. It performs no VM loading or mutation.
- Preserve read-outcome behavior: successful reads advance the cursor; empty/end outcomes return promptly; gaps advance to recovery and increment the gap-event counter; terminal read errors throw with their original outcome.
- Move the existing ready-for-ingress? implementation into the VM engine, replacing its current alias. Preserve its semantics. Expose the walker's existing vm-load-program for composition.
- Introduce no accept-datoms, ready-for-program?, new VM protocol, or operation registry.

### Execution and composition

- Adapt existing step-on-stream into the single-VM composition helper: step-on-stream [session ready-fn load-fn step-fn] -> updated-session. It checks VM readiness before reading, loads successful observations through the supplied loader, and executes one existing VM step. A gap consumes the composition step, as today.
- Move and adapt engine/run-on-stream into stream-observer: run-on-stream [session ready-fn load-fn run-fn] -> updated-session. It uses the supplied VM operations without inspecting VM fields. Preserve sequential batch processing, gap recovery, and return on empty input, end, or unfinished suspended execution.
- Wire these helpers with existing engine/ready-for-ingress?, ast-walker/vm-load-program, vm/step, and vm/run. Continue supplying functions directly, as the existing code does.
- Make walker step advance execution only, retaining idle no-op behavior. Make walker run use its existing scheduler through ffi/maybe-run; it must not poll the program stream.
- Keep eval as AST conversion, loading, and execution; keep VM reset semantics. Leave lexical-environment restoration, effect scheduling, and FFI processing unchanged.
- Preserve loading only between evaluations. Composition must not read another batch while the VM has active, queued, or waiting work.

### Caller and documentation migration

- Update the REPL to hold observer and VM state separately. Datom-literal evaluation appends through the observer's program handle and calls the composition runner. Direct source evaluation continues using vm/eval.
- Keep REPL reset and VM selection creating a fresh VM and fresh program medium/observer. Preserve existing output, history, ingress-loss reporting, and summary keys, sourcing observation information from the observer state.
- Update stream-based test helpers and callers to carry the session pair. Keep standalone VM construction and direct-evaluation tests working.
- Remove obsolete engine forwarding functions and VM-owned ingress documentation. Correct the walker's misleading "v1's, unchanged" description while documenting the resulting ownership boundary.
- Leave v1 evaluators untouched. Fan-out, additional v2 evaluators, new scheduling policies, and stream-only evaluation are outside this change.

### Verification and review

Write focused contract tests before implementation:
- Observer construction and reads work without any VM; no operation closes or appends to the observed stream.
- Independent observers maintain independent cursors over the same stream.
- Composition works with the real walker and a minimal alternate consumer with different state fields, proving it does not inspect CESK internals.
- Busy, queued, and suspended execution prevents further program reads.
- Successive batches, empty input, end, gaps, and terminal errors retain existing behavior.
- Direct eval, VM reset, effectful primitives, FFI resumption, and REPL reset retain their expected results.
- Program reads occur at batch boundaries; attachment and cursor creation do not enter the instruction loop.

Run focused observer, engine, walker, FFI, parity, and REPL tests on JVM and the repository's affected CLJS/CLJD lanes; inspect fresh generated CLJD output and lint changed namespaces.

Under the orchestrator workflow, obtain independent Architect review of the ownership boundary and actual diff before reporting readiness. Follow the roster and explicit external-payload authorization rules for delegation. Do not stage or commit.

## Orchestrator execution trace (append-only)

- Recorded-GMT: 2026-09-05 10:30:07 GMT | Recorded-Local: 2026-09-05 17:30:07 Asia/Ho_Chi_Minh | Coding-Agent: interactive | Session-ID: not-applicable (interactive seat)
- The first sandboxed launch exited 1 before reviewing, reporting `Not logged in · Please run /login`. Its complete stdout is preserved in `architect-stream-observer-separation-20260905-101500.claude-fable-5-1.stdout.log`.
- Following approved host escalation, retried the initial launch using the same assigned Claude Session-ID `f4e3fed9-73ca-4ea5-a485-a45263d8eff0`, model, read-only tool scope, and plan permission mode. No substantive reviewer conversation existed to resume from the failed authentication attempt.
- The retry is running. Its separate, previously nonexistent output artifact is `architect-stream-observer-separation-20260905-101500-retry.claude-fable-5-1.stdout.log`. No prior artifact was truncated.
