Created-GMT: 2026-11-13 23:15:00 GMT
Created-Local: 2026-11-14 06:15:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 4f407e96-0ab5-418f-815e-2203561b2ae9 (capture thread.started)
# Task: engineer — corpus round: simple try/finally oracle fixtures (any Clojure code, not LaTTe-specific)
Role: Engineer
Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-11-14 06:15 +0700 | Status: active

## The task

The port admits `try/finally` in yang.clojure. The unit tests pin the mechanism. The oracle
verifies the compiled output matches the JVM host. Write simple Clojure test cases that
exercise `try/finally`, compile through yang.clojure, run through the oracle, and compare
against the JVM host.

NOT LaTTe proof terms — any ordinary Clojure code with try/finally:

1. `(try (body) (finally (cleanup)))` — cleanup runs, result passes through
2. `(try (throw (ex-info "..." {...})) (catch Exception e (finally (cleanup))))` — cleanup on caught path
3. `(try (body) (finally (cleanup)))` where body throws uncaught — cleanup still runs
4. `(try 1 (finally 2))` — result 1, cleanup runs, `finally`'s return value discarded
5. Nested: `(try (try (body) (finally (inner))) (finally (outer)))`
6. `finally` + `recur` inside a loop

Each case: compile through yang.clojure, run through the LaTTe oracle comparison harness
(oracle vs JVM host), verify the outputs match. Add to the existing oracle/gate corpus.

## Worktree

`/Users/sto/workspace/datomworld-r3-orch` (launch cwd), branch `exception-r3-impl`
(or a new branch cut from current master — your choice). Files: the oracle test namespace
(`test/yang/latte_oracle_test.clj` or wherever the oracle comparison harness lives).

## Constraints

No VM files; no kit contract-file changes; no weakening existing assertions. Focused runs
locally are fine. NO suite-scale or contract test locally (shiva-only). Commit + push.

End with Completed-GMT/Completed-Local and: **CORPUS LANDED: <sha> | fixtures: <count> |
oracle comparison: <all green / N failures>** or **BLOCKED: <what>**.
