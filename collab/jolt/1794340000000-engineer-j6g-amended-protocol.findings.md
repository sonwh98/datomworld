Completed-GMT: 2026-11-04 18:05:00 GMT
Completed-Local: 2026-11-05 01:05:00 +07
Coding-Agent: antigravity (gemini-3.8-flash)
Session-ID: 4e7c9a61-b2d4-4f83-9e25-8a31c7d2e540

# J6g Engineer findings: Implementation of the amended Workload W protocol

## Disposition

The amended Workload W measurement protocol specified by the Astra sample-floor ruling (option d) and BRIEF-j6g-amended-protocol has been implemented in full.

Only permitted file `src/dev/jolt_workload_w.clj` was edited. All adapter, parser, pump, TCP, driver, testee, and launcher files remain strictly frozen. Per the brief's constraints, no local test execution or build was performed; verification will be executed remotely by the orchestrator.

Code hygiene checks performed:
- `cljstyle check src/dev/jolt_workload_w.clj src/dev/jolt_workload_w_run.clj`: exit 0.
- `git diff --check`: exit 0.
- No `collab/` paths in code comments.

---

## 1. Independently driven B peer scheduler design

B's request generation has been decoupled from the single owner round loop:
- **Dedicated thread:** B dispatches requests on an independent thread (`b-scheduler-thread`, lines 580–622) using standard Clojure/jolt stdlib threading (`(Thread. ^Runnable ...)`).
- **Independent 10 Hz clock:** The thread calculates nominal scheduled ticks strictly on a 100 ms grid: `target-time = sched-start-ms + k * 100`. Tick resets after slow owner rounds are prohibited and omitted; the schedule grid is preserved across any local delays.
- **Capacity constraint:** At each scheduled tick, if `< 8` requests are outstanding (`(< (count @b-outstanding) 8)`), a 1 KiB request (`make-1kib-request`) is generated and dispatched via the existing client seam send path: `((:send! @b-seam) req-bs)`. No pump internals or event loops are called by the scheduler thread.
- **Missed offers counter:** If `cur-outstanding >= 8`, the offer is not dispatched. The capacity block is tracked via the `b-missed-offers` counter atom (`(swap! b-missed-offers inc)`) and logged in `@b-scheduled-offers` with `:missed? true`.
- **Three distinct timestamps per request:**
  1. `scheduled-at`: Nominal scheduled arrival time on the 100 ms grid (`target-time`).
  2. `sent-at`: Actual dispatch timestamp (`poller/clock-ms`) when `(:send! @b-seam)` is invoked.
  3. `received-at`: Reply receipt timestamp (`poller/clock-ms`) in peer B's `:binary!` callback.
- **Thread lifecycle:** Started immediately prior to entering the owner round loop and cleanly stopped/joined via `(reset! b-scheduler-running? false)` and `(.join ^Thread th 1000)` inside a `try ... finally` block surrounding the owner round loop.

---

## 2. The five-point per-round verifier (with file:line)

The defective `loop-results` check has been replaced by `step-and-verify-repl!` (`src/dev/jolt_workload_w.clj:277-340`), executed once per owner round containing the loop submission (`src/dev/jolt_workload_w.clj:646-657`).

The five assertions are:

1. **(a) Input append succeeded:**
   - File:line: `src/dev/jolt_workload_w.clj:289`
   - Check: `append-ok? (= :dao.stream/ok (:dao.stream/outcome append-res))`
   - Evaluates the outcome returned by `driver/submit-line!`.

2. **(b) Shell `:round` advanced by exactly one:**
   - File:line: `src/dev/jolt_workload_w.clj:306`
   - Check: `round-ok? (= (inc (or before-round 0)) stepped-round)`
   - Compares `(get-in before [:repl :round])` with `(get-in stepped [:repl :round])`.

3. **(c) Exactly one driver result with text `"5"`:**
   - File:line: `src/dev/jolt_workload_w.clj:314-315`
   - Check:
     ```clojure
     results (filter (fn [e]
                       (let [ev (get e driver/event-key)]
                         (or (= :yin.repl.driver/result ev)
                             (= :result ev))))
                     entries)
     result-texts (mapv #(or (get % driver/text-key) (get % :text)) results)
     result-ok? (and (= 1 (count results))
                     (= ["5"] result-texts))
     ```
   - Matches Astra ruling §2 observable `{:yin.repl.driver/event :result :text "5"}` (and its qualified driver key equivalent).

4. **(d) Successor records `:last-value 5` with a halted VM, no pending run, and no ingress loss:**
   - File:line: `src/dev/jolt_workload_w.clj:317-321`
   - Check:
     ```clojure
     successor-shell (:repl next-st)
     last-val-ok? (= 5 (:last-value successor-shell))
     vm-halted? (vm/halted? (:vm successor-shell))
     no-pending? (nil? (:pending-run successor-shell))
     no-ingress-loss? (not (boolean (:ingress-loss? successor-shell)))
     successor-ok? (and last-val-ok? vm-halted? no-pending? no-ingress-loss?)
     ```

5. **(e) Re-draining outbox yields no duplicate:**
   - File:line: `src/dev/jolt_workload_w.clj:322-323`
   - Check:
     ```clojure
     [dup-entries final-st] (driver/take-outbox next-st)
     no-dup-ok? (empty? dup-entries)
     ```
   - Re-drains the outbox from `next-st` and asserts that `dup-entries` is empty, keeping `final-st` as the round successor.

Per-round verifier failures are recorded in `repl-verifier-failures` with failure details and emitted to stdout.

---

## 3. Pacing and cap constants

- **Peer A transfer duration:** Paced linearly over `target-duration-ms = 120000` (120 seconds).
- **Peer A payload:** Single binary frame of 4,194,304 bytes (`:ws/max-frame-bytes`).
- **Watchdog hard cap:** Whole run capped at 300,000 ms (5 minutes, lines 394 & 637).
- **Validity floor:** $\ge 500$ overlapping completed samples (`valid-run? (>= sample-count 500)`).
- **Target samples:** $\ge 600$ completed samples (`target-samples = 600`).
- **Peer B offered load:** 10 Hz (1 request every 100 ms).
- **Concurrency cap:** At most 8 requests outstanding at any time.
- **Latency criteria:**
  - Send-to-receive RTT: empirical $p_{99} \le 250\text{ ms}$.
  - Scheduled-arrival-to-reply latency: empirical $p_{99} \le 250\text{ ms}$.
  - Applied to both the prospective 600 cohort and all qualifying transfer-interval samples.
- **Prospective statistical cohort:**
  - Cohort members: First 600 scheduled requests whose `scheduled-at >= (:started-at @a-window)` (lines 790–801). Selected prospectively in arrival order; never chosen from fastest replies.
  - Evaluation (`evaluate-cohort-offer` lines 343–376): An offer is a violation if it was missed due to capacity limits, unanswered, received after Peer A's delivery, or exceeded 250 ms in either send-to-receive or scheduled-to-reply latency.
  - Tail bound: Exact one-sided 95% upper bound on violation probability $q_U = 1 - 0.05^{1/n} \le 1\%$, computed via `binomial-upper-bound-95` (lines 232–267). At $n=600$, permits at most 1 violation (`cohort-violations <= 1`).

---

## 4. Cursor telemetry

In `W-PROGRESS` (lines 699–712):
- **Driver state materialization cursor:**
  `[:repl :indexer :materialization :cursor]` path read from driver state `@repl-state`.
- **Shell summary publication cursor:**
  `[:index-publication :cursor]` path read from shell summary `(repl/repl-state (:repl @repl-state))`.
- Telemetry outputs both `:index-cursor` and `:index-publication-cursor` (along with the full `:index-publication` descriptor) on each 10-second tick to enable discrimination between the driver and shell state objects.

---

## 5. Ruled protocol compliance and exceptions

All items specified in the Astra ruling option (d) and BRIEF-j6g-amended-protocol were implemented as instructed. No discrepancies or unimplementable elements were identified.
