[claude-code:unrecognized_model] {"model":"glm-5.3","query_source":"sdk"}
```text
Completed-GMT: 2026-09-13 06:16:06 GMT
Completed-Local: 2026-09-13 13:16:06 +07
Coding-Agent: glm
Session-ID: dcda6fce-9c4e-4e07-8311-57fd6a7513d9
Role: VM Runtime Review | Model: glm-5.3
```

# Round-4 Confirmation — `docs/design/yin.vm.macro.md` (r4)

**Verdict at the end: APPROVE.** All three round-3 findings are resolved as specified and the resolution mechanics trace correctly against `stream_observer.cljc`. One new P3 (an unnamed disposition for a stray source macro lambda outside a definition shape) and nothing at P1/P2.

## 1. Round-3 findings: resolution status

| # | Round-3 finding | Status | Where |
|---|---|---|---|
| P2-1 | `:errors` never drained; §6.1's gate re-creates the head-of-line block | **Resolved — verified by trace (§2 below)** | §5 `:forwarded` counter + `drain-errors` read-and-reset ("nothing else clears them"); §6.1 gates the evaluator on `:forwarded > 0` *independently* of printing `:errors`, so both can hold in one round; Phase 1 tests pin "drained expander reports nothing on the next drain" and the A/B interleaving |
| P3-2 | `run-on-stream` error shape: value-shaped errors let un-updated callers continue silently | **Resolved — option (a) adopted verbatim, with the right refinement** | §5 prerequisite keeps the throw, carries `{:session {:observer o' :vm vm'}}` in `ex-data`; the rejected return-shape and exactly my reason are recorded; the load-vs-flush cursor distinction is folded in correctly; Phase 0 tests cover all three failure positions |
| P3-3 | Admission should check the closed node vocabulary | **Resolved** | §3.1 step 1 `:unknown-type`, admission over *every* entity in the index (not just root-reachable — the right call, since harvest reads disconnected definitions); Phase 1 test list includes it and the disconnected-cycle case |
| nits | §3.4 exactness sentence; decisions 10/11 order | **Resolved** | §3.4 lines on the no-inline contract added; decisions now in order |

## 2. §5/§6.1 re-verified against `stream_observer.cljc` with the drain

**(a) Bad input, then good input, two rounds — the second round runs.** Round 1: `load-program` returns the `:error` result (no throw), `run-on-stream` takes the `:advance` branch, the observer cursor moves past the bad batch, `flush` delivers the event to the log (`:forwarded` untouched — log is not `:out`), the loop re-observes, blocks, returns. `drain-errors` → `{:errors [e] :forwarded 0}`: nothing runs, error printed, **and the expander is reset**. Round 2: the good batch expands `:ok`, flush appends the program, `:forwarded` becomes 1, drain drives the evaluator. The jam is gone at both the cursor and the driver channel. The "driver that never drains grows a list — its bug, not a stuck expander" sentence is the right framing.

**(b) A-ok then B-error inside one `run-on-stream` call.** A: load stages both slots, flush clears both (`:forwarded` 1), loop continues (ready). B: load returns `:error` — `out-staged` is set to nil *by the `when`*, which is safe precisely because `load-program` only ever runs when `ready?` held (both slots nil from A's flush), so nothing of A's is clobbered; the event stages for the log, flush delivers it, loop blocks, returns. Drain: `{:errors [eB] :forwarded 1}` → §6.1 runs A exactly once and reports B exactly once. Verified against the coordination's calling order; the Phase 1 test pins this exact interleaving.

**(c) `:forwarded` increment point.** Correct: the outcome table bumps it on flush `ok` on `:out` only, not at load. The discriminating case works: batch loaded but program-out `full` → round ends not-ready → drain reports `:forwarded 0` → the evaluator is *not* driven against a program that never landed; the later successful retry bumps it once. At-load counting would have driven the evaluator on nothing; at-flush counting also makes "exactly one increment per batch" a consequence of per-medium staging (one payload, one `ok`, one bump, `full` retried without a second bump).

**Prerequisite shape (re-checked).** The load/run cursor distinction is implementable in the existing code: a load throw happens inside the observe-effect fn before `:advance` publishes the successor (cursor before B); a `run-vm` throw happens after `:advance` (cursor after B), and for the expander the carried `:vm` has the *partially flushed* slots — if the out append succeeded before a log-throw, the retry re-appends only the log. `:forwarded` survives in the carried `:vm` (incremented during the loop's earlier flushes), so a recovered session's count is not lost. Keeping the throw preserves every existing caller's semantics; recovery stays opt-in. This is the correct fix in the correct place.

## 3. §3.1 step 5 ordering rule — sanity check for silent-wrong-code

The r2 hazard was: `defmacro m`, then `defn m`, then `(m 1)` — stale store silently expanding dead code *forever*. Under r4: the `defn m` shape appears only post-expansion, so step 5 post-harvests the final tree and `dissoc`s `m`, effective next batch; the `yin/def m <plain>` is still forwarded, so the evaluator binds the function this batch, and the next batch's `(m 1)` goes through as an application. The residual window is: `(m 1)` in the *same* batch as the `defn m` — it expands with the old macro. This is now (i) explicit ("both apply to their own forms in source order as Clojure would not, and the design accepts that"), (ii) bounded to one batch, (iii) convergent from the next batch, and (iv) pinned by tests covering all three redefinition shapes (source `yin/def`, `(defn m …)`, `(def m other-fn)` — the last effective in-batch via step 2, the second effective next-batch via step 5, both stated). A one-pass deterministic rule with a declared one-batch divergence is the right trade against iterating harvest/expansion to a mutual fixpoint, which would break the depth-guard's meaning. No silent-wrong-code case of the r2 kind remains: every divergence from Clojure is either stated in the document or loud.

Also verified in passing: the §3.2 application branch (operator first → re-check → operands) preserves outermost-first for binder macros — in `((choose) f [x] (x 1))`, the re-check fires with the *original* operands, so `defn` receives `(x 1)` unexpanded and the lambda it builds shadows `x`; recognition is not an expansion, each invocation consumes depth, so the chain is bounded; and the widened `:generated-macro` prohibition closes astra's r3 test/validator contradiction by removing the contradictory case rather than widening the validator.

## 4. New findings (introduced by r4)

### [P3 — suggestion/alignment] P3-1. A source macro lambda outside a definition shape has no named disposition

Step 8 asserts "no lambda with `:yin/macro? true` remains" in the final tree. Source macro lambdas *in* definition shape are replaced (step 3) and generated ones are rejected (`:generated-macro`) — but a source batch (hand-assembled, or a future frontend) containing a bare `:macro? true` lambda in a non-definition position is admitted (admission's closed vocabulary permits `:macro?` on lambdas — harvest needs it), never harvested, never replaced, and then trips the step-8 assert with no error kind named. Specify the disposition: either reject at admission (`:malformed-input :reason :stray-macro-lambda`), or name the step-8 failure kind — and note that such a lambda forwarded to the walker would load as a plain closure (`ast-walker` ignores `:macro?`), which is exactly the quiet case the scan exists to prevent. One sentence; suggest the admission-time rejection for symmetry with the other whole-index checks.

### [P3 — suggestion/alignment] P3-2. Cosmetic: Appendix C row 5 still says `:generated-definition`

The round-2 table (historical) names the error `:generated-definition`; §3.2 now uses `:generated-macro` (round-3 table row 20 records the widening). Since Appendix C is the resolution ledger reviewers cross-check against the text, a parenthetical "(now `:generated-macro`, row 20)" on row 5 would prevent the appearance of a dangling error kind. No design change.

No other new issues: the admission-failure event (allocated from the watermark alone, no input values in diagnostics, staged through the same per-medium path, never regenerated) is coherent; the `closed`/`invalid-value`/`transport-error` reclassification as protocol-outcomes-the-forwarder-cannot-continue is correct; decision 5's qualification matches §2.2; and step numbering (`mark-tail` as step 6 in §3.4) is consistent.

## Verdict

**APPROVE.** All round-2 and round-3 findings from both reviewers are resolved and the resolutions are verified against the actual coordination code; the two residual P3s are one naming/disposition sentence and one cosmetic ledger note, neither blocking. The design is ready for Phase 0 as sequenced (codec and `:yin/root`, the `run-on-stream` throw-with-`ex-data` fix, then the expander), with Appendix B's landing-order risks as written.
