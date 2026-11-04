Created-GMT: 2026-11-03 16:20:00 GMT
Created-Local: 2026-11-03 23:20:00 Asia/Ho_Chi_Minh
Coding-Agent: glm (glm-5.3); codex flagged the round content mid-flight (filter false-positive)
Session-ID: not-applicable (orchestrator-dispatched, fresh session)
# Task: H3-1 wedge diagnostic — locate the exact non-terminating loop in the encoder session layer

Role: diagnostic engineer (glm). NOTE: a kimi code-quality review follows before landing — write for review.
Reviewer/landing: the ZCode glm orchestrator seat; the architect rules on any yin.vm implication (session_12e44768 holds the full ruling history).

## Context

Worktree `/Users/sto/workspace/datomworld-h31`, branch `hardening3-h3-1`, HEAD `8ea857c1`. Read in order:
1. `test/d2_wedge_probe.clj` — the diagnostic probe the previous round built (instrumented vm/step, watchdog, the evaluator-session driver that COMPLETES).
2. `test/yang/clojure_test.clj` `run-guest-machine` (~line 1982) — the semantic branch that WEDGES (tu/make-encoder-session + encoder/forward-on-stream).
3. `src/cljc/dao/stream/observer.cljc` `run-on-stream` (~line 219) and `src/cljc/yin/vm/encoder.cljc` `forward-on-stream`.
4. `docs/agents/known-gaps.md` if context helps; `collab/1794040000000-architect-h3-1-continuation-ruling.kimi-k3.1-pro.stdout.log` — the architect's requirements for THIS diagnostic.

## The measured state (do not re-derive; verify where cheap)

- The D2-compiled `check-assume-have-discharge` fixture's semantic cell, driven through the **evaluator session directly** (observer/run-on-stream over semantic/create-vm), TERMINATES: halted, well under 2M steps (probe committed at 8ea857c1).
- The SAME fixture's semantic cell through `kernel-c` → `run-guest-machine`'s semantic branch (**tu/make-encoder-session + encoder/forward-on-stream**) WEDGES: two 2400s runs stalled mid-fixture; a 5400s trial never returned.
- Therefore the wedge lives in the encoder session layer — encoder/forward-on-stream's run loop, its budget-exhaustion interplay, or the make-encoder-session machinery. NOT in the semantic evaluator's stepping, NOT in D2's record construction at the evaluator level.
- One suspicious prior finding: the H3-1 packet flagged that `run-bounded`'s step counter resets per `(run consumer)` invocation (observer.cljc's loop calls `run` once per loaded batch) — each invocation gets a fresh 2M budget. If the encoder layer's `run` re-enters the bounded loop without progress across invocations, the round never terminates while every chunk looks in-budget.

## Your job (per the ruling's steps 3-4)

1. Instrument `encoder/forward-on-stream`'s run loop (a probe file, like the existing one — NOT a yin.vm edit) to count: batches observed, runs entered, steps per run, and whether the machine advances between runs. The existing probe pattern (`test/d2_wedge_probe.clj`, instrumented vm/step + watchdog) is your template.
2. Locate the exact non-terminating cycle: which loop re-enters, what state repeats, and whether the budget-exhaustion throw (ex-info with `:consumer v` in its data) is caught by `carry-session` and fed back into the loop instead of propagating — the `consumer-left-by-run` recovery path is a candidate.
3. Produce the **minimized reproduction**: the smallest program + harness invocation that wedges, as a standalone file.
4. Capture (bounded — no closure serialization): the advanced `:consumer`'s halted/blocked state, continuation depth, and the control node at the top frame at wedge time.

## Boundaries

- Diagnostic-only. NO changes to `src/cljc/**` (yin.vm, yang.call, the compilers) — a shared-runtime change requires the minimized reproduction to return for an explicit architectural ruling first.
- The fixture, its inputs, and the expected A/B results are fixed. Diagnostic runs contribute ZERO coverage.
- Test-side harness changes are allowed ONLY in a new probe file; `test/yang/latte_oracle_test.clj` and `test/yang/clojure_test.clj` stay untouched this round.

## Deliverable

A probe file + a findings report (collab/, or noted in the branch commit message if the pre-commit hook rejects flat files) covering: the exact cycle located (with line references), the minimized reproduction, the captured state, and your recommendation — harness fix (which line), or a yin.vm architectural ruling request. Commit on the branch; push; do not merge. The kimi quality gate reviews before the landing decision.
