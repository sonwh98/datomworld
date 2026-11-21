Created-GMT: 2026-11-21 13:06:21 GMT
Created-Local: 2026-11-21 20:06:21 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated; fresh session — resume-by-state)

# Task: yang.preludes P1′ fix r2 — guest try/catch broken (38 yang.clojure-test failures)

Role: Engineer

Implementers:
- Model: gpt-6.2-sol | Assigned: 2026-11-21 20:06 +07 | Status: active | Rationale: same model, third dispatch on the unit (implementation + fix r1); fresh session again per the 0.170 read-only-resume rule

## State (resume-by-state)

Worktree `/Users/sto/workspace/dw-p1prime`, branch `feat/p1prime-yrt-effect`. Committed: `f954d5ec` (your implementation round). UNCOMMITTED on top: your fix r1 (linker-compatible helpers in `src/cljc/yang/clojure.cljc` + test adjustments) — that fix resolved the h31 wedge budget exhaustion (verified: `clj -M:test -n d2-wedge-min-repro` = 1 test/6 assertions/0 failures, matching master) and cleared the 2 errors in yang.clojure-test, but **38 failures remain**. Do not revert fix r1; build on it.

## The remaining failure (orchestrator-verified)

`clj -M:test -n yang.clojure-test` = 105 tests / 1003 assertions / **38 failures, 0 errors**. Master `f23728d3` = 105 tests / 1011 assertions / 0 failures.

ALL sampled failures share one signature — guest `try/catch` no longer catches: every `test-exception-r3-*` probe across BOTH `:walker` and `:semantic` lanes returns `[:thrown {}]` where the expected result is a caught condition, e.g.:

- `test-exception-r3-astra-probes` (clojure_test.clj:1805, 1816, 1832): `[:yin.k/ok [:yang.call/not-callable ...]]` expected, actual `[:thrown {}]`; retained-wrapper rethrow expected `[:yin.k/error ...]`, actual `[:thrown {}]`; `(cell/authentic? e)` probe same.
- `test-exception-r3-modified-condition-refuses` (clojure_test.clj:1705): expected `[:yin.k/error #:yang.call{:reason :yang.call/unsupported-binding, :data {:forged true}}]`, actual `[:thrown {}]`.

Diagnosis direction: P1′ migrated bundled `yang.clojure`'s 4-slot frames to `(yrt.effect/ctx nil 0 0 nil)` 5-slot frames and `clj/raise`/`try-value` onto `yrt.effect`. The guest exception path (source `try`/`catch` lowering onto `:handler` frames) is now failing to deliver — `[:thrown {}]` means the escape reached the top boundary uncaught, i.e. either the `:handler` frame is not installed where guest `try` lowers it, `raise` skips it (frame-kind/arity mismatch in the new 5-slot layout), or the exception-lowering in `src/cljc/yang/clojure.cljc` still emits the OLD frame contract while `yrt.effect/raise` expects the new one (or vice versa). Check the `dsg__*`/`try`-related definitions in `yang.clojure.prelude` and the `try` lowering paths for contract mismatch.

## Task

1. Fix the guest exception path so all 38 failures pass WITHOUT weakening any fixture: the assertions and budgets in `test/yang/clojure_test.clj` are protected (they encode the r3 protocol probes signed off in review). Fix the implementation, not the tests.
2. The §4.3 one-rule-per-frame-kind semantics and the review-confirmed `perform`/`handle` design must stay intact — do not regress what the reviewer confirmed.
3. Verify (all must pass, run them all):
   - `clj -M:test -n yang.clojure-test` → 0 failures, 0 errors
   - `clj -M:test -n d2-wedge-min-repro` → stays 0 failures
   - `clj -M:test -n yang.rt.effect-test -n yang.clojure-callsite-test` → stays 54/674/0
   - `clj -M:test -n yang.latte-oracle-test` (focused) → stays green
   - `clj -M:test -n yang.clojure-callsite-test` covers replay goldens; if any golden updates, list them explicitly
   - `clj ./bin/analyze-deps.clj` clean
   - Complete your prior round's unfinished verification if quick (Node compile test optional; prioritize the JVM set above)
4. `git add -N` new files; do NOT commit.

## Deliverable

Final report: root cause with file:line, the fix, the full verification matrix with exact counts, and any golden changes listed. Budget note: this is the second fix round on this unit — if you cannot reach green, report exactly what remains failing with evidence rather than leaving partial edits.
