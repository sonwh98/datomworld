Completed-GMT: 2026-10-24 (clock not read; no precise timestamp available)
Completed-Local: 2026-10-24, +07 (clock not read)

Phase B is implemented and its tests pass. The findings write to `collab/` was denied, so the full report is below for you to promote.

## Changed files
All in the `dao-star-m1` worktree, nothing staged or committed.
- `src/cljc/dao/star/`:
  - `facts.cljc`: fact constructors, `valid-fact?`, `index-step`, `resolve-token`.
  - `signals.cljc`: `decode`, using `clojure.data.json` on the JVM.
  - `lease.cljc`: the optional fifth namespace, holding the stream `drain` helper, the holder agent, and the exchange-lease and lineage-lease grantor wrappers.
  - `router.cljc`: pure `classify` and a stepped router.
  - `driver.cljc`: per-session `dao.await` VM, begin handler, and the lease logic.
- `test/dao/star/`: `facts`, `signals`, `router`, `driver` (also the public harness), `loopback` and `peer_close` tests.

## Outcomes
- **Phase B tests:** `clojure -M:test -n dao.star.facts-test -n dao.star.signals-test -n dao.star.router-test -n dao.star.driver-test -n dao.star.loopback-test -n dao.star.peer-close-test`: 34 tests, 590 assertions, 0 failures, 0 errors. The `^:slow` stress test is included in that count.
- **Stream lane stand-in:** the same run plus `dao.stream.http-test` and `-r 'dao\.stream\..*-test'`: 538 tests, 4464 assertions, 0 failures.
- **`bb test:sub:clj dao.stream`:** not run, since `bb` was not permitted this session.
- **kondo:** clean (0 errors, 0 warnings) on all `src/cljc/dao/star`, `test/dao/star` and the Phase A files.
- **Process:** I wrote the code before the driver tests, not strictly test-first. `facts` and `signals` were verified first, then `router`, then the code and tests for `driver` and `loopback` together.

## Completion Criteria item 1 coverage
- **Begin without a token:** `request-with-no-token-begins-a-lineage`.
- **Agent answers, parks, resumes:** `agent-answers-parks-and-resumes-on-the-next-input` in memory, and `agent-receives-answers-and-resumes-over-the-real-boundary` on loopback. The VM is a real `dao.await` continuation, and the driver does all the I/O.
- **Subscribe consumes nothing:** `subscribe-consumes-nothing-and-resubscribe-gets-the-current-projection`.
- **Stale token re-projected:** `stale-token-is-reprojected-and_runs-nothing`.
- **Fact before UI:** `no-ui-with-the-successor-id-before-its-fact-is-accepted`. It uses a gated facts medium, and the driver retries the pending fact until it answers `ok`.
- **Closed socket:** `a-closed-socket-is-observed-as-http-closed` on loopback, plus `a-peer-close-is-read-as-data-and-drops-the-subscription` in memory. See concern 1 below.
- **Silent exchange lapses:** `silent-exchange-lapses-and-closes`, driven by hand-appended ticks.
- **Unreturned lineage lapses:** `unreturned-lineage-lapses-record-retired-facts-remain`.
- **Bound handling:** `a-driver-at-its-bound-consumes-nothing-and-begins-instead`.
- **Halt:** `the-program-halting-ends-the-lineage-and-releases`.
- **Just-published token:** `just-published-token-is-never-begin` (25 chained posts) and a `^:slow` stress variant (400 chained posts).
- **Ingress gap:** `after-an-ingress-gap-no-exchange-unleased-and-no-closed-exchange-leased`.
- **Driver stops renewing:** `driver-stops-renewing-after-observing-the-lapse` and `driver-stops-renewing-after-its-own-gap`.
- **Renewal in flight:** `renewal-in-flight-counts-while-live-and-does-not-revive-a-retired-lease`.
- **Router:** its order, co-location refusal (`:refused :co-located`), gap and failed-forward behaviour are in `router_test.clj`.

## Concerns and deviations
1. **http-kit never reports a client-initiated close** of an idle streaming exchange. A minimal repro with no dao code showed no `on-close` after FIN or RST, and 40 further writes all returned true. So Phase A's `peer-closed!` is unreachable by a real client disconnect through the current `jvm.clj`.
   - The loopback closed-socket test closes from the boundary side with `close-exchange` instead, and asserts that `:http/closed` lands on ingress and the driver drops the subscription.
   - The in-memory test covers a peer close deposited by a fake boundary.
   - `close-exchange` ends the chunked response and http-kit keeps the connection alive, so the client sees the chunk terminator, not TCP EOF.
   - Fixing the real client-close detection needs an owner or Architect decision, since `jvm.clj` is a Phase A file I may not edit.
2. **Datastar wording:** no `src/` code mentions Datastar or UCF. The `datastar` query-parameter name appears as a literal in `signals.cljc`, because the spec dictates it.
3. **Programs avoid `loop`/`recur`:** yang.clojure has neither, so the test programs recurse through `(def lp (fn ...))`. A literal map containing a variable is also treated as a constant, so programs build models with `assoc`.
4. **Reclaim success:** for the exchange lease it reports success when the exchange is no longer open afterwards, not the raw boolean from `close-exchange`. Otherwise an exchange the peer already closed would stall the judge.
5. **Policy:** the exchange lease's policy ends a lease with cause `:policy` whenever its exchange is absent from the inventory. That covers both peer close and gap reconciliation.
6. **Noise handling:** routed messages are not validated beyond their `:kind`. Facts and lease records on the facts medium are judged by `valid-fact?` and `index-step`.
7. **Not covered:**
   - Roots lost in a session-facts gap get no lineage grant; the spec is silent on this.
   - Requests to paths other than `/`, `/s/…` and `/k/…` are unrouted and lapse by silence.
   - cljs and cljd were not run, and the host-boundary gate test was not run.
   - Mode 2 and Datastar-specific behaviour are out of scope.
8. **Router co-location check:** it uses a `ReflectionHandle` instance check on the JVM and otherwise relies on the declared `:co-located? true`.
