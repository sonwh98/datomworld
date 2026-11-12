Completed-GMT: 2026-11-10 21:55 GMT
Role: Reviewer (confirmation round)
# Confirm or refute your four findings against the R3 fix commit

You previously reviewed `8cd9fc92..778747ce` and returned REQUEST CHANGES with P1-1 (token
transplant/R5), P1-2 (ordinary catch exposes wire envelope), P1-3 (retention deletes an
original condition field), P2-4 (two assertions don't pin the outer contract). Your findings
log: collab/exception-track/1794341300000-reviewer-r3-exception-clojure.gpt-6-astra.stdout.log.

The engineer's fix is commit **db2434dd** on branch `exception-r3-impl`
(`git diff 778747ce..db2434dd`, read-only, ~320 lines). Its mechanism: a *generated exception
wrapper* — a task-owned cell containing `[visible condition witness]`; the witness is a
closure only the compiler mints (body names `dsg__call_token`); escape re-derives the witness
from `[visible condition]` read out of the cell and compares it (complete-contents pattern,
same as `dsg__make_admission`). Original condition stored untouched, never assoc'd/dissoc'd.
`ex-message`/`ex-data` lower through the wrapper decoder.

The engineer reports: P1-1/P1-2/P1-3/P2-4 all fixed; `yang.clojure-test` 101 tests/959
assertions 0F/0E; kondo 0/0; contract test deferred to shiva (suite-scale, owner ruling).

## Your job — rule on these invariants (this is why you are asked, not the orchestrator)

1. **Cell forgery**: is `cell/authentic?` plus witness re-derivation sufficient against a GUEST
   minting a cell with forged contents (`[forged-visible forged-condition witness]`), or copying
   a genuine cell's contents into a new cell? Trace what makes a cell authentic
   (`src/cljc/yin/vm/values.cljc`, `data.cljc`) and whether guest code can mint one or transplant
   contents. This decides whether P1-1 is genuinely closed or merely moved.
2. **No guest-applied callable on the error path** (the decoder/witness comparison must read only
   compiler-controlled state).
3. **Visible/retained divergence**: any path where the wrapper's visible value and its retained
   condition can disagree?
4. **Test integrity**: does db2434dd weaken or move any previously-passing expectation? The
   pre-commit formatter reflowed some `defn` forms — verify that is the only non-semantic churn.

## Deliverable

Per-finding verdict (CONFIRMED FIXED / STILL BROKEN with severity P0-P3 and file:line), an
explicit ruling on the cell-forgery question, and a final line: **CONFIRMED** or
**CHANGES STILL REQUIRED**. Do NOT edit anything. Begin with Completed-GMT/Completed-Local.
