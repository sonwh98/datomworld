Created-GMT: 2026-11-22 15:19:45 GMT
Created-Local: 2026-11-22 22:19:45 ICT (+07)
Coding-Agent: interactive (ZCode / glm-5.3-flash, orchestrator seat)
Session-ID: pending (agy provider-generated)

# Task: in-session dao.jing store switching for yin.repl — Architect design round

Role: Lead System Architect

Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-22 22:19 ICT | Status: active | Rationale: architecture/design decisions with real fork-in-the-road choices; argon is the architecture strength per team.md; flat subscription

This is a DESIGN round, not an implementation round. You are read-only: do not
edit any file. Produce a design decision record as your deliverable.

## Feature (owner request)

`--index-store` selects the dao.jing implementation at launch (mem, file:<dir>,
dht:) and the handle is fixed for the session lifetime today. The owner wants
the implementation changeable INSIDE a running REPL: start on `mem`, then
switch to `file:<dir>` or `dht:` (or between durable stores) without exiting.

## Read first

- The dispatching brief: collab/yin-repl/1795224000000-brief-in-session-store-switch.md
  (it names the five design decisions and their options — adjudicate each)
- docs/design/datom.world.md (axioms, invariants)
- src/cljc/yin/repl/store.cljc (whole file, ~190 lines: parse-arg :55,
  checked-spec :109, open :140, retain-on-reset? policy :164-182, close! :182)
- src/cljc/yin/repl/main.cljc and src/cljc/yin/shell/store.cljc (composition)
- docs/design/yin.repl.dht-composition.md and src/cljc/yin/shell/dht.cljc (the dht: join)
- host/gambit/yin/jolt/gambit/yin.jolt (Gambit capability map: mem only)
- The C4 ruling: collab/jolt/1795040000000-reviewer-p1a-confirm-codex.gpt-6.1-sol.findings.md
- The P1e honest-degradation pattern:
  collab/jolt/1795213000000-engineer-p1e-dht-gambit-probe.gpt-6.2-sol.findings.md

## Decisions to adjudicate (options named in the brief; none pre-chosen)

1. **Swap semantics for the live VM**: (a) quiesce — require empty/reset session
   before swap; (b) migrate — carry the index across (needs a defined copy
   protocol and failure semantics per direction); (c) hybrid — user chooses
   per swap. State which you select and why, in terms of the repo's invariants.
2. **Ordering**: open-first (new store fully opens before the old closes; on
   open failure the old store stays and the session survives — the P1e lesson)
   vs close-first. Adjudicate explicitly.
3. **The swap's meaning for `dht:`**: joining a ring is network identity
   (principal, follow, publish flags), not just storage. Swap INTO dht: needs
   join parameters; swap OUT means leaving the ring. Define the semantics.
4. **Command surface**: `(use-store ...)` at the yin> prompt vs launcher-level
   vs both; argument grammar; interaction with `state.edn` resume.
5. **Gambit boundary (owner ruling C4, 2026-11-19)**: blocking FFI refuses on
   Gambit BY DESIGN; durable open path is C4-blocking-refused. The swap command
   on Gambit must degrade honestly (mem→mem only; durable targets refuse with
   the documented C4 message and the session survives). Do NOT qualify blocking
   file ops to make the swap work.

## Deliverable

Write your findings to:
collab/yin-repl/1795360785000-architect-store-swap-design.gemini-4-argon-high.findings.md

If the sandbox blocks that write, stage the file at the repository root under
the same name and say so in your final response.

The findings file must contain, for each of the five decisions: the chosen
option, the rationale grounded in the repo's invariants and existing code, the
failure story, and what changes where (expected files:
src/cljc/yin/repl/{store.cljc,main.cljc}, src/cljc/yin/shell/store.cljc,
host/gambit/yin/jolt/gambit/yin.jolt + Gambit overlays, test/ rows per backend,
src/cljc/yin/vm/docs/yin.repl.md). End with: a slice plan suitable for
implementation dispatches, an extended verification bar (the brief suggests:
swap mem→file→mem on JVM/Node/Chez; failed-swap control; Gambit refusal-then-
survive; tri-host landing gate), and any risks or open questions for the owner.

Do not edit any source file. Do not run test suites.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then summarize: each decision's verdict in one line, the slice plan, and the
findings file path.
