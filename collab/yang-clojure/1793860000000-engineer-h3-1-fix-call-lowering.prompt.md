Created-GMT: 2026-11-03 05:05:00 GMT
Created-Local: 2026-11-03 12:05:00 Asia/Ho_Chi_Minh
Coding-Agent: agy (Gemini 4 Argon High)
Session-ID: not-applicable (orchestrator-dispatched; continues your H3-1 worktree)
# Task: H3-1 fix round — the call-lowering fix, per the architect's approved scope

Role: compiler engineer (agy)
Reviewer/landing: the ZCode glm orchestrator seat; kimi holds the quality gate and the architect re-review.

## Governing instructions

The architect reviewed your diagnostic packet and APPROVED the fix round with this exact scope (their constraints are binding):

- `src/cljc/yang/clojure.cljc` **call lowering only** — you may choose the statically safe intra-unit fast path or the token-forwarded outcome lane, but you MUST preserve: dynamic admission for first-class/cross-boundary calls, local shadowing, error outcomes, and tail/non-tail behavior.
- `test/yang/clojure_test.clj:1946-1947`: add `:consumer v` to the exhaustion `ex-data` (the 1-line harness fix), WITH a targeted exhaustion test proving the carried consumer is the advanced VM.
- Focused regression tests and minimal reproductions as needed.
- FORBIDDEN: `yang.call` or `yin.vm` changes; any acceptance-budget change (the 12M proposal is DEFERRED by ruling); coverage credit from diagnostic trials.

Work in `/Users/sto/workspace/datomworld-h31`, branch `hardening3-h3-1` (your packet commit 8a1ca4b7 is the base). Commit on the branch; push; do NOT merge.

## The target

From your own packet: the two blocked fixtures (`check-accepted` c-walker-only, `check-assume-have-discharge` both lanes) exhaust the 2,000,000-step budget through intra-unit/admitted Clojure call lowering overhead. Your recommended fix brings all 106 fixtures inside the existing 2,000,000 budget on both lanes. Fix exactly that: the call-lowering path for intra-unit/admitted calls so the avoidable per-call cost (the overhead your packet measured between the lowered forms and the direct application) is removed, WITHOUT weakening the guards the adoption and the H3-0 review pinned: admission for anything not statically intra-unit, the outcome-token machinery in tail position, operand evaluation order, and the H3-0 reconciliation's shape-based routing (entry-present callees to admission, entry-absent to invoke-value).

## Required verification (all with real output in your report)

1. The focused gate test: `clojure -M:test -v yang.latte-oracle-test/test-kernel-differential-gate` — after the fix, report the gate's own counts. Expected direction: the two blocked fixtures complete (gate counts drop the :blocked entries); if they pass, the corpus moves to 102 agreements + 2 deviations + 2 upstream failures; the `(:gate-status evidence)` assertion may STILL fail while any admitted arity remains unexercised — state which assertions fail and why.
2. The Clojure compiler suite: `bb test:sub:clj yang.base` — zero new failures vs master (122+ tests green at last landing).
3. The exhaustion test you add proves `:consumer v` lands in the ex-data (the carried consumer is the advanced VM).
4. clj-kondo on every touched file: 0 errors, no new warnings.
5. Both evaluator lanes agree wherever the harness checks agreement.

## Report

Findings + the exact diff + all command outputs, committed or beside the branch. The kimi quality gate re-reviews before landing.
