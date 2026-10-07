Completed-GMT: 2026-09-29 08:09:15 GMT
Completed-Local: 2026-09-29 15:09:15 +07
Coding-Agent: claude
Session-ID: f9fe29ab-85ec-4a4d-8667-ca972a237a16

# Report r3: deterministic regression test for the unminted-cursor race

This round adds to `report.md` (root cause, driver fix, and the loop counts:
4/20 failed before the fix, 0/36 after). The only file changed in this round is
`test/yin/repl/driver_test.cljc` (+52). The driver fix from round 2 is
unchanged: `src/cljc/yin/repl/driver.cljc` is still +19/−4.

## The test

`no-request-crosses-before-its-response-cursor-is-minted`, in
`test/yin/repl/driver_test.cljc`.

- **Fixture:** `unminted-reflection` is a `reify` of `stream/IDaoStreamReader`
  that behaves like a `dao.stream.remote` reflection:
  - Until the test sets `minted?`, `cursor` answers
    `{:dao.stream/outcome :dao.stream/transport-error :dao.stream/retry? true}`.
    That is the answer a reflection gives while the server has not yet answered
    the cursor request.
  - After that, `cursor` resolves `newest` against the responses ring at that
    moment, just as the server does when the request reaches it.
  - `next` reads the ring directly.
- **Setup:** the RPC client is built with `stream/anchor-newest` over that
  reader, as `connect/open` builds it.
- **Assertions:**
  1. Step 0, cursor unminted:
     - the requests medium answers `:dao.stream/blocked`, so nothing was appended;
     - `:queued` is `["(+ 1 2)"]`;
     - `pending-write?` is true.
  2. Step 1, after the mint answers:
     - exactly the queued request is appended (`:op/eval ["(+ 1 2)"]`);
     - the queue is empty.
  3. Step 2: the answer appended after the mint is read and published as `"3"`.

## Proof that it bites

I disabled the guard temporarily by making `response-cursor-unminted?` return
false, which turns off all three uses in `handle-line`, `release-queue` and
`pending-write?`. With the guard off, `clj -M:test -n yin.repl.driver-test`
gave **2 failures**:

```
FAIL in (no-request-crosses-before-its-response-cursor-is-minted) (driver_test.cljc:225)
nothing is appended while the response cursor is an anchor
  actual: (not (= :dao.stream/blocked :dao.stream/ok))
FAIL in (no-request-crosses-before-its-response-cursor-is-minted) (driver_test.cljc:228)
the line waits in the queue
  actual: (not (= ["(+ 1 2)"] []))
```

That is the flake's exact precondition: the request crossed while the response
cursor was still an anchor.

The guard is restored. `grep -rn "REVERT-PROBE\|FLAKE" src test/yin` finds
nothing, and `git diff --stat -- src test/yin` lists only `driver.cljc` (+19/−4)
and `driver_test.cljc` (+52).

One limit: the test checks the invariant (no request is sent before the cursor
is minted). It does not replay the answer-skipped-by-a-later-mint interleaving
itself. With the guard in place that interleaving can't happen, and the 20-run
cross-process loop in `report.md` shows the loss it causes.

## Verification

- `clj -M:kondo --lint test/yin/repl/driver_test.cljc src/cljc/yin/repl/driver.cljc`:
  0 errors, 0 warnings.
- `clj -M:test -n yin.repl.driver-test -n yin.repl.main-test`: 31 tests,
  180 assertions, 0 failures, 0 errors.
- Not run in this round: `bb test:cljs` and cljstyle (not asked for), and
  `bb test:cljd` (excluded).
  - The new test uses only `reify` of a `cljc` protocol plus the existing
    helpers, and no reader conditionals. The shadow node-test auto-discovers
    `yin.repl.driver-test`, so the cljs lane will pick it up. Its result there
    and on cljd is unverified.

## Scope kept

- `dao.stream.rpc` is untouched. The `cursor-pending` suggestion from
  `report.md` stays with the Architect.
- I did not rebuild `build/yin-repl-peer`; that is the orchestrator's job.
- Nothing is staged or committed.
