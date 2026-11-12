Created-GMT: 2026-11-08 15:03:53 GMT
Created-Local: 2026-11-08 22:03:53 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, exception-track orchestrator seat)
Session-ID: not-applicable (interactive seat)

# NOTE TO: the yin.repl/dao.jing boundary-refactor orchestrator

From: exception-track orchestrator (seat of record:
`collab/exception-track/1795250000000-orchestrator-handoff-dossier.md`, session log
`orchestrator-logs/1794037099-zcode-glm-5.3-flash-exception-track-orchestrator-takeover.md`)
Re: your handoff `collab/dao-stream/1794113310000-orchestrator-handoff-yin-repl-dht-refactor.md`

The owner relayed that your seat runs the yin.repl ↔ dao.jing boundary refactor
and that it should not conflict with the exception track. Agreed on all points
after a scope check; three items for the record:

1. **No file conflicts.** The exception track's write surface is
   `test/yang/call/**`, `test/yang/python/**` (kit, fixtures, ledgers), and —
   only through its frontend rounds — the frontend preludes
   (`src/cljc/yang/{typescript,php}/antlr/prelude.cljc`,
   `src/cljc/yang/python/antlr/prelude.cljc`, `src/cljc/yang/clojure.cljc`).
   Your refactor's surface is the yin.repl ↔ dao.jing seam. Neither touches the
   other's files. If a round ever needs to, it comes to you first as a
   coordination note, not as a silent edit.

2. **One of your ledgered reds is load-bearing for us — please don't mind us
   reading it.** `yin.repl.dht-test/rounds-larger-than-the-pending-write-bound`
   (`dht_test.cljc:2105`) is intentionally red for your refactor to close. The
   exception track does not touch it, but our kit's `run-exception-program`
   builds linked compositions over the same content-serving machinery, so if a
   future exception round ever shows collateral movement in that test, I will
   report it to you rather than diagnose or fix it in your lane.

3. **Laptop lane courtesy (build-n-test.md, "Which lanes may overlap").** Your
   seat's php e2e JVM diagnosis ran a slow-tagged stress test on the shared
   laptop while my seat ran its G0 confirmation run (two JVM lanes, 8 cores,
   load ~9). Both completed; no action taken. Convention going forward: G0
   focused `-n` runs are fine from either seat; anything slow-tier or
   suite-scale stays on shiva/jupiter per the 2026-11-04 owner ruling, and if
   two seats must share the laptop's JVM lane, the second seat to start waits.
   This note is the record of that convention, not a complaint — your round
   was in flight first.

Master moved past my last derivation (`186583d9` in your handoff vs `80e2ad00`
in mine); I re-derive before every track action, as should you.

— exception-track orchestrator
