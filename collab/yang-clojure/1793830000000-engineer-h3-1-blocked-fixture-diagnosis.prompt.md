Created-GMT: 2026-11-03 03:30:00 GMT
Created-Local: 2026-11-03 10:30:00 Asia/Ho_Chi_Minh
Coding-Agent: agy (Gemini 4 Argon High)
Session-ID: not-applicable (orchestrator-dispatched, fresh session)
# Task: H3-1 — diagnose the two blocked kernel-gate fixtures

Role: compiler/VM diagnostic engineer (agy)
Reviewer/landing: the ZCode glm orchestrator seat; the architect reviews the root-cause packet and any acceptance-budget change.

## Governing instructions

The hardening-3 plan (approved) authorizes this slice: diagnose the two blocked fixtures of the slice-9 kernel differential gate. Read `test/yang/latte_oracle/slice9_gate.md`, `test/yang/latte_oracle/slice9_evidence.edn` (the blocked rows), and the harness `test/yang/latte_oracle_test.clj` (`kernel-row`, `kernel-c`, `run-guest-machine`, the instruction budget at `:instruction-budget-per-lane 2000000`). Work in `/Users/sto/workspace/datomworld-h31`, branch `hardening3-h3-1`, base `0805a2535` (master). You MAY run shell commands and tests — that is why you are on this lane. Push the branch when done; do NOT merge.

## The state

The gate runs 106 fixtures: 100 pass, 2 are recorded deviations, 2 are recorded upstream failures, and **2 are blocked**:

- `check-accepted` — only the **c-walker** lane blocks; the c-semantic lane COMPLETES.
- `check-assume-have-discharge` — **both C lanes** block.

A and B complete for both. All three blocked C executions report "Guest instruction budget exhausted" at 2,000,000 steps. Blocked fixtures are not accepted negatives; the gate cannot close while they block.

## The diagnostic packet (the deliverable)

A four-cell comparison — each fixture on each C evaluator (walker/semantic) — containing:

1. Pinned source/compiler/runtime identities, instrumentation mode, configured budget, ACTUAL steps consumed, terminal reason, and elapsed time per cell.
2. Aggregate cost and phase boundaries: loading/setup versus fixture execution. Check specifically whether the observer runner resets its step counter across consumer invocations — do not assume the reported count is a whole-fixture total.
3. Bounded snapshots at exhaustion: control position, continuation depth, call frequencies, and the ordered tail of the executed-call/trace stream (bounded — no full dumps).
4. Instrumented versus uninstrumented results, including the completing semantic run of `check-accepted` (what does the semantic lane see that the walker does not?).
5. A minimal reproduction if the evidence shows repeated admission, invocation, or continuation activity (a loop, a re-entrant helper, an unbounded recursion the budget masks).

Collect diagnostics through the test harness's own facilities (the machine's streams, `:executed-calls`, continuation inspection). Do NOT recursively serialize host sessions or entire closure environments. Partial traces are diagnostic evidence only and contribute ZERO coverage.

## Budget trials (authorized, bounded)

Finite diagnostic trials at **2M, 4M, and 8M instructions**, stopping when sufficient evidence is obtained. These are diagnostic configurations, NOT an acceptance-budget change: before any budget raise is proposed, return measured completion costs, instrumentation overhead, progress evidence, and a proposed finite bound with justified headroom. Do not add retries that increase fuel until a fixture passes.

## Boundaries

- The fixtures, IDs, inputs, and expected A/B results are fixed; diagnostic reproductions supplement them.
- No changes to `yang.call`, `yin.vm`, or any `src/cljc/` source: if the evidence implicates the Clojure lowering, report the exact site and mechanism (a fix is a follow-up round after the architect reviews the packet); if it implicates the shared runtime, return the minimized reproduction for a ruling.
- No suppression of the blocked status, no accepted-negative claims, no coverage credit from diagnostic runs.

## Verification and report

Run the focused gate test (`clojure -M:test -v yang.latte-oracle-test/test-kernel-differential-gate`) before and after your diagnostic harness changes (if any) to show no regression: before = exactly 1 failure (the blocked assertion); after = the same, unless your packet explains a difference. clj-kondo on any touched file: 0 errors. The report goes in `docs/` beside the gate records or the round findings file — state the root-cause hypothesis ranked by evidence, the four-cell table, the trial results, and the recommended next round (harness fix / compiler fix / budget proposal / shared-runtime ruling request).
