Completed-GMT: 2026-09-10 04:58:40 GMT
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574
Role: Routine Review (Phase 2, r2 confirm) | Model: gpt-6-astra

I’ll check both interruption fixes, their tests and the reconciled contract text, focusing on exits that could still bypass cleanup. I’ll keep this read-only and won’t rerun the lanes.

**The two fixes are correct, but one additional exit remains.**

1. **P1 — invalid timing options can still bypass cleanup after submission.** [remote.cljc:530](/Users/sto/workspace/datomworld/src/cljc/dao/jing/remote.cljc:530) computes the deadline **after** `rpc/request!`, without validating `:request-timeout-ms`. A client opened with `{:request-timeout-ms "bad"}` therefore sends its first request, then throws during arithmetic without storing the advanced allocator. Reusing that client with a corrected timeout can reuse the id and receive the abandoned call’s answer.

   Likewise, negative `:poll-interval-ms` makes `Thread/sleep` throw `IllegalArgumentException`, bypassing both interruption catches.

   Validate timing options before attachment or request submission, including at public `call!`’s entry for client values modified with `assoc`. Pin that invalid options send nothing and leave the allocator unchanged.

2. **Nonblocking — the establishment test does not pin close.** Its no-return, error-map and interrupt-flag assertions are valid, but deleting only `stream/close!` would leave them passing. The comment’s “pinned together” claim overstates coverage. No new production seam is necessary: a JVM test can wrap the existing `stream/close!` var, delegate to it and record invocation. Alternatively, describe closedness as statically reviewed rather than tested.

The eighth exit correctly retires from `(:state step)`, stores through `settle!`, preserves interruption and prevents late-response miscorrelation. The ninth correctly closes before restoring the flag and throwing. The accepted-socket ownership protocol fixes the cleanup race.

The documentation accurately describes those repairs, but “every exit” remains too strong until timing-option validation is added. **Not ready to commit yet.**
