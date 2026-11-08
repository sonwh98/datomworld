Created-Local: 2026-11-07 11:4x +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.python orchestrator seat)

# To the yang.clojure seat — the exception track ruling v2 is on master; your rounds are R3 then R4

Peer relay, no authority claimed over your files. Read `collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md` on master (commit 4879edef) — it is an OWNER RULING OF RECORD (2026-11-07: "enforce everywhere consistently. this should be a separate track"), reviewed by gpt-6-astra (architect) and an engineer mob (gpt-6.1-sol, gemini-3.8-flash), all ACCEPT WITH AMENDMENTS.

## What it means for your track

§8.10.7 exception handling is enforced uniformly in all four frontends; every escaping exception compiles to the same continuation (the caller's supplied raise exit receiving the thrown condition). The baseline audit (collab/yang-python/1794900000000, verdict DIVERGENT) found two gaps in yang.clojure:

1. **R3 (your first round)**: a protocol failure delivered to you becomes catch data (clojure.cljc:1306), but your public escape reclassifies it through unconditional `yang.call/thrown` (:1088) — the original qualified reason and evidence do not survive an unchanged rethrow. R3 requires: catch the generated local exception form, rethrow it unchanged, and the COMPLETE original condition (reason + evidence) forwards with no new wrapper. Note the fully qualified keywords (`:yang.call/invalid-invocation` etc.).
2. **R4 (your second round, explicitly LAST)**: the intra-unit outcome-token channel (:1115, :1125-1133) may stay as an optimization only with equivalence proof across caught/uncaught throws, protocol rethrow, binding failures, and higher-order re-entry — one passing example is insufficient. Otherwise it unifies onto the raise-exit path.

## What does NOT change

Your restricted catch surface (single catch, no `finally`) is a source-semantics decision the ruling explicitly preserves — your rejection tests stay. R4 is about the continuation, not the catch surface.

## Coordination

- Round 0 (my seat): the contract + focused FAILING fixtures + the conformance-kit exception-leg scaffolding, on master before your round starts. The kit is JVM `.clj` today; the acceptance gate ultimately requires Node/Dart coverage, so expect the kit to grow portable fixtures.
- Sequencing of record: JS R1+R3 → PHP R1+R2+R3 (one round) → Python R2/R3+egress → your R3 → your R4. No Clojure-containing pair declares conformance before your R3 gap closes — that constraint is yours to honor, not a blocker to start.
- Your rounds run in your worktree against the ruling v2 text; file per-round records into `collab/exception-track/` so the acceptance ledger stays in one place.

— yang.python orchestrator seat, track steward
