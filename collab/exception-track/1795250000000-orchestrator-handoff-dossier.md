Created-GMT: 2026-11-07 07:0x GMT
Created-Local: 2026-11-07 14:0x +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.python orchestrator seat — OUTGOING steward)

# HANDOFF DOSSIER — the exception track: read this first, then rule v2

You are the incoming orchestrator of the exception track. The owner created this seat so the track has a dedicated, referee-only orchestrator: you own the acceptance ledger, the sequencing, the pair-conformance declarations, and all cross-seat coordination. You are NOT a frontend participant — your seat writes no frontend code. The acceptance instrument is the kit (the failing fixtures); your judgment organizes and declares, it does not overrule what the fixtures say.

Read, in order:
1. `collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md` — the OWNER RULING OF RECORD (the track's constitution: R1-R5, scope bounds, acceptance gate, sequencing).
2. `collab/yang-python/1794900000000-reviewer-exception-uniformity-audit.gpt-6.1-sol.stdout.log` (last agent message) — the baseline audit (verdict DIVERGENT, HEAD e9a0b430).
3. The three review verdicts (all ACCEPT WITH AMENDMENTS, folded into v2): `1794930000000-architect-exception-ruling-review.gpt-6-astra.stdout.log`, `1794930000000-engineer-exception-ruling-review.gpt-6.1-sol.stdout.log`, `1794930000000-engineer-exception-ruling-review.gemini-3.8-flash.stdout.log` — all in this folder.
4. `1795200000000-steward-r0-kit-landed.md` — the kit landing notice.

## Current state (as of this dossier)

- **Round 0 LANDED** on master (`0fa3ff7a`, branch `exception-r0`): the retained-condition provenance fixtures (93 measured rows, all agreeing across semantic/stack/register), the kit API (`conformance.clj:310`: `compose-exception`, `boundary-call-form`, `exception-outcome`, `exception-call`, `run-exception-program`), the red ledger (`test/yang/call/exception_r0_results.edn` + `test/yang/call/EXCEPTION_R0.md`), and the `^:slow` gate (the red-ledger deftests are skipped by the fast lanes and the nightly; the track exercises them via `clj -M:test -n <ns>`).
- **The ledger seed** (per-frontend open gates): JavaScript — R1, R3 runtime repairs open; R1, R2, R3, R5 acceptance open. PHP — R1, R2, R3 runtime; R1, R2, R3, R5 acceptance (plus PHP E-rows diagnosed to envelope class checking, prelude.cljc:143). Python — R2, R3-egress runtime (:1045 raw foreign reception, :1046 generic-TypeError protocol loss, :912 always-fresh egress); R1, R2, R3, R5 acceptance. Clojure — R3 then R4 runtime; R1, R2, R3, R4, R5 acceptance.
- **Held dispatches, released by the kit landing** (each seat self-executes per its own ack):
  - yang.typescript/yang.php: JS R1+R3 is BRIEFED and HELD for two things — the kit (landed) and their in-flight B2 round on prelude.cljc (their call: start after B2 lands). Their ack: `1795160000000-orchestrator-tsphp-ack-js-first-dispatch-held-for-kit.md`.
  - yang.clojure: R3 brief drafted (`collab/yang-clojure/1795040000000-engineer-exception-r3.prompt.md`), dispatch held for the kit AND for their cc1 unit landing (one-writer discipline on yang.clojure.cljc). Their acks: `1795020000000-yangclojure-ack-r3-r4-queued.md`, `1795050000000-yangclojure-r3-ready-kit-gap-flagged.md`.
- **PAUSED, by owner directive**: the track is paused while master goes green first (the nightly run 20261106-0944 was red; the yang.python seat's nightly-reds-fix round is in verification as this dossier is written). DO NOT dispatch any frontend round until the owner says master is green and lifts the pause. Your first action after the lift is item 3 below.

## The rounds, in the sequencing of record (v2 — the v1 order is REJECTED)

1. **JS R1+R3** (ts/php seat) — entry bridge + protocol-condition retention, one round; the pairing is mandatory (R3 cannot cross the boundary while entries ignore the exit).
2. **PHP R1+R2+R3** (ts/php seat) — ONE coherent round: function entry (:2264), Closure entry (:4217), and the three call helpers (`php/call`, `call-with-binding-profile`, `call-named`); ForeignException reception; protocol retention. CAUTION of record: bridge-before-reception breaks existing local PHP typed catches.
3. **Python R2/R3 + egress** (yang.python seat) — :1045, :1046, :912; the egress change must make retained wrappers condition-preserving, not fresh.
4. **Clojure R3** (yang.clojure seat) — the public escape stops reclassifying retained conditions (:1089/:1306).
5. **Clojure R4** (same seat, explicitly last) — the intra-unit token channel unifies or proves equivalence at the §8.10.9 bar.

JS and Python proceed independently once their preconditions clear; no Clojure-containing pair declares conformance before Clojure R3 lands (their own registered constraint).

## Your first three actions (after the owner lifts the pause)

1. **Confirm the ledger runs**: `clj -M:test -n yang.call.exception-contract-test -n yang.python.antlr.exception-call-test` in a current master clone — expect the recorded red state (see EXCEPTION_R0.md); the counts ARE the baseline. If they drift from the .edn ledger, stop and diagnose before anything else.
2. **Ping the two held seats** with a short "kit is on master at 0fa3ff7a, your preconditions are yours to confirm" note in this folder, and ask ts/php to state their B2→JS ordering explicitly.
3. **Stand up the acceptance ledger** as a living document here (`acceptance-ledger.md`): one row per (fixture, frontend, backend, VM, host) from exception_r0_results.edn, updated per round record; a round cannot be accepted without its ledger deltas.

## File conventions

- Everything in this folder. Round records: `<ts>-<role>-<round>.<model>.stdout.log` for dispatch logs, `<ts>-<seat>-<what>.md` for orchestrator notes. Per-round acceptance = the round's record + its ledger deltas + the kit's green on its gates.
- The 12-directed-pair matrix, the context-lifetime cases, and the host-by-VM portability matrix are the END-GATE obligations (v2's acceptance section) — start the matrix scaffold as soon as two frontends are compliant so conformance evidence accumulates incrementally rather than in one terminal push.
- Known CLI quirks you will hit: kimi is quota-limited (429); qwen's plan mode denies tools headless (use `agy` for gemini or codex instead); `codex exec resume` rejects `-s`/`-C`.

## What the outgoing seat keeps

The yang.python seat remains an ordinary participant: it delivers Python R2/R3+egress against the kit and files its record like any other seat. It currently owns master-green recovery and is therefore dark for track work until the owner says otherwise.

— the yang.python orchestrator seat, outgoing steward
