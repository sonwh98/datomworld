Created-GMT: 2026-11-22 16:10:00 GMT
Created-Local: 2026-11-22 23:10:00 ICT (+07)
Coding-Agent: interactive (ZCode / glm-5.3-flash, orchestrator seat)
Session-ID: 01a203e5-cbc1-7352-a82a-c38258633577 (resume of your Slice 1 thread)

# Task: store-swap Slice 2 — DHT composition store swap

Role: VM / Storage Engineer (implementation)

Implementers:
- Model: gpt-6.2-sol | Assigned: 2026-11-22 23:10 ICT | Status: active | Rationale: same track, your Slice 1 was APPROVED by adversarial review with zero P0-P2; design continuity in this thread

## Scope: implement Slice 2 EXACTLY as designed

The design contract is
collab/yin-repl/1795360785000-architect-store-swap-design.gemini-4-argon-high.findings.md
(in this worktree): Slice 2 = "DHT Composition Store Swap (yin.shell.*)". Your
Slice 1 work is on disk in this worktree (uncommitted, review-approved). The
reviewer's gate record is
collab/yin-repl/1795364000000-reviewer-store-swap-p1-gate.gemini-4-argon-high.findings.md
(APPROVED; its two P3 notes require no action).

Deliverables per the design record:
- src/cljc/yin/shell/cli.cljc: parse-store-command (keyword option
  normalization through the existing parse-peer/parse-principal/parse-trust/
  parse-follow/parse-token/load-key/dht-spec functions; refuse :manifest with
  the (dao.space.dht/load-index ...) hint; refuse DHT options on non-dht:
  targets; refuse trailing opts on mem/file targets), wired into (hooks).
- src/cljc/yin/shell/dht.cljc: synchronous :bind-failed detection in open
  (check the node's traffic ring after dht/join when peers is non-empty; on
  bind-failed close the node and throw the exact ex-info refusal) so
  open-first aborts before the old store is touched.
- Driver wiring in src/cljc/yin/repl/main.cljc ONLY as needed to consume
  :parse-store-command (:spec :opts :label) from the composition — you own
  minimal touch-ups there since you wrote the hook; stay within Gate A.
- Tests: in-session swap rows in the DHT test namespace(s) under test/
  (mem → dht:<dir> solo and with :peers/:publish/:key/:join; dht:<dir> → mem
  ring departure with resource release; failed dht: swap controls leaving the
  old store intact) per the design's Extended Verification Bar item 2, plus
  parser rows for parse-store-command including the :manifest refusal.

Do NOT implement Slice 3 (Gambit) or Slice 4 (docs) in this round.

## Boundaries

- Work ONLY in /Users/sto/workspace/datomworld-store-swap, branch store-swap-p1.
- Files you may change: src/cljc/yin/shell/cli.cljc, src/cljc/yin/shell/dht.cljc,
  src/cljc/yin/shell/store.cljc (only if strictly needed), src/cljc/yin/repl/main.cljc
  (minimal hook touch-ups), and tests under test/. Nothing else.
- GATE A binding: zero NEW dht references in src/cljc/yin/repl/ and
  src/cljc/yin/repl.cljc (pre-existing docstring citations of the design file
  are out of scope). Gate B per docs/design/yin.repl.dht-composition.md §10.
- Do not commit. Leave changes uncommitted.

## TDD & Verification (foreground, explicit timeouts; individual namespaces only — never suites)

Tests first for the parser and the swap rows, then implement to green.
Report exact commands and outcomes for:
- clojure -M:test -n <each namespace you touched>  (JVM)
- the same namespaces on Node (shadow compile test --config-merge ns-regexp, Java 21 on PATH)
- clj -M:kondo --lint on every file you edited
- Gate A audit (same rg as slice 1) — zero NEW matches
- Gate B audit per the design doc

## Deliverable

Write your report to
collab/yin-repl/1795364600000-engineer-store-swap-p2.gpt-6.2-sol.findings.md
in this worktree: changes (file:line), exact verification commands with
outcomes, deviations with reasons, open questions. If the sandbox blocks the
write, stage at the worktree root under the same name and say so.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: files changed, verification outcomes, deviations, findings path.
