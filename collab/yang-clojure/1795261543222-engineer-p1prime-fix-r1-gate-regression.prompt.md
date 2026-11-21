Created-GMT: 2026-11-21 12:25:43 GMT
Created-Local: 2026-11-21 19:25:43 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated; fresh session — resume-by-state from prior round 01a1fd83-d6c4-7f10-bb28-9c5347b6dcb1)

# Task: yang.preludes P1′ fix r1 — landing-gate regression (h31 wedge budget exhaustion on :semantic)

Role: Engineer

Implementers:
- Model: gpt-6.2-sol | Assigned: 2026-11-21 19:25 +07 | Status: active | Rationale: same model as the implementing round; fresh session because codex 0.170 `exec resume` mounts read-only (jupiter lesson of record) — resume by state from the worktree, which is intact

## Context (resume-by-state)

Your prior round implemented P1′ in `/Users/sto/workspace/dw-p1prime` (branch `feat/p1prime-yrt-effect`), committed locally as `f954d5ec` and pushed. All your prior gates passed and the independent review APPROVED it. The commit is intact; nothing was reverted. This is a follow-up defect fix, not a redo.

## The regression (verified by the orchestrator)

The jupiter landing gate failed on `bb test:changed`: `yang.python.antlr`-adjacent fixture **`test-h31-wedge-min-repro`** in namespace **`d2-wedge-min-repro`** errors. Orchestrator-verified locally:

- master `f23728d3`: `clj -M:test -n d2-wedge-min-repro` → 1 test / 6 assertions / 0 failures.
- P1′ `f954d5ec`: `clj -M:test -n d2-wedge-min-repro` → 1 test / 1 assertion / **1 error**:

```text
ERROR in (test-h31-wedge-min-repro) (observer.cljc:212)
Uncaught exception, not in assertion.
actual: clojure.lang.ExceptionInfo: Guest instruction budget exhausted
{:lane :semantic, :steps 2000000, ...}
```

The failure env dump shows the new P1′ machinery in a loop: repeated `:return` frames at `pc 2073`/`pc 1741` with `r {:type :continuation}`, `%_ {:handlers [:handler {:type :continuation} nil 1 0] ...}`, `ctx-cell`, `dsg__*` closures — i.e. the `yrt.effect` handle/perform resumption path appears to be re-entering/re-capturing instead of making progress, exhausting the 2,000,000-step budget on the `:semantic` lane. The test's own output (`:ARM-A {:steps 0, :halted true ...}`) and the wedge-repro harness are in `test/d2/wedge_min_repro*` — read the fixture first to understand what property it protects (it is an H31 wedge-min regression fixture: keep its intent intact; do not weaken its assertions or raise budgets to mask the loop).

## Task

1. Diagnose and fix the regression in `/Users/sto/workspace/dw-p1prime` (branch `feat/p1prime-yrt-effect`, currently at `f954d5ec` with a clean tree — amend nothing, no commits; leave changes uncommitted).
2. Likely suspect is `yrt.effect`'s `perform`/`handle` continuation hand-off interacting with the semantic VM's continuation representation (the stacked `[:handler {:type :continuation} ...]` frames and repeated pc 2073 entries suggest the handler is being re-entered with the same continuation each time). But verify, don't assume — read `src/cljc/yang/rt/effect.cljc`, the semantic lane's `%capture`/continuation handling, and compare with how `:walker`/`:stack`/`:register` lanes treat it (the walker lane passed; check whether the test's semantic-lane run differs in mechanism).
3. Re-verify at minimum:
   - `clj -M:test -n d2-wedge-min-repro` (must match master's green: 1 test / 6 assertions / 0 failures)
   - `clj -M:test -n yang.rt.effect-test -n yang.clojure-callsite-test` (must stay green — 54 tests / 674 assertions baseline)
   - `clj -M:test -n yang.latte-oracle-test` (focused LaTTe fixtures must stay green)
   - `clj ./bin/analyze-deps.clj` clean
4. `git add -N` any new files. Do NOT commit.

## Deliverable

Final report listing: root cause (with file:line), the fix, each verification command with counts, and confirmation that the wedge fixture's assertions/budgets are unchanged. Your working tree is `/Users/sto/workspace/dw-p1prime`.
