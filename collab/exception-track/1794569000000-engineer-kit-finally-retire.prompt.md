Created-GMT: 2026-11-13 02:20:00 GMT
Created-Local: 2026-11-13 09:20:00 +07 (+0700)
Coding-Agent: qwen
Session-ID: 7f449035-53bf-4109-933a-25b49bce2fa8
# Task: engineer — KIT ROUND: retire the clojure finally-refusal per the owner's ruling
Role: Engineer (owner ruling RETIRE, 1794556000000 — implement it exactly; you are independent of the port's implementers; sol reviews after)
Implementers:
- Model: qwen3.8-max | Assigned: 2026-11-13 09:20 +0700 | Status: active | Rationale: non-codex implementation (sol holds the review), and you carry no port-authorship conflict.

## Worktree

You are running with cwd `/Users/sto/workspace/datomworld-kitfinally`, branch `exception-kit-finally` cut from current `origin/master`. Writes permitted there.

## The ruling to implement (read 1794556000000 DECIDED section)

RETIRE the `finally` refusal from the acceptance instrument:

1. `test/yang/call/exception_contract.clj`:
   - Remove the pair-matrix exclusion predicate at :162 (`:when (not (and (= scenario
     :finally-replacement) (= caller :clojure)))`) — clojure-caller finally-replacement legs
     become LIVE for the first time (clojure→python, clojure→php, clojure→typescript ×3 backends).
   - Convert the `:clojure-finally-refusal` obligation at :178: from asserting refusal to
     asserting SUPPORT (per the ledger's execution convention, `:execution :supports` shape or
     remove-and-let-the-new-pair-legs-carry-it; your choice, justify).
2. `test/yang/call/exception_source_test.clj:37-41`: `(try 1 (finally 2))` currently must THROW
   `clojure.lang.ExceptionInfo` — it must now COMPILE AND RUN, result 2, cleanup executed.
3. NO changes to the clojure frontend or any src/ file — the capability already landed with the
   port (pins f15423c2 prove finally-on-throw/ordering/nested).
4. Commit message MUST carry the supersession line for v2 §3:
   "owner ruling RETIRE (1794556000000): dialect surface derives from the source language; the
   LaTTe corpus verifies admissions and no longer authors them. v2 §3's finally-refusal clause is
   superseded."

## Verification (the first-time measurement matters)

The new clojure-caller finally-replacement pair legs have NEVER run anywhere. Run the kit on
shiva yourself via rverify? NO — suite-scale is the orchestrator's: locally run only
`clj -M:test -n yang.call.exception-source-test` and the contract test's SINGLE new legs if the
runner supports -v targeting (`clojure -M:test -n yang.call.exception-contract-test -v
yang.call.exception-contract-test/...pair...`) — check available deftest names first. If the new
legs RED, that is a FINDING (report it precisely — likely the §8.10.8 cleanup-before-transfer
interacting with foreign unwinding); never weaken a pair assertion to pass.

kondo on changed files 0/0. Commit + push `exception-kit-finally`.

End with Completed-GMT/Completed-Local and: **KIT RETIRED: <sha> | new-legs local result:
<green|FINDINGS>** or **BLOCKED: <what>**.
