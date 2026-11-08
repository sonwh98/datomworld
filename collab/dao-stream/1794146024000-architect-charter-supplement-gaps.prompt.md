Created-GMT: 2026-11-08 02:0x GMT (real values below)
Coding-Agent: codex
Session-ID: 6d870a1d-2e73-483a-9ffe-1fe240f24d55

# Task: charter supplement — close four gaps so the yin.repl/dao.jing refactor can start; plus an ETA

Role: Lead System Architect (you authored the charter being supplemented)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-08 | Status: active

## Context

Your ruling (collab/dao-stream/1794062607000-architect-refactor-first-ruling-gpt6astra.findings.md)
is the approved charter for the yin.repl/dao.jing boundary refactor. Master is now
green except ledgered items (node+dart nightly 20261108-08 fully green; the dart
php-catalog family closed on master 186583d9), so the punt condition is lifted and
the owner is ready to start. The owner added a REQUIREMENT the charter must carry:
yin.repl depends ONLY on dao.jing — it must not know anything about dao.jing.dht —
so that any implementation of dao.jing can be swapped out. The charter governs the
extraction but does not yet define the swappable seam.

## Produce four artifacts (read-only; no code edits)

1. **Coupling inventory → dispositions.** Every dao.jing.dht touchpoint in
   src/cljc/yin/repl/** (including yin/repl/dht.cljc, repl.cljc:942/1355,
   query.cljc:66, index.cljc:369, main.cljc call sites, and any others your
   grep finds): for each, file:line, what it does, and its disposition —
   "moves to composition" / "becomes a dao.jing-generic call" / "dies".
   Be exhaustive; this is the refactor's checklist.
2. **The dao.jing-facing contract at signature level.** The engine's required
   capabilities as an explicit contract: which dao.jing entry points constitute
   the surface the engine may touch (put/fetch segments, submit publication,
   read-back verification, recovery install, …), with their actual signatures
   from the code, and the guarantee any dao.jing store implementation must
   satisfy. State what the composition adds on top (HEAD persistence, announce,
   repair, transport stepping) so the split is unambiguous.
3. **Two swappability acceptance gates.** (a) a grep gate: src/cljc/yin/repl/**
   contains zero references to dao.jing.dht (exact grep command to run in CI or
   by the orchestrator); (b) a runtime gate: the yin.repl test suite passes with
   a non-DHT store injected (mem/file-backed) — specify how the injection is
   done (fixture/composition change) and which test subset proves the swap.
4. **Adapter dispositions.** For each adapter your ruling flagged (linker
   progression, query services, rollback state, event filtering): its target
   interface on the composition side, in one to three lines each.

## Plus: an ETA

Estimate the refactor's duration in working days given YOUR OWN commit sequence
and the fleet reality (implementation rounds on muse/qwen-class models took
30-90 min each this session; astra review rounds 1-2h; the invariant fixtures
largely exist per your ruling; the relayed available fleet is agy/codex/muse/qwen).
Break the estimate down by your sequence's steps, state your confidence, and
name the step most likely to blow the estimate.

## Constraints

Read-only. Ground every artifact in the current code (master 186583d9). Begin
the final response exactly with Completed-GMT / Completed-Local.
