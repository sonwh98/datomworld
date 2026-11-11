Created-GMT: 2026-11-10 20:33:50 GMT
Created-Local: 2026-11-11 03:33:50 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat — yang.typescript orchestrator)
Session-ID: B1FBBBB5-52A0-42A2-BAFC-9742A463A51C
# Task: JS R1+R3 — the ts exception boundary (one round)
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: muse-spark-1.3-contributor | Assigned: 2026-11-11 03:33 +07 | Status: active | Rationale: the track's standing implementation lane; delivers on the laptop (shiva muse rounds wedge — measured twice)

Implement JS R1+R3 in the worktree `/Users/sto/workspace/datomworld-jsr13` (branch
`exception-js-r1r3`, cut from master `4cfa609d`). This is ONE round — do not attempt R3 alone.

## Read first (all paths absolute in the MAIN checkout; the worktree has no collab/)

- `/Users/sto/workspace/datomworld/collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md` — RULING OF RECORD v2. §R1 and §R3 are your requirements; §R5 and the scope bounds bind you; the acceptance gate shapes your tests.
- `/Users/sto/workspace/datomworld/collab/exception-track/1794970001000-to-tsphp-your-js-and-php-rounds.md` — the steward's relay spelling out the js defects (prelude.cljc:1581 entry never reads the raise exit; :1704 guest handler stack; :1627 protocol reception discards the condition into fresh text).
- `/Users/sto/workspace/datomworld/collab/exception-track/1794343000000-exception-track-surface-registry.md` — your ceded surface and the round rules.
- `/Users/sto/workspace/datomworld/docs/design/yang.antlr.md` §8.10, §8.10.7, §8.10.8 — the call convention, the exception reception rules, and the cleanup/restoration requirement.
- `/Users/sto/workspace/datomworld/test/yang/call/conformance.clj` + `/Users/sto/workspace/datomworld/test/yang/call/EXCEPTION_R0.md` — the round-0 kit (compose-exception, boundary-call-form, exception-outcome, exception-call, run-exception-program) and how to exercise it.

## The work (in the worktree's `typescript/antlr/prelude.cljc` unless named otherwise)

**R1 — the entry bridge.** The published function entry (~:1581) currently applies `:code`
directly and never reads the supplied `:yang.call/raise`; an escaping throw follows the
guest continuation handler stack (js.rt/ctx ~:1704) instead of the caller's supplied exit.
Change the entry to:
- install the bridge BEFORE argument binding;
- deliver every escaping source exception AND argument-binding failure to the supplied
  raise exit as the thrown condition `{:yang.call/reason :yang.call/thrown,
  :yang.call/value <original>}` — exactly once, with execution after the call unreachable;
- satisfy §8.10.8: complete cleanup and handler/depth/frame restoration BEFORE the
  transfer; a later generator resume never reuses a raise exit belonging to a completed call.
- closure entry and higher-order entry carry the same bridge (per ruling v2 R1 "every
  frontend's published function entry, closure entry, and higher-order entry").

**R3 — protocol failures retain and forward the condition.** Reception (~:1627) currently
builds fresh TypeErrors containing only selected text, discarding the condition. Map
`:yang.call/not-callable`, `:yang.call/invalid-invocation`, `:yang.call/unsupported-binding`
(fully qualified keys) to a documented local exception form that RETAINS the complete
original condition (qualified reason AND plain evidence), so an unchanged rethrow FORWARDS
the original condition without a new wrapper. js may deliver otherwise-foreign payloads raw
(ruling v2 R2 item 2 — this is js's allowed reception).

**R5 — no new shape-routing.** A returned condition-shaped map is ordinary data; shape is
never evidence of a condition to forward; forged or source-writable attributes never
authenticate a retained condition.

## Surface bounds (the registry cedes exactly this to you)

Edit ONLY: `typescript/antlr/prelude.cljc` (the entry-bridge region ~:1581, the
protocol-condition/reception paths ~:1627, and their immediate helpers), plus test files
under `test/yang/call/` and the existing ts source-call test namespaces as needed for new
fixtures. Do NOT touch any other file's exception regions (php, python, clojure.cljc), any
other ts prelude region without cause, or the six clojure R3 ledger rows (in flight on
`exception-r3-impl` — current master's ledger is your baseline, those rows are not yours).

## Tests and gates

- G0 iterate: focused namespaces only, on the laptop, e.g. `clojure -M:test -n
  yang.call.exception-contract-test` and the ts source-call namespaces
  (`-n <ns>`, single failing test `-v <ns>/<var>`). NEVER a suite, a subsystem set, or
  `bb test` — suite-scale runs belong to the orchestrator on the remote hosts.
- Exercise the kit per EXCEPTION_R0.md. Author RED fixtures first where the current
  behavior is wrong (TDD), then make them green.
- Acceptance targets: every ledger row your round covers flips from its `:accepted-runtime`
  red to green; the js source-call tests stay green; any ledger-row flip you did NOT author
  STOPS your round for diagnosis — report it, do not explain it away.
- Identity assertions BEFORE rendering: original reason, complete evidence, payload
  identity, exactly-once delivery, cleanup order. Equal rendered text is not identity evidence.

## Deliverables

- Commit your work on `exception-js-r1r3` (format: `fix(yang.typescript): ...`). Do NOT
  merge, do NOT push, do NOT touch master.
- Write your findings to
  `/Users/sto/workspace/datomworld/collab/yang-typescript/1794342830000-engineer-js-r1r3-exception.muse-spark-1.3-contributor.findings.md`
  starting with the same header fields (Created-GMT/Created-Local/Coding-Agent/Session-ID
  above), then: changed files with line ranges, exact commands run with outcomes and
  assertion counts, ledger rows flipped (row id → green), unrun checks, unresolved concerns.
- Preserve unrelated changes; never weaken a test to pass it; if a required dependency
  demands expanding the surface bounds, STOP and report instead of editing.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes, unresolved concerns, and any incomplete
work. Do not claim edits or tests that did not occur.
