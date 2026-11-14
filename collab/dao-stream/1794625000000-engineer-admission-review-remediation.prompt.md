Created-GMT: 2026-11-14 02:58:00 GMT
Created-Local: 2026-11-14 09:58:00 +07

# Task: Remediate Non-Codex Review Findings on `92819cdf`

Role: Senior Clojure/ClojureScript/ClojureDart Stream & Shell Engineer

Context:
- Branch: `fix/master-gate-and-dht-tests` at commit `92819cdf`.
- An independent adversarial review (`collab/dao-stream/1794623000000-reviewer-admission-fix.qwen3.8-max.stdout.log`) identified 1 blocking `P1` code issue, 1 substantive `P2` test-fixture issue, and several clean `P3` improvements.

Required Remediations (implement ALL cleanly, do NOT stop partway):
1. **[P1] `src/cljc/yin/shell/session.cljc` (`recheck-on-tick`, ~lines 108-140):**
   - Remove the fabricated `(if (and (:progress? served) (:pending-run engine') (= left 1)) (str text ";; require still pending\n") text)` branch. Return `[session text]` directly — `yin.repl/pending-text` already owns and emits the pending notice (`";; require pending: ..."` or `";; require still pending; ..."`).
   - **[P3] Preserve `events` in `recheck-on-tick`**: when `(:progress? served)` is true on iteration 1, do NOT discard caller `events` into `[]`; pass `events` across progress rounds and only consume/clear them to `[]` once a round takes the non-progress `repl/recheck-on-tick` branch (or exit when loop ends).
   - **[P3] Update `recheck-on-tick` docstring** accurately: explain that it bounds per-tick continuation to `repl/link-round-budget` rounds (matching the pre-refactor driver's per-tick ceiling expected by `continuing-tick-progress-is-bounded-test`), resuming while `(:progress? served)` holds and offering `events` inward on at most one non-progress round.

2. **[P1 + P2 + P3] `test/yin/vm/linker/admission_test.cljc`:**
   - **[P1]** At line ~1287 (`continuing-tick-progress-is-bounded-test`), update `(is (str/includes? text "require still pending"))` to `(is (str/includes? text "require pending"))` so it matches the genuine engine notice from `yin.repl/pending-text`.
   - **[P2] Remove the `identical?` fabrication (`(if (= state next) state next)` at `tick!` ~627-632):**
     - Thread the full `session/transport-state` across `tick!` and `type!` (so `:awaiting`, `:key`, and `:limits` are preserved across ticks instead of reconstructed per call from `(session/make ...)`). Keep `:node` synced cleanly for existing test readers if needed, or read from `:session` consistently.
     - Have `tick!` return the actual successor state without `(if (= state next) state next)`.
     - Update the assertion in `a-late-answer-completes-the-require-with-no-typed-line-test` (~line 987) from `(is (identical? state state'))` to `(is (= state state'))` alongside `(is (nil? text'))` and `(is (= (:pending-run state) (:pending-run state')))`.
   - **[P3]** In `type!` (~line 644), accumulate text (`(str text t)` rather than `(or t text)` where earlier text should not be dropped; check if any caller expects nil when empty vs "" cleanly — verify all callers pass!).
   - **[P3]** Clean up continuation indentation at the `tick!` sites (~lines 731-735, 849-853, 980-984, 1010, 1062, 1502-1505) and wrap `type!` `let` bindings cleanly (~80 cols per `.cljstyle`).

3. **[P3] `test/yin/vm/linker/dht_end_to_end_test.cljc`:**
   - In `eval-at` (~line 162), accumulate `(cond-> text resumed (str resumed))` / `(if (and text resumed) (str text resumed) (or resumed text))` cleanly instead of dropping `text` when `resumed` is non-nil (verify return nil-ness expectations in `dht_end_to_end_test`).
   - In `hydrate` (~lines 227-231), pass `(:session (get-in ... [:shells k]))` directly to `repl.dht/admitting?` and `repl.dht/refusal` instead of constructing a self-referential `:engine` map.

4. **[P3] `test/yin/repl/dht_test.cljc` (`a-dht-store-refuses-a-second-content-source`, ~line 1701):**
   - Pin the refusal reason message on `:content-client` (and `:content-store`) using `(is (str/includes? (ex-message (refusal-of ...)) "second content source"))` and rename `"and the client refusal left the directory unlocked"` to `"the refused open left the directory free for a later open"`.

Verification (run ALL of these on `jupiter` after final edit):
1. `clj -M:test -n yin.vm.linker.admission-test` -> 0 failures, 0 errors
2. `DATOM_SLOW_TESTS=1 clj -M:test -n yin.vm.linker.admission-test` -> 0 failures, 0 errors
3. `clj -M:test -n yin.vm.linker.dht-end-to-end-test` and `DATOM_SLOW_TESTS=1 clj -M:test -n yin.vm.linker.dht-end-to-end-test` -> 0 failures, 0 errors
4. `clj -M:test -n yin.repl.dht-test -n yin.repl.dht-head-test -n yin.repl.shell-session-test -n dao.host.gate-test` -> 0 failures, 0 errors
5. `rg -n 'dao\.jing\.dht|yin\.link\.dht|yin\.shell\.dht|datagram' src/cljc/yin/repl src/cljc/yin/repl.cljc` -> 0 matches

Leave the working tree ready (or commit on `fix/master-gate-and-dht-tests` if git permits), and write concise findings with verbatim test summaries to `collab/dao-stream/1794625000000-engineer-admission-review-remediation.findings.md`.

Begin your final summary with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
