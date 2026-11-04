Created-GMT: 2026-11-03 17:30:00 GMT
Created-Local: 2026-11-04 00:30:00 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: not-applicable (orchestrator-dispatched, fresh session)
# Task: fix the oracle harness's evidence-walk blowup — the post-exhaustion stall

Role: harness engineer (codex)
Reviewer/landing: the ZCode glm orchestrator seat; the kimi quality gate follows.

## Context

Worktree `/Users/sto/workspace/datomworld-evwalk`, branch `fix-evidence-walk`, base master @ ed4ca1dc4. Read the verified diagnostic first: `collab/yang-clojure/1794100000000-engineer-h3-1-wedge-round2.glm-5.3.findings.md` (glm's round; the seat ran its minimized reproduction and confirmed every arm).

## The defect (verified, not hypothetical)

When a kernel-gate fixture's budget exhausts, `kernel-attempt` (test/yang/latte_oracle_test.clj ~:1029) catches the exhaustion exception and builds the blocked record from `(ex-data e)` via `evidence-data` (~:1645). `evidence-data` dissoc's `:session` — but NOT `:consumer`, which after a full 2M-step run is the entire advanced machine. The walk then descends through `upstream-record-data` (~:1014), whose `walk/postwalk` calls `upstream-fn` (a `requiring-resolve`, ~:286) per node, with no cycle guard and no memoization. Measured (glm's arms D/E, seat-reproduced): the walk is unfinished at 120 seconds; 78,913 unique containers with 3,442 shared revisits and no cycle — exponential shared-revisit expansion. The two blocked fixtures on master therefore stall the focused gate for 2400s+ whenever it runs.

## The fix (test-side only)

In `test/yang/latte_oracle_test.clj` ONLY:
- `kernel-attempt` must not walk the machine: dissoc `:consumer` alongside `:session` from the exception data before `evidence-data` (the machine is diagnostic machinery, same class as the host session — the existing comment at ~:1036 already says so for `:session`). The blocked record keeps `:message`, the causes, and `:host-session-attached?`; add the advanced machine's BOUNDED summary instead if useful (halted/blocked/continuation-depth — scalars only).
- OR, if you find a narrower fix that preserves more evidence: memoize the walk with an identity map so shared containers are visited once, plus a cycle guard. Choose ONE approach; justify it in the report. Do not do both.
- Add a regression: a fixture whose budget exhausts must produce its blocked record within a bounded wall time (assert the record's shape and that the walk terminated — e.g. run the smallest exhaustion you can construct and assert completion under a few seconds).

## Acceptance

1. `clojure -M:test -v yang.latte-oracle-test/test-kernel-differential-gate` (run `clojure -M:test -m d2-wedge-probe`-style prep first if ANTLR outputs are missing — actually `bb gen:antlr` via `~/.local/share/mise/installs/babashka/latest/bin/bb gen:antlr` in the worktree): the gate COMPLETES — counts `{pass 100, recorded-upstream-failure 2, recorded-deviation 2, blocked 2}` on master's compiler, 661 assertions, exactly the by-design `(:gate-status evidence)` assertion failing, and the whole test finishing in minutes, not 2400s.
2. The full `yang.latte-oracle-test` namespace: 26 tests, the same single by-design failure, no errors.
3. clj-kondo on the touched file: 0 errors.

## Boundaries

- `test/yang/latte_oracle_test.clj` only. No `src/cljc/**`, no evidence/narrative files, no other test files.
- Diagnostic runs contribute zero coverage; the blocked fixtures stay honestly blocked.
- The D2 arc (branch hardening3-h3-1) is reference-only — do not merge or cherry-pick it.

## Deliverable

Commit on the branch (no push; the seat lands after the kimi gate) with the approach justification, the focused-gate timing before/after, and the regression's output.
