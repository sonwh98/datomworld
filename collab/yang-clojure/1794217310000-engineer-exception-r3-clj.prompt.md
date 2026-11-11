Created-GMT: 2026-11-09 09:41 GMT
Created-Local: 2026-11-09 16:41 +0700
Coding-Agent: agy
Session-ID: pending (provider-generated)
# Task: exception uniformity R3 — Clojure protocol failures retain and forward the complete condition
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: claude-opus-5-5-high | Assigned: 2026-11-09 16:41 +0700 | Status: failed | Rationale: RESOURCE_EXHAUSTED 429, no work done (agy Claude quota resets 2h23m; conversation f3c91f17-b805-4c26-a9a5-63e204a42935)
- Model: gemini-3.8-flash-high | Assigned: 2026-11-09 16:52 +0700 | Status: active | Rationale: owner directive — use gemini-3.8-flash, Claude models lack credits

Implement R3 of the exception-uniformity track in `/Users/sto/workspace/datomworld-r3-orch`
(branch `exception-r3-orch`, base `b1b9abae` — already checked out for you; do NOT create
another worktree or switch branches).

## Read first, in this order

1. **THE SPEC**: `collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md`
   — read §R3 and §R5 in full. R3 is the only rule in scope this round.
2. **THE REPAIR NOTICE (authoritative for file sites)**:
   `collab/yang-clojure/1794213768000-orchestrator-exception-r3-brief-repair.md` —
   every line citation is pinned against master `b1b9abae`. The older brief
   `collab/yang-clojure/1795040000000-engineer-exception-r3.prompt.md` has DRIFTED
   citations (pre-cc1) and an outdated "kit not on master" note; read it for intent only.
3. `src/cljc/yang/clojure.cljc` — the sites below.
4. `collab/exception-track/acceptance-ledger.md` — the acceptance instrument (Clojure rows).

## The defect (verified on `b1b9abae` by the orchestrator)

`condition->outcome` (clojure.cljc:1518–1530) is the single place where a caught condition
becomes an outcome packet. Its else branch carries the condition through
(`(outcome-node error-tag condition)`), but the `:yang.call/thrown` branch **unwraps to
`:yang.call/value`** — the qualified reason and every evidence field die. Entry-body's tail
path (1307–1310) then re-applies `yang.call/thrown` to an already-outcome value. Net
effect: an unchanged rethrow through the public escape reclassifies, and
`:yang.call/{not-callable, invalid-invocation, unsupported-binding}` do not survive.

## THE INVARIANTS, as a decision table (work through this row by row)

For a value `v` arriving at the raise exit, decide ONLY on the qualified
`:yang.call/reason` keyword read via the data accessor — never on the value's shape
(this is R5, and it is the whole of the difficulty):

| v has `:yang.call/reason` = | what it is | outcome packet must be |
| --- | --- | --- |
| `:yang.call/thrown` | a §8.10.7 condition whose payload is `:yang.call/value` | error carrying THAT condition (reason + value + any `:yang.call/data`) unchanged |
| `:yang.call/not-callable` | a guest protocol condition | error carrying THAT condition unchanged (reason + evidence) |
| `:yang.call/invalid-invocation` | a guest protocol condition | error carrying THAT condition unchanged |
| `:yang.call/unsupported-binding` | a guest protocol condition | error carrying THAT condition unchanged |
| absent / anything else | a guest `throw` payload (§8.10.7) | error `{:yang.call/reason :yang.call/thrown :yang.call/value v}` — a NEW wrapper around `v` |

Three traps that the tests will catch if you get them wrong:
1. A guest-thrown map that *happens to contain* `:yang.call/reason :yang.call/not-callable`
   is NOT a condition — it is a payload and must be wrapped in `:yang.call/thrown`.
   Distinguishing it is the job of the *caller* (who knew it came from `throw`), not of
   `condition->outcome`. If your design cannot tell these apart at the raise exit,
   STOP and report — do not invent a shape test.
2. Do not drop `:yang.call/data` / other evidence fields when forwarding. Whole map, unchanged.
3. Do not let the entry-body tail path (1307–1310) re-wrap a value that already
   carries a condition.

## R3's requirement

An unchanged rethrow forwards the COMPLETE original condition — fully qualified reason AND
plain evidence — with no new wrapper. The unconditional reclassification goes away or
becomes conditional on the absence of a retained condition.

Two raise-exit sources must both stay correct (this is the crux; the stood-down seat's
analysis in `git -C /Users/sto/workspace/datomworld-r3 diff` is available as REFERENCE —
read it, do not copy the worktree):

1. **Guest protocol machinery** (`yang.call/condition` with a qualified `:yang.call/reason`)
   — forwards UNCHANGED, every evidence entry intact, payload identity kept.
2. **A guest `throw`** (§8.10.7) — the escaping value is the PAYLOAD of a NEW
   `yang.call/thrown` condition. A thrown map that happens to spell `:yang.call/thrown`
   or look condition-shaped is still just a payload: its shape is never evidence
   (R5 — forged-retention / shape-throw rows).

## Boundaries (do not cross)

- **Do NOT touch the intra-unit token channel** — `:clj/escape-token` and `dsg__call_token`
  packet authentication at 1527–1531, 1548–1558, 1590–1602. That is R4's scope.
- **Do NOT touch the catch surface** (single catch, no `finally`) — preserved source semantics.
- **No yin.vm changes** unless a host primitive is genuinely required; if it is, STOP and
  report instead of editing outside your files.
- Files you may change: `src/cljc/yang/clojure.cljc` and `test/yang/clojure_test.clj`.

## Deliverables

1. The R3 fix in `yang.clojure.cljc`.
2. Regressions in `test/yang/clojure_test.clj`: (a) each of the three qualified reasons
   caught guest-side and rethrown unchanged retains reason + evidence; (b) the public escape
   forwards the complete condition; (c) a thrown condition-shaped map stays a payload
   (R5); (d) a forged/authenticating attribute does not make a value a condition (R5);
   (e) the existing rejection tests (single catch, no finally) stay green.
3. The focused set must stay green: the new regressions, the D2 pins, the attribution
   battery, the cell-ref pins. `kondo` 0 errors/0 warnings on touched files.

## Verification you must run (report REAL outputs)

```sh
cd /Users/sto/workspace/datomworld-r3-orch
eval "$(mise env -s bash)"          # mise is trusted already
clj -M:test -n yang.clojure-test     # the full home namespace
clj -M:kondo --lint src/cljc/yang/clojure.cljc test/yang/clojure_test.clj
```

Then the track's acceptance instrument (this is REQUIRED this round — the kit IS on master):

```sh
clj -M:test -n yang.call.exception-contract-test -n yang.python.antlr.exception-call-test
```

Baseline of record: **10 tests / 279 assertions, 159 failures / 39 errors** (= 81 passes).
Note: the orchestrator observed a drift to 150F/63E on a clean master run and is still
diagnosing it — if your run differs from 159/39, report your exact numbers and which
rows changed; do not try to "fix" the drift.

The rows your round must flip red→green (Clojure only):
`:clojure/protocol-{not-callable, invalid-invocation, unsupported-binding}` and
`:clojure/saved-protocol-{not-callable, invalid-invocation, unsupported-binding}`.
Do NOT touch `:clojure/shape-*` (already green) or any Clojure→Python pair row (R2, another round).

## Rules

- **FOREGROUND only** — run every test foreground with an explicit timeout. Never background
  test lanes; a backgrounded wait that expects a notification idles the session.
- Work only in the named files. Preserve unrelated changes. Do not weaken tests.
- Commit on `exception-r3-orch` and push. **Do NOT merge.**
- Inspect your final diff before reporting.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes, the acceptance-run numbers and row
deltas, unresolved concerns, and any incomplete work. Do not claim edits or tests that
did not occur.
