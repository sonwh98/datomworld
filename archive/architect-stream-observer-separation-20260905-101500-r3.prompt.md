Created-GMT: 2026-09-05 10:38:19 GMT
Created-Local: 2026-09-05 17:38:19 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: f4e3fed9-73ca-4ea5-a485-a45263d8eff0

# Task: Confirm revised stream-observer separation plan

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-05 17:15:00 Asia/Ho_Chi_Minh | Status: active | Rationale: Architect primary; independent Claude review of the GPT-authored plan.
- Status-Event: 2026-09-05 17:31:39 Asia/Ho_Chi_Minh | Model: claude-fable-5-1 | Status: interrupted | Rationale: User explicitly required --permission-mode auto; orchestrator interrupted the plan-mode process to resume the exact same session with that flag.
- Model: claude-fable-5-1 | Assigned: 2026-09-05 17:31:39 Asia/Ho_Chi_Minh | Status: active | Rationale: Same reviewer and session resumed in user-requested auto permission mode.
- Status-Event: 2026-09-05 17:34:32 Asia/Ho_Chi_Minh | Model: claude-fable-5-1 | Status: changes-required | Rationale: Review found three plan blockers and two boundary clarifications.
- Model: claude-fable-5-1 | Assigned: 2026-09-05 17:38:19 Asia/Ho_Chi_Minh | Status: active | Rationale: Same Architect session asked to confirm the reconciled plan.

Perform a read-only follow-up review of the complete replacement plan below. Use the context and source inspection from the same session. Confirm whether every prior blocker in `collab/architect-stream-observer-separation-20260905-101500.claude-fable-5-1.findings.md` is resolved and whether the replacement plan is decision-complete. Do not edit files, run tests, stage, commit, delegate, or inspect unrelated content. Permission mode is auto per explicit user instruction, while the task remains read-only.

Begin exactly with:
Completed-GMT: <actual timestamp>
Completed-Local: <actual timestamp and Asia/Ho_Chi_Minh>
Coding-Agent: claude
Session-ID: f4e3fed9-73ca-4ea5-a485-a45263d8eff0

Then give a verdict of approve, approve with nonblocking notes, or changes required. Report only remaining findings or state that there are none, and explicitly reconcile prior findings 1 through 5. Return the complete answer now.

# Revised plan: Separate stream observation from VM execution

## Summary

Move program observation state out of `ASTWalkerVM` and let composition coordinate an independent observer with a VM. Reuse the existing readiness predicate, program loader, `step`, `run`, `eval`, scheduler, and FFI bridge. Do not add a VM protocol, operation registry, `accept-datoms`, or `ready-for-program?`.

This is an ownership refactor. Preserve complete program-datom batches, direct AST evaluation, VM language semantics, gap recovery, malformed-batch retry behavior, and REPL reset behavior. One existing accidental coupling changes deliberately: direct `vm/eval` will no longer poll or drain the independent program stream after its AST completes.

## Observer state and operations

- Represent an observer as a plain map containing its reader handle, opaque cursor, and `:ingress-gaps` count. Provide a constructor over an existing reader handle which mints `:dao.stream/oldest`, matching current behavior. Stream creation and descriptor attachment remain host-composition responsibilities.
- Remove `:in-stream`, `:in-cursor`, and `:ingress-gaps` from `ASTWalkerVM`, its positional constructor paths, and its construction options. If `create-vm` receives the obsolete `:in-stream` option, throw an actionable migration error before `vm/empty-state` can allocate FFI streams.
- Rename `ingest-next-program` to `observe-next`. It accepts observer state only and returns `{:status :ok, :state observer', :value batch}` on success, or `{:status :blocked|:end|:gap, :state observer'}` for the existing nonterminal outcomes. It performs no VM loading or mutation.
- Preserve DaoStream outcome behavior exactly: success stores the successor cursor; blocked and end leave the cursor unchanged; gap stores the recovery cursor and increments `:ingress-gaps`; cursor mismatch, invalid cursor, transport error, and unexpected outcomes throw with the original outcome.
- Observation itself may compute an advanced immutable observer value, but composition commits it only if program loading returns successfully. If the loader throws, the composition call throws without returning a session, so its caller retains the old session and cursor. The malformed batch therefore remains at the cursor and is retried on later observed evaluation, matching current behavior. Document this poison-batch property; add no new status or recovery API.
- Keep the program writer handle as its own host-composition/REPL field. The observer holds the reader handle it needs, but no append or close operation is exposed through observer functions.

## VM and composition execution

- Move the existing `ready-for-ingress?` implementation from `stream-observer` into `engine`, preserving its exact checks and semantics. The observer must require only DaoStream and must not require the engine. Readiness remains VM-specific policy supplied to composition as the existing function.
- Make the walker’s existing `vm-load-program` public so composition can deliver an observed complete batch. It continues to call `datoms->ast` and install `:program`, `:control`, `:halted?`, `:blocked?`, and `:value` exactly as today; do not add a parallel loader.
- Make walker `step` execution-only. Before calling `vm-step`, guard with the existing engine readiness predicate: an idle fresh or completed VM returns unchanged. A non-idle VM executes one existing step. This preserves the no-op currently supplied by stream-aware `step-on-stream` and prevents nil control reaching the unknown-node branch.
- Make walker `run` execution-only by passing its existing raw scheduler to `ffi/maybe-run`. It processes the active program, ready queue, wait set, and FFI bridge exactly as today, but never observes the program stream.
- Keep `eval` as the existing AST-to-datoms conversion, existing loader call, and execution through `vm/run`. Because `run` becomes execution-only, `eval` completes only its supplied AST and no longer drains a separately queued program batch. Record this intentional behavior change in the v2 divergence register and REPL documentation.
- Adapt `stream-observer/step-on-stream` into the single-VM composition function `step-on-stream [session ready-fn load-fn step-fn]`. A session is `{:observer observer :vm vm}`. If the VM is ready, observe once; on success load the value and execute one VM step, returning both updated values. On gap, commit observer recovery and consume the composition step. On blocked/end, return the updated session. If the VM is not ready, execute one VM step without observing. Loader exceptions return no session and therefore commit neither VM nor advanced observer state.
- Move/adapt `engine/run-on-stream` to `stream-observer/run-on-stream [session ready-fn load-fn run-fn]`. If ready, observe; on success load the batch and loop; on gap commit recovery and loop; on blocked/end return the session. If not ready, call the supplied existing VM `run`; return if it remains not ready (including suspension), otherwise loop to observation. This preserves sequential batches and ensures no read while active, queued, waiting, or blocked.
- Remove the obsolete observer forwarding definitions and `run-on-stream` implementation from `engine`; preserve `engine/run-loop`. Do not introduce a dependency cycle.

## REPL and caller migration

- REPL state holds `:program-stream`, `:observer`, and `:vm` separately. The shell appends datom-literal batches through `:program-stream`; observer functions only read. The program stream and observer share the same host handle in the local ring-buffer composition, without transferring writer responsibility to the observer.
- Datom-literal evaluation compares observer `:ingress-gaps` before and after the composition runner and otherwise preserves existing output, last-value, and failure handling. A loader failure returns the caller’s prior session, so later datom-literal evaluation sees the same malformed batch again until reset.
- Direct source and AST evaluation continue through `vm/eval`. Since that no longer polls the program stream, source evaluation after a failed datom-literal batch succeeds; add a contract test for this deliberate decoupling. Later datom-literal observation still retries the malformed batch.
- Reset and VM selection recreate a fresh VM, program stream, and observer from oldest, preserving current behavior. Update REPL state summaries to read gap data from the observer while retaining the current externally rendered summary shape.
- Update test utilities and stream-based callers to carry `{:observer :vm}` sessions. Standalone VM construction and direct-evaluation tests keep using the existing VM interface. Leave v1 unchanged; fan-out, other v2 evaluators, new scheduling policies, stream-only evaluation, and poison-batch recovery are deferred.
- Update namespace docs, the v2 implementation/divergence documents, and REPL docs to state ownership, the intentional direct-eval change, and poison-batch retry semantics. Correct the walker’s misleading “v1’s, unchanged” wording.

## Test and acceptance plan

- Test observer construction and all read outcomes without a VM; assert observer operations never append or close. Test two observers over one stream have independent cursors.
- Test composition with the real walker and a minimal alternate state shape through supplied existing functions, proving the observer does not inspect CESK fields.
- Test fresh/completed walker `step` is an identity operation and active walker `step` still advances exactly once.
- Test active, queued, waiting, and blocked VMs prevent observation; successful batches execute sequentially; empty/end return promptly; gap recovery and counting are unchanged.
- Test loader failure leaves the caller’s session/cursor unchanged and re-observes the malformed batch on the next datom-literal attempt.
- Test direct `eval`, reset, effectful primitives, FFI blocking/resumption/correlation, parity, and REPL last-value/output behavior. Add the direct-source-after-malformed-datom test and confirm a subsequent datom-literal attempt still encounters the poison batch.
- Run focused observer, engine, walker, FFI, parity, and REPL JVM tests; affected CLJS and CLJD lanes through repository runners; inspect fresh generated CLJD output; lint changed namespaces; verify no obsolete v2 stream-driver or VM-owned-ingress references remain.
- Obtain independent review of the implemented diff before readiness reporting. Do not stage or commit without explicit user instruction.
