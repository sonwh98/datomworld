Created-GMT: 2026-11-22 15:34:40 GMT
Created-Local: 2026-11-22 22:34:40 ICT (+07)
Coding-Agent: interactive (ZCode / glm-5.3-flash, orchestrator seat)
Session-ID: pending (codex provider-generated)

# Task: store-swap Slice 1 — engine quiescence and mem ↔ file:<dir> swap

Role: VM / Storage Engineer (implementation)

Implementers:
- Model: gpt-6.2-sol | Assigned: 2026-11-22 22:34 ICT | Status: active | Rationale: complex multi-file implementation per the brief's routing (codex implements); codex primary per routing-status 2026-11-22

## Scope: implement Slice 1 EXACTLY as designed

The design decision record is
collab/yin-repl/1795360785000-architect-store-swap-design.gemini-4-argon-high.findings.md
(copy in this worktree; the canonical is in the main checkout). Read it first —
it is the contract. Your slice is "Slice 1: Engine Quiescence & mem ↔
file:<dir> Store Swap" (its "What Changes Where" items 1–4 and the
test row). Do NOT implement Slice 2 (DHT composition swap) or Slice 3
(Gambit) in this round.

The originating feature brief is
collab/yin-repl/1795224000000-brief-in-session-store-switch.md (also copied here).

## Boundaries

- Work ONLY in this worktree (/Users/sto/workspace/datomworld-store-swap), branch store-swap-p1.
- Files you may change: src/cljc/yin/repl/store.cljc, src/cljc/yin/repl.cljc,
  src/cljc/yin/repl/adapter.cljc, src/cljc/yin/repl/serve.cljc,
  src/cljc/yin/repl/driver.cljc, src/cljc/yin/repl/main.cljc, and tests under
  test/yin/repl/. Nothing else.
- GATE A is binding: zero occurrences of dao.jing.dht, dao.space.dht,
  yin.repl.dht, yin.vm.linker.(dht|head), :dht, or --dht- inside
  src/cljc/yin/repl/ and src/cljc/yin/repl.cljc. The driver/main swap hook
  must stay plain-data (composition hooks), no DHT names.
- Do not commit. Leave changes uncommitted for verification and review.

## TDD

Tests first where feasible: extend test/yin/repl/store_test.cljc (or the
appropriate existing test namespaces) with the slice-1 rows from the design's
"Extended Verification Bar" items 1 (the mem→file→mem round-trip, refusals
when not quiesced, failed-swap controls) and 4 (remote --port isolation),
adapted to what is testable in-process. Then implement to green.

## Verification (run these, report exact commands and outcomes)

- clojure -M:test -n yin.repl.store-test (and any other namespace you add tests to, individually)
- clj -M:kondo --lint src/cljc/yin/repl.cljc src/cljc/yin/repl/
- Gate A audit: rg the forbidden patterns over src/cljc/yin/repl/ and src/cljc/yin/repl.cljc — zero matches.
- Do NOT run suites; do not run bb test. Individual namespaces only (owner ruling 2026-11-01). Foreground only, explicit timeouts; never background a lane.

## Deliverable

Write your report to
collab/yin-repl/1795361680000-engineer-store-swap-p1.gpt-6.2-sol.findings.md
in this worktree: what you changed and why (file:line), the exact verification
commands with their outcomes (test counts, assertion counts), any deviation
from the design record with the reason, and any open questions. If the sandbox
blocks that write, stage the file at the worktree root with the same name and
say so in your final response.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: files changed, verification outcomes, deviations, findings path.
