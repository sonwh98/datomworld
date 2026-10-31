Created-GMT: 2026-09-20 08:11:00 GMT
Created-Local: 2026-09-20 15:11:00 +07 (Indochina Time)
Session-ID: 4b1292c3-fe79-43fa-b169-4e6b860cd9b6 (resumed — your W2 session)
# Task: dao.stream.waitset Phases W3+W4 — cadence containers and consumer adoption

W2 is committed on this branch (`45bfcc16`). Your unit is the FINAL build
phase: W3 (cadence) + W4 (adoption), per the revised plan's sections
"Phase W3 — Cadence containers" and "Phase W4 — Consumer adoption" — read
both VERBATIM, they are prescriptive. Also re-read "Decisions" (D-items on
nudges and the pure cadence-step) and the consensus report in
`collab/1789842300000-architect-dao-stream-waitset-consensus-report.md`
(Dispute A: no external parks, pure cadence-step, contentless wake tokens;
Dispute B as closed in R4).

W3 build:
- `src/cljc/dao/stream/waitset/cadence.cljc` — the pure layer:
  `(cadence-step cadence-state moved?) -> {:cadence-state ... :sleep-ms ...}`
  over `{:poll-ms :backoff {:factor :ceiling-ms}}`; wake resets to
  `:poll-ms`, idle climbs to the ceiling. Never calls `check`, reads no
  clock. Tested on all three hosts with no fixture.
- Three host wake-sources, each docstring-labelled *host policy*, holding
  NO interpreter state: `src/clj/dao/stream/waitset/driver.clj`
  (LinkedBlockingQueue of contentless tokens; `sleep!` polls with timeout
  and drains surplus; `nudge!` offers one), `src/cljs/dao/stream/waitset/driver.cljs`
  and `src/cljd/dao/stream/waitset/driver.cljd` (arm exactly one timer at
  `:sleep-ms`, cancel-and-rearm, Node timers unrefed, firing calls the
  composition root's own tick; `nudge!` cancels + schedules the tick once
  on a microtask with a pending flag).
- NO `make-driver` container, no atom holding a waitset, no `:budget`, no
  external-entry submission, no `run-loop!` that dispatches results.

W4 build — the adoption table in the plan is authoritative. The engine row
is DONE (W2). Your unit: the `yin.repl.serve` per-session probe-or-host-
cadence assignment (one active waiter per session, probe retired on
terminal, pending-response never becomes a `:put` entry), the `yin.repl`
host shell tick owners' cadence adoption (fixed 25 ms → cadence-step +
sleep!/arm!; read-line handlers and `request-stop!` become
composition-wired `nudge!` callers; `moved?` counts any outstanding
pending write), the serving row's tick-owner cadence adoption, and the
`dao.jing.remote` daemon's optional sleep! swap. The ws ack sweep, the GUI
pump, `rpc/poll!`, `observe/step` consumers and all single-stream rows
stay UNCHANGED. The deletion rules are the acceptance criteria: every
adoption deletes the unconditional poll or fixed timer it replaces; an
adoption that deletes nothing is reverted.

Tests: W3's list verbatim (parked set polled with no nudges; nudge beats
the timer; repeated nudges coalesce; no re-entry from inside a tick; idle
owner at the backoff ceiling; wake resets the curve; composition-wrapped
deposit nudges), plus W4's per-site verification and the unchanged rows
asserted unchanged.

Verification, single simple commands: `clojure -M:test -n
dao.stream.waitset.cadence-test` then `clojure -M:test` (full, 1541+ to
beat) then the CLJS lane (mise Java 21 workaround). The orchestrator runs
CLJD (including your new cljd driver) and the cross-host peers. No
staging, no commit.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: what you built per phase, the adoption table as implemented,
test counts, exact lane outcomes, and anything unresolved.
