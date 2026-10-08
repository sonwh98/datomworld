Created-GMT: 2026-10-07 13:00:00 GMT
Created-Local: 2026-10-07 20:00:00 +0700
Coding-Agent: gpt-6.1-sol (codex, session 01a10e41-8c96-7a82-838e-67ea10b58bb1)
Session-ID: 01a10e41-8c96-7a82-838e-67ea10b58bb1

# Task: UCF M-next D15a — the holder-driver control/program split
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-07 20:00:00 +0700 | Status: active | Rationale: the driver's author through D13/D14; the split is over your own code

Implement D15a in /Users/sto/workspace/datomworld-d10b (worktree, branch
ucf-d15a-driver-split off master 49960017; D15's composition dispatches
on a fresh branch after you land). Read first: the ruling
(collab/1791370000000-architect-d15-driver-seam-ruling-astra
.gpt-6-astra.findings.md — THE contract; machine-facing shapes,
the drain-splitting rule, the accept-tail split, the four public
contracts, D15a's ten pinning rows), and src/cljc/yin/vm/ucf/holder/
driver.cljc (your own D13/D14 code: public step :2134, step-active
:1944, run-cycle :1890, renew :1781, drain-control :1710, the
drain :1804-1836 combining custody replies with writer/discharge and
reader/settle, accept :1578-1597, step-releasing :2073).

The contract (the ruling, binding):

Four public functions over the existing driver state, retaining its
:phase/:status/:detail/:machine conventions, adding no wire fields:

- `driver/control-step [state]` — custody protocol progress only:
  reads authenticated control replies and complete ledger history,
  validates binding and tenure, renews, retries existing requests,
  publishes control diagnostics, advances release/report/offer
  acknowledgment and closure handling. NEVER: vm/run, program replay,
  writer/emit, live reader observation, program stream operations,
  program resource attachment, lowering a newly granted checkpoint,
  export preparation's program-side acquisitions, or applying program
  outcomes in a way that executes a continuation.
- `driver/program-step [state]` — checkpoint activation and program
  advancement: validation/restoration, pending program-result
  application, execution, replay, emission, live observation, and
  source/export preparation. Rechecks tenure before execution and IO.
- `driver/stop [state]` — the shutdown semantics: control keeps
  progressing during the bounded drain; program never once :running?
  is false; a grant arriving after stop is authenticated and released
  without lower.
- `driver/owed-control-write? [state]` — boolean, for moved? cadence.

The drain-splitting rule (the ruling's hardest point): the current
drain combines custody replies with writer/discharge and reader/settle
(:1804-1836). Route custody replies immediately; retain program
replies/outcomes for the program step, preserving attribution and
arrival order. Buffering must not introduce a crash-loss window:
recovery must reproduce an unconsumed observation from its durable
source or a durable retained record before advancing an unrecoverable
reader position.

The accept-tail split: grant observation does not authorize execution
— accept and retain authenticated grant evidence without lowering or
activating; the original tenure basis must not be refreshed when
lowering eventually occurs.

D15a's ten pinning rows (all three lanes) — from the ruling, verbatim:
1. Control isolation; 2. Mixed inbox with restart before application;
3. Grant while paused (resume before expiry / after expiry refuses +
cleanup); 4. Tenure changes between steps; 5. Shutdown race; 6.
Full-stream drain; 7. Journal uncertainty at each newly split bracket;
8. Exit continuity; 9. Cadence per owed request family; 10.
Compatibility — existing driver acceptance and recovery tests remain
valid through step; split scheduling produces the same program effects
and custody outcomes without duplicate cycles.

Acceptance criteria:
- Test-first per row; portable .cljc; all three lanes.
- Permitted diff: src/cljc/yin/vm/ucf/holder/driver.cljc, its test
  file, a small holder-internal helper namespace if separation needs
  it, and the relevant contract documentation. Widening engine,
  authority or wire contracts requires a further ruling — stop and
  report instead.
- The existing public step remains valid (row 10); the D14 journal
  contract and the uncertain-append stalls are preserved at every
  newly split bracket.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red
and green evidence, unresolved concerns, and any incomplete work. Do
not claim edits or tests that did not occur.
