[claude-code:unrecognized_model] {"model":"glm-5.3","query_source":"sdk"}
```text
Completed-GMT: 2026-09-13 06:08:52 GMT
Completed-Local: 2026-09-13 13:08:52 +07
Coding-Agent: glm
Session-ID: dcda6fce-9c4e-4e07-8311-57fd6a7513d9
Role: VM Runtime Review | Model: glm-5.3
```

# Round-3 Review — `docs/design/yin.vm.macro.md` (r3)

**Verdict at the end: REQUEST CHANGES — on exactly one new sentence (P2-1 below). Every round-2 finding is resolved; all three §5 traces pass against `stream_observer.cljc` except that the REPL round gate re-creates the head-of-line jam through the `:errors` channel that decision 11 just fixed through the cursor.**

## 1. Round-2 findings: resolution status

| # | Round-2 finding | Status | Where / gap |
|---|---|---|---|
| P2-1 | Failure events never reach the log; deterministic failure head-of-line blocks `program-in` | **Resolved** (mechanics verified, §2a below) — **but the resolution introduces P2-1-new** | Decision 11; §3.1 `:error` result; §5 staging; §6.1 round; Phase 1/2 tests |
| P2-2 | Dual-medium flush non-atomic; `out=ok, log=full` duplicates the program | **Resolved** (verified, §2b) | §5 per-medium staging `:out-staged`/`:log-staged`, outcome table, at-most-once per medium; Phase 1 tests |
| P2-3 | Stale expander store after plain redefinition expands dead code | **Resolved** | §3.1 step 2 (plain lambda `dissoc`s); §2.2 two-namespaces paragraph (accurate: stores are disjoint by media, neither direction silent); Phase 1 test |
| P3-4 | `:alloc` "monotonic" contradicted its seeding formula | **Resolved** | §3.1 seed now `(min (:next-eid alloc) (dec min-eid-in-batch) (- (inc first-user-id)))`; "never re-seeded upward"; retry reuses staged allocation; the two gensym tests (differ across overlapping batches, equal on replay) pin it |
| P3-5 | `program-out` `m = ev` ambiguity; event attrs homeless after schema drop | **Resolved** | Decision 9 ("`program-out` datoms carry `default-op` only"); §4.1; `yin.vm.macro/event-schema` with the merge instruction for `dao.space` commits |
| P3-6 | Unconditional root fact vs "compile output unchanged" | **Resolved** | Phase 0 test reworded "modulo the root fact" |
| P3-7 | Phase 2 `:semantic` test unstated prerequisite | **Resolved** | Deferred to semantic Phases 1–2, with the continuation-depth measurement ("by inspecting `k`") — the right strengthening |
| P3-8 | Working representation ambiguity; `eid(node)` undefined on maps; final scan must be scope-aware | **Resolved** | §3.1 working-representation paragraph (eids in the index, maps only at the body-invocation boundary); §3.2 pseudocode is now eid-based; admission over the index (Appendix B's "never `datoms->ast` first" is correct — the decoder recurses and overflows on a cycle); final scan narrowed to `:macro?` lambdas, application fixpoint by construction |
| P3-9 | Missed-macro diagnostics | **Resolved** | §6.1 `repl-state` lists the store's macro names |

## 2. §5 verified against `stream_observer.cljc` (traces)

**(a) Bad batch then good batch, two rounds.** Verified against `run-on-stream` (stream_observer.cljc:175-199): with decision 11, `load-program` now *returns* the updated expander for a failing batch — the observe-effect fn returns `ok`, the branch is `:advance`, the observer cursor advances (stream_observer.cljc:190-192), `run-vm` flushes the log-only stage, `ready?` (defined over both slots — an error-only batch leaves both nil) is true, and the *same round's* loop observes the next batch. The cursor genuinely advances on the `:error` load, exactly as claimed. The un-jam **works at the stream layer — and then fails at the REPL gate**: `:errors` is only ever `conj`ed (§5 `load-program`) and read (§6.1 "if `:errors` is non-empty, print them as the round's result … otherwise drive the evaluator"), never cleared. After the first failing input, every subsequent round finds a non-empty `:errors`, prints the stale error, and *never drives the evaluator again*. That is the round-2 head-of-line block re-entering through a different channel — see P2-1. The intended reading is visible in the state comment ("failures *this round*, for the driver") but the round protocol never says who clears it, and the driver cannot distinguish this round's errors from last round's without a drain.

**(b) `out=ok, log=full` then retry.** Verified: flush clears each slot independently on `ok`; the retry round finds `out-staged` nil and re-appends only the log payload. Exactly one program append. Flush order between the two media is no longer load-bearing — the correct outcome of per-medium staging. Phase 1's test list pins all three interleavings (`out=ok,log=full`; `out=full`; repeated `full`).

**(c) No log medium.** Verified: `(when (:log x) (:log r))` keeps `:log-staged` nil for both `:ok` and `:error` results; `ready?` reduces to the out slot; `:errors` still reaches the driver, so failure reporting survives the absent log. Consistent with decision 9 ("when the expander has one").

## 3. The §5 prerequisite claim (astra P1-1)

**Verified against the code.** `run-on-stream`'s loop accumulates `{observer', vm'}` in locals and publishes only by returning (stream_observer.cljc:175-199). A throw from batch B's load (or run, or a terminal read) after A was forwarded propagates with the loop state unrecoverable; the caller holds the pre-A session and a retry re-observes A — for an evaluator this merely re-loads (loaders replace state), but for any *forwarder* (the expander appends to another medium) it duplicates A. The defect is real, is in the generic coordination, and predates this design.

**Placement is right, the return shape needs one caution.** `{:observer :vm :error}` as a *return* converts throws into values: every existing caller that reasons "an exception from `run-on-stream` means the round failed" (the REPL session loop, `test_utils`, `stream_observer_test`) will, unchanged, treat the new error-carrying session as a normal one and silently continue past a forwarder defect — the silent-progress failure mode this codebase forbids. Either (a) keep the throw and carry the partial session in the exception's data (`ex-info … {:session s' …}`), so recovery is opt-in and existing callers keep their semantics; or (b) keep the proposed return shape and make "update every existing caller to check `:error`" an explicit part of the Phase 0 deliverable. The Phase 0 test list (A forwarded, B throws, exactly one A on retry; throwing `run-vm`; terminal read after a successful batch) is exactly right either way. This is P3-2 below — it changes the migration story, not the fix.

## 4. §3.4 `mark-tail` recompute

**Sound, and correctly whole-tree.** The recompute (set *and clear*) is the right fix for the stale-flag hazard the doc itself names: an additive pass would leave `:tail? true` on syntax a macro moved into an operand, and a tailcall in operand position drops the continuation the enclosing application still needs. The per-node context rules are complete and consistent:

- "lambda body always tail" is exact under the lowering contract that exists: the semantic spec's lowering table emits every lambda body out-of-line ending in `:return` (no inlining rule), so a body-final `tailcall` is always correct relative to that `:return`, regardless of where the lambda value flows. Nothing is pushed onto behavior that doesn't exist.
- The "linearize owns inlining" clause is an honest forward obligation, not a hidden problem: a *future* inlining optimization would have to re-derive tail context of the spliced body — the same class of obligation lowering already carries for `:if` tail inheritance. Worth one added sentence stating that under the current no-inline contract the rule is exact (see P3-3 note below), but no design change is required.
- Immediately-applied lambdas check out both ways: tail context → `tailcall` into the body against the caller's K (O(1)); non-tail context → one frame for the let-call, constant depth, the body's own tail flag still correct relative to its `:return`.
- The walker remains unaffected (verified previously: it never reads `:tail?` and has structural TCO via `apply-function` reusing the caller's `k`), so walker parity is safe while the flags are recomputed.

Phase 1's added tests ("clears a stale flag on marked syntax moved into an operand and into an `:if` test") guard precisely the hazard. The §3.2 one-time operator re-check is also sound: a rebuilt application's re-check either takes the macro arm (consuming depth on its re-expansion) or is a no-op — it cannot cycle without the depth guard tripping; and the entry-condition re-evaluation means an unchanged node's re-check answers the same as at entry.

## 5. New findings (introduced by r3)

### [P2 — must address] P2-1. `:errors` is never drained — §6.1's round gate re-creates the head-of-line block

As traced in §2(a): `:errors` accumulates across rounds, §6.1 gates the *entire evaluator step* on it being empty, and nothing in §5 or §6.1 clears it. As literally specified, the first failing macro input makes every later round print the stale error and run nothing — the exact failure mode decision 11 was adopted to remove, re-entering through the driver channel instead of the cursor channel. The fix is one sentence: state the drain point — the driver clears `:errors` after printing them (equivalently: "read-and-reset"), or the round protocol clears the slot when a new batch is appended. The existing Phase 2 test ("a bad macro input prints its error and the next input evaluates normally") will catch an implementation of the current text, but the spec contradicts its own test as written.

### [P3 — suggestion/alignment] P3-2. Choose the `run-on-stream` error shape with its migration cost stated

Per §3 above: prefer carrying the partial session in the thrown exception's data (recovery stays opt-in, existing callers keep throw semantics), or explicitly scope "update all existing callers to check `:error`" into the Phase 0 deliverable. As written, the return-shaped fix silently converts defects into normal returns for any caller not updated in the same change.

### [P3 — suggestion/alignment] P3-3. Admission should check the closed node vocabulary

§3.1 step 1 checks refs, acyclicity, and plain-data ground values — but not that every typed entity's `:yin/type` is in the closed vocabulary. An unknown type (e.g. a batch carrying retired `:yin/macro-expand` datoms from the old codec) passes admission, is traversed and copied verbatim (the fixed ref-attribute order is undefined on it), and is rejected only if and when the evaluator's loader decodes it. Add `:reason :unknown-type` to `:malformed-input` so the forwarder rejects at its own boundary, symmetric with the other admission checks. Two one-line doc nits while editing: §3.4 could state that the rules are exact under the current no-inline lowering contract; and Decision 11 is listed before Decision 10.

## Verdict

**REQUEST CHANGES** — narrowly. All nine round-2 findings are genuinely resolved and the r3 mechanics verified against the code; the sole blocking item is the missing `:errors` drain sentence (P2-1), which the author can fold in together with the two P3s in one editing pass. With that sentence present, this reviewer would approve the design for Phase 0.
